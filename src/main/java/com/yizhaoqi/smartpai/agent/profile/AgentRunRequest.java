package com.yizhaoqi.smartpai.agent.profile;

import com.yizhaoqi.smartpai.agent.SourceMode;
import com.yizhaoqi.smartpai.agent.model.AgentMessage;
import java.util.List;

public record AgentRunRequest(String userId, String message, List<AgentMessage> history,
                              String conversationId, SourceMode sourceMode, AgentMode mode,
                              CodingExecutionContext coding) {
    public AgentRunRequest {
        history = history == null ? List.of() : List.copyOf(history);
        sourceMode = sourceMode == null ? SourceMode.AUTO : sourceMode;
        mode = mode == null ? AgentMode.WORK : mode;
        if (mode == AgentMode.WORK && coding != null) throw new SecurityException("Work cannot bind a workspace");
    }
}
