package com.yizhaoqi.smartpai.coding.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.profile.WorkspacePermission;
import com.yizhaoqi.smartpai.coding.*;
import com.yizhaoqi.smartpai.coding.CodingTypes.*;
import com.yizhaoqi.smartpai.coding.workspace.GitHubRepositoryClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: isolated remote Docker, a small green Maven fixture repo; never creates a PR. */
@EnabledIfEnvironmentVariable(named="CODING_DOCKER_TEST_URL",matches=".+")
class CodingDockerIntegrationTest {
    @Test void realRunnerEnforcesIsolationQuotaAndRunsMavenVerification() {
        var config=new CodingProperties();config.setDockerUrl(System.getenv("CODING_DOCKER_TEST_URL"));
        config.setDockerTrustStore(System.getenv("CODING_DOCKER_TEST_TRUST_STORE"));
        config.setDockerKeyStore(System.getenv("CODING_DOCKER_TEST_KEY_STORE"));
        config.setDockerStorePasswordEnv("CODING_DOCKER_TEST_STORE_PASSWORD");
        if(System.getenv("CODING_DOCKER_TEST_IMAGE")!=null) config.setImage(System.getenv("CODING_DOCKER_TEST_IMAGE"));
        var repo=new CodingProperties.Repository();repo.setFullName(Objects.requireNonNull(System.getenv("CODING_DOCKER_TEST_REPOSITORY")));
        String branch=Objects.requireNonNullElse(System.getenv("CODING_DOCKER_TEST_BRANCH"),"main");repo.setAllowedBranches(Set.of(branch));
        repo.setReadTokenEnv(System.getenv("CODING_DOCKER_TEST_TOKEN_ENV"));
        var json=new ObjectMapper();var github=new GitHubRepositoryClient(json);
        var workspace=new Workspace(UUID.randomUUID().toString(),1,"fixture",branch,github.branch(repo,branch),EnumSet.allOf(WorkspacePermission.class),"NEW",null);
        var sandbox=new DockerSandboxExecutor(config,json);
        try {
            sandbox.prepare(workspace,repo,() -> {});
            assertTrue(sandbox.execute(workspace,Operation.READ,Map.of("path","pom.xml"),Duration.ofSeconds(10),() -> {}).path("ok").asBoolean());
            assertFalse(sandbox.execute(workspace,Operation.READ,Map.of("path","../etc/passwd"),Duration.ofSeconds(10),() -> {}).path("ok").asBoolean());
            var check=sandbox.execute(workspace,Operation.COMMAND,Map.of("argv",List.of("python3","-c",
                    "import os, pathlib, socket, shutil; assert os.getuid()!=0; assert not pathlib.Path('/var/run/docker.sock').exists(); assert 'YERAG_CLONE_TOKEN' not in os.environ; assert shutil.disk_usage('/workspace').total<=268439552; s=socket.socket(); s.settimeout(1); assert s.connect_ex(('169.254.169.254',80))!=0")),Duration.ofSeconds(10),() -> {});
            assertTrue(check.path("ok").asBoolean(),check.toString());
            var maven=sandbox.execute(workspace,Operation.COMMAND,Map.of("argv",List.of("mvn","--version")),Duration.ofSeconds(10),() -> {});
            assertTrue(maven.path("ok").asBoolean(),maven.toString());
            var verification=sandbox.execute(workspace,Operation.TEST,Map.of(),Duration.ofSeconds(120),() -> {});
            assertEquals("PASSED",verification.path("verification").path("status").asText(),verification.toString());
            var snapshot=sandbox.execute(workspace,Operation.SNAPSHOT,Map.of(),Duration.ofSeconds(20),() -> {});
            assertTrue(snapshot.path("changes").isEmpty());
        } finally { sandbox.destroy(workspace.id()); }
    }
}
