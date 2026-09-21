package com.yizhaoqi.smartpai.agent;

import com.yizhaoqi.smartpai.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class AgentToolRegistry {
    private final Map<String, AgentTool> tools;

    public AgentToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> map = new LinkedHashMap<>();
        tools.forEach(tool -> map.put(tool.name(), tool));
        this.tools = Map.copyOf(map);
    }

    public Optional<AgentTool> find(String name) { return Optional.ofNullable(tools.get(name)); }
    public List<Map<String, Object>> definitions() { return tools.values().stream().map(AgentTool::definition).toList(); }
}
