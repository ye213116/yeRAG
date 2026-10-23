package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.agent.*;
import com.yizhaoqi.smartpai.agent.model.AgentMessage;
import com.yizhaoqi.smartpai.rag.*;
import com.yizhaoqi.smartpai.rag.model.RagTypes.*;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.function.Consumer;

@Service
public class RagFallbackService {
    private final RagPipeline pipeline;
    private final AiProperties config;
    public RagFallbackService(RagPipeline pipeline,AiProperties config) { this.pipeline=pipeline; this.config=config; }
    public Mono<AgentRunResult> answer(String user,String question,List<AgentMessage> history,AgentEventListener listener) {
        return Mono.defer(() -> pipeline.run(pipeline.request(user,question,history,Mode.FIXED_QA,
            config.getAgent().getDefaultTopK(),0),new RagObserver() {
                public void progress(String stage) { listener.onStatus("rag_stage",stage,Map.of()); }
                public void chunk(String text) { listener.onChunk(text); }
            }).map(result -> {
                var sources=RagSources.from(result.context(),0);
                String appendix=sources.isEmpty()?"":"\n\n参考来源：\n"+sources.values().stream().map(SourceReference::markdown).collect(java.util.stream.Collectors.joining("\n"));
                if(!appendix.isEmpty()) listener.onChunk(appendix);
                return new AgentRunResult(result.answer()+appendix,result.context().references(),sources);
            }));
    }
    public Disposable stream(String user,String question,List<Map<String,String>> history,
            Consumer<String> onChunk,Consumer<Map<Integer,String>> onReferences,
            Consumer<Throwable> onError,Runnable onComplete) {
        List<AgentMessage> messages=history.stream().map(m -> new AgentMessage(m.get("role"),m.get("content"),null,List.of())).toList();
        return answer(user,question,messages,new AgentEventListener() {
            public void onStatus(String status,String message,Map<String,Object> details) {}
            public void onChunk(String text) { onChunk.accept(text); }
        }).subscribe(result -> onReferences.accept(result.references()),onError,onComplete);
    }
}
