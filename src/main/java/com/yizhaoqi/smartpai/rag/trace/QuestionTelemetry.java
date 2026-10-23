package com.yizhaoqi.smartpai.rag.trace;

import com.yizhaoqi.smartpai.agent.AgentRunResult;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.rag.RagFailure;
import com.yizhaoqi.smartpai.rag.model.RagTypes.Status;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.function.Supplier;

@Component
public class QuestionTelemetry {
    private final EvidenceRecorder recorder;
    private final EvidenceStore store;
    private final AiProperties ai;
    public QuestionTelemetry(EvidenceRecorder recorder,EvidenceStore store,AiProperties ai) { this.recorder=recorder; this.store=store; this.ai=ai; }
    public Mono<AgentRunResult> observe(String user,String question,String conversation,String requestId,Supplier<Mono<AgentRunResult>> work) {
        return observe(user,question,conversation,requestId,work,id -> {});
    }
    public Mono<AgentRunResult> observe(String user,String question,String conversation,String requestId,Supplier<Mono<AgentRunResult>> work,
                                        java.util.function.Consumer<String> started) {
        return Mono.defer(() -> {
            var run=recorder.start(user,null,"question",Map.of("question",question,"conversationId",Objects.toString(conversation,""),"requestId",Objects.toString(requestId,"")));
            started.accept(run.id());
            var context=new RunContext(user,run.id(),run.id(),System.nanoTime()+ai.getAgent().getTotalTimeout().toNanos(),run);
            return Mono.defer(work).timeout(context.remaining()).publishOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .map(result -> {
                    run.event("answer",Map.of("answer",result.answer(),"references",result.references(),"sources",result.sources()));
                    run.finish(Status.SUCCEEDED);
                    return new AgentRunResult(result.answer(),result.references(),result.sources(),run.id());
                })
                .doOnSuccess(result -> { if(result==null) run.finish(Status.SKIPPED); })
                .doOnError(error -> run.finish(RagFailure.status(error)))
                .doFinally(signal -> { if(signal==reactor.core.publisher.SignalType.CANCEL) run.finish(Status.CANCELLED); })
                .contextWrite(c -> c.put(RunContext.KEY,context));
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
    public void persisted(String id,String owner,boolean success) {
        if(id==null) return;
        try {
            ObjectNode run=(ObjectNode)store.read(id,owner);
            run.put("answerPersisted",success); run.put("persistenceCheckedAt",System.currentTimeMillis()); store.save(id,run);
        } catch(Exception error) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Cannot record persistence for run={}",id); }
    }
}
