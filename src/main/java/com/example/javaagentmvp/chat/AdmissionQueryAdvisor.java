package com.example.javaagentmvp.chat;

import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryCompileService;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryContext;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryPromptFormatter;
import com.example.javaagentmvp.admissionworkflow.compiler.ClarificationSupport;
import com.example.javaagentmvp.admissionworkflow.compiler.FixedGuidanceSupport;
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

import java.util.List;

/**
 * Compiles admission IR for the current turn, injects structured task context, and short-circuits
 * when required slots are missing.
 */
public class AdmissionQueryAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(AdmissionQueryAdvisor.class);

    static final int ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 50;

    private final AdmissionQueryCompileService admissionQueryCompileService;

    public AdmissionQueryAdvisor(AdmissionQueryCompileService admissionQueryCompileService) {
        this.admissionQueryCompileService = admissionQueryCompileService;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        UserTurnContextExtractor.UserTurnContext context = UserTurnContextExtractor.extract(request);
        AdmissionQueryIr query = admissionQueryCompileService.compile(
                context.currentUserMessage(),
                context.priorUserMessages());
        AdmissionQueryContext.set(query);

        AdmissionIntent intent = query.toIntent();
        boolean inheritedFromPrior = query.parseTrace() != null && query.parseTrace().inheritedFromPrior();

        if (query.blocksMcpExecution()) {
            String clarification = ClarificationSupport.buildMessage(query.needsClarification());
            log.info(
                    "AdmissionQuery clarification: task={}, needs={}, message={}",
                    query.task(),
                    query.needsClarification(),
                    clarification);
            ChatTurnFlowLog.step(
                    ChatTurnFlowLog.Step.ROUTE_DECISION,
                    "clarification short-circuit intent=%s needs=%s",
                    intent,
                    query.needsClarification());
            ChatTurnFlowLog.skipped(ChatTurnFlowLog.Step.TASK_PROMPT, "awaiting user clarification");
            ChatTurnFlowLog.skipped(ChatTurnFlowLog.Step.RAG_RETRIEVE, "clarification short-circuit");
            ChatTurnFlowLog.skipped(ChatTurnFlowLog.Step.MCP_PROCESS, "clarification short-circuit");
            return clarificationResponse(clarification);
        }

        if (FixedGuidanceSupport.requiresFixedGuidance(query)) {
            log.info("AdmissionQuery fixed guidance: task=unknown off-topic turn");
            ChatTurnFlowLog.step(
                    ChatTurnFlowLog.Step.ROUTE_DECISION,
                    "fixed guidance short-circuit intent=%s",
                    intent);
            ChatTurnFlowLog.skipped(ChatTurnFlowLog.Step.TASK_PROMPT, "unknown off-topic turn");
            ChatTurnFlowLog.skipped(ChatTurnFlowLog.Step.RAG_RETRIEVE, "fixed guidance short-circuit");
            ChatTurnFlowLog.skipped(ChatTurnFlowLog.Step.MCP_PROCESS, "fixed guidance short-circuit");
            return clarificationResponse(FixedGuidanceSupport.MESSAGE);
        }

        ChatTurnFlowLog.step(
                ChatTurnFlowLog.Step.ROUTE_DECISION,
                "continue intent=%s inherited=%s priorTurns=%d",
                intent,
                inheritedFromPrior,
                context.priorUserMessages().size());

        if (!AdmissionQueryPromptFormatter.needsTaskPrompt(query)) {
            log.debug("AdmissionQuery: task={}, no task prompt", query.task());
            ChatTurnFlowLog.skipped(ChatTurnFlowLog.Step.TASK_PROMPT, "task=%s no structured prompt", query.task());
            return chain.nextCall(request);
        }

        String taskBlock = AdmissionQueryPromptFormatter.format(query);
        log.info(
                "AdmissionQuery: task={}, provinces={}, preferences={}, filters={}",
                query.task(),
                query.slots().provincesOrEmpty(),
                query.preferences().size(),
                query.filters().excludeMajorKeywords().size() + query.filters().excludeSchoolNameContains().size());
        ChatTurnFlowLog.step(
                ChatTurnFlowLog.Step.TASK_PROMPT,
                "task=%s provinces=%s preferences=%d excludeFilters=%d promptChars=%d",
                query.task(),
                query.slots().provincesOrEmpty(),
                query.preferences().size(),
                query.filters().excludeMajorKeywords().size() + query.filters().excludeSchoolNameContains().size(),
                taskBlock.length());

        ChatClientRequest updated = request.mutate()
                .prompt(request.prompt().augmentSystemMessage(taskBlock))
                .build();
        return chain.nextCall(updated);
    }

    private static ChatClientResponse clarificationResponse(String message) {
        return ChatClientResponse.builder()
                .chatResponse(ChatResponse.builder()
                        .generations(List.of(new Generation(new AssistantMessage(message))))
                        .build())
                .build();
    }

    @Override
    public String getName() {
        return AdmissionQueryAdvisor.class.getSimpleName();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
