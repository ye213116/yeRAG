package com.yizhaoqi.smartpai.agent.mcp;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

public record McpToolDescriptor(String name, String description, Map<String, Object> inputSchema,
                                Boolean readOnlyHint, Boolean destructiveHint) {
    public McpToolDescriptor {
        inputSchema = inputSchema == null ? Map.of()
                : freezeMap(inputSchema);
    }

    private static Map<String, Object> freezeMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, freeze(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(key, freeze(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof java.util.List<?> list) {
            return Collections.unmodifiableList(list.stream().map(McpToolDescriptor::freeze).toList());
        }
        return value;
    }
}
