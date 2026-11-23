package com.yizhaoqi.smartpai.agent.profile;

import com.yizhaoqi.smartpai.config.AiProperties;
import java.time.Duration;

public final class AgentProfileResolver {
    private final AiProperties.Agent config;
    public AgentProfileResolver(AiProperties.Agent config) { this.config = config; }

    public AgentProfile resolve(AgentRunRequest request) {
        return resolve(request, 2);
    }

    public AgentProfile resolve(AgentRunRequest request, int memoryToolCalls) {
        boolean coding = request.mode() == AgentMode.CODING;
        var profile = coding ? config.getProfiles().getCoding() : config.getProfiles().getWork();
        if (coding) {
            if (!profile.isEnabled()) throw new IllegalStateException("Coding is disabled");
            if (request.coding() == null) throw new SecurityException("Coding requires an authorized workspace");
            request.coding().require(WorkspacePermission.READ);
        }
        int calls = profile.getMaxToolCalls() == null ? config.getMaxToolCalls() : profile.getMaxToolCalls();
        return new AgentProfile(request.mode(), profile.getAllowedTools(), calls,
                profile.getMaxDecisions() == null ? calls + config.getMaxArtifactToolCalls() + memoryToolCalls + 4 : profile.getMaxDecisions(),
                value(profile.getLlmTimeout(), config.getLlmTimeout()),
                value(profile.getToolTimeout(), config.getToolTimeout()),
                value(profile.getTotalTimeout(), config.getTotalTimeout()),
                !coding && config.getPlanning().isEnabled());
    }

    private static Duration value(Duration override, Duration fallback) { return override == null ? fallback : override; }
}
