package com.yizhaoqi.smartpai.agent.mcp;

import java.util.Map;

public interface McpClientFacade extends AutoCloseable {
    void initialize();
    McpToolPage listTools(String cursor);
    McpToolCallResult callTool(String toolName, Map<String, Object> arguments, String identityToken);
    @Override void close();
}
