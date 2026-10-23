package com.yizhaoqi.smartpai.rag;
import com.yizhaoqi.smartpai.rag.model.RagTypes.*;
import reactor.core.publisher.Mono;
public interface RagStage {
    StageKey key();
    Mono<StageOutcome> execute(RagContext context);
}
