package com.yizhaoqi.smartpai.agent.tool;

import java.util.Map;

public interface AgentTool {
    enum Source { KNOWLEDGE, WEB, MEMORY, ARTIFACT, WORKSPACE, OTHER }
    default Source source() { return Source.OTHER; }
    /** Explicit opt-in: interrupted calls may run again during step recovery. */
    default boolean replaySafe() { return false; }
    String name();
    Map<String, Object> definition();
    default reactor.core.publisher.Mono<ToolExecutionResult> executeAsync(String arguments,ToolExecutionContext context) {
        return reactor.core.publisher.Mono.fromCallable(() -> execute(arguments,context))
            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
    ToolExecutionResult execute(String arguments, ToolExecutionContext context);
}
