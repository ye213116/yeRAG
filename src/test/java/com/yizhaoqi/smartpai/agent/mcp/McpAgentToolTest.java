package com.yizhaoqi.smartpai.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.tool.ToolExecutionContext;
import com.yizhaoqi.smartpai.agent.tool.ToolExecutionResult;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class McpAgentToolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private McpProperties properties;
    private McpProperties.Server server;
    private McpProperties.ReadOnlyTool configured;
    private CapturingClient client;

    @BeforeEach
    void setUp() {
        properties = McpPropertiesTest.valid();
        server = properties.getServers().get("ops");
        configured = server.getReadOnlyTools().get(0);
        client = new CapturingClient();
    }

    @Test
    void exposesNamespacedSchemaAndForwardsSignedIdentityOutsideArguments() throws Exception {
        McpAgentTool tool = tool();

        ToolExecutionResult result = tool.execute("{\"query\":\"revenue\"}", new ToolExecutionContext("alice"));

        assertTrue(result.success());
        assertEquals("ops__lookup", tool.name());
        assertEquals(false, ((Map<?, ?>) ((Map<?, ?>) tool.definition().get("function"))
                .get("parameters")).get("additionalProperties"));
        assertEquals(Map.of("query", "revenue"), client.arguments);
        assertFalse(client.arguments.containsKey("userId"));
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(Keys.hmacShaKeyFor(Base64.getDecoder().decode(server.getIdentitySigningKey())))
                .build().parseClaimsJws(client.identityToken).getBody();
        assertEquals("yerag", claims.getIssuer());
        assertEquals("alice", claims.getSubject());
        assertEquals("ops", claims.getAudience());
        assertEquals("lookup_metrics", claims.get("tool", String.class));
        JsonNode content = mapper.readTree(result.content());
        assertTrue(content.path("security_notice").isTextual());
        assertEquals("ok", content.path("text").path(0).asText());
    }

    @Test
    void rejectsUnknownAndReservedArgumentsWithoutCallingServer() {
        McpAgentTool tool = tool();

        assertFalse(tool.execute("{\"query\":\"x\",\"extra\":1}",
                new ToolExecutionContext("alice")).success());
        assertFalse(tool.execute("not-json", new ToolExecutionContext("alice")).success());
        assertEquals(0, client.calls.get());
    }

    @Test
    void defaultResultBudgetRetainsTextBeyondTheOldSmallCeiling() throws Exception {
        String text="完整的远程工具资料与最后的例外条件。".repeat(1000);
        client.result=new McpToolCallResult(List.of(text),null,false,false);
        var result=tool().execute("{\"query\":\"x\"}",new ToolExecutionContext("alice"));
        assertTrue(result.success());
        assertEquals(text,mapper.readTree(result.content()).path("text").get(0).asText());
        assertFalse(mapper.readTree(result.content()).path("truncated").asBoolean());
    }

    @Test
    void rejectsAmbiguousJsonAndOversizedUtf8BeforeCallingServer() {
        McpAgentTool tool = tool();
        var context = new ToolExecutionContext("alice");
        assertFalse(tool.execute("{\"query\":\"x\",\"query\":\"y\"}", context).success());
        assertFalse(tool.execute("{\"query\":\"x\"} {}", context).success());
        properties.setMaxArgumentCharacters(256);
        assertFalse(tool.execute("{\"query\":\"" + "中".repeat(100) + "\"}", context).success());
        assertEquals(0, client.calls.get());
    }

    @Test
    void refusesSchemasThatCouldFetchExternalResources() {
        assertFalse(McpAgentTool.supportedSchema(Map.of("type", "object", "properties",
                Map.of("query", Map.of("$ref", "https://example.invalid/schema")))));
    }

    @Test
    void rejectsUnsupportedContentAndKeepsRemoteErrorsAsToolResults() {
        McpAgentTool tool = tool();
        client.result = new McpToolCallResult(List.of(), null, false, true);
        assertFalse(tool.execute("{\"query\":\"x\"}", new ToolExecutionContext("alice")).success());

        client.result = new McpToolCallResult(List.of("not authorized"), null, true, false);
        ToolExecutionResult remoteError = tool.execute("{\"query\":\"x\"}", new ToolExecutionContext("alice"));
        assertFalse(remoteError.success());
        assertTrue(remoteError.content().contains("not authorized"));
    }

    @Test
    void returnsValidBoundedJsonForLargeResults() throws Exception {
        properties.setMaxResultCharacters(512);
        client.result = new McpToolCallResult(List.of("x".repeat(5_000)), Map.of("large", "y".repeat(5_000)),
                false, false);

        ToolExecutionResult result = tool().execute("{\"query\":\"x\"}", new ToolExecutionContext("alice"));

        assertTrue(result.success());
        assertTrue(result.content().length() <= 512);
        assertTrue(mapper.readTree(result.content()).path("truncated").asBoolean());
    }

    private McpAgentTool tool() {
        McpToolDescriptor descriptor = new McpToolDescriptor("lookup_metrics", "Lookup metrics", Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of("type", "string")),
                "required", List.of("query")), true, false);
        return new McpAgentTool("ops", descriptor, configured, client, server, properties, mapper,
                new McpIdentityTokenService(), error -> { });
    }

    private static class CapturingClient implements McpClientFacade {
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> arguments;
        String identityToken;
        McpToolCallResult result = new McpToolCallResult(List.of("ok"), Map.of("value", 1), false, false);
        public void initialize() { }
        public McpToolPage listTools(String cursor) { return new McpToolPage(List.of(), null); }
        public McpToolCallResult callTool(String toolName, Map<String, Object> arguments, String identityToken) {
            calls.incrementAndGet(); this.arguments = arguments; this.identityToken = identityToken; return result;
        }
        public void close() { }
    }
}
