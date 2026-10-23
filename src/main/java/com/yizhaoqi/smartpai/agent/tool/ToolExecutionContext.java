package com.yizhaoqi.smartpai.agent.tool;

public record ToolExecutionContext(String userId,int referenceOffset,String toolCallId) {
    public ToolExecutionContext(String userId) { this(userId,0,null); }
    public ToolExecutionContext(String userId,int offset) { this(userId,offset,null); }
}
