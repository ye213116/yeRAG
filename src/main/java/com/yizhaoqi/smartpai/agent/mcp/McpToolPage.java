package com.yizhaoqi.smartpai.agent.mcp;

import java.util.List;

public record McpToolPage(List<McpToolDescriptor> tools, String nextCursor) {
    public McpToolPage {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
