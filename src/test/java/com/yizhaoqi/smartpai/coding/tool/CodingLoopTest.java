package com.yizhaoqi.smartpai.coding.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.*;
import com.yizhaoqi.smartpai.agent.model.*;
import com.yizhaoqi.smartpai.agent.profile.*;
import com.yizhaoqi.smartpai.agent.tool.*;
import com.yizhaoqi.smartpai.coding.CodingTypes.*;
import com.yizhaoqi.smartpai.coding.sandbox.SandboxExecutor;
import com.yizhaoqi.smartpai.coding.task.*;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.*;
import reactor.core.publisher.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CodingLoopTest {
    ObjectMapper json=new ObjectMapper();
    SandboxExecutor sandbox=mock(SandboxExecutor.class);
    CodingTaskStore store=mock(CodingTaskStore.class);
    CodingTools.CodingToolExecutor executor=new CodingTools.CodingToolExecutor(sandbox,store,json);
    AtomicInteger edits=new AtomicInteger(),tests=new AtomicInteger();
    List<Operation> executed=new ArrayList<>();
    AiProperties config=new AiProperties();
    String id=UUID.randomUUID().toString(),workspaceId=UUID.randomUUID().toString();
    Set<WorkspacePermission> grants=Set.of(WorkspacePermission.READ,WorkspacePermission.WRITE,WorkspacePermission.EXECUTE);
    AgentRunRequest request;
    @BeforeEach void setup() {
        config.getAgent().getProfiles().getCoding().setEnabled(true);
        var workspace=new Workspace(workspaceId,1,"demo","main","a".repeat(40),grants,"READY",id);
        var task=new Task(id,1,workspaceId,"session","request","hash","fix",SourceMode.AUTO,Status.RUNNING,grants,List.of(),null,null,null,"NONE",null);
        when(store.task(1,id)).thenReturn(task);when(store.workspace(1,workspaceId)).thenReturn(workspace);
        request=new AgentRunRequest("1","fix",List.of(),"session",SourceMode.KNOWLEDGE,AgentMode.CODING,new CodingExecutionContext(id,workspaceId,grants,() -> {}));
        when(sandbox.execute(any(),any(),anyMap(),any(),any())).thenAnswer(call -> {
            Operation op=call.getArgument(1);executed.add(op);
            if(op==Operation.PATCH) {edits.incrementAndGet();return json.valueToTree(Map.of("ok",true));}
            if(op==Operation.SNAPSHOT) return json.valueToTree(Map.of("ok",true,"changeSetHash","revision-"+edits.get(),"diff","fixture diff"));
            if(op==Operation.TEST) {
                int attempt=tests.incrementAndGet();
                return json.valueToTree(Map.of("ok",attempt>1,"exitCode",attempt>1?0:1,"text",attempt>1?"tests passed":"assertion failed",
                        "verification",Map.of("id","test-"+attempt,"status",attempt>1?"PASSED":"FAILED","changeSetHash","revision-"+edits.get(),"tests",1)));
            }
            return json.valueToTree(Map.of("ok",true,"content","fixture source"));
        });
    }
    KnowledgeAgentService runtime(List<AgentDecision> script) {
        var queue=new ArrayDeque<>(script);
        AgentLlmClient client=new AgentLlmClient() {
            public Mono<AgentDecision> decide(List<AgentMessage> messages,List<Map<String,Object>> definitions) {
                return Mono.fromSupplier(() -> {assertFalse(queue.isEmpty(),"Unexpected decision after scripted task completion");return queue.remove();});
            }
            public Flux<String> streamFinal(List<AgentMessage> messages) { return Flux.just("budget-limited summary"); }
        };
        var factory=new CodingTools();
        var agent=new KnowledgeAgentService(client,new AgentToolRegistry(List.of(factory.readFile(executor),factory.applyPatch(executor),factory.executeCommand(executor),factory.gitDiff(executor))),config);
        agent.configureCodingGate(new CodingCompletionGate(sandbox,store,executor));return agent;
    }
    AgentDecision call(String name,String args) { return new AgentDecision(null,List.of(new ToolCall(UUID.randomUUID().toString(),name,args))); }
    AgentDecision done() { return new AgentDecision("verified summary",List.of()); }
    @Test void oneSharedLoopCanReadEditFailRepairRetestAndFinish() {
        var agent=runtime(List.of(call("read_file","{\"path\":\"Example.java\"}"),
                call("apply_patch","{\"patch\":\"first\",\"expectedHashes\":{}}"),call("execute_command","{\"test\":true}"),
                call("apply_patch","{\"patch\":\"repair\",\"expectedHashes\":{}}"),call("execute_command","{\"test\":true}"),done()));
        assertEquals("verified summary",agent.run(request,new AgentEventListener(){}).block().answer());
        assertEquals(2,edits.get());assertEquals(2,tests.get());
        assertEquals("PASSED",executor.verification(id).path("status").asText());
        assertEquals("revision-2",executor.verification(id).path("changeSetHash").asText());
        assertTrue(executed.indexOf(Operation.PATCH)<executed.indexOf(Operation.TEST));
    }
    @Test void modelCannotFinishBeforeServerVerificationAndTheGateFeedsFailuresBackIntoTheLoop() {
        var agent=runtime(List.of(done(),call("apply_patch","{\"patch\":\"repair\",\"expectedHashes\":{}}"),done(),done()));
        assertEquals("verified summary",agent.run(request,new AgentEventListener(){}).block().answer());
        assertEquals(2,tests.get());assertEquals(1,edits.get());assertEquals("PASSED",executor.verification(id).path("status").asText());
    }
    @Test void aLaterEditInvalidatesOldVerificationEvenIfTheModelDoesNotRequestAnotherTest() {
        tests.set(1);
        var agent=runtime(List.of(call("execute_command","{\"test\":true}"),call("apply_patch","{\"patch\":\"later edit\",\"expectedHashes\":{}}"),done(),done()));
        agent.run(request,new AgentEventListener(){}).block();
        assertEquals(3,tests.get());assertEquals("revision-1",executor.verification(id).path("changeSetHash").asText());
    }
    @Test void identityAndWorkspaceFieldsInModelArgumentsNeverReachTheSandbox() {
        var factory=new CodingTools();var tool=factory.readFile(executor);
        var result=tool.execute("{\"path\":\"Example.java\",\"workspaceId\":\"other\"}",new ToolExecutionContext("1",0,"call","session",SourceMode.AUTO,Map.of(),request.coding()));
        assertFalse(result.success());verifyNoInteractions(sandbox);
    }
}
