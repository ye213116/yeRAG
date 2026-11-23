package com.yizhaoqi.smartpai.coding.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.coding.CodingTypes.*;
import com.yizhaoqi.smartpai.coding.CodingProperties;
import java.time.Duration;
import java.util.Map;

/** Only the Docker implementation executes repository code. No host-process fallback. */
public interface SandboxExecutor {
    void preflight();
    JsonNode execute(Workspace workspace, Operation operation, Map<String,Object> arguments,
                     Duration timeout, Runnable authorization);
    void prepare(Workspace workspace, CodingProperties.Repository repository, Runnable authorization);
    void stop(String workspaceId);
    void destroy(String workspaceId);
}
