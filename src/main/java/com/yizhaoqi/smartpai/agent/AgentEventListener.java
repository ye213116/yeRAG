package com.yizhaoqi.smartpai.agent;

import java.util.Map;

public interface AgentEventListener {
    default void onStatus(String status, String message, Map<String, Object> details) {}
    default void onChunk(String chunk) {}
}
