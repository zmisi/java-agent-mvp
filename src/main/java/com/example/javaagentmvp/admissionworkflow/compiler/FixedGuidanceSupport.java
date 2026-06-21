package com.example.javaagentmvp.admissionworkflow.compiler;

/** Deterministic welcome / capability intro when the compiler cannot resolve an admission task. */
public final class FixedGuidanceSupport {

    public static final String MESSAGE = """
            你好！我是高考志愿专业匹配助手，可帮你根据分数、科类、省份等信息，智能推荐「冲/稳/保」三档适配的院校与专业。请告诉我你的：
            ✅ 高考分数（如600分）
            ✅ 所在省份（如安徽）
            ✅ 科类（物理类/历史类）
            ✅ 是否有倾向专业大类（如工科、经管、法学等）

            我将为你生成精准匹配结果。

            也可以帮你查询排名、招生政策与章程等，直接说出你的问题即可。""";

    private FixedGuidanceSupport() {
    }

    /**
     * {@code task=unknown} with no clarifiable admission slots — short-circuit before LLM/RAG/MCP.
     * Off-topic turns (e.g. chit-chat after a prior search) are handled here without keyword lists.
     */
    public static boolean requiresFixedGuidance(AdmissionQueryIr query) {
        if (query == null) {
            return true;
        }
        if (!"unknown".equals(query.task())) {
            return false;
        }
        return !query.blocksMcpExecution();
    }
}
