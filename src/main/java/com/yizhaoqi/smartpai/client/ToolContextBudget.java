package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.rag.TokenBudget;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fits accumulated tool data to the model token budget without dropping protocol messages or user constraints. */
final class ToolContextBudget {
    private static final Set<String> BODY_FIELDS = Set.of("untrusted_content", "content", "text", "snippet", "page_text");
    private static final String NOTICE = "部分工具正文因整轮输入预算被裁剪或省略，context_truncated/truncated 表示当前可见内容不完整。"
            + "只能依据保留的原文作答；来源元数据与中间记录不能替代正文，说明证据缺口，不得声称阅读了被省略的内容。";

    record Result(int originalBytes, int finalBytes, int originalTokens, int finalTokens, int truncatedTools) {}

    @SuppressWarnings("unchecked")
    static Result fit(ObjectMapper json, Map<String, Object> request, int limit, TokenBudget.Counter tokens) throws JsonProcessingException {
        int originalBytes = json.writeValueAsBytes(request).length;
        int originalTokens = tokens.requestTokens(json.valueToTree(request));
        if (originalTokens <= limit || limit <= 0) return new Result(originalBytes, originalBytes, originalTokens, originalTokens, 0);
        var messages = new ArrayList<>((List<Map<String, Object>>) request.get("messages"));
        var tools = new ArrayList<Payload>();
        for (var message : messages) {
            if ("tool".equals(message.get("role")) && message.get("content") instanceof String content && !content.isEmpty())
                tools.add(new Payload(message, content, json));
        }
        if (tools.isEmpty()) return new Result(originalBytes, originalBytes, originalTokens, originalTokens, 0);
        messages.add(Map.of("role", "system", "content", NOTICE));
        request.put("messages", messages);
        int count = tokens.requestTokens(json.valueToTree(request));
        while (count > limit) {
            Slot largest = null;
            for (Payload payload : tools) {
                for (Slot slot : payload.slots()) {
                    if (largest == null || slot.tokens(tokens) > largest.tokens(tokens)) largest = slot;
                }
            }
            if (largest != null && largest.tokens(tokens) > 0) {
                String original = largest.parent.path(largest.field).asText();
                largest.parent.put(largest.field, "");
                largest.parent.put("truncated", true);
                largest.payload.root.put("context_truncated", true);
                largest.payload.changed = true;
                largest.payload.write(json);
                // Count decoded tool text with the model tokenizer; HTTP escaping does not consume model context.
                if (tokens.requestTokens(json.valueToTree(request)) <= limit) {
                    int low = 0, high = original.codePointCount(0, original.length());
                    while (low < high) {
                        int middle = low + (high - low + 1) / 2;
                        largest.parent.put(largest.field, original.substring(0, original.offsetByCodePoints(0, middle)));
                        largest.payload.write(json);
                        if (tokens.requestTokens(json.valueToTree(request)) <= limit) low = middle;
                        else high = middle - 1;
                    }
                    largest.parent.put(largest.field, original.substring(0, original.offsetByCodePoints(0, low)));
                    largest.payload.write(json);
                }
            } else {
                // If even metadata cannot fit, explicitly omit an old result; never silently truncate JSON or remove a tool response.
                Payload oldest = tools.stream().filter(payload -> !payload.omitted).findFirst().orElse(null);
                if (oldest == null) break;
                oldest.root = json.createObjectNode().put("context_truncated", true)
                        .put("notice", "Tool result omitted due to context budget; no evidence body is retained.");
                oldest.changed = true;
                oldest.omitted = true;
                oldest.write(json);
            }
            count = tokens.requestTokens(json.valueToTree(request));
        }
        return new Result(originalBytes, json.writeValueAsBytes(request).length, originalTokens, count, (int) tools.stream().filter(payload -> payload.changed).count());
    }

    private static final class Payload {
        final Map<String, Object> message;
        ObjectNode root;
        boolean changed;
        boolean omitted;

        Payload(Map<String, Object> message, String content, ObjectMapper json) {
            this.message = message;
            JsonNode parsed = null;
            try { parsed = json.readTree(content); } catch (JsonProcessingException ignored) { /* Plain MCP output is untrusted text. */ }
            if (parsed instanceof ObjectNode object) root = object;
            else if (parsed != null && parsed.isArray()) root = json.createObjectNode().set("results", parsed);
            else root = json.createObjectNode().put("untrusted_content", content);
        }

        List<Slot> slots() {
            var slots = new ArrayList<Slot>();
            collect(root, slots);
            return slots;
        }

        private void collect(JsonNode node, List<Slot> slots) {
            if (node instanceof ObjectNode object) {
                object.fields().forEachRemaining(field -> {
                    if (BODY_FIELDS.contains(field.getKey()) && field.getValue().isTextual())
                        slots.add(new Slot(this, object, field.getKey()));
                    else if (field.getValue().isContainerNode()) collect(field.getValue(), slots);
                });
            } else if (node.isArray()) node.forEach(child -> collect(child, slots));
        }

        void write(ObjectMapper json) throws JsonProcessingException { message.put("content", json.writeValueAsString(root)); }
    }

    private record Slot(Payload payload, ObjectNode parent, String field) {
        int tokens(TokenBudget.Counter counter) { return counter.count(parent.path(field).asText()); }
    }
}
