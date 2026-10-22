package com.yizhaoqi.smartpai.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.AgentToolRegistry;
import com.yizhaoqi.smartpai.agent.tool.AgentTool;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "ai.mcp", name = "enabled", havingValue = "true")
public class McpClientManager implements ApplicationRunner {
    private static final Logger logger = LoggerFactory.getLogger(McpClientManager.class);

    private final McpProperties properties;
    private final McpClientFacadeFactory clientFactory;
    private final AgentToolRegistry registry;
    private final ObjectMapper objectMapper;
    private final JsonSchemaValidator schemaValidator;
    private final McpIdentityTokenService tokenService = new McpIdentityTokenService();
    private final Map<String, ServerState> states = new ConcurrentHashMap<>();
    private final Set<McpClientFacade> retiredClients = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean();

    public McpClientManager(McpProperties properties, McpClientFacadeFactory clientFactory,
                            AgentToolRegistry registry, ObjectMapper objectMapper) {
        properties.validateConfiguration();
        this.properties = properties;
        this.clientFactory = clientFactory;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.schemaValidator = new DefaultJsonSchemaValidator(objectMapper.copy());
        int threads = Math.max(1, Math.min(4, properties.getServers().size()));
        this.executor = Executors.newScheduledThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "yerag-mcp-client");
            thread.setDaemon(true);
            return thread;
        });
        properties.getServers().keySet().forEach(id -> states.put(id, new ServerState()));
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!running.compareAndSet(false, true)) return;
        properties.getServers().keySet().forEach(id -> executor.execute(() -> connect(id)));
        long refreshMillis = properties.getRefreshInterval().toMillis();
        executor.scheduleWithFixedDelay(this::refreshReadyServers, refreshMillis, refreshMillis,
                TimeUnit.MILLISECONDS);
    }

    private void connect(String serverId) {
        if (!running.get()) return;
        ServerState state = states.get(serverId);
        if (state == null || !state.busy.compareAndSet(false, true)) return;
        McpClientFacade candidate = null;
        try {
            McpProperties.Server server = properties.getServers().get(serverId);
            candidate = clientFactory.create(serverId, server, () -> scheduleRefresh(serverId));
            candidate.initialize();
            List<AgentTool> tools = discover(serverId, server, candidate);
            synchronized (state) {
                if (!running.get()) { candidate.close(); return; }
                state.client = candidate;
                state.failures = 0;
                state.ready = true;
                registry.replaceMcpTools(serverId, tools);
            }
            logger.info("MCP server ready: server={}, tools={}", serverId, tools.size());
            candidate = null;
        } catch (Exception error) {
            registry.removeMcpTools(serverId);
            int failures;
            synchronized (state) {
                if (state.client == candidate) state.client = null;
                state.ready = false;
                failures = ++state.failures;
            }
            logger.warn("MCP server unavailable: server={}, error={}", serverId,
                    error.getClass().getSimpleName());
            scheduleReconnect(serverId, failures);
        } finally {
            state.busy.set(false);
            if (candidate != null) closeQuietly(candidate);
        }
    }

    private void refresh(String serverId) {
        if (!running.get()) return;
        ServerState state = states.get(serverId);
        if (state == null || !state.ready || !state.busy.compareAndSet(false, true)) return;
        McpClientFacade client = state.client;
        try {
            List<AgentTool> tools = discover(serverId, properties.getServers().get(serverId), client);
            synchronized (state) {
                if (!running.get() || !state.ready || state.client != client) return;
                registry.replaceMcpTools(serverId, tools);
            }
            logger.info("MCP tools refreshed: server={}, tools={}", serverId, tools.size());
        } catch (Exception error) {
            markUnavailable(serverId, client, error);
        } finally {
            state.busy.set(false);
        }
    }

    private List<AgentTool> discover(String serverId, McpProperties.Server server, McpClientFacade client) {
        List<McpToolDescriptor> discovered = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            if (++pages > properties.getMaxListPages()) {
                throw new IllegalStateException("MCP tools/list exceeded the page limit");
            }
            McpToolPage page = client.listTools(cursor);
            discovered.addAll(page.tools());
            if (discovered.size() > properties.getMaxToolsPerServer()) {
                throw new IllegalStateException("MCP tools/list exceeded the tool limit");
            }
            cursor = normalizeCursor(page.nextCursor());
            if (cursor != null && !cursors.add(cursor)) {
                throw new IllegalStateException("MCP tools/list repeated a cursor");
            }
        } while (cursor != null);

        Map<String, McpToolDescriptor> byName = new HashMap<>();
        for (McpToolDescriptor tool : discovered) {
            if (tool.name() == null || tool.name().isBlank() || byName.putIfAbsent(tool.name(), tool) != null) {
                logger.warn("Ignoring invalid or duplicate MCP tool: server={}", serverId);
            }
        }

        List<AgentTool> tools = new ArrayList<>();
        for (McpProperties.ReadOnlyTool configured : server.getReadOnlyTools()) {
            McpToolDescriptor descriptor = byName.get(configured.getRemoteName());
            if (descriptor == null) {
                logger.warn("Allowlisted MCP tool was not discovered: server={}, tool={}",
                        serverId, configured.getRemoteName());
                continue;
            }
            if (Boolean.TRUE.equals(descriptor.destructiveHint()) || Boolean.FALSE.equals(descriptor.readOnlyHint())) {
                logger.warn("Ignoring MCP tool with non-read-only annotations: server={}, tool={}",
                        serverId, configured.getRemoteName());
                continue;
            }
            if (!validSchema(descriptor)) {
                logger.warn("Ignoring MCP tool with unsupported input schema: server={}, tool={}",
                        serverId, configured.getRemoteName());
                continue;
            }
            tools.add(new McpAgentTool(serverId, descriptor, configured, client, server, properties,
                    objectMapper, tokenService, error -> markUnavailable(serverId, client, error)));
        }
        return List.copyOf(tools);
    }

    private boolean validSchema(McpToolDescriptor descriptor) {
        try {
            return McpAgentTool.supportedSchema(descriptor.inputSchema())
                    && schemaValidator.validateSchema(descriptor.inputSchema()).valid();
        } catch (RuntimeException invalidSchema) {
            return false;
        }
    }

    private void markUnavailable(String serverId, McpClientFacade expectedClient, Throwable error) {
        ServerState state = states.get(serverId);
        if (state == null) return;
        int failures;
        synchronized (state) {
            if (state.client != expectedClient) return;
            state.client = null;
            state.ready = false;
            failures = ++state.failures;
            registry.removeMcpTools(serverId);
        }
        retiredClients.add(expectedClient);
        if (running.get()) {
            try {
                executor.execute(() -> {
                    closeQuietly(expectedClient);
                    retiredClients.remove(expectedClient);
                });
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                closeQuietly(expectedClient);
                retiredClients.remove(expectedClient);
            }
        } else {
            closeQuietly(expectedClient);
            retiredClients.remove(expectedClient);
        }
        logger.warn("MCP server degraded: server={}, error={}", serverId,
                error.getClass().getSimpleName());
        scheduleReconnect(serverId, failures);
    }

    private void refreshReadyServers() {
        if (!running.get()) return;
        states.forEach((serverId, state) -> { if (state.ready) refresh(serverId); });
    }

    private void scheduleRefresh(String serverId) {
        if (running.get()) executor.execute(() -> refresh(serverId));
    }

    private void scheduleReconnect(String serverId, int failures) {
        if (!running.get()) return;
        long seconds = Math.min(60, 1L << Math.min(6, Math.max(0, failures - 1)));
        long jitterMillis = ThreadLocalRandom.current().nextLong(251);
        executor.schedule(() -> connect(serverId), seconds * 1000 + jitterMillis, TimeUnit.MILLISECONDS);
    }

    private static String normalizeCursor(String cursor) {
        return cursor == null || cursor.isBlank() ? null : cursor;
    }

    @PreDestroy
    public void close() {
        if (!running.getAndSet(false)) {
            executor.shutdownNow();
            return;
        }
        states.forEach((serverId, state) -> {
            McpClientFacade client;
            synchronized (state) {
                registry.removeMcpTools(serverId);
                client = state.client; state.client = null; state.ready = false;
            }
            if (client != null) closeQuietly(client);
        });
        retiredClients.forEach(McpClientManager::closeQuietly);
        retiredClients.clear();
        executor.shutdownNow();
    }

    private static void closeQuietly(McpClientFacade client) {
        try { client.close(); } catch (RuntimeException ignored) { }
    }

    private static class ServerState {
        private final AtomicBoolean busy = new AtomicBoolean();
        private volatile McpClientFacade client;
        private volatile boolean ready;
        private int failures;
    }
}
