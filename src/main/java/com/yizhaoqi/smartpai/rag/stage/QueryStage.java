package com.yizhaoqi.smartpai.rag.stage;

import com.yizhaoqi.smartpai.agent.tool.QueryRewriteService;
import com.yizhaoqi.smartpai.rag.*;
import com.yizhaoqi.smartpai.rag.model.RagTypes.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.Map;

@Component
public class QueryStage implements RagStage {
    private final QueryRewriteService rewriter;
    public QueryStage(QueryRewriteService rewriter) { this.rewriter=rewriter; }
    public StageKey key() { return StageKey.QUERY; }
    public Mono<StageOutcome> execute(RagContext c) {
        if(!c.request().options().rewrite()) return Mono.fromSupplier(() -> publish(c,c.request().question(),Status.SKIPPED,"DISABLED"));
        return rewriter.rewriteDetailed(c.request().question(),c.request().history())
            .map(result -> publish(c,result.query(),result.status(),result.reason()));
    }
    private StageOutcome publish(RagContext c,String query,Status status,String reason) {
        c.state().query(query);
        return new StageOutcome(status,reason,Map.of("original",c.request().question(),"rewritten",query,"promptVersion","rewrite-json-v1"));
    }
}
