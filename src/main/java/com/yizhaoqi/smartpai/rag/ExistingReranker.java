package com.yizhaoqi.smartpai.rag;

import com.yizhaoqi.smartpai.client.RerankClient;
import com.yizhaoqi.smartpai.config.RetrievalProperties;
import com.yizhaoqi.smartpai.rag.model.RagTypes.Candidate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.*;

/** Reuses the configured DashScope adapter. No second provider configuration or scoring scale. */
@Component
public class ExistingReranker implements Reranker {
    public static class NotConfigured extends RuntimeException {}
    private final RerankClient client;
    private final RetrievalProperties config;
    public ExistingReranker(RerankClient client,RetrievalProperties config) { this.client=client; this.config=config; }
    public Mono<Map<String,Double>> rerank(String query,List<Candidate> candidates) {
        return Mono.fromCallable(() -> {
            if(!client.available()) throw new NotConfigured();
            List<String> documents=candidates.stream().map(c -> TokenBudget.prefixChars(c.text(),config.getRerank().getMaxDocChars())).toList();
            var hits=client.rerank(query,documents,documents.size());
            if(hits==null) throw new IllegalStateException("RERANK_UNAVAILABLE");
            Map<String,Double> scores=new LinkedHashMap<>();
            for(var hit:hits) {
                if(hit.index()<0 || hit.index()>=candidates.size() || !Double.isFinite(hit.score())
                        || scores.put(candidates.get(hit.index()).id(),hit.score())!=null)
                    throw new IllegalArgumentException("Invalid rerank response");
            }
            if(scores.size()!=candidates.size()) throw new IllegalArgumentException("Incomplete rerank response");
            return Map.copyOf(scores);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
