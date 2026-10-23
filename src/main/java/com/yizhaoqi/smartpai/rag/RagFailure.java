package com.yizhaoqi.smartpai.rag;

import com.yizhaoqi.smartpai.rag.model.RagTypes.Status;
public class RagFailure extends RuntimeException {
    private final Status status;
    public RagFailure(Status status, String code) { super(code); this.status = status; }
    public Status status() { return status; }
    public static Status status(Throwable error) {
        if (error instanceof RagFailure failure) return failure.status();
        if (error instanceof java.util.concurrent.TimeoutException) return Status.TIMED_OUT;
        if (error instanceof java.util.concurrent.CancellationException) return Status.CANCELLED;
        return Status.FAILED;
    }
}
