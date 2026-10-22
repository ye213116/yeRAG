package com.yizhaoqi.smartpai.agent.mcp;

public interface McpClientFacadeFactory {
    McpClientFacade create(String serverId, McpProperties.Server server, Runnable toolsChanged);
}
