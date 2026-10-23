package com.yizhaoqi.smartpai.rag;
import com.yizhaoqi.smartpai.rag.model.RagTypes.Request;
import com.yizhaoqi.smartpai.rag.trace.*;
public record RagContext(Request request, RagState state, RunContext runtime,
                         EvidenceRecorder.Run evidence, RagObserver observer) {}
