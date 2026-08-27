package com.yizhaoqi.smartpai.agent.tool;

import java.util.Map;

public record ToolExecutionResult(boolean success, String content, int resultCount,
                                  Map<Integer, String> references) {
    public static ToolExecutionResult error(String code, String message) {
        return new ToolExecutionResult(false,
                "{\"error\":\"" + code + "\",\"message\":\"" + message.replace("\"", "'") + "\"}",
                0, Map.of());
    }
}
