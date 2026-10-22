package com.yizhaoqi.smartpai.agent.mcp;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpPropertiesTest {
    @Test
    void providesSecureDisabledDefaults() {
        McpProperties value = new McpProperties();

        assertFalse(value.isEnabled());
        assertFalse(value.isAllowInsecureHttp());
        assertEquals(Duration.ofSeconds(5), value.getConnectTimeout());
        assertEquals(Duration.ofSeconds(8), value.getRequestTimeout());
        assertEquals(Duration.ofMinutes(5), value.getRefreshInterval());
        assertEquals(20, value.getMaxListPages());
        assertEquals(200, value.getMaxToolsPerServer());
    }

    @Test
    void acceptsValidExplicitReadOnlyWhitelist() {
        McpProperties value = valid();
        assertDoesNotThrow(value::validateConfiguration);
    }

    @Test
    void rejectsInsecureUrlAndDuplicateAliases() {
        McpProperties insecure = valid();
        insecure.getServers().get("ops").setBaseUrl("http://localhost:9000");
        assertThrows(IllegalArgumentException.class, insecure::validateConfiguration);

        McpProperties duplicates = valid();
        McpProperties.ReadOnlyTool duplicate = tool("other", "lookup");
        duplicates.getServers().get("ops").getReadOnlyTools().add(duplicate);
        assertThrows(IllegalArgumentException.class, duplicates::validateConfiguration);
    }

    static McpProperties valid() {
        McpProperties value = new McpProperties();
        value.setEnabled(true);
        McpProperties.Server server = new McpProperties.Server();
        server.setBaseUrl("https://mcp.example.test");
        server.setIdentitySigningKey(Base64.getEncoder().encodeToString(new byte[32]));
        server.setReadOnlyTools(new java.util.ArrayList<>(List.of(tool("lookup_metrics", "lookup"))));
        value.setServers(Map.of("ops", server));
        return value;
    }

    static McpProperties.ReadOnlyTool tool(String remote, String alias) {
        McpProperties.ReadOnlyTool tool = new McpProperties.ReadOnlyTool();
        tool.setRemoteName(remote);
        tool.setAlias(alias);
        return tool;
    }
}
