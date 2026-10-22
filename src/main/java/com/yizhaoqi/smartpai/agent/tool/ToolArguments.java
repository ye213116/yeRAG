package com.yizhaoqi.smartpai.agent.tool;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;

/** Strict JSON parsing shared by the built-in read-only tools. */
final class ToolArguments {
    private ToolArguments() { }

    static JsonNode parse(ObjectMapper mapper, String json, Set<String> fields) {
        if (json == null || json.length() > 8192) throw new IllegalArgumentException("invalid argument size");
        try {
            JsonNode root = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("arguments must be an object");
            root.fieldNames().forEachRemaining(name -> {
                if (!fields.contains(name)) throw new IllegalArgumentException("unknown argument: " + name);
            });
            return root;
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("arguments must be valid JSON", invalid);
        }
    }

    static String text(JsonNode root, String key, int max) {
        if (!root.path(key).isTextual()) throw new IllegalArgumentException(key + " must be a string");
        String value = root.path(key).textValue().trim();
        if (value.isEmpty() || value.length() > max) throw new IllegalArgumentException("invalid " + key + " length");
        return value;
    }

    static int integer(JsonNode root, String key, int fallback, int min, int max) {
        if (!root.has(key)) return fallback;
        JsonNode node = root.get(key);
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < min || node.intValue() > max)
            throw new IllegalArgumentException(key + " is out of range");
        return node.intValue();
    }
}
