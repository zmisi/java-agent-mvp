package com.example.javaagentmvp.chat;

import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionForcedMcpContext;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryContext;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryPromptFormatter;
import com.example.javaagentmvp.admissionworkflow.execution.AdmissionQueryMcpExecutor;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.io.ResourceLoader;

import java.util.List;
import java.util.Optional;

/**
 * Pre-executes MCP from compiled IR before the LLM turn (E-6/E-7), then narrows the model to
 * synthesis-only instructions (E-16).
 */
public class ForcedMcpAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ForcedMcpAdvisor.class);

    static final int ORDER = AdmissionQueryAdvisor.ORDER + 10;

    private final AdmissionQueryMcpExecutor admissionQueryMcpExecutor;
    private final AgentPromptProperties agentPromptProperties;
    private final ResourceLoader resourceLoader;

    public ForcedMcpAdvisor(
            AdmissionQueryMcpExecutor admissionQueryMcpExecutor,
            AgentPromptProperties agentPromptProperties,
            ResourceLoader resourceLoader) {
        this.admissionQueryMcpExecutor = admissionQueryMcpExecutor;
        this.agentPromptProperties = agentPromptProperties;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Optional<AdmissionQueryIr> queryOpt = AdmissionQueryContext.current();
        if (queryOpt.isEmpty() || !admissionQueryMcpExecutor.requiresForcedMcp(queryOpt.get())) {
            return chain.nextCall(request);
        }

        AdmissionQueryIr query = queryOpt.get();
        AdmissionQueryMcpExecutor.ExecutionResult execution = admissionQueryMcpExecutor.execute(query);
        if (!execution.attempted()) {
            return chain.nextCall(request);
        }

        if (!execution.success()) {
            log.warn(
                    "ForcedMcp failed task={} error={}",
                    query.task(),
                    execution.errorMessage());
            ChatTurnFlowLog.step(
                    ChatTurnFlowLog.Step.MCP_PROCESS,
                    "forced pre-execute failed task=%s error=%s",
                    query.task(),
                    execution.errorMessage());
            return failureResponse(
                    "暂时无法查询录取数据，请稍后重试。"
                            + (execution.errorMessage() == null ? "" : "（" + execution.errorMessage() + "）"));
        }

        AdmissionForcedMcpContext.markPreExecuted(query.task(), execution.toolResponse());
        ChatTurnFlowLog.step(
                ChatTurnFlowLog.Step.MCP_PROCESS,
                "forced pre-execute task=%s tables=%d",
                query.task(),
                execution.tableCount());

        String synthesisBlock = AdmissionQueryPromptFormatter.formatPostMcpSynthesis(
                query,
                loadSynthesisPrompt(query.toIntent()));
        ChatClientRequest updated = request.mutate()
                .prompt(request.prompt().augmentSystemMessage(synthesisBlock))
                .build();
        return chain.nextCall(updated);
    }

    private String loadSynthesisPrompt(AdmissionIntent intent) {
        String location = switch (intent) {
            case RANK -> agentPromptProperties.rankQueryPromptLocation();
            case POLICY -> agentPromptProperties.policyQueryPromptLocation();
            default -> agentPromptProperties.scoreMajorPromptLocation();
        };
        return PromptResourceLoader.load(location, resourceLoader);
    }

    private static ChatClientResponse failureResponse(String message) {
        return ChatClientResponse.builder()
                .chatResponse(ChatResponse.builder()
                        .generations(List.of(new Generation(new AssistantMessage(message))))
                        .build())
                .build();
    }

    @Override
    public String getName() {
        return ForcedMcpAdvisor.class.getSimpleName();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
