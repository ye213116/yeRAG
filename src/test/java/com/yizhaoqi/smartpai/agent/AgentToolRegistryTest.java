package com.yizhaoqi.smartpai.agent;

import com.yizhaoqi.smartpai.agent.tool.AgentTool;
import com.yizhaoqi.smartpai.agent.tool.ToolExecutionContext;
import com.yizhaoqi.smartpai.agent.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentToolRegistryTest {
    @Test
    void atomicallyReplacesMcpToolsWithoutChangingExistingSnapshot() {
        AgentTool local = tool("search_knowledge_base");
        AgentToolRegistry registry = new AgentToolRegistry(List.of(local));
        AgentToolRegistry.ToolCatalogSnapshot before = registry.snapshot();

        registry.replaceMcpTools("ops", List.of(tool("ops__lookup")));
        AgentToolRegistry.ToolCatalogSnapshot after = registry.snapshot();

        assertTrue(before.find("search_knowledge_base").isPresent());
        assertTrue(before.find("ops__lookup").isEmpty());
        assertTrue(after.find("search_knowledge_base").isPresent());
        assertTrue(after.find("ops__lookup").isPresent());

        registry.removeMcpTools("ops");
        assertTrue(registry.find("ops__lookup").isEmpty());
        assertTrue(after.find("ops__lookup").isPresent());
    }

    @Test
    void conflictingServerUpdateDoesNotPoisonPublishedCatalog() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool("local")));
        registry.replaceMcpTools("ops", List.of(tool("ops__lookup")));
        assertThrows(IllegalStateException.class,
                () -> registry.replaceMcpTools("other", List.of(tool("ops__lookup"))));
        assertEquals(2, registry.snapshot().tools().size());
    }

    @Test
    void rejectsDuplicateLocalAndConflictingMcpNames() {
        assertThrows(IllegalStateException.class,
                () -> new AgentToolRegistry(List.of(tool("same"), tool("same"))));
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool("local")));
        assertThrows(IllegalStateException.class,
                () -> registry.replaceMcpTools("ops", List.of(tool("local"))));
    }

    private AgentTool tool(String name) {
        return new AgentTool() {
            public String name() { return name; }
            public Map<String, Object> definition() { return Map.of("name", name); }
            public ToolExecutionResult execute(String arguments, ToolExecutionContext context) {
                return new ToolExecutionResult(true, "{}", 0, Map.of());
            }
        };
    }
}
