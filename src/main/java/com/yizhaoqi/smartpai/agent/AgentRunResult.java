package com.yizhaoqi.smartpai.agent;

import java.util.Map;

public record AgentRunResult(String answer, Map<Integer, String> references, Map<Integer, SourceReference> sources) {
    public AgentRunResult(String answer, Map<Integer, String> references) {
        this(answer, references, Map.of());
    }
}
