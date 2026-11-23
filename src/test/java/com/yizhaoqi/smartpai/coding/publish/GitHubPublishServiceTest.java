package com.yizhaoqi.smartpai.coding.publish;

import com.fasterxml.jackson.databind.*;
import com.yizhaoqi.smartpai.agent.SourceMode;
import com.yizhaoqi.smartpai.agent.profile.WorkspacePermission;
import com.yizhaoqi.smartpai.coding.*;
import com.yizhaoqi.smartpai.coding.CodingTypes.*;
import com.yizhaoqi.smartpai.coding.task.CodingTaskStore;
import com.yizhaoqi.smartpai.coding.workspace.GitHubRepositoryClient;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GitHubPublishServiceTest {
    ObjectMapper json=new ObjectMapper();
    CodingTaskStore store=mock(CodingTaskStore.class);
    GitHubRepositoryClient github=mock(GitHubRepositoryClient.class);
    CodingProperties config=new CodingProperties();
    GitHubPublishService publisher;
    MockedStatic<CodingProperties> secrets;
    String id=UUID.randomUUID().toString(), workspaceId=UUID.randomUUID().toString(),base="a".repeat(40);
    String url="https://github.com/example/repo/pull/1";
    Workspace workspace;
    JsonNode result;
    @BeforeEach void setup() throws Exception {
        config.setEnabled(true);var repo=new CodingProperties.Repository();repo.setFullName("example/repo");repo.setAllowedUsers(Set.of(1L));repo.setPublishTokenEnv("TEST_PUBLISH_TOKEN");config.getRepositories().put("demo",repo);
        result=json.readTree("""
            {"ok":true,"changeSetHash":"reviewed-hash","changes":[{"path":"Example.java","content":"class Example {}","mode":"100644"}],
             "verification":{"id":"verified-run","status":"PASSED","changeSetHash":"reviewed-hash","tests":2,"failures":0,"errors":0}}
            """);
        workspace=new Workspace(workspaceId,1,"demo","main",base,Set.of(WorkspacePermission.READ,WorkspacePermission.WRITE,WorkspacePermission.EXECUTE),"FROZEN",null);
        when(store.workspace(1,workspaceId)).thenReturn(workspace);when(store.task(1,id)).thenAnswer(call -> task("NONE"));when(store.beginPublish(1,id)).thenReturn(true);
        when(github.branch(repo,"main")).thenReturn(base);
        secrets=mockStatic(CodingProperties.class);secrets.when(() -> CodingProperties.secret("TEST_PUBLISH_TOKEN")).thenReturn("fixture-token");
        publisher=new GitHubPublishService(store,config,github);
    }
    @AfterEach void close() { secrets.close(); }
    Task task(String state) { return new Task(id,1,workspaceId,"session","request","hash","fix",SourceMode.AUTO,Status.REVIEW_READY,workspace.permissions(),List.of(),"answer",result,"run",state,"CREATED".equals(state)?url:null); }
    GitHubPublishService.Confirmation confirmation() { return new GitHubPublishService.Confirmation(base,"reviewed-hash","verified-run"); }
    @Test void staleDiffOrVerificationCannotCauseAnyRemoteRequest() {
        assertThrows(ResponseStatusException.class,() -> publisher.publish(1,id,new GitHubPublishService.Confirmation(base,"stale","verified-run")));
        assertThrows(ResponseStatusException.class,() -> publisher.publish(1,id,new GitHubPublishService.Confirmation(base,"reviewed-hash","old-test")));
        verifyNoInteractions(github);verify(store,never()).beginPublish(anyLong(),anyString());
    }
    @Test void changedTargetBranchIsRejectedBeforeAnyRemoteMutation() {
        when(github.request(eq("GET"),contains("/pulls?"),isNull(),anyString(),eq(false))).thenReturn(json.createArrayNode());
        when(github.branch(any(),eq("main"))).thenReturn("b".repeat(40));
        assertThrows(ResponseStatusException.class,() -> publisher.publish(1,id,confirmation()));
        verify(github,never()).request(eq("POST"),anyString(),any(),anyString(),anyBoolean());
        verify(store).publication(id,"FAILED",null);
    }
    @Test void missingWritePermissionStopsBeforeCredentialsOrGitHubAreUsed() {
        when(store.workspace(1,workspaceId)).thenReturn(new Workspace(workspaceId,1,"demo","main",base,Set.of(WorkspacePermission.READ),"FROZEN",null));
        assertThrows(SecurityException.class,() -> publisher.publish(1,id,confirmation()));verifyNoInteractions(github);
    }
    @Test void pendingContainerCleanupCannotPublishEvenWithAnOtherwiseValidConfirmation() {
        ((com.fasterxml.jackson.databind.node.ObjectNode)result).put("cleanupPending",true);
        assertThrows(ResponseStatusException.class,() -> publisher.publish(1,id,confirmation()));verifyNoInteractions(github);
    }
    @Test void partialPublishingFailureRetriesTheExistingBranchWithoutOverwritingIt() {
        var created=new AtomicBoolean();var failFirstPr=new AtomicBoolean(true);
        when(github.request(anyString(),anyString(),any(),anyString(),anyBoolean())).thenAnswer(call -> {
            String method=call.getArgument(0),path=call.getArgument(1);
            if(path.contains("/pulls?")) return json.createArrayNode();
            if(path.endsWith("/git/commits/"+base)) return json.readTree("{\"tree\":{\"sha\":\"base-tree\"}}");
            if(path.endsWith("/git/trees")) return json.readTree("{\"sha\":\"new-tree\"}");
            if(path.contains("/git/ref/")) return created.get()?json.readTree("{\"object\":{\"sha\":\"commit\"}}"):null;
            if(path.endsWith("/git/commits/commit")) return json.valueToTree(Map.of("tree",Map.of("sha","new-tree"),"parents",List.of(Map.of("sha",base))));
            if(path.endsWith("/git/commits")) return json.readTree("{\"sha\":\"commit\"}");
            if(path.endsWith("/git/refs")) {created.set(true);return json.createObjectNode();}
            if(method.equals("POST") && path.endsWith("/pulls")) {
                if(failFirstPr.getAndSet(false)) throw new IllegalStateException("fixture connection lost");
                return json.valueToTree(Map.of("html_url",url));
            }
            throw new AssertionError(path);
        });
        assertThrows(IllegalStateException.class,() -> publisher.publish(1,id,confirmation()));
        assertEquals(url,publisher.publish(1,id,confirmation()));
        verify(github,times(1)).request(eq("POST"),endsWith("/git/refs"),any(),anyString(),eq(false));
        verify(store).publication(id,"CREATED",url);
    }
    @Test void alreadyCreatedPublicationIsIdempotentEvenWhenTheTargetHasAdvanced() {
        when(store.task(1,id)).thenReturn(task("CREATED"));
        assertEquals(url,publisher.publish(1,id,confirmation()));verifyNoInteractions(github);
    }
    @Test void lostPrResponseRecoversExistingPrBeforeCheckingTheNowAdvancedTarget() {
        when(github.request(eq("GET"),contains("/pulls?"),isNull(),anyString(),eq(false)))
                .thenReturn(json.valueToTree(List.of(Map.of("html_url",url))));
        assertEquals(url,publisher.publish(1,id,confirmation()));
        verify(github,never()).branch(any(),anyString());
        verify(github,never()).request(eq("POST"),anyString(),any(),anyString(),anyBoolean());
    }
}
