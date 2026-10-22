package com.yizhaoqi.smartpai.agent.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@ConfigurationProperties(prefix = "ai.mcp")
@Data
public class McpProperties {
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private boolean enabled = false;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(8);
    private Duration refreshInterval = Duration.ofMinutes(5);
    private Duration identityTokenTtl = Duration.ofSeconds(30);
    private int maxListPages = 20;
    private int maxToolsPerServer = 200;
    private int maxArgumentCharacters = 16_384;
    private int maxResultCharacters = 6_000;
    private boolean allowInsecureHttp = false;
    private Map<String, Server> servers = new LinkedHashMap<>();

    public void validateConfiguration() {
        if (!enabled) return;
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(requestTimeout, "request-timeout");
        requirePositive(refreshInterval, "refresh-interval");
        requirePositive(identityTokenTtl, "identity-token-ttl");
        if (maxListPages < 1) throw invalid("max-list-pages must be positive");
        if (maxToolsPerServer < 1) throw invalid("max-tools-per-server must be positive");
        if (maxArgumentCharacters < 256) throw invalid("max-argument-characters must be at least 256");
        if (maxResultCharacters < 512) throw invalid("max-result-characters must be at least 512");

        Set<String> exposedNames = new HashSet<>();
        for (Map.Entry<String, Server> entry : servers.entrySet()) {
            String serverId = entry.getKey();
            Server server = entry.getValue();
            if (!validName(serverId)) throw invalid("invalid server id: " + serverId);
            if (server == null) throw invalid("missing server configuration: " + serverId);
            validateServer(serverId, server, exposedNames);
        }
    }

    private void validateServer(String serverId, Server server, Set<String> exposedNames) {
        URI uri;
        try { uri = URI.create(requireText(server.getBaseUrl(), "base-url for " + serverId)); }
        catch (IllegalArgumentException error) { throw invalid("invalid base-url for " + serverId); }
        String scheme = uri.getScheme();
        if (scheme == null || (!"https".equalsIgnoreCase(scheme)
                && !(allowInsecureHttp && "http".equalsIgnoreCase(scheme)))) {
            throw invalid("base-url must use HTTPS for " + serverId);
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw invalid("base-url must be an absolute URL without query or fragment for " + serverId);
        }
        if (server.getEndpoint() == null || !server.getEndpoint().startsWith("/")
                || server.getEndpoint().startsWith("//") || server.getEndpoint().contains("\\")
                || server.getEndpoint().contains("?") || server.getEndpoint().contains("#")) {
            throw invalid("endpoint must be an absolute path for " + serverId);
        }
        validateSigningKey(serverId, server.getIdentitySigningKey());

        Set<String> remoteNames = new HashSet<>();
        Set<String> aliases = new HashSet<>();
        for (ReadOnlyTool tool : server.getReadOnlyTools()) {
            String remoteName = requireText(tool.getRemoteName(), "remote-name for " + serverId);
            String alias = requireText(tool.getAlias(), "alias for " + serverId);
            if (!validName(alias)) throw invalid("invalid tool alias: " + serverId + "/" + alias);
            String exposedName = serverId + "__" + alias;
            if (exposedName.length() > 64) throw invalid("exposed tool name exceeds 64 characters: " + exposedName);
            if (!remoteNames.add(remoteName)) throw invalid("duplicate remote tool: " + serverId + "/" + remoteName);
            if (!aliases.add(alias) || !exposedNames.add(exposedName)) {
                throw invalid("duplicate exposed tool: " + exposedName);
            }
        }
    }

    private static void validateSigningKey(String serverId, String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(requireText(value, "identity-signing-key for " + serverId));
            if (decoded.length < 32) throw invalid("identity-signing-key must decode to at least 32 bytes for " + serverId);
        } catch (IllegalArgumentException error) {
            if (error.getMessage() != null && error.getMessage().startsWith("Invalid ai.mcp")) throw error;
            throw invalid("identity-signing-key must be valid Base64 for " + serverId);
        }
    }

    private static boolean validName(String value) {
        return value != null && !value.isBlank() && TOOL_NAME.matcher(value).matches();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw invalid(field + " must not be blank");
        return value.trim();
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) throw invalid(field + " must be positive");
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid ai.mcp configuration: " + message);
    }

    @Data
    public static class Server {
        private String baseUrl;
        private String endpoint = "/mcp";
        @lombok.ToString.Exclude private String serviceToken;
        @lombok.ToString.Exclude private String identitySigningKey;
        private List<ReadOnlyTool> readOnlyTools = new ArrayList<>();
    }

    @Data
    public static class ReadOnlyTool {
        private String remoteName;
        private String alias;
        private String descriptionOverride;
    }
}
