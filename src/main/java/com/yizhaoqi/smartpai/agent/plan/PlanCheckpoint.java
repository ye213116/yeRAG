package com.yizhaoqi.smartpai.agent.plan;

import com.yizhaoqi.smartpai.agent.SourceReference;
import com.yizhaoqi.smartpai.agent.model.AgentMessage;
import com.yizhaoqi.smartpai.agent.model.ToolCall;
import java.util.*;

/** Written only at step boundaries, never with an unmatched assistant/tool-call batch. */
public record PlanCheckpoint(int schemaVersion, String contextHash, String policyHash, List<AgentMessage> messages,
                             List<PlanStep> steps, Map<Integer, String> references,
                             Map<Integer, SourceReference> sources, List<ReadValidation> reads,
                             int calls, int memoryCalls, int artifactCalls, int decisions, int referenceOffset) {
    public record ReadValidation(ToolCall call, int offset, String contentHash) {}
    public PlanCheckpoint {
        messages = List.copyOf(messages); steps = List.copyOf(steps);
        references = Map.copyOf(references); sources = Map.copyOf(sources); reads = List.copyOf(reads);
    }
}
