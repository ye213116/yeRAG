package com.yizhaoqi.smartpai.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SdkMcpClientFacadeFactoryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void performsStreamableHttpDiscoveryAndInjectsIdentityOnlyOnToolCall() throws Exception {
        AtomicReference<String> identityHeader = new AtomicReference<>();
        AtomicReference<JsonNode> toolCallBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> handle(exchange, identityHeader, toolCallBody));
        server.start();

        McpProperties properties = new McpProperties();
        properties.setAllowInsecureHttp(true);
        McpProperties.Server configured = new McpProperties.Server();
        configured.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        configured.setEndpoint("/mcp");
        SdkMcpClientFacadeFactory factory = new SdkMcpClientFacadeFactory(mapper, properties);
        McpClientFacade client = factory.create("test", configured, () -> { });
        try {
            client.initialize();
            McpToolPage page = client.listTools(null);
            assertEquals(List.of("echo"), page.tools().stream().map(McpToolDescriptor::name).toList());

            McpToolCallResult result = client.callTool("echo", Map.of("value", "hello"), "signed-user-token");
            assertEquals(List.of("hello"), result.text());
            assertEquals("signed-user-token", identityHeader.get());
            assertEquals("hello", toolCallBody.get().path("params").path("arguments").path("value").asText());
            assertFalse(toolCallBody.get().toString().contains("signed-user-token"));
        } finally {
            client.close();
            server.stop(0);
        }
    }

    @Test
    void interceptsToolsChangedBeforeSdkCanRunUnboundedAggregation() {
        FakeTransport delegate = new FakeTransport();
        AtomicInteger changed = new AtomicInteger();
        AtomicInteger forwarded = new AtomicInteger();
        SdkMcpClientFacadeFactory.BoundedToolsChangeTransport transport =
                new SdkMcpClientFacadeFactory.BoundedToolsChangeTransport(delegate, changed::incrementAndGet);
        transport.connect(incoming -> incoming.doOnNext(ignored -> forwarded.incrementAndGet())).block();

        delegate.emit(new McpSchema.JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED));
        delegate.emit(new McpSchema.JSONRPCNotification("notifications/other"));

        assertEquals(1, changed.get());
        assertEquals(1, forwarded.get());
    }

    private void handle(HttpExchange exchange, AtomicReference<String> identityHeader,
                        AtomicReference<JsonNode> toolCallBody) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        JsonNode request = mapper.readTree(exchange.getRequestBody());
        String method = request.path("method").asText();
        if ("notifications/initialized".equals(method)) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }
        Object id = request.path("id").isIntegralNumber()
                ? request.path("id").longValue() : request.path("id").asText();
        Map<String, Object> result;
        if ("initialize".equals(method)) {
            result = Map.of(
                    "protocolVersion", "2025-11-25",
                    "capabilities", Map.of("tools", Map.of("listChanged", false)),
                    "serverInfo", Map.of("name", "test-server", "version", "1.0.0"));
        } else if ("tools/list".equals(method)) {
            result = Map.of("tools", List.of(Map.of(
                    "name", "echo",
                    "description", "Echo a value",
                    "inputSchema", Map.of("type", "object", "properties",
                            Map.of("value", Map.of("type", "string"))))));
        } else if ("tools/call".equals(method)) {
            identityHeader.set(exchange.getRequestHeaders().getFirst(SdkMcpClientFacadeFactory.IDENTITY_TOKEN_HEADER));
            toolCallBody.set(request);
            result = Map.of(
                    "content", List.of(Map.of("type", "text", "text", "hello")),
                    "structuredContent", Map.of("echoed", true),
                    "isError", false);
        } else {
            send(exchange, Map.of("jsonrpc", "2.0", "id", id,
                    "error", Map.of("code", -32601, "message", "not found")));
            return;
        }
        send(exchange, Map.of("jsonrpc", "2.0", "id", id, "result", result));
    }

    private void send(HttpExchange exchange, Map<String, Object> response) throws IOException {
        byte[] bytes = mapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static class FakeTransport implements McpClientTransport {
        private Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;
        public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
            this.handler = handler;
            return Mono.empty();
        }
        void emit(McpSchema.JSONRPCMessage message) { handler.apply(Mono.just(message)).block(); }
        public Mono<Void> closeGracefully() { return Mono.empty(); }
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) { return Mono.empty(); }
        public <T> T unmarshalFrom(Object value, TypeRef<T> typeRef) { throw new UnsupportedOperationException(); }
    }
}
