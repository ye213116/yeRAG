package com.yizhaoqi.smartpai.rag.stage;
import com.yizhaoqi.smartpai.rag.*;
import com.yizhaoqi.smartpai.rag.model.RagTypes.*;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
@Component
public class RetrieveStage implements RagStage {
    private final HybridSearchService search;
    public RetrieveStage(HybridSearchService search) { this.search=search; }
    public StageKey key() { return StageKey.RETRIEVE; }
    public Mono<StageOutcome> execute(RagContext c) {
        return search.searchWithPermissionDetailed(c.state().query(),c.request().userId(),c.request().options().candidateK(),c.request().options().rrfK())
            .map(result -> { c.state().retrieval(result); return new StageOutcome(result.status(),"RETRIEVAL_"+result.status(),java.util.Map.of(
                "status",result.status(),"routes",result.routes(),"candidates",result.candidates(),"query",c.state().query(),
                "candidateK",c.request().options().candidateK(),"rrfK",c.request().options().rrfK(),"fusionVersion","rrf-v1")); });
    }
}
