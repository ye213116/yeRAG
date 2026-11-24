package com.yizhaoqi.smartpai.agent.plan;

import com.fasterxml.jackson.databind.*;
import com.yizhaoqi.smartpai.agent.model.AgentMessage;
import com.yizhaoqi.smartpai.memory.MemoryStore;
import java.util.List;

/** JSON map ordering may change across JVMs; it must not invalidate an otherwise identical checkpoint. */
public final class PlanFingerprint {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private PlanFingerprint() {}
    private static String canonical(String value) {
        if (value == null) return null;
        try { return JSON.writeValueAsString(JSON.readValue(value, Object.class)); }
        catch (Exception text) { return value; }
    }
    public static String content(String value) { return MemoryStore.hash(canonical(value)); }
    public static String context(List<AgentMessage> messages) {
        try {
            var stable = messages.stream().map(message -> new AgentMessage(message.role(), canonical(message.content()),
                    message.toolCallId(), message.toolCalls())).toList();
            return MemoryStore.hash(JSON.writeValueAsString(stable));
        } catch (Exception invalid) { throw new IllegalStateException(invalid); }
    }
}
