package com.example.javaagentmvp.chat.ui;

import com.example.javaagentmvp.chat.persistence.model.ChatMemoryMessageRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptBuilderTest {

    private final TranscriptBuilder builder = new TranscriptBuilder(
            new McpTableExtractor(new ObjectMapper()),
            new ObjectMapper());

    @Test
    void readsPersistedUiTablesFromAssistantPayload() {
        List<ChatMemoryMessageRow> rows = List.of(
                row(1, """
                        {"kind":"user","text":"安徽630分可报哪些专业？"}
                        """),
                row(2, """
                        {"kind":"assistant","text":"630分可报考合肥工业大学软件工程等专业。","uiTables":[{"title":"可报专业","columns":[{"key":"major_name","label":"专业"}],"rows":[{"major_name":"软件工程"}]}]}
                        """));

        List<TranscriptBuilder.TranscriptRow> transcript = builder.build(rows);

        assertEquals(2, transcript.size());
        assertEquals(1, transcript.get(1).tables().size());
        assertEquals("软件工程", transcript.get(1).tables().get(0).rows().get(0).get("major_name"));
    }

    @Test
    void backfillsGroupsForLegacyPersistedUiTables() {
        List<ChatMemoryMessageRow> rows = List.of(
                row(1, """
                        {"kind":"user","text":"安徽630分可报哪些专业？"}
                        """),
                row(2, """
                        {"kind":"assistant","text":"630分可报考合肥工业大学软件工程等专业。","uiTables":[{"title":"可报专业","columns":[{"key":"major_name","label":"专业"}],"rows":[{"university_code":"HFUT","university_name":"合肥工业大学","major_name":"软件工程","campus":"宣城校区","min_score":"628","min_rank":"12000","max_score":"633","year":"2025","subject_group":"物理类","admission_type":"普通批"}]}]}
                        """));

        List<TranscriptBuilder.TranscriptRow> transcript = builder.build(rows);

        assertEquals(1, transcript.get(1).tables().get(0).groups().size());
        assertEquals("合肥工业大学", transcript.get(1).tables().get(0).groups().get(0).universityName());
        assertEquals("软件工程", transcript.get(1).tables().get(0).groups().get(0).majors().get(0).get("major_name"));
    }

    @Test
    void attachesTablesToFinalAssistantAndSkipsToolOnlyAssistant() {
        List<ChatMemoryMessageRow> rows = List.of(
                row(1, """
                        {"kind":"user","text":"安徽630分可报哪些专业？"}
                        """),
                row(2, """
                        {"kind":"assistant","text":"","toolCalls":[{"id":"1","type":"function","name":"opstream_agent_admission_score_getMajorByScore","arguments":"{\\"score\\":630,\\"province\\":\\"安徽\\"}"}]}
                        """),
                row(3, """
                        {"kind":"tool","responses":[{"id":"1","name":"opstream_agent_admission_score_getMajorByScore","responseData":"[{\\"text\\":\\"{\\\\\\"count\\\\\\":1,\\\\\\"majors\\\\\\":[{\\\\\\"university_name\\\\\\":\\\\\\"合肥工业大学\\\\\\",\\\\\\"major_name\\\\\\":\\\\\\"软件工程\\\\\\",\\\\\\"campus\\\\\\":\\\\\\"宣城校区\\\\\\",\\\\\\"min_score\\\\\\":\\\\\\"628\\\\\\",\\\\\\"min_rank\\\\\\":12000,\\\\\\"max_score\\\\\\":\\\\\\"633\\\\\\",\\\\\\"year\\\\\\":2025,\\\\\\"subject_group\\\\\\":\\\\\\"物理类\\\\\\",\\\\\\"admission_type\\\\\\":\\\\\\"普通批\\\\\\"}]}\\"}]"}]}
                        """),
                row(4, """
                        {"kind":"assistant","text":"630分可报考合肥工业大学软件工程等专业。"}
                        """));

        List<TranscriptBuilder.TranscriptRow> transcript = builder.build(rows);

        assertEquals(2, transcript.size());
        assertEquals("user", transcript.get(0).role());
        assertTrue(transcript.get(0).tables().isEmpty());
        assertEquals("assistant", transcript.get(1).role());
        assertEquals(1, transcript.get(1).tables().size());
        assertEquals("软件工程", transcript.get(1).tables().get(0).rows().get(0).get("major_name"));
    }

    private static ChatMemoryMessageRow row(long id, String payloadJson) {
        ChatMemoryMessageRow row = new ChatMemoryMessageRow();
        row.setId(id);
        row.setCreatedAt("2025-01-01T00:00:00Z");
        row.setPayloadJson(payloadJson);
        return row;
    }
}
