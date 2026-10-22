package com.yizhaoqi.smartpai.agent.tool;

import java.util.Map;

public interface AgentTool {
    enum Source { KNOWLEDGE, WEB, OTHER }
    default Source source() { return Source.OTHER; }
    String name();
    Map<String, Object> definition();
    ToolExecutionResult execute(String arguments, ToolExecutionContext context);
}
