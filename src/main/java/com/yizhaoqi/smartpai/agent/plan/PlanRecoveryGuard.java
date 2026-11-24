package com.yizhaoqi.smartpai.agent.plan;

import com.yizhaoqi.smartpai.agent.*;
import com.yizhaoqi.smartpai.agent.profile.*;
import com.yizhaoqi.smartpai.agent.tool.ToolExecutionContext;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Old evidence carries no authority: recheck ACL and versioned private reads before using it. */
@Component
public class PlanRecoveryGuard {
    private final HybridSearchService search;
    private final AgentProfileResolver profiles;
    private final ToolAccessPolicy access = new ToolAccessPolicy();
    public PlanRecoveryGuard(HybridSearchService search, AiProperties ai) {
        this.search = search; this.profiles = new AgentProfileResolver(ai.getAgent());
    }
    public Mono<Void> validate(PlanStore.Execution execution, AgentRunRequest request, AgentToolRegistry.ToolCatalogSnapshot tools) {
        return Mono.defer(() -> {
            var saved = execution.checkpoint();
            var profile = profiles.resolve(request);
            if (saved.schemaVersion() != 1 || saved.messages().isEmpty() || saved.steps().size() < 3 || saved.steps().size() > 6)
                return Mono.error(new SecurityException("不兼容的执行检查点，请重新发起任务"));
            saved.messages().stream().flatMap(message -> message.toolCalls().stream()).forEach(call -> {
                var tool = tools.find(call.name()).orElseThrow(() -> new SecurityException("工具配置已变化"));
                access.require(profile, request, tool);
            });
            return Flux.fromIterable(saved.sources().values()).concatMap(source -> Mono.fromRunnable(() -> {
                if ("knowledge".equals(source.kind())) {
                    if (source.chunkId() == null || search.readDocumentWithPermission(source.fileMd5(), source.chunkId(), 1, request.userId())
                            .stream().noneMatch(chunk -> source.fileMd5().equals(chunk.getFileMd5()) && source.chunkId().equals(chunk.getChunkId())))
                        throw new SecurityException("原证据已不可访问，请重新发起任务");
                }
            }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()))
                    .thenMany(Flux.fromIterable(saved.reads()).concatMap(read -> {
                        var tool = tools.find(read.call().name()).orElseThrow(() -> new SecurityException("原读取工具不可用"));
                        if (!tool.replaySafe()) return Mono.error(new SecurityException("原读取工具不可安全重放"));
                        access.require(profile, request, tool);
                        var context = new ToolExecutionContext(request.userId(), read.offset(), "validate-" + read.call().id(),
                                request.conversationId(), request.sourceMode(), saved.sources(), null);
                        return tool.executeAsync(read.call().arguments(), context).timeout(profile.toolTimeout()).flatMap(result -> {
                            if (!result.success() || !PlanFingerprint.content(result.content()).equals(read.contentHash()))
                                return Mono.error(new SecurityException("记忆、历史或成果版本已变化，请重新发起任务"));
                            return Mono.empty();
                        });
                    })).then();
        });
    }
}
