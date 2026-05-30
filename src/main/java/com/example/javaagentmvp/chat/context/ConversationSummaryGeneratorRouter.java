package com.example.javaagentmvp.chat.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

@Primary
@Component
public class ConversationSummaryGeneratorRouter implements ConversationSummaryGenerator {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummaryGeneratorRouter.class);

    private final CompactionProperties properties;
    private final ModelSummaryGenerator modelSummaryGenerator;
    private final RuleBasedSummaryGenerator ruleBasedSummaryGenerator;

    public ConversationSummaryGeneratorRouter(
            CompactionProperties properties,
            ModelSummaryGenerator modelSummaryGenerator,
            RuleBasedSummaryGenerator ruleBasedSummaryGenerator) {
        this.properties = properties;
        this.modelSummaryGenerator = modelSummaryGenerator;
        this.ruleBasedSummaryGenerator = ruleBasedSummaryGenerator;
    }

    @Override
    public String generate(String conversationId, List<Message> history) {
        if (properties.summaryMode() == CompactionSummaryMode.RULES) {
            return ruleBasedSummaryGenerator.generate(conversationId, history);
        }
        try {
            return modelSummaryGenerator.generate(conversationId, history);
        } catch (Exception ex) {
            if (!properties.fallbackToRulesOnFailure()) {
                throw ex;
            }
            log.warn("[COMPACT] model summary failed, falling back to rules: {}", ex.getMessage());
            return ruleBasedSummaryGenerator.generate(conversationId, history);
        }
    }
}
