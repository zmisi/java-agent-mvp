package com.example.javaagentmvp.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.document.Document;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * For multi-school admissions retrieval, injects context grouped by school and applies the
 * configurable answer-format template. Skips {@link org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor}
 * so the pre-fetched chunks are not replaced by a single topK search.
 */
public class AdmissionsAnswerFormatAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(AdmissionsAnswerFormatAdvisor.class);

    private final RagProperties ragProperties;

    private final int order;

    public AdmissionsAnswerFormatAdvisor(RagProperties ragProperties) {
        this(ragProperties, Ordered.HIGHEST_PRECEDENCE + 200);
    }

    public AdmissionsAnswerFormatAdvisor(RagProperties ragProperties, int order) {
        this.ragProperties = ragProperties;
        this.order = order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (RagFlowContext.isSkipped() || !RagFlowContext.isMultiSchoolAdmissions()) {
            return chain.nextCall(request);
        }

        String userMessage = extractUserMessage(request);
        List<Document> documents = RagFlowContext.preRetrievedDocuments();
        String groupedContext = AdmissionsGroupedContextFormatter.buildGroupedContext(
                documents, ragProperties.admissions().schools());
        String augmented = AdmissionsPromptBuilder.buildAugmentedUserMessage(
                userMessage, groupedContext, ragProperties.admissions().answerFormatTemplate());

        log.info("[RAG {}] {} 多学校招生 — 注入按校分组上下文 ({} 个片段, format applied)",
                RagFlowContext.flowId(), RagFlowLogStep.FORMAT, documents.size());

        ChatClientRequest updated = request.mutate()
                .prompt(request.prompt().augmentUserMessage(augmented))
                .build();
        return chain.nextCall(updated);
    }

    @Override
    public String getName() {
        return AdmissionsAnswerFormatAdvisor.class.getSimpleName();
    }

    @Override
    public int getOrder() {
        return order;
    }

    private static String extractUserMessage(ChatClientRequest request) {
        List<Message> messages = request.prompt().getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.getMessageType() == MessageType.USER) {
                String text = message.getText();
                return text == null ? "" : text;
            }
        }
        return "";
    }
}
