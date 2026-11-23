package com.yizhaoqi.smartpai.agent.profile;

import java.time.Duration;
import java.util.Set;

public record AgentProfile(AgentMode mode, Set<String> allowedTools, int maxToolCalls,
                           int maxDecisions, Duration llmTimeout, Duration toolTimeout,
                           Duration totalTimeout, boolean planningEnabled) {
    public AgentProfile {
        allowedTools = Set.copyOf(allowedTools);
        if (maxToolCalls < 1 || maxToolCalls > 200 || maxDecisions < 1 || maxDecisions > 300)
            throw new IllegalArgumentException("Invalid profile budget");
        for (Duration duration : new Duration[]{llmTimeout, toolTimeout, totalTimeout})
            if (duration == null || duration.isNegative() || duration.isZero())
                throw new IllegalArgumentException("Profile timeouts must be positive");
    }
}
