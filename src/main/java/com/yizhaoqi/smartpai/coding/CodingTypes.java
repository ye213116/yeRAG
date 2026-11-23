package com.yizhaoqi.smartpai.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.agent.SourceMode;
import com.yizhaoqi.smartpai.agent.model.AgentMessage;
import com.yizhaoqi.smartpai.agent.profile.WorkspacePermission;
import java.util.*;

public final class CodingTypes {
    private CodingTypes() {}
    public enum Status { QUEUED, PREPARING, RUNNING, REVIEW_READY, FAILED, CANCELLED, TIMED_OUT, INTERRUPTED }
    public enum Operation { SETUP, LIST, SEARCH, READ, PATCH, COMMAND, TEST, SNAPSHOT }
    public record Workspace(String id, long ownerId, String repositoryId, String branch, String baseSha,
                            Set<WorkspacePermission> permissions, String status, String activeTaskId) {}
    public record Task(String id,long ownerId,String workspaceId,String conversationId,String requestId,
                       String requestHash,String message,SourceMode sourceMode,Status status,
                       Set<WorkspacePermission> permissions,List<AgentMessage> history,String summary,
                       JsonNode result,String runId,String publishState,String prUrl) {}
    public record Submission(String taskId,String answer) {}
    public static void uuid(String id) {
        if (id == null || !id.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("Invalid coding identifier");
    }
    public static Set<WorkspacePermission> permissions(Set<WorkspacePermission> values) {
        if (values == null || !values.contains(WorkspacePermission.READ)
                || (values.contains(WorkspacePermission.EXECUTE) && !values.contains(WorkspacePermission.WRITE)))
            throw new IllegalArgumentException("Read is required; command execution also requires write access");
        return Set.copyOf(values);
    }
}
