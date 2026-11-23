package com.yizhaoqi.smartpai.coding;

import com.yizhaoqi.smartpai.agent.*;
import com.yizhaoqi.smartpai.agent.tool.AgentTool;
import com.yizhaoqi.smartpai.coding.sandbox.SandboxExecutor;
import com.yizhaoqi.smartpai.coding.task.CodingTaskWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"ai.coding.enabled=true","ai.agent.profiles.coding.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:coding-enabled-context;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@DirtiesContext
class CodingEnabledContextTest {
    @MockitoBean SandboxExecutor sandbox;
    @MockitoBean com.yizhaoqi.smartpai.config.EsIndexInitializer esIndexInitializer;
    @MockitoBean com.yizhaoqi.smartpai.config.AdminUserInitializer adminUserInitializer;
    @MockitoBean com.yizhaoqi.smartpai.config.OrgTagInitializer orgTagInitializer;
    @Autowired AgentToolRegistry registry;
    @Autowired KnowledgeAgentService runtime;
    @Autowired CodingTaskWorker worker;
    @Test void enabledModuleWiresSixToolsAndTheExistingSharedRuntime() {
        assertNotNull(runtime);assertNotNull(worker);
        var tools=registry.snapshot().tools().values().stream().filter(t -> t.source()==AgentTool.Source.WORKSPACE).map(AgentTool::name).sorted().toList();
        assertEquals(java.util.List.of("apply_patch","execute_command","git_diff","list_files","read_file","search_code"),tools);
    }
}
