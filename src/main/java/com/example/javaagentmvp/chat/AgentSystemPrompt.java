package com.example.javaagentmvp.chat;

import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Loads the agent system prompt from an external file (see {@link AgentPromptProperties#location()}),
 * merging admission task modules via placeholders.
 */
@Component
public class AgentSystemPrompt {

    private static final String SCORE_MAJOR_PLACEHOLDER = "{scoreMajorPrompt}";
    private static final String RANK_QUERY_PLACEHOLDER = "{rankQueryPrompt}";
    private static final String POLICY_QUERY_PLACEHOLDER = "{policyQueryPrompt}";

    private final String text;

    public AgentSystemPrompt(AgentPromptProperties properties, ResourceLoader resourceLoader) {
        String mainPrompt = PromptResourceLoader.load(properties.location(), resourceLoader);
        String scoreMajorPrompt = PromptResourceLoader.load(properties.scoreMajorPromptLocation(), resourceLoader);
        String rankQueryPrompt = PromptResourceLoader.load(properties.rankQueryPromptLocation(), resourceLoader);
        String policyQueryPrompt = PromptResourceLoader.load(properties.policyQueryPromptLocation(), resourceLoader);
        String merged = mainPrompt
                .replace(SCORE_MAJOR_PLACEHOLDER, scoreMajorPrompt.strip())
                .replace(RANK_QUERY_PLACEHOLDER, rankQueryPrompt.strip())
                .replace(POLICY_QUERY_PLACEHOLDER, policyQueryPrompt.strip());
        this.text = merged.strip().replace("{schema}", properties.schema());
    }

    public String text() {
        return text;
    }
}
