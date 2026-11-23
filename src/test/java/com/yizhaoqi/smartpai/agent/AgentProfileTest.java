package com.yizhaoqi.smartpai.agent;

import com.yizhaoqi.smartpai.agent.profile.*;
import com.yizhaoqi.smartpai.agent.tool.*;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class AgentProfileTest {
    private static AgentRunRequest request(AgentMode mode, SourceMode source, CodingExecutionContext coding) {
        return new AgentRunRequest("1","fix",List.of(),"conversation",source,mode,coding);
    }
    private static AgentTool tool(String name) {
        return new AgentTool() {
            public Source source() { return Source.WORKSPACE; }
            public String name() { return name; }
            public Map<String,Object> definition() { return Map.of(); }
            public ToolExecutionResult execute(String args,ToolExecutionContext context) { throw new AssertionError(); }
        };
    }
    @Test void workCannotGainWorkspaceAccessEvenWithAutoAndAnExplicitToolName() {
        var config = new AiProperties().getAgent();
        config.getProfiles().getWork().getAllowedTools().add("apply_patch");
        var request = request(AgentMode.WORK,SourceMode.AUTO,null);
        var profile = new AgentProfileResolver(config).resolve(request);
        assertFalse(new ToolAccessPolicy().allows(profile,request,tool("apply_patch")));
        assertEquals(Duration.ofSeconds(60),profile.totalTimeout());
    }
    @Test void codingRequiresFeatureFlagAndServerWorkspace() {
        var config = new AiProperties().getAgent();
        var resolver = new AgentProfileResolver(config);
        assertThrows(IllegalStateException.class,() -> resolver.resolve(request(AgentMode.CODING,SourceMode.AUTO,null)));
        config.getProfiles().getCoding().setEnabled(true);
        assertThrows(SecurityException.class,() -> resolver.resolve(request(AgentMode.CODING,SourceMode.AUTO,null)));
    }
    @Test void sourceSelectionDoesNotGrantWritesAndRevocationIsCheckedAtExecution() {
        var config = new AiProperties().getAgent(); config.getProfiles().getCoding().setEnabled(true);
        AtomicBoolean revoked = new AtomicBoolean();
        var coding = new CodingExecutionContext("task","workspace",Set.of(WorkspacePermission.READ),() -> {
            if (revoked.get()) throw new SecurityException();
        });
        var request = request(AgentMode.CODING,SourceMode.KNOWLEDGE,coding);
        var profile = new AgentProfileResolver(config).resolve(request);
        var policy = new ToolAccessPolicy();
        assertTrue(policy.allows(profile,request,tool("read_file")));
        assertFalse(policy.allows(profile,request,tool("apply_patch")));
        assertFalse(policy.allows(profile,request,tool("execute_command")));
        assertEquals(Duration.ofMinutes(15),profile.totalTimeout());
        assertFalse(profile.planningEnabled());
        revoked.set(true);
        assertThrows(SecurityException.class,() -> policy.require(profile,request,tool("read_file")));
    }
    @Test void modeParsingFailsClosedAndProfileCopiesTheAllowlist() {
        assertEquals(AgentMode.WORK,AgentMode.parse(null));
        assertThrows(IllegalArgumentException.class,() -> AgentMode.parse("AUTO"));
        var config = new AiProperties().getAgent();
        var profile = new AgentProfileResolver(config).resolve(request(AgentMode.WORK,SourceMode.AUTO,null));
        config.getProfiles().getWork().getAllowedTools().add("new_tool");
        assertFalse(profile.allowedTools().contains("new_tool"));
    }
    @Test void planningAlsoUsesTheSelectedProfileLlmTimeout() {
        AgentLlmClient client=new AgentLlmClient() {
            public reactor.core.publisher.Mono<com.yizhaoqi.smartpai.agent.model.AgentDecision> decide(
                    List<com.yizhaoqi.smartpai.agent.model.AgentMessage> messages,List<Map<String,Object>> tools) {
                return reactor.core.publisher.Mono.never();
            }
            public reactor.core.publisher.Flux<String> streamFinal(List<com.yizhaoqi.smartpai.agent.model.AgentMessage> messages) {
                return reactor.core.publisher.Flux.empty();
            }
        };
        var steps=new AgentPlanner(client,new AiProperties().getAgent()).plan(List.of(),List.of("search_knowledge_base"),Duration.ofMillis(10)).block(Duration.ofSeconds(2));
        assertTrue(steps.isEmpty());
    }
}
