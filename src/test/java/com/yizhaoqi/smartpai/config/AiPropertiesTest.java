package com.yizhaoqi.smartpai.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class AiPropertiesTest {

    @Test
    void providesSafeAgentDefaults() {
        AiProperties.Agent agent = new AiProperties().getAgent();

        assertTrue(agent.isEnabled());
        assertTrue(agent.isFallbackEnabled());
        assertEquals(8, agent.getMaxToolCalls());
        assertEquals(500, agent.getMaxQueryLength());
        assertEquals(5, agent.getDefaultTopK());
        assertEquals(10, agent.getMaxTopK());
        assertEquals(6000, agent.getMaxToolResultCharacters());
        assertEquals(Duration.ofSeconds(30), agent.getLlmTimeout());
        assertEquals(Duration.ofSeconds(10), agent.getToolTimeout());
        assertEquals(Duration.ofSeconds(60), agent.getTotalTimeout());
    }
}
