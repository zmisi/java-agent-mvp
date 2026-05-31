package com.example.javaagentmvp.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
        boolean enabled,
        @DefaultValue("false") boolean rebuildOnStartup,
        @DefaultValue("false") boolean clearBeforeRebuild,
        @DefaultValue("agent_ui") String vectorSchemaName,
        @DefaultValue("rag_vector_store") String vectorTableName,
        String documentLocationPattern,
        int topK,
        double maxDistance,
        boolean routeDatabaseQueries,
        String contextAddon,
        Routing routing,
        Admissions admissions,
        Hybrid hybrid) {

    public RagProperties {
        if (routing == null) {
            routing = new Routing(List.of(), List.of());
        }
        if (admissions == null) {
            admissions = new Admissions(
                    true,
                    List.of("招生", "录取", "分数", "分数线", "投档", "专业", "志愿", "高考", "考生"),
                    4,
                    12,
                    List.of(),
                    defaultAnswerFormatTemplate());
        }
        if (hybrid == null) {
            hybrid = new Hybrid(true, 2, 3, 3, 60, 1.0, 0.9, "auto", "simple");
        }
    }

    public record Routing(
            @DefaultValue List<String> ragPatterns,
            @DefaultValue List<String> databasePatterns) {

        public Routing {
            ragPatterns = ragPatterns == null ? List.of() : List.copyOf(ragPatterns);
            databasePatterns = databasePatterns == null ? List.of() : List.copyOf(databasePatterns);
        }
    }

    /**
     * Admissions-style question routing: when a question is about scores/majors but does not mention
     * a specific school, we fan out retrieval across all configured schools and merge results.
     */
    public record Admissions(
            @DefaultValue("true") boolean enabled,
            @DefaultValue List<String> intentKeywords,
            @DefaultValue("4") int perSchoolTopK,
            @DefaultValue("12") int totalTopK,
            @DefaultValue List<School> schools,
            @DefaultValue String answerFormatTemplate) {

        public Admissions {
            intentKeywords = intentKeywords == null ? List.of() : List.copyOf(intentKeywords);
            schools = schools == null ? List.of() : List.copyOf(schools);
            if (answerFormatTemplate == null || answerFormatTemplate.isBlank()) {
                answerFormatTemplate = defaultAnswerFormatTemplate();
            }
        }
    }

    public record Hybrid(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("2") int vectorTopKMultiplier,
            @DefaultValue("3") int lexicalTopKMultiplier,
            @DefaultValue("3") int fusionTopKMultiplier,
            @DefaultValue("60") int rrfK,
            @DefaultValue("1.0") double vectorWeight,
            @DefaultValue("0.9") double lexicalWeight,
            @DefaultValue("auto") String lexicalEngine,
            @DefaultValue("simple") String ftsDictionary) {

        public Hybrid {
            if (vectorTopKMultiplier < 1) {
                vectorTopKMultiplier = 1;
            }
            if (lexicalTopKMultiplier < 1) {
                lexicalTopKMultiplier = 1;
            }
            if (fusionTopKMultiplier < 1) {
                fusionTopKMultiplier = 1;
            }
            if (rrfK < 1) {
                rrfK = 1;
            }
            if (ftsDictionary == null || ftsDictionary.isBlank()) {
                ftsDictionary = "simple";
            }
            if (lexicalEngine == null || lexicalEngine.isBlank()) {
                lexicalEngine = "auto";
            }
            lexicalEngine = lexicalEngine.strip().toLowerCase();
        }
    }

    private static String defaultAnswerFormatTemplate() {
        return """
                回答格式要求（必须遵守）：
                1. 按学校分组输出，每个学校单独成段，标题使用学校中文全称。
                2. 每所学校下列出与考生分数相关的专业、录取分数/投档线等信息；注明资料来源文件名。
                3. 若某学校在上下文中没有分数或专业信息，明确写「资料未包含该校录取分数」。
                4. 仅根据上下文回答，不得编造；缺少省份、科类、批次、校区等信息时，向用户追问。
                """;
    }

    public record School(
            String key,
            String displayName,
            @DefaultValue List<String> aliases,
            @DefaultValue List<String> pathContains) {

        public School {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            pathContains = pathContains == null ? List.of() : List.copyOf(pathContains);
        }
    }
}
