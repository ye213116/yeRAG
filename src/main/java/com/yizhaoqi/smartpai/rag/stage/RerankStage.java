package com.yizhaoqi.smartpai.rag.stage;
import com.yizhaoqi.smartpai.rag.*;
import com.yizhaoqi.smartpai.rag.model.RagTypes.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.*;
@Component
public class RerankStage implements RagStage {
    private final Reranker reranker;
    private final RagProperties config;
    private final com.yizhaoqi.smartpai.config.RetrievalProperties retrieval;
    public RerankStage(Reranker reranker,RagProperties config,com.yizhaoqi.smartpai.config.RetrievalProperties retrieval) {
        this.reranker=reranker; this.config=config; this.retrieval=retrieval;
    }
    public StageKey key() { return StageKey.RERANK; }
    public Mono<StageOutcome> execute(RagContext c) {
        List<Candidate> input=c.state().retrieval().candidates();
        if (!c.request().options().rerank() || input.isEmpty()) return Mono.just(publish(c,input,null,Status.SKIPPED,"DISABLED_OR_EMPTY"));
        // Reserve time for downstream stages. A model timeout is recoverable; total deadline is not.
        var remaining=c.runtime().remaining();
        var timeout=config.getStageTimeout().dividedBy(2);
        if (timeout.compareTo(remaining)>0) timeout=remaining;
        List<Candidate> window=input.stream().limit(Math.max(1,retrieval.getRerank().getTopN())).toList();
        c.evidence().event("rerankRequest",Map.of("query",c.state().query(),"model",retrieval.getRerank().getModel(),
            "documents",window.stream().map(v -> Map.of("id",v.id(),"text",TokenBudget.prefixChars(v.text(),retrieval.getRerank().getMaxDocChars()))).toList(),
            "maxDocChars",retrieval.getRerank().getMaxDocChars()));
        if(timeout.compareTo(retrieval.getRerank().getTimeout())>0) timeout=retrieval.getRerank().getTimeout();
        return Mono.defer(() -> reranker.rerank(c.state().query(),window)).timeout(timeout)
            .map(scores -> publish(c,input,scores,Status.SUCCEEDED,"RERANKED"))
            .onErrorResume(error -> {
                c.runtime().remaining();
                if(error instanceof ExistingReranker.NotConfigured) return Mono.just(publish(c,input,null,Status.SKIPPED,"NOT_CONFIGURED"));
                if (error instanceof SecurityException) return Mono.error(error);
                c.evidence().event("rerankFailure",Map.of("errorCode",error.getClass().getSimpleName()));
                return Mono.just(publish(c,input,null,Status.DEGRADED,"RERANK_FAILED"));
            });
    }
    private StageOutcome publish(RagContext c,List<Candidate> input,Map<String,Double> scores,Status status,String reason) {
        List<Candidate> ranked=scores==null?input:input.stream().filter(v -> scores.containsKey(v.id())).map(v -> {
            Double score=scores.get(v.id()); if (score==null || !Double.isFinite(score)) throw new IllegalArgumentException("Missing rerank score");
            return v.scored(score,v.routeScores());
        }).sorted(Comparator.comparingDouble(Candidate::score).reversed().thenComparing(Candidate::id)).toList();
        List<Candidate> kept=ranked.stream().filter(v -> scores==null || v.score()>=c.request().options().rerankThreshold()).toList();
        List<Decision> decisions=new ArrayList<>();
        for (int i=0;i<input.size();i++) {
            Candidate v=input.get(i); int rank=-1;
            for (int j=0;j<kept.size();j++) if (kept.get(j).id().equals(v.id())) rank=j+1;
            // A candidate outside topN has no rerank score. Keep the boxed null:
            // mixing primitive v.score() with Double in a ternary would unbox it.
            Double score=scores==null?Double.valueOf(v.score()):scores.get(v.id());
            decisions.add(new Decision(v.id(),rank>0,rank>0?reason:scores!=null && !scores.containsKey(v.id())?"OUTSIDE_RERANK_WINDOW":"BELOW_THRESHOLD",i+1,rank,score));
        }
        Selection selection=new Selection(kept,decisions); c.state().rerank(selection);
        return new StageOutcome(status,reason,Map.of("candidates",selection.candidates(),"decisions",selection.decisions(),
            "model",scores==null?"NOT_USED":retrieval.getRerank().getModel(),"threshold",c.request().options().rerankThreshold(),"policyVersion","rerank-v1"));
    }
}
