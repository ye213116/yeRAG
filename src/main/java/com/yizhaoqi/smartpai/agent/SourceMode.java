package com.yizhaoqi.smartpai.agent;

import com.yizhaoqi.smartpai.agent.tool.AgentTool;

/** Request-scoped source restriction, enforced before publishing or executing tools. */
public enum SourceMode {
    AUTO, KNOWLEDGE, WEB;

    public static SourceMode parse(Object value) {
        if (value == null) return AUTO;
        if (!(value instanceof String text)) throw new IllegalArgumentException("sourceMode must be auto, knowledge or web");
        return switch (text) {
            case "auto" -> AUTO;
            case "knowledge" -> KNOWLEDGE;
            case "web" -> WEB;
            default -> throw new IllegalArgumentException("sourceMode must be auto, knowledge or web");
        };
    }

    public boolean allows(AgentTool tool) {
        return this == AUTO || (this == KNOWLEDGE && tool.source() == AgentTool.Source.KNOWLEDGE)
                || (this == WEB && tool.source() == AgentTool.Source.WEB);
    }
}
