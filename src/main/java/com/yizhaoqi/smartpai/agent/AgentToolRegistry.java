package com.yizhaoqi.smartpai.agent;

import com.yizhaoqi.smartpai.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class AgentToolRegistry {
    private final Map<String, AgentTool> localTools;
    private final AtomicReference<Map<String, Map<String, AgentTool>>> mcpTools =
            new AtomicReference<>(Map.of());

    public AgentToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> map = new LinkedHashMap<>();
        tools.forEach(tool -> {
            AgentTool previous = map.putIfAbsent(tool.name(), tool);
            if (previous != null) throw new IllegalStateException("duplicate Agent tool: " + tool.name());
        });
        this.localTools = immutable(map);
    }

    public Optional<AgentTool> find(String name) { return snapshot().find(name); }
    public List<Map<String, Object>> definitions() { return snapshot().definitions(); }

    public ToolCatalogSnapshot snapshot() {
        Map<String, AgentTool> combined = new LinkedHashMap<>(localTools);
        mcpTools.get().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(server ->
                server.getValue().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(tool -> {
                    AgentTool previous = combined.putIfAbsent(tool.getKey(), tool.getValue());
                    if (previous != null) throw new IllegalStateException("duplicate Agent tool: " + tool.getKey());
                }));
        return new ToolCatalogSnapshot(immutable(combined));
    }

    public void replaceMcpTools(String serverId, List<AgentTool> tools) {
        Map<String, AgentTool> replacement = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            if (localTools.containsKey(tool.name())) {
                throw new IllegalStateException("MCP tool conflicts with local tool: " + tool.name());
            }
            AgentTool previous = replacement.putIfAbsent(tool.name(), tool);
            if (previous != null) throw new IllegalStateException("duplicate MCP tool: " + tool.name());
        }
        updateServer(serverId, immutable(replacement));
    }

    public void removeMcpTools(String serverId) { updateServer(serverId, null); }

    private void updateServer(String serverId, Map<String, AgentTool> replacement) {
        while (true) {
            Map<String, Map<String, AgentTool>> current = mcpTools.get();
            if (replacement != null) {
                current.forEach((otherServer, existing) -> {
                    if (!otherServer.equals(serverId)) {
                        for (String name : replacement.keySet()) {
                            if (existing.containsKey(name)) throw new IllegalStateException("duplicate MCP tool: " + name);
                        }
                    }
                });
            }
            Map<String, Map<String, AgentTool>> updated = new LinkedHashMap<>(current);
            if (replacement == null) updated.remove(serverId); else updated.put(serverId, replacement);
            if (mcpTools.compareAndSet(current, immutable(updated))) return;
        }
    }

    private static <K, V> Map<K, V> immutable(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public record ToolCatalogSnapshot(Map<String, AgentTool> tools) {
        public ToolCatalogSnapshot {
            tools = immutable(tools);
        }

        public Optional<AgentTool> find(String name) { return Optional.ofNullable(tools.get(name)); }

        public List<Map<String, Object>> definitions() {
            List<Map<String, Object>> definitions = new ArrayList<>();
            tools.values().forEach(tool -> definitions.add(tool.definition()));
            return List.copyOf(definitions);
        }
    }
}
