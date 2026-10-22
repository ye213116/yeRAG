package com.yizhaoqi.smartpai.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.AgentToolRegistry;
import com.yizhaoqi.smartpai.agent.tool.AgentTool;
import com.yizhaoqi.smartpai.agent.tool.ToolExecutionContext;
import com.yizhaoqi.smartpai.agent.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class McpClientManagerTest {
    @Test
    void discoversBoundedPagesRefreshesAndPreservesLocalRegistryAvailability() throws Exception {
        McpProperties properties = McpPropertiesTest.valid();
        properties.setRefreshInterval(Duration.ofHours(1));
        AgentToolRegistry registry = new AgentToolRegistry(List.of(localTool()));
        FakeFactory factory = new FakeFactory();
        McpClientManager manager = new McpClientManager(properties, factory, registry, new ObjectMapper());

        try {
            manager.run(null);
            await(() -> registry.find("ops__lookup").isPresent());
            assertEquals(2, factory.client.listCalls.get());

            factory.changed.run();
            await(() -> factory.client.listCalls.get() >= 4);
            assertTrue(registry.find("ops__lookup").isPresent());

            factory.client.throwOnCall = true;
            ToolExecutionResult failed = registry.find("ops__lookup").orElseThrow()
                    .execute("{\"query\":\"x\"}", new ToolExecutionContext("alice"));
            assertFalse(failed.success());
            assertTrue(registry.find("ops__lookup").isEmpty());
            assertTrue(registry.find("search_knowledge_base").isPresent());
        } finally {
            manager.close();
        }
        assertTrue(registry.find("ops__lookup").isEmpty());
        assertTrue(factory.client.closed);
    }

    @Test
    void stopsDiscoveryAtConfiguredPageLimit() throws Exception {
        McpProperties properties = McpPropertiesTest.valid();
        properties.setMaxListPages(1);
        FakeFactory factory = new FakeFactory();
        AgentToolRegistry registry = new AgentToolRegistry(List.of(localTool()));
        McpClientManager manager = new McpClientManager(properties, factory, registry, new ObjectMapper());
        try {
            manager.run(null);
            await(() -> factory.client.listCalls.get() >= 1);
            assertEquals(1, factory.client.listCalls.get());
            assertTrue(registry.find("ops__lookup").isEmpty());
            assertTrue(registry.find("search_knowledge_base").isPresent());
        } finally {
            manager.close();
        }
    }

    @Test
    void skipsToolsWhoseAnnotationsContradictReadOnlyWhitelist() throws Exception {
        McpProperties properties = McpPropertiesTest.valid();
        FakeFactory factory = new FakeFactory();
        factory.client.tool = new McpToolDescriptor("lookup_metrics", "danger", schema(), false, true);
        AgentToolRegistry registry = new AgentToolRegistry(List.of());
        McpClientManager manager = new McpClientManager(properties, factory, registry, new ObjectMapper());
        try {
            manager.run(null);
            await(() -> factory.client.listCalls.get() >= 2);
            assertTrue(registry.find("ops__lookup").isEmpty());
        } finally {
            manager.close();
        }
    }

    private static Map<String, Object> schema() {
        return Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")));
    }

    private static AgentTool localTool() {
        return new AgentTool() {
            public String name() { return "search_knowledge_base"; }
            public Map<String, Object> definition() { return Map.of("name", name()); }
            public ToolExecutionResult execute(String arguments, ToolExecutionContext context) {
                return new ToolExecutionResult(true, "{}", 0, Map.of());
            }
        };
    }

    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!check.ok() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(check.ok(), "condition was not reached before timeout");
    }

    @FunctionalInterface private interface Check { boolean ok(); }

    private static class FakeFactory implements McpClientFacadeFactory {
        final FakeClient client = new FakeClient();
        volatile Runnable changed;
        public McpClientFacade create(String serverId, McpProperties.Server server, Runnable toolsChanged) {
            changed = toolsChanged;
            return client;
        }
    }

    private static class FakeClient implements McpClientFacade {
        final AtomicInteger listCalls = new AtomicInteger();
        volatile boolean closed;
        volatile boolean throwOnCall;
        volatile McpToolDescriptor tool = new McpToolDescriptor("lookup_metrics", "lookup", schema(), true, false);
        public void initialize() { }
        public McpToolPage listTools(String cursor) {
            listCalls.incrementAndGet();
            if (cursor == null) return new McpToolPage(List.of(
                    new McpToolDescriptor("not_allowed", "ignored", schema(), true, false)), "page-2");
            return new McpToolPage(List.of(tool), null);
        }
        public McpToolCallResult callTool(String toolName, Map<String, Object> arguments, String identityToken) {
            if (throwOnCall) throw new IllegalStateException("offline");
            return new McpToolCallResult(List.of("ok"), null, false, false);
        }
        public void close() { closed = true; }
    }
}
