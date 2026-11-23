package com.yizhaoqi.smartpai.agent.profile;

/** Execution mode is independent of information-source restrictions. */
public enum AgentMode {
    WORK, CODING;

    public static AgentMode parse(Object value) {
        if (value == null || "work".equals(value)) return WORK;
        if ("coding".equals(value)) return CODING;
        throw new IllegalArgumentException("agentMode must be work or coding");
    }
}
