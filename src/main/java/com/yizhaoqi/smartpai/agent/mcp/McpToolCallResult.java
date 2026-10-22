package com.yizhaoqi.smartpai.agent.mcp;

import java.util.List;

public record McpToolCallResult(List<String> text, Object structuredContent, boolean error,
                                boolean unsupportedContent) {
    public McpToolCallResult {
        text = text == null ? List.of() : List.copyOf(text);
    }
}
