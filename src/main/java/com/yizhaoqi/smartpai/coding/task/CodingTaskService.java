package com.yizhaoqi.smartpai.coding.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.SourceMode;
import com.yizhaoqi.smartpai.agent.profile.*;
import com.yizhaoqi.smartpai.coding.*;
import com.yizhaoqi.smartpai.coding.CodingTypes.*;
import com.yizhaoqi.smartpai.coding.sandbox.SandboxExecutor;
import com.yizhaoqi.smartpai.coding.workspace.GitHubRepositoryClient;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.memory.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@ConditionalOnProperty(name="ai.coding.enabled",havingValue="true")
public class CodingTaskService {
    private final CodingTaskStore store;
    private final CodingProperties properties;
    private final AiProperties ai;
    private final MemoryStore memory;
    private final MemoryContextService context;
    private final GitHubRepositoryClient github;
    private final SandboxExecutor sandbox;
    private final ObjectMapper json;
    public CodingTaskService(CodingTaskStore store,CodingProperties properties,AiProperties ai,MemoryStore memory,
                             MemoryContextService context,GitHubRepositoryClient github,SandboxExecutor sandbox,ObjectMapper json) {
        this.store=store;this.properties=properties;this.ai=ai;this.memory=memory;this.context=context;this.github=github;this.sandbox=sandbox;this.json=json;
    }
    public void enabled() {
        if(!properties.isEnabled() || !ai.getAgent().isEnabled() || !ai.getAgent().getProfiles().getCoding().isEnabled())
            throw new IllegalStateException("Coding is disabled");
    }
    public Workspace create(long owner,String repositoryId,String branch,Set<WorkspacePermission> permissions) {
        enabled();
        var repository=properties.repository(repositoryId,owner);
        var granted=CodingTypes.permissions(permissions);
        sandbox.preflight();
        return store.createWorkspace(owner,repositoryId,branch,github.branch(repository,branch),granted);
    }
    public Submission submit(long owner,String workspaceId,String session,String requestId,String question,SourceMode sourceMode) {
        enabled();
        Workspace workspace=store.workspace(owner,workspaceId);
        properties.repository(workspace.repositoryId(),owner);
        String fingerprint=MemoryStore.hash(store.encode(List.of("coding",workspaceId,session,question,sourceMode.name(),workspace.permissions().stream().map(Enum::name).sorted().toList())));
        Task replay=store.replay(owner,requestId,fingerprint);
        if(replay!=null) return acknowledgement(replay.id());
        var begin=memory.begin(owner,session,requestId,question,fingerprint);
        if(!begin.fresh()) throw CodingTaskStore.conflict("提交确认不存在，请检查任务列表");
        var turn=begin.turn();
        try {
            var history=context.build(turn).messages();
            String id=UUID.randomUUID().toString();
            var task=new Task(id,owner,workspaceId,session,requestId,fingerprint,question,sourceMode,Status.QUEUED,
                    workspace.permissions(),history,null,null,null,"NONE",null);
            var response=acknowledgement(id);
            return store.transaction(() -> {
                store.enqueue(task);
                if(!memory.complete(turn,response.answer())) throw CodingTaskStore.conflict("会话已经切换，任务未提交");
                return response;
            });
        } catch(RuntimeException failed) { memory.cancel(turn,"FAILED"); throw failed; }
    }
    private Submission acknowledgement(String id) {
        return new Submission(id,"Coding 任务已提交，可在开发任务面板查看进度。任务 ID："+id+"。关闭网页或切换对话后仍会继续执行。");
    }
    public CodingExecutionContext execution(Task task) {
        return new CodingExecutionContext(task.id(),task.workspaceId(),task.permissions(),() -> {
            enabled();
            Workspace w=store.workspace(task.ownerId(),task.workspaceId());
            properties.repository(w.repositoryId(),task.ownerId());
            if(!w.permissions().containsAll(task.permissions())) throw new SecurityException("Workspace permissions were revoked");
            if(!task.id().equals(w.activeTaskId()) || !store.running(task.id()) || store.cancelled(task.id()))
                throw new TaskCancelled();
        });
    }
    public Map<String,Object> view(long owner,String id) {
        Task task=store.task(owner,id);
        Workspace workspace=store.workspace(owner,task.workspaceId());
        properties.repository(workspace.repositoryId(),owner);
        var view=new LinkedHashMap<String,Object>();
        view.put("id",id);view.put("workspaceId",task.workspaceId());view.put("status",task.status());view.put("summary",task.summary());
        view.put("baseSha",workspace.baseSha());view.put("result",task.result());view.put("runId",task.runId());
        view.put("publishState",task.publishState());view.put("prUrl",task.prUrl());view.put("events",store.events(id));
        return view;
    }
    public void permissions(long owner,String id,Set<WorkspacePermission> permissions) {
        Workspace workspace=store.workspace(owner,id);properties.repository(workspace.repositoryId(),owner);
        store.permissions(owner,id,CodingTypes.permissions(permissions));
        if(workspace.activeTaskId()!=null) {
            Task task=store.task(owner,workspace.activeTaskId());
            if(!permissions.containsAll(task.permissions())) { store.cancel(owner,task.id()); sandbox.stop(id); }
        }
    }
    public void cancel(long owner,String id) {
        Task task=store.task(owner,id);store.cancel(owner,id);
        if(task.id().equals(store.workspace(owner,task.workspaceId()).activeTaskId()) && store.cancelled(id)) sandbox.stop(task.workspaceId());
    }
    public static final class TaskCancelled extends RuntimeException {}
}
