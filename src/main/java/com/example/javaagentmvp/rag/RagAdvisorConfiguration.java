package com.example.javaagentmvp.rag;

import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagAdvisorConfiguration {

    @Bean
    RagFlowStartAdvisor ragFlowStartAdvisor(RagRetrievalService ragRetrievalService, RagQueryRouter ragQueryRouter) {
        return new RagFlowStartAdvisor(ragRetrievalService, ragQueryRouter);
    }

    @Bean
    AdmissionsAnswerFormatAdvisor admissionsAnswerFormatAdvisor(RagProperties ragProperties) {
        return new AdmissionsAnswerFormatAdvisor(ragProperties);
    }

    @Bean
    ConditionalQuestionAnswerAdvisor conditionalQuestionAnswerAdvisor(
            VectorStore ragVectorStore,
            RagProperties ragProperties) {
        QuestionAnswerAdvisor delegate = QuestionAnswerAdvisor.builder(ragVectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(ragProperties.topK())
                        .build())
                .build();
        return new ConditionalQuestionAnswerAdvisor(delegate);
    }

    @Bean
    RagFlowLoggingAdvisor ragFlowLoggingAdvisor() {
        return new RagFlowLoggingAdvisor(RagAdvisorOrder.FLOW_LOGGING);
    }
}
