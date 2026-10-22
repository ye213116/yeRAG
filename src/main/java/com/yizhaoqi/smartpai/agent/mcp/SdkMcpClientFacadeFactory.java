package com.yizhaoqi.smartpai.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

@Component
public class SdkMcpClientFacadeFactory implements McpClientFacadeFactory {
    static final String IDENTITY_TOKEN_CONTEXT_KEY = "yerag.identity-token";
    static final String IDENTITY_TOKEN_HEADER = "X-YeRAG-User-Token";

    private final ObjectMapper objectMapper;
    private final McpProperties properties;

    public SdkMcpClientFacadeFactory(ObjectMapper objectMapper, McpProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public McpClientFacade create(String serverId, McpProperties.Server server, Runnable toolsChanged) {
        JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(objectMapper.copy());
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(server.getBaseUrl())
                .endpoint(server.getEndpoint())
                .connectTimeout(properties.getConnectTimeout())
                .maxResponseSize(1_048_576)
                .httpRequestCustomizer((request, method, endpoint, body, context) -> {
                    if (server.getServiceToken() != null && !server.getServiceToken().isBlank()) {
                        request.setHeader("Authorization", "Bearer " + server.getServiceToken().trim());
                    }
                    Object identityToken = context.get(IDENTITY_TOKEN_CONTEXT_KEY);
                    if (identityToken instanceof String token && !token.isBlank()) {
                        request.setHeader(IDENTITY_TOKEN_HEADER, token);
                    }
                })
                .jsonMapper(mapper)
                .build();
        McpClientTransport boundedNotificationTransport = new BoundedToolsChangeTransport(transport, toolsChanged);
        McpAsyncClient client = McpClient.async(boundedNotificationTransport)
                .enableCallToolSchemaCaching(false)
                .requestTimeout(properties.getRequestTimeout())
                .initializationTimeout(properties.getConnectTimeout())
                .clientInfo(McpSchema.Implementation.builder("yerag", "0.0.1").build())
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .build();
        return new SdkFacade(client, properties);
    }

    /**
     * The SDK's default tools/list_changed handler aggregates every page without a
     * global bound. Intercept that notification and let McpClientManager perform its
     * explicitly bounded refresh instead.
     */
    static class BoundedToolsChangeTransport implements McpClientTransport {
        private final McpClientTransport delegate;
        private final Runnable toolsChanged;

        BoundedToolsChangeTransport(McpClientTransport delegate, Runnable toolsChanged) {
            this.delegate = delegate;
            this.toolsChanged = toolsChanged;
        }

        @Override
        public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
            return delegate.connect(incoming -> handler.apply(incoming.flatMap(message -> {
                if (message instanceof McpSchema.JSONRPCNotification notification
                        && McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED.equals(notification.method())) {
                    toolsChanged.run();
                    return Mono.empty();
                }
                return Mono.just(message);
            })));
        }

        @Override public Mono<Void> closeGracefully() { return delegate.closeGracefully(); }
        @Override public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return delegate.sendMessage(message);
        }
        @Override public <T> T unmarshalFrom(Object value, TypeRef<T> typeRef) {
            return delegate.unmarshalFrom(value, typeRef);
        }
        @Override public List<String> protocolVersions() { return delegate.protocolVersions(); }
        @Override public void setExceptionHandler(Consumer<Throwable> handler) {
            delegate.setExceptionHandler(handler);
        }
    }

    private static class SdkFacade implements McpClientFacade {
        private final McpAsyncClient client;
        private final McpProperties properties;

        private SdkFacade(McpAsyncClient client, McpProperties properties) {
            this.client = client;
            this.properties = properties;
        }

        @Override
        public void initialize() {
            McpSchema.InitializeResult result = client.initialize().block(properties.getConnectTimeout());
            if (result == null) throw new IllegalStateException("MCP initialization completed without a result");
        }

        @Override
        public McpToolPage listTools(String cursor) {
            McpSchema.ListToolsResult result = client.listTools(cursor).block(properties.getRequestTimeout());
            if (result == null) throw new IllegalStateException("MCP tools/list completed without a result");
            List<McpToolDescriptor> tools = result.tools().stream().map(SdkFacade::descriptor).toList();
            return new McpToolPage(tools, result.nextCursor());
        }

        @Override
        public McpToolCallResult callTool(String toolName, Map<String, Object> arguments, String identityToken) {
            McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder(toolName)
                    .arguments(arguments)
                    .build();
            McpTransportContext transportContext = McpTransportContext.create(
                    Map.of(IDENTITY_TOKEN_CONTEXT_KEY, identityToken));
            McpSchema.CallToolResult result = client.callTool(request)
                    .contextWrite(context -> context.put(McpTransportContext.KEY, transportContext))
                    .block(properties.getRequestTimeout());
            if (result == null) throw new IllegalStateException("MCP tools/call completed without a result");
            List<String> text = new ArrayList<>();
            boolean unsupported = false;
            if (result.content() != null) {
                for (McpSchema.Content content : result.content()) {
                    if (content instanceof McpSchema.TextContent value) text.add(value.text());
                    else unsupported = true;
                }
            }
            return new McpToolCallResult(text, result.structuredContent(), Boolean.TRUE.equals(result.isError()), unsupported);
        }

        @Override
        public void close() {
            try { client.closeGracefully().block(properties.getRequestTimeout()); }
            catch (RuntimeException ignored) { client.close(); }
        }

        private static McpToolDescriptor descriptor(McpSchema.Tool tool) {
            McpSchema.ToolAnnotations annotations = tool.annotations();
            return new McpToolDescriptor(tool.name(), tool.description(), tool.inputSchema(),
                    annotations == null ? null : annotations.readOnlyHint(),
                    annotations == null ? null : annotations.destructiveHint());
        }
    }
}
