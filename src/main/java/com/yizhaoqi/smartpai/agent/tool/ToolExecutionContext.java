package com.yizhaoqi.smartpai.agent.tool;

/** Identity and conversation are injected by the server, never by model arguments. */
public record ToolExecutionContext(String userId,int referenceOffset,String toolCallId,String conversationId,
                                   com.yizhaoqi.smartpai.agent.SourceMode sourceMode,
                                   java.util.Map<Integer,com.yizhaoqi.smartpai.agent.SourceReference> sources,
                                   com.yizhaoqi.smartpai.agent.profile.CodingExecutionContext coding) {
    public ToolExecutionContext {
        sourceMode = sourceMode == null ? com.yizhaoqi.smartpai.agent.SourceMode.AUTO : sourceMode;
        sources = sources == null ? java.util.Map.of() : java.util.Map.copyOf(sources);
    }
    public ToolExecutionContext(String userId,int offset,String callId,String conversationId,
                                com.yizhaoqi.smartpai.agent.SourceMode sourceMode,
                                java.util.Map<Integer,com.yizhaoqi.smartpai.agent.SourceReference> sources) {
        this(userId,offset,callId,conversationId,sourceMode,sources,null);
    }
    public ToolExecutionContext(String userId,int offset,String callId,String conversationId) {
        this(userId,offset,callId,conversationId,com.yizhaoqi.smartpai.agent.SourceMode.AUTO,java.util.Map.of());
    }
    public ToolExecutionContext(String userId) { this(userId,0,null,null); }
    public ToolExecutionContext(String userId,int offset) { this(userId,offset,null,null); }
    public ToolExecutionContext(String userId,int offset,String callId) { this(userId,offset,callId,null); }
}
