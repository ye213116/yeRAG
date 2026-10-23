package com.yizhaoqi.smartpai.rag.trace;

import com.yizhaoqi.smartpai.rag.RagFailure;
import com.yizhaoqi.smartpai.rag.model.RagTypes.Status;
import java.time.Duration;

/** Request-owned identity/deadline. Propagated with Reactor Context, never model arguments. */
public record RunContext(String owner, String questionRunId, String parentRunId, long deadlineNanos,
                         EvidenceRecorder.Run questionEvidence) {
    public RunContext(String owner,String questionRunId,String parentRunId,long deadlineNanos) {
        this(owner,questionRunId,parentRunId,deadlineNanos,null);
    }
    public static final Class<RunContext> KEY = RunContext.class;
    public Duration remaining() {
        long remaining=deadlineNanos-System.nanoTime();
        if (remaining<=0) throw new RagFailure(Status.TIMED_OUT,"QUESTION_DEADLINE_EXCEEDED");
        return Duration.ofNanos(remaining);
    }
    public RunContext child(String parent) {
        if (questionEvidence!=null) questionEvidence.event("child", java.util.Map.of("runId",parent,"parentRunId",parentRunId));
        return new RunContext(owner, questionRunId,parent,deadlineNanos,questionEvidence);
    }
}
