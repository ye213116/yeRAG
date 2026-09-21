package com.yizhaoqi.smartpai.agent.tool;

import java.util.Map;

public interface AgentTool {
    String name();
    Map<String, Object> definition();
    ToolExecutionResult execute(String arguments, ToolExecutionContext context);
}
