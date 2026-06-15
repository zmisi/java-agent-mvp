package com.example.javaagentmvp.admissionworkflow.intent;

/**
 * Formats a structured task block for injection into the system prompt.
 */
public final class ResolvedTurnPromptFormatter {

    private ResolvedTurnPromptFormatter() {
    }

    public static String format(ResolvedTurn resolved) {
        if (resolved == null || !resolved.needsTaskPrompt()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 当前对话任务（系统自动解析，请严格遵循）\n");
        sb.append("- 意图: ").append(intentLabel(resolved.intent())).append('\n');

        if (resolved.intent() == AdmissionIntent.POLICY) {
            if (resolved.inheritedIntent()) {
                sb.append("- 说明: 用户已从分数/位次查询切换为政策问题，勿沿用上一轮 MCP 数据\n");
            }
            sb.append('\n');
            sb.append("**必须**依据下方 RAG 知识库片段回答；**禁止**调用 getMajorByScore / getRankByScore，**禁止**输出冲/稳/保表格。\n");
            return sb.toString().strip();
        }

        appendSlot(sb, "分数", resolved.slots().score() != null ? resolved.slots().score() + "分" : null, resolved.delta());
        appendSlot(sb, "省份", resolved.slots().province(), resolved.delta());
        appendSlot(sb, "科类", resolved.slots().subjectGroup(), resolved.delta());
        appendSlot(sb, "年份", resolved.slots().year() != null ? String.valueOf(resolved.slots().year()) : null, resolved.delta());
        appendSlot(sb, "批次", resolved.slots().admissionType(), resolved.delta());

        if (resolved.inheritedIntent()) {
            sb.append("- 说明: 本轮为追问，已合并上文参数\n");
        }

        sb.append('\n');
        if (resolved.intent() == AdmissionIntent.RANK) {
            sb.append("**必须**调用 getRankByScore，参数见上；禁止用知识库一分一段文档回答位次。");
            sb.append("系统会自动格式化位次表格，调用工具后简要确认即可。\n");
        }
        else if (resolved.intent() == AdmissionIntent.SCORE) {
            sb.append("**必须**调用 getMajorByScore，参数见上；禁止用知识库片段编造专业录取分。");
            sb.append("缺少必填参数时先追问用户。\n");
        }
        return sb.toString().strip();
    }

    private static String intentLabel(AdmissionIntent intent) {
        return switch (intent) {
            case RANK -> "查位次 (RANK)";
            case SCORE -> "查专业/院校 (SCORE)";
            case POLICY -> "查政策/简章 (POLICY)";
            case REPORT -> "综合报告 (REPORT)";
            case UNKNOWN -> "未知 (UNKNOWN)";
        };
    }

    private static void appendSlot(StringBuilder sb, String label, String value, SlotDelta delta) {
        sb.append("- ").append(label).append(": ");
        if (value == null || value.isBlank()) {
            sb.append("（未指定）");
        }
        else {
            sb.append(value);
            if (slotChanged(label, delta)) {
                sb.append("（本轮变更）");
            }
        }
        sb.append('\n');
    }

    private static boolean slotChanged(String label, SlotDelta delta) {
        return switch (label) {
            case "分数" -> delta == SlotDelta.SCORE;
            case "省份" -> delta == SlotDelta.PROVINCE;
            case "科类" -> delta == SlotDelta.SUBJECT_GROUP;
            case "年份" -> delta == SlotDelta.YEAR;
            case "批次" -> delta == SlotDelta.ADMISSION_TYPE;
            default -> false;
        };
    }
}
