package com.yizhaoqi.smartpai.agent.tool;

/** Identity and conversation are injected by the server, never by model arguments. */
public record ToolExecutionContext(String userId,int referenceOffset,String toolCallId,String conversationId) {
    public ToolExecutionContext(String userId) { this(userId,0,null,null); }
    public ToolExecutionContext(String userId,int offset) { this(userId,offset,null,null); }
    public ToolExecutionContext(String userId,int offset,String callId) { this(userId,offset,callId,null); }
}
