package com.yizhaoqi.smartpai.agent.profile;

import java.util.Set;

/** Constructed by the task service, never bound from model or HTTP arguments. */
public record CodingExecutionContext(String taskId, String workspaceId,
                                     Set<WorkspacePermission> permissions, Runnable checkCurrentAuthorization) {
    public CodingExecutionContext {
        if (taskId == null || workspaceId == null || checkCurrentAuthorization == null)
            throw new IllegalArgumentException("server-owned task and workspace are required");
        permissions = Set.copyOf(permissions);
    }

    public void require(WorkspacePermission permission) {
        checkCurrentAuthorization.run();
        if (!permissions.contains(permission)) throw new SecurityException("Workspace permission denied");
    }
}
