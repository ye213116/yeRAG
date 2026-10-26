package com.yizhaoqi.smartpai.rag;

import com.yizhaoqi.smartpai.agent.model.AgentMessage;
import com.yizhaoqi.smartpai.rag.model.RagTypes.*;
import com.yizhaoqi.smartpai.rag.trace.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.*;

@Service
public class RagPipeline {
    private final List<RagStage> stages;
    private final RagProperties properties;
    private final EvidenceRecorder recorder;
    private final com.yizhaoqi.smartpai.config.RetrievalProperties retrieval;
    private final com.yizhaoqi.smartpai.config.AiProperties ai;
    public RagPipeline(List<RagStage> stages, RagProperties properties, EvidenceRecorder recorder,com.yizhaoqi.smartpai.config.RetrievalProperties retrieval,com.yizhaoqi.smartpai.config.AiProperties ai) {
        this.stages=stages.stream().sorted(Comparator.comparing(RagStage::key)).toList();
        if (this.stages.size()!=StageKey.values().length || this.stages.stream().map(RagStage::key).distinct().count()!=stages.size())
            throw new IllegalArgumentException("Exactly one implementation per RAG stage required");
        this.properties=properties; this.recorder=recorder; this.retrieval=retrieval; this.ai=ai; properties.validate();
    }
    public Request request(String user, String question, List<AgentMessage> history, Mode mode, int topK, int offset) {
        if(!user.startsWith("id:") && !user.startsWith("name:")) user=user.matches("[0-9]+")?"id:"+user:"name:"+user;
        var p=properties;
        topK=Math.max(1,Math.min(retrieval.getMaxTopK(),topK));
        int depth=p.getCandidateK()==0?(int)Math.min(10000,(long)topK*Math.max(1,retrieval.getRecallMultiplier())):p.getCandidateK();
        return new Request(user,question,history,mode,topK,offset,new Options(p.isRewriteEnabled() && ai.getAgent().isQueryRewriteEnabled(),p.isRerankEnabled() && retrieval.getRerank().isEnabled() && retrieval.getMode()==com.yizhaoqi.smartpai.config.RetrievalProperties.Mode.RRF,
            p.isMergeEnabled(),p.isLegacyClipping(),depth,retrieval.getRrfRankConstant(),p.getEvidenceTokens(),
            p.getContextTokens(),p.getSafetyTokens(),p.getRerankThreshold()));
    }
    public Mono<Result> run(Request request, RagObserver observer) {
        return Mono.deferContextual(view -> {
            RunContext parent=view.getOrDefault(RunContext.KEY,null);
            if (parent!=null && !parent.owner().equals(request.userId())) return Mono.error(new SecurityException("Run owner mismatch"));
            var evidence=recorder.start(request.userId(),parent==null?null:parent.parentRunId(),"pipeline",request);
            var runtime=parent==null?new RunContext(request.userId(),evidence.id(),evidence.id(),System.nanoTime()+ai.getAgent().getTotalTimeout().toNanos()):parent.child(evidence.id());
            evidence.event("retrievalConfig",Map.of("mode",retrieval.getMode(),"bm25Weight",retrieval.getBm25Weight(),"knnWeight",retrieval.getKnnWeight(),
                "routeTimeoutMs",retrieval.getRouteTimeout().toMillis(),"candidateIdentity","INDEX_AND_ES_HIT_ID"));
            if (view.hasKey("rag.toolCallId")) evidence.event("toolCallId",view.get("rag.toolCallId"));
            var context=new RagContext(request,new RagState(),runtime,evidence,observer);
            var degraded=new java.util.concurrent.atomic.AtomicBoolean();
            Mono<Void> chain=Mono.empty();
            for (RagStage stage : stages) {
                if (stage.key()==StageKey.GENERATE && request.mode()==Mode.RETRIEVE_ONLY) continue;
                chain=chain.then(Mono.defer(() -> {
                    observer.progress(stage.key().name());
                    long started=System.nanoTime();
                    Duration remaining=runtime.remaining();
                    Duration timeout=stage.key()==StageKey.GENERATE?remaining:remaining.compareTo(properties.getStageTimeout())<0?remaining:properties.getStageTimeout();
                    return Mono.defer(() -> stage.execute(context)).timeout(timeout)
                        .doOnNext(outcome -> {
                            if (outcome.status()==Status.DEGRADED) degraded.set(true);
                            evidence.event(stage.key().name(),Map.of("elapsedMs",(System.nanoTime()-started)/1_000_000,"outcome",outcome));
                            if(stage.key()==StageKey.RERANK) {
                                String scoreKind=outcome.status()==Status.SUCCEEDED?"RERANK":retrieval.getMode()==com.yizhaoqi.smartpai.config.RetrievalProperties.Mode.LEGACY?"ES":"RRF";
                                var top=context.state().rerank().candidates().stream().limit(request.topK()).toList();
                                List<RankedChunk> snapshot=new ArrayList<>();
                                for(int i=0;i<top.size();i++) snapshot.add(RankedChunk.of(top.get(i),i+1,scoreKind));
                                evidence.event("TOP_K",Map.of("k",request.topK(),"candidates",snapshot));
                            }
                            if (outcome.status()==Status.FAILED) throw new RagFailure(Status.FAILED,outcome.reason());
                        })
                        .doOnError(error -> evidence.event(stage.key().name(),Map.of("status",RagFailure.status(error),
                            "elapsedMs",(System.nanoTime()-started)/1_000_000,"errorCode",error.getClass().getSimpleName())))
                        .doOnCancel(() -> evidence.event(stage.key().name(),Map.of("status","CANCELLED","elapsedMs",(System.nanoTime()-started)/1_000_000)))
                        .then();
                }));
            }
            return chain.publishOn(reactor.core.scheduler.Schedulers.boundedElastic()).then(Mono.fromSupplier(() -> {
                    Status status=context.state().context().passages().isEmpty()?Status.NO_EVIDENCE:degraded.get()?Status.DEGRADED:Status.SUCCEEDED;
                    evidence.finish(status);
                    return new Result(evidence.id(),status,context.state().context(),context.state().answer());
                }))
                .doOnError(error -> evidence.finish(RagFailure.status(error)))
                .doFinally(signal -> { if (signal==reactor.core.publisher.SignalType.CANCEL) evidence.finish(Status.CANCELLED); })
                .contextWrite(c -> c.put(RunContext.KEY,runtime));
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
}
