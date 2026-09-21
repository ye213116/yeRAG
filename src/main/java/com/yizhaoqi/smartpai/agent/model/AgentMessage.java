package com.yizhaoqi.smartpai.agent.model;

import java.util.List;

public record AgentMessage(String role, String content, String toolCallId, List<ToolCall> toolCalls) {
    public static AgentMessage system(String content) {
        return new AgentMessage("system", content, null, List.of());
    }

    public static AgentMessage user(String content) {
        return new AgentMessage("user", content, null, List.of());
    }

    public static AgentMessage assistant(String content) {
        return new AgentMessage("assistant", content, null, List.of());
    }

    public static AgentMessage assistantToolCalls(List<ToolCall> calls) {
        return new AgentMessage("assistant", null, null, List.copyOf(calls));
    }

    public static AgentMessage tool(String callId, String content) {
        return new AgentMessage("tool", content, callId, List.of());
    }
}
