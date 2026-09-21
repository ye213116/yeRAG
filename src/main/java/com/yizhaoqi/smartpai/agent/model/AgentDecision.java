package com.yizhaoqi.smartpai.agent.model;

import java.util.List;

public record AgentDecision(String content, List<ToolCall> toolCalls) {
    public AgentDecision {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean requestsTool() {
        return !toolCalls.isEmpty();
    }
}
