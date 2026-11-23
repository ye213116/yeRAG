package com.yizhaoqi.smartpai.coding.task;

import com.fasterxml.jackson.databind.*;
import com.yizhaoqi.smartpai.agent.*;
import com.yizhaoqi.smartpai.agent.profile.*;
import com.yizhaoqi.smartpai.coding.*;
import com.yizhaoqi.smartpai.coding.CodingTypes.*;
import com.yizhaoqi.smartpai.coding.context.CodingContextService;
import com.yizhaoqi.smartpai.coding.sandbox.SandboxExecutor;
import com.yizhaoqi.smartpai.coding.tool.CodingTools.CodingToolExecutor;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.rag.trace.*;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CodingTaskWorkerTest {
    CodingTaskStore store=mock(CodingTaskStore.class);
    CodingTaskService tasks=mock(CodingTaskService.class);
    KnowledgeAgentService agent=mock(KnowledgeAgentService.class);
    SandboxExecutor sandbox=mock(SandboxExecutor.class);
    CodingToolExecutor tools=mock(CodingToolExecutor.class);
    CodingContextService context=mock(CodingContextService.class);
    ObjectMapper json=new ObjectMapper();
    Task task;
    Workspace workspace;
    CodingTaskWorker worker;
    @BeforeEach void setup() throws Exception {
        String id=UUID.randomUUID().toString(),wid=UUID.randomUUID().toString();
        var grants=Set.of(WorkspacePermission.READ,WorkspacePermission.WRITE);
        workspace=new Workspace(wid,1,"demo","main","a".repeat(40),grants,"NEW",id);
        task=new Task(id,1,wid,"session","request","hash","fix",SourceMode.AUTO,Status.PREPARING,grants,List.of(),null,null,null,"NONE",null);
        when(store.workspace(1,wid)).thenReturn(workspace);
        when(tasks.execution(task)).thenReturn(new CodingExecutionContext(id,wid,grants,() -> {}));
        when(context.build(eq(task),eq(workspace),any())).thenReturn(List.of());
        when(agent.run(any(AgentRunRequest.class),any())).thenReturn(Mono.just(new AgentRunResult("full answer",Map.of())));
        when(sandbox.execute(eq(workspace),eq(Operation.SNAPSHOT),anyMap(),any(),any()))
                .thenReturn(json.readTree("{\"ok\":true,\"changeSetHash\":\"hash\",\"changes\":[],\"diff\":\"fixture\"}"));
        var properties=new CodingProperties();properties.setEnabled(true);
        var repository=new CodingProperties.Repository();repository.setFullName("example/repo");repository.setAllowedUsers(Set.of(1L));properties.getRepositories().put("demo",repository);
        var ai=new AiProperties();ai.getAgent().getProfiles().getCoding().setEnabled(true);
        var recorder=mock(EvidenceRecorder.class);var run=mock(EvidenceRecorder.Run.class);
        when(recorder.start(anyString(),isNull(),eq("question"),any())).thenReturn(run);when(run.id()).thenReturn(UUID.randomUUID().toString());
        var telemetry=new QuestionTelemetry(recorder,mock(EvidenceStore.class),ai);
        worker=new CodingTaskWorker(store,tasks,properties,ai,agent,sandbox,tools,telemetry,json,context);
    }
    @AfterEach void close() { worker.close(); }
    @Test void durableSnapshotIsSavedBeforeWorkspaceDeletionAndCleanupCompletion() {
        worker.run(task);
        var ordered=inOrder(sandbox,store);
        ordered.verify(sandbox).prepare(eq(workspace),any(),any());
        ordered.verify(sandbox).stop(workspace.id());
        ordered.verify(sandbox).execute(eq(workspace),eq(Operation.SNAPSHOT),anyMap(),any(),any());
        ordered.verify(store).finish(eq(task.id()),eq(Status.REVIEW_READY),eq("full answer"),argThat(result -> result.path("cleanupPending").asBoolean() && "hash".equals(result.path("changeSetHash").asText())));
        ordered.verify(sandbox).destroy(workspace.id());
        ordered.verify(store).cleanupCompleted(task.id());
    }
    @Test void failedSqlSavePreservesWorkspaceForRecovery() {
        doThrow(new IllegalStateException("fixture SQL outage")).when(store).finish(anyString(),any(),anyString(),any());
        assertThrows(IllegalStateException.class,() -> worker.run(task));
        verify(sandbox,never()).destroy(anyString());verify(tools).forget(task.id());
    }
    @Test void cleanupFailureRetainsTheSavedResultAndPendingRetry() {
        doThrow(new IllegalStateException("fixture Docker outage")).when(sandbox).destroy(workspace.id());
        assertDoesNotThrow(() -> worker.run(task));
        verify(store).finish(eq(task.id()),eq(Status.REVIEW_READY),anyString(),argThat(result -> result.path("cleanupPending").asBoolean()));
        verify(store).event(eq(task.id()),eq("cleanup_pending"),any());verify(store,never()).cleanupCompleted(anyString());
    }
    @Test void cancelledAdmissionSettlesWithoutPreparingOrCallingTheModel() {
        when(tasks.execution(task)).thenReturn(new CodingExecutionContext(task.id(),workspace.id(),task.permissions(),() -> {throw new CodingTaskService.TaskCancelled();}));
        when(store.cancelled(task.id())).thenReturn(true);
        worker.run(task);
        verify(sandbox,never()).prepare(any(),any(),any());verifyNoInteractions(agent);
        verify(store).finish(eq(task.id()),eq(Status.CANCELLED),anyString(),any());
    }
}
