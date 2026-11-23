package com.yizhaoqi.smartpai.coding.sandbox;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.yizhaoqi.smartpai.coding.CodingProperties;
import com.yizhaoqi.smartpai.coding.CodingTypes.*;
import com.yizhaoqi.smartpai.agent.profile.WorkspacePermission;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DockerSandboxExecutorTest {
    HttpServer server;
    ObjectMapper json=new ObjectMapper();
    JsonNode container;
    List<String> requests=new ArrayList<>();
    DockerSandboxExecutor executor;
    boolean resources=true;
    Workspace workspace=new Workspace(UUID.randomUUID().toString(),1,"demo","main","a".repeat(40),Set.of(WorkspacePermission.READ),"READY","task");
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/",exchange -> {
            String path=exchange.getRequestURI().getPath();requests.add(exchange.getRequestMethod()+" "+path);
            byte[] body;
            try {
                if(path.equals("/containers/create")) { container=json.readTree(exchange.getRequestBody());body="{\"Id\":\"fixture\"}".getBytes(StandardCharsets.UTF_8); }
                else if(path.endsWith("/logs")) {
                    byte[] payload="{\"ok\":true,\"content\":\"fixture text\"}".getBytes(StandardCharsets.UTF_8);
                    body=ByteBuffer.allocate(payload.length+8).putInt(0x01000000).putInt(payload.length).put(payload).array();
                } else if(path.equals("/info")) body=json.writeValueAsBytes(Map.of("OSType","linux","MemoryLimit",resources,"PidsLimit",true,"CpuCfsQuota",true));
                else if(path.startsWith("/networks/")) body="{\"Internal\":true}".getBytes(StandardCharsets.UTF_8);
                else if(path.endsWith("/json")) body="{\"State\":{\"Running\":false,\"ExitCode\":0}}".getBytes(StandardCharsets.UTF_8);
                else body="{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });server.start();
        executor=new DockerSandboxExecutor(new CodingProperties(),json,HttpClient.newHttpClient(),URI.create("http://localhost:"+server.getAddress().getPort()));
    }
    @AfterEach void close() { server.stop(0); }
    @Test void modelArgumentsOnlyReachTheHelperAndReadMountsAreReadOnly() {
        executor.preflight();
        var result=executor.execute(workspace,Operation.READ,Map.of("path","Example.java"),Duration.ofSeconds(3),() -> {});
        assertEquals("fixture text",result.path("content").asText());
        assertEquals(List.of("python3","/opt/yerag/helper.py"),json.convertValue(container.path("Cmd"),List.class));
        assertEquals("10001:10001",container.path("User").asText());
        JsonNode host=container.path("HostConfig");assertTrue(host.path("ReadonlyRootfs").asBoolean());
        assertEquals("none",host.path("NetworkMode").asText());
        assertEquals(256,host.path("PidsLimit").asInt());assertEquals(2_000_000_000L,host.path("NanoCpus").asLong());
        for(var mount:host.path("Mounts")) { assertEquals("volume",mount.path("Type").asText());assertTrue(mount.path("ReadOnly").asBoolean()); }
        assertEquals(1,container.path("Env").size());
        assertTrue(requests.contains("DELETE /containers/fixture"));
    }
    @Test void authorizationRevocationRemovesTheContainerInsteadOfLeavingAProcessRunning() {
        var checks=new AtomicInteger();
        assertThrows(SecurityException.class,() -> executor.execute(workspace,Operation.READ,Map.of("path","Example.java"),Duration.ofSeconds(3),() -> {
            if(checks.incrementAndGet()>=3) throw new SecurityException();
        }));
        assertTrue(requests.contains("POST /containers/fixture/start"));
        assertTrue(requests.contains("DELETE /containers/fixture"));
    }
    @Test void resourceChecksAndLogFramingFailClosed() {
        resources=false;assertThrows(IllegalStateException.class,executor::preflight);
        assertThrows(java.io.IOException.class,() -> DockerSandboxExecutor.demultiplex(new byte[7],100));
        assertThrows(java.io.IOException.class,() -> DockerSandboxExecutor.demultiplex(ByteBuffer.allocate(8).putInt(0x01000000).putInt(500).array(),100));
    }
    @Test void interruptedControllerStillDeletesTheDeterministicallyNamedContainer() {
        Thread.currentThread().interrupt();
        try {
            executor.stop(workspace.id());
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(requests.contains("DELETE /containers/yerag-coding-"+workspace.id()+"-operation"));
        } finally { Thread.interrupted(); }
    }
}
