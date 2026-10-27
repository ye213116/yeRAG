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
        var request=c.request();
        var recall=request.recallStrategy()!=RecallStrategy.HYBRID
            ?search.searchWithPermissionDetailed(c.state().query(),request.userId(),request.options().candidateK(),request.options().rrfK(),request.corpusMd5s(),request.recallStrategy())
            :request.corpusMd5s().isEmpty()
            ?search.searchWithPermissionDetailed(c.state().query(),request.userId(),request.options().candidateK(),request.options().rrfK())
            :search.searchWithPermissionDetailed(c.state().query(),request.userId(),request.options().candidateK(),request.options().rrfK(),request.corpusMd5s());
        return recall
            .map(result -> { c.state().retrieval(result); return new StageOutcome(result.status(),"RETRIEVAL_"+result.status(),java.util.Map.of(
                "status",result.status(),"routes",result.routes(),"candidates",result.candidates(),"query",c.state().query(),
                "candidateK",c.request().options().candidateK(),"rrfK",c.request().options().rrfK(),"fusionVersion",request.recallStrategy()==RecallStrategy.HYBRID?"rrf-v1":"NOT_APPLIED","recallStrategy",request.recallStrategy())); });
    }
}
