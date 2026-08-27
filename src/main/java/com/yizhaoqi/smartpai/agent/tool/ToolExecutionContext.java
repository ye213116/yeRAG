package com.yizhaoqi.smartpai.agent.tool;

public record ToolExecutionContext(String userId, int referenceOffset) {
    public ToolExecutionContext(String userId) { this(userId, 0); }
}
