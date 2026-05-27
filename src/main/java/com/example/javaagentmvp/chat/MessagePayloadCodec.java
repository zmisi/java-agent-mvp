package com.example.javaagentmvp.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Persists Spring AI {@link Message} instances as JSONB payloads (incl. tool calls / tool responses).
 */
public final class MessagePayloadCodec {

    private MessagePayloadCodec() {
    }

    public static JsonNode toJson(Message message, ObjectMapper mapper) {
        return switch (message.getMessageType()) {
            case USER -> {
                ObjectNode n = mapper.createObjectNode();
                n.put("kind", "user");
                n.put("text", UserMessageTextCleaner.clean(message.getText()));
                yield n;
            }
            case ASSISTANT -> {
                AssistantMessage assistant = (AssistantMessage) message;
                ObjectNode n = mapper.createObjectNode();
                n.put("kind", "assistant");
                n.put("text", assistant.getText() != null ? assistant.getText() : "");
                if (assistant.hasToolCalls()) {
                    ArrayNode arr = n.putArray("toolCalls");
                    for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
                        ObjectNode tc = arr.addObject();
                        tc.put("id", toolCall.id());
                        tc.put("type", toolCall.type());
                        tc.put("name", toolCall.name());
                        tc.put("arguments", toolCall.arguments());
                    }
                }
                yield n;
            }
            case TOOL -> {
                ToolResponseMessage tool = (ToolResponseMessage) message;
                ObjectNode n = mapper.createObjectNode();
                n.put("kind", "tool");
                ArrayNode arr = n.putArray("responses");
                for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                    ObjectNode r = arr.addObject();
                    r.put("id", response.id());
                    r.put("name", response.name());
                    r.put("responseData", response.responseData());
                }
                yield n;
            }
            case SYSTEM -> {
                ObjectNode n = mapper.createObjectNode();
                n.put("kind", "system");
                n.put("text", message.getText());
                yield n;
            }
            default -> throw new IllegalArgumentException("Unsupported message type: " + message.getMessageType());
        };
    }

    public static Message fromJson(JsonNode node, ObjectMapper mapper) {
        String kind = node.path("kind").asText("").toLowerCase();
        return switch (kind) {
            case "user" -> new UserMessage(UserMessageTextCleaner.clean(node.path("text").asText("")));
            case "system" -> new SystemMessage(node.path("text").asText(""));
            case "assistant" -> {
                String text = node.path("text").asText("");
                JsonNode toolCallsNode = node.get("toolCalls");
                if (toolCallsNode == null || !toolCallsNode.isArray() || toolCallsNode.isEmpty()) {
                    yield new AssistantMessage(text);
                }
                List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
                for (JsonNode tc : toolCallsNode) {
                    toolCalls.add(new AssistantMessage.ToolCall(
                            tc.path("id").asText(""),
                            tc.path("type").asText("function"),
                            tc.path("name").asText(""),
                            tc.path("arguments").asText("")));
                }
                yield new AssistantMessage(text, Map.of(), toolCalls, Collections.<Media>emptyList());
            }
            case "tool" -> {
                JsonNode responsesNode = node.get("responses");
                if (responsesNode == null || !responsesNode.isArray()) {
                    yield new ToolResponseMessage(List.of());
                }
                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                for (JsonNode r : responsesNode) {
                    responses.add(new ToolResponseMessage.ToolResponse(
                            r.path("id").asText(""),
                            r.path("name").asText(""),
                            r.path("responseData").asText("")));
                }
                yield new ToolResponseMessage(responses);
            }
            default -> throw new IllegalArgumentException("Unsupported payload kind: " + kind + " raw=" + node);
        };
    }

    /** Plain text for UI transcript (not necessarily identical to model-facing text). */
    public static String toDisplayText(JsonNode node) {
        String kind = node.path("kind").asText("").toLowerCase();
        return switch (kind) {
            case "user", "system" -> UserMessageTextCleaner.clean(node.path("text").asText(""));
            case "assistant" -> {
                String text = node.path("text").asText("");
                JsonNode toolCallsNode = node.get("toolCalls");
                if (toolCallsNode == null || !toolCallsNode.isArray() || toolCallsNode.isEmpty()) {
                    yield text;
                }
                StringBuilder sb = new StringBuilder();
                if (!text.isBlank()) {
                    sb.append(text).append("\n\n");
                }
                sb.append("（工具调用）");
                for (JsonNode tc : toolCallsNode) {
                    sb.append("\n- ").append(tc.path("name").asText("")).append("()");
                }
                yield sb.toString();
            }
            case "tool" -> {
                JsonNode responsesNode = node.get("responses");
                if (responsesNode == null || !responsesNode.isArray()) {
                    yield "（工具结果）";
                }
                StringBuilder sb = new StringBuilder("（工具结果）");
                for (JsonNode r : responsesNode) {
                    String data = r.path("responseData").asText("");
                    String excerpt = data.length() > 400 ? data.substring(0, 400) + "…" : data;
                    sb.append("\n- ").append(r.path("name").asText("")).append(": ").append(excerpt);
                }
                yield sb.toString();
            }
            default -> node.toString();
        };
    }

    public static String displayRole(JsonNode node) {
        return switch (node.path("kind").asText("").toLowerCase()) {
            case "user" -> "user";
            case "assistant" -> "assistant";
            case "tool" -> "tool";
            case "system" -> "system";
            default -> "unknown";
        };
    }

    public static List<Message> fromJsonRows(List<String> jsonPayloads, ObjectMapper mapper) {
        if (jsonPayloads.isEmpty()) {
            return Collections.emptyList();
        }
        List<Message> out = new ArrayList<>(jsonPayloads.size());
        for (String json : jsonPayloads) {
            try {
                out.add(fromJson(mapper.readTree(json), mapper));
            }
            catch (RuntimeException ex) {
                throw new IllegalStateException("Failed to parse chat payload: " + json, ex);
            }
            catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new IllegalStateException("Failed to parse chat payload: " + json, ex);
            }
        }
        return out;
    }
}
