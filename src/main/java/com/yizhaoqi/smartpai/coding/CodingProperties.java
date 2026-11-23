package com.yizhaoqi.smartpai.coding;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;

@Data
@Component
@ConfigurationProperties(prefix = "ai.coding")
public class CodingProperties {
    private boolean enabled;
    private Map<String, Repository> repositories = new LinkedHashMap<>();
    private String dockerUrl;
    private String dockerTrustStore;
    private String dockerKeyStore;
    private String dockerStorePasswordEnv = "YERAG_DOCKER_STORE_PASSWORD";
    private String image = "yerag-coding:java17";
    private String cloneNetwork = "yerag-coding-clone";
    private String buildNetwork = "yerag-coding-build";
    private String proxyUrl = "http://egress:3128";
    private Duration commandTimeout = Duration.ofSeconds(120);
    private int maxLogBytes = 1024 * 1024;
    private int maxWorkspaceBytes = 256 * 1024 * 1024;
    private int maxResultBytes = 2 * 1024 * 1024;
    private int concurrency = 1;

    @Data
    public static class Repository {
        private String fullName;
        private Set<Long> allowedUsers = new HashSet<>();
        private Set<com.yizhaoqi.smartpai.agent.profile.WorkspacePermission> allowedPermissions =
                java.util.EnumSet.allOf(com.yizhaoqi.smartpai.agent.profile.WorkspacePermission.class);
        private Set<String> allowedBranches = new HashSet<>(Set.of("main"));
        private String readTokenEnv;
        private String publishTokenEnv;
        public void validate() {
            if (fullName == null || !fullName.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
                    || fullName.contains("..") || allowedBranches.isEmpty())
                throw new IllegalArgumentException("Invalid registered GitHub repository");
        }
    }

    public Repository repository(String id, long user) {
        Repository repository = repositories.get(id);
        if (!enabled || repository == null || !repository.getAllowedUsers().contains(user)
                || !repository.getAllowedPermissions().contains(com.yizhaoqi.smartpai.agent.profile.WorkspacePermission.READ))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"仓库不存在或未授权");
        repository.validate();
        return repository;
    }

    public static String secret(String name) {
        if (name == null || name.isBlank()) return "";
        if (!name.matches("[A-Z][A-Z0-9_]{0,127}")) throw new IllegalArgumentException("Invalid credential reference");
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Configured credential is unavailable");
        return value;
    }
}
