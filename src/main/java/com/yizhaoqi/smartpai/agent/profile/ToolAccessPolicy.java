package com.yizhaoqi.smartpai.agent.profile;

import com.yizhaoqi.smartpai.agent.mcp.McpAgentTool;
import com.yizhaoqi.smartpai.agent.tool.AgentTool;
import java.util.Map;

public final class ToolAccessPolicy {
    private static final Map<String, WorkspacePermission> CODING_TOOLS = Map.of(
            "list_files", WorkspacePermission.READ, "search_code", WorkspacePermission.READ,
            "read_file", WorkspacePermission.READ, "git_diff", WorkspacePermission.READ,
            "apply_patch", WorkspacePermission.WRITE, "execute_command", WorkspacePermission.EXECUTE);

    public boolean allows(AgentProfile profile, AgentRunRequest request, AgentTool tool) {
        if (tool.source() == AgentTool.Source.WORKSPACE || CODING_TOOLS.containsKey(tool.name())) {
            if (profile.mode() != AgentMode.CODING || request.coding() == null) return false;
            WorkspacePermission required = CODING_TOOLS.get(tool.name());
            if (required == null || !request.coding().permissions().contains(required)) return false;
            if (required == WorkspacePermission.EXECUTE && !request.coding().permissions().contains(WorkspacePermission.WRITE)) return false;
        }
        return (profile.allowedTools().contains(tool.name()) || (profile.mode() == AgentMode.WORK && tool instanceof McpAgentTool))
                && request.sourceMode().allows(tool);
    }

    public void require(AgentProfile profile, AgentRunRequest request, AgentTool tool) {
        if (!allows(profile, request, tool)) throw new SecurityException("Tool is not permitted for this execution");
        if (request.coding() != null) request.coding().require(CODING_TOOLS.getOrDefault(tool.name(), WorkspacePermission.READ));
    }
}
