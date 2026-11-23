package com.yizhaoqi.smartpai.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.SourceMode;
import com.yizhaoqi.smartpai.agent.profile.WorkspacePermission;
import com.yizhaoqi.smartpai.coding.CodingTypes.*;
import com.yizhaoqi.smartpai.coding.task.*;
import com.yizhaoqi.smartpai.coding.sandbox.SandboxExecutor;
import com.yizhaoqi.smartpai.coding.workspace.GitHubRepositoryClient;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.memory.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CodingTaskStoreTest {
    JdbcTemplate db;
    CodingTaskStore store;
    MemoryStore memory;
    CodingTaskService service;
    CodingProperties config;
    SandboxExecutor sandbox;
    Workspace workspace;
    String session;
    @BeforeEach void setup() throws Exception {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:coding"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(ds);
        try(var connection=ds.getConnection()) { ScriptUtils.executeSqlScript(connection,new FileSystemResource("docs/databases/ddl.sql")); }
        db.update("INSERT INTO users(id,username,password,role) VALUES(1,'alice','x','USER'),(2,'bob','x','USER')");
        var tx=new DataSourceTransactionManager(ds);
        store=new CodingTaskStore(db,new ObjectMapper(),tx);memory=new MemoryStore(db,tx,new MemoryProperties());
        config=new CodingProperties();config.setEnabled(true);
        var repo=new CodingProperties.Repository();repo.setFullName("example/repo");repo.setAllowedUsers(Set.of(1L));
        config.getRepositories().put("demo",repo);
        var ai=new AiProperties();ai.getAgent().getProfiles().getCoding().setEnabled(true);
        var context=mock(MemoryContextService.class);when(context.build(any())).thenReturn(new MemoryTypes.Context(List.of(),false));
        sandbox=mock(SandboxExecutor.class);
        service=new CodingTaskService(store,config,ai,memory,context,mock(GitHubRepositoryClient.class),sandbox,new ObjectMapper());
        workspace=store.createWorkspace(1,"demo","main","a".repeat(40),Set.of(WorkspacePermission.READ,WorkspacePermission.WRITE,WorkspacePermission.EXECUTE));
        session=memory.current(1,false);
    }
    Submission submit(String request) { return service.submit(1,workspace.id(),session,request,"fix this",SourceMode.AUTO); }
    @Test void submissionAtomicallySavesOneAcknowledgementAndSurvivesConversationSwitchAndDeletion() {
        var first=submit("r1");assertEquals(first.taskId(),submit("r1").taskId());
        assertEquals(1,memory.history(1,session).size());
        assertNull(memory.session(1,session).get("active_turn_id"));
        memory.current(1,true);memory.deleteSession(1,session);
        assertEquals(Status.QUEUED,store.task(1,first.taskId()).status());
        assertEquals(Status.PREPARING,store.claim().orElseThrow().status());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM coding_task",Integer.class));
    }
    @Test void parameterChangesAndWorkReplayCannotReuseCodingIdentity() {
        submit("r1");
        assertThrows(ResponseStatusException.class,() -> service.submit(1,workspace.id(),session,"r1","different",SourceMode.AUTO));
        assertThrows(ResponseStatusException.class,() -> service.submit(1,workspace.id(),session,"r1","fix this",SourceMode.WEB));
        assertThrows(ResponseStatusException.class,() -> memory.begin(1,session,"r1","fix this"));
    }
    @Test void secondTaskCannotStealWorkspaceAndLeavesNoQueuedOrActiveChatOrphan() {
        var first=submit("r1");
        assertThrows(ResponseStatusException.class,() -> submit("r2"));
        assertEquals(first.taskId(),store.workspace(1,workspace.id()).activeTaskId());
        assertEquals(1,store.list(1).size());assertNull(memory.session(1,session).get("active_turn_id"));
    }
    @Test void ownershipAndPermissionRevocationApplyAtExecutionTime() {
        var submitted=submit("r1");Task task=store.claim().orElseThrow();
        var execution=service.execution(task);execution.require(WorkspacePermission.READ);
        assertThrows(ResponseStatusException.class,() -> store.workspace(2,workspace.id()));
        assertThrows(ResponseStatusException.class,() -> service.view(2,submitted.taskId()));
        service.permissions(1,workspace.id(),Set.of(WorkspacePermission.READ));
        assertTrue(store.cancelled(task.id()));verify(sandbox).stop(workspace.id());
        assertThrows(SecurityException.class,() -> execution.require(WorkspacePermission.WRITE));
    }
    @Test void cancellationSettlesQueuedTasksAndRunningTasksOnlyAfterCleanup() {
        var queued=submit("r1");service.cancel(1,queued.taskId());
        assertEquals(Status.CANCELLED,store.task(1,queued.taskId()).status());assertTrue(store.claim().isEmpty());
        var running=submit("r2");Task task=store.claim().orElseThrow();service.cancel(1,running.taskId());
        assertEquals(Status.PREPARING,store.task(1,task.id()).status());
        assertThrows(CodingTaskService.TaskCancelled.class,() -> service.execution(task).require(WorkspacePermission.READ));
        store.finish(task.id(),Status.CANCELLED,"stopped",null);
        assertEquals(Status.CANCELLED,store.task(1,task.id()).status());
        assertEquals("FROZEN",store.workspace(1,workspace.id()).status());
    }
    @Test void staleExecutionIsIdentifiableAndNeverAutomaticallyClaimedAgain() {
        var submitted=submit("r1");store.claim();
        db.update("UPDATE coding_task SET heartbeat_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(90)),submitted.taskId());
        assertEquals(List.of(submitted.taskId()),store.interruptStale());assertTrue(store.claim().isEmpty());
        store.finish(submitted.taskId(),Status.INTERRUPTED,"restart",null);
        assertEquals(Status.INTERRUPTED,store.task(1,submitted.taskId()).status());
    }
    @Test void serverPermissionCeilingRejectsSelfGrantedWriteAndCleanupBlocksPublication() {
        config.getRepositories().get("demo").setAllowedPermissions(Set.of(WorkspacePermission.READ));
        assertThrows(SecurityException.class,() -> submit("denied"));
        config.getRepositories().get("demo").setAllowedPermissions(workspace.permissions());
        var accepted=submit("r1");store.claim();
        store.finish(accepted.taskId(),Status.REVIEW_READY,"done",new ObjectMapper().createObjectNode().put("cleanupPending",true));
        assertEquals(1,store.cleanupPending().size());store.cleanupCompleted(accepted.taskId());
        assertTrue(store.cleanupPending().isEmpty());
    }
}
