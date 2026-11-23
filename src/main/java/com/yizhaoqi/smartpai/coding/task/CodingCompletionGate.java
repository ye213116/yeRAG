package com.yizhaoqi.smartpai.coding.task;

import com.yizhaoqi.smartpai.agent.profile.*;
import com.yizhaoqi.smartpai.coding.CodingTypes.Operation;
import com.yizhaoqi.smartpai.coding.sandbox.SandboxExecutor;
import com.yizhaoqi.smartpai.coding.tool.CodingTools.CodingToolExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Map;

/** Concrete Coding completion rule; it does not introduce another agent loop. */
@Component
@ConditionalOnProperty(name="ai.coding.enabled",havingValue="true")
public class CodingCompletionGate {
    private final SandboxExecutor sandbox;
    private final CodingTaskStore store;
    private final CodingToolExecutor tools;
    public CodingCompletionGate(SandboxExecutor sandbox,CodingTaskStore store,CodingToolExecutor tools) {
        this.sandbox=sandbox;this.store=store;this.tools=tools;
    }
    public boolean requiresVerification(AgentRunRequest request) {
        var context=request.coding();
        if(context==null || !context.permissions().contains(WorkspacePermission.EXECUTE) || tools.attempts(context.taskId())>=3) return false;
        context.require(WorkspacePermission.READ);
        var workspace=store.workspace(Long.parseLong(request.userId()),context.workspaceId());
        var snapshot=sandbox.execute(workspace,Operation.SNAPSHOT,Map.of(),Duration.ofSeconds(30),() -> context.require(WorkspacePermission.READ));
        if(!snapshot.path("ok").asBoolean()) throw new IllegalStateException("Cannot verify workspace snapshot");
        var verification=tools.verification(context.taskId());
        return verification==null || !"PASSED".equals(verification.path("status").asText()) || !verification.path("changeSetHash").asText().equals(snapshot.path("changeSetHash").asText());
    }
}
