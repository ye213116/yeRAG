package com.yizhaoqi.smartpai.agent.tool;

import java.util.Map;
import com.yizhaoqi.smartpai.agent.SourceReference;

public record ToolExecutionResult(boolean success, String content, int resultCount,
                                  Map<Integer, String> references, Map<Integer, SourceReference> sources, String errorCode) {
    public ToolExecutionResult(boolean success, String content, int resultCount, Map<Integer, String> references,
                               Map<Integer, SourceReference> sources) {
        this(success, content, resultCount, references, sources, null);
    }
    public ToolExecutionResult(boolean success, String content, int resultCount, Map<Integer, String> references) {
        this(success, content, resultCount, references, Map.of());
    }
    public static ToolExecutionResult error(String code, String message) {
        return new ToolExecutionResult(false,
                "{\"error\":\"" + escaped(code) + "\",\"message\":\"" + escaped(message) + "\"}",
                0, Map.of(), Map.of(), code);
    }

    private static String escaped(String value) {
        return new String(com.fasterxml.jackson.core.io.JsonStringEncoder.getInstance().quoteAsString(value));
    }
}
