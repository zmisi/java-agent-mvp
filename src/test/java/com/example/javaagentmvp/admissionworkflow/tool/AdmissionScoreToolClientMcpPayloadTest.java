package com.example.javaagentmvp.admissionworkflow.tool;

import com.example.javaagentmvp.chat.ui.McpTableExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionScoreToolClientMcpPayloadTest {

    private final McpTableExtractor extractor = new McpTableExtractor(new ObjectMapper());

    @Test
    void unwrapsMcpContentBlockBeforeReadingCount() {
        String mcpResponse = """
                [{"text":"{\\"count\\":382,\\"majors\\":[{\\"major_name\\":\\"计算机\\"}]}"}]
                """;

        var root = extractor.parseMajorByScoreRoot(mcpResponse);

        assertThat(root).isPresent();
        assertThat(root.get().path("count").asInt()).isEqualTo(382);
        assertThat(root.get().path("majors").isArray()).isTrue();
        assertThat(root.get().path("majors")).hasSize(1);
    }
}
