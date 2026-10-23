package com.yizhaoqi.smartpai.rag;

import com.fasterxml.jackson.databind.*;
import com.yizhaoqi.smartpai.rag.trace.*;
import com.yizhaoqi.smartpai.rag.model.RagTypes.Status;
import com.yizhaoqi.smartpai.controller.RagEvidenceController;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.model.User;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvidenceStoreTest {
    @TempDir Path directory;
    ObjectMapper json=new ObjectMapper(); RagProperties config=new RagProperties();
    @BeforeEach void setup(){config.setEvidenceDirectory(directory.toString());}
    @Test void snapshotsAreFrozenAndOwnerAndExpiryAreEnforced() throws Exception {
        var store=new LocalEvidenceStore(json,config); var recorder=new EvidenceRecorder(store,json,config);
        Map<String,String> request=new HashMap<>(Map.of("question","original"));
        var run=recorder.start("name:alice",null,"question",request); request.put("question","mutated");
        run.finish(Status.SUCCEEDED); run.finish(Status.FAILED);
        JsonNode snapshot=store.read(run.id(),"name:alice");
        assertEquals("original",snapshot.path("events").path(0).path("data").path("question").asText());
        assertEquals("SUCCEEDED",snapshot.get("status").asText());
        assertThrows(SecurityException.class,() -> store.read(run.id(),"name:bob"));
        assertThrows(IllegalArgumentException.class,() -> store.read("../escape","name:alice"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)snapshot).put("startedAt",0); store.save(run.id(),snapshot);
        assertThrows(java.io.FileNotFoundException.class,() -> store.read(run.id(),"name:alice"));
        Files.setLastModifiedTime(directory.resolve(run.id()+".json"),java.nio.file.attribute.FileTime.fromMillis(0));
        store.expire(); assertFalse(Files.exists(directory.resolve(run.id()+".json")));
    }
    @Test void oversizedEvidenceIsExplicitlyIncompleteAndWriteFailureDoesNotThrow() throws Exception {
        config.setMaxEvidenceBytes(4096); var store=new LocalEvidenceStore(json,config);
        var run=new EvidenceRecorder(store,json,config).start("name:alice",null,"question",Map.of());
        run.event("huge","x".repeat(10000)); run.finish(Status.SUCCEEDED);
        assertEquals("INCOMPLETE",store.read(run.id(),"name:alice").get("evidenceStatus").asText());
        EvidenceStore broken=mock(EvidenceStore.class); doThrow(new java.io.IOException()).when(broken).save(anyString(),any());
        var failed=new EvidenceRecorder(broken,json,config).start("name:alice",null,"question",Map.of());
        assertDoesNotThrow(() -> failed.finish(Status.FAILED));
        assertEquals("INCOMPLETE",failed.snapshot().get("evidenceStatus").asText());
    }
    @Test void numericUsernameCannotReadSomebodyElsesNumericIdTrace() throws Exception {
        var store=new LocalEvidenceStore(json,config); var recorder=new EvidenceRecorder(store,json,config);
        var run=recorder.start("id:123",null,"question",Map.of()); run.finish(Status.SUCCEEDED);
        UserRepository users=mock(UserRepository.class); User alice=new User(); alice.setId(7L); alice.setUsername("123");
        when(users.findByUsername("123")).thenReturn(Optional.of(alice));
        var controller=new RagEvidenceController(store,users);
        assertEquals(404,assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> controller.get(run.id(),() -> "123")).getStatusCode().value());
        var own=recorder.start("name:123",null,"question",Map.of()); own.finish(Status.SUCCEEDED);
        assertEquals(own.id(),controller.get(own.id(),() -> "123").get("runId").asText());
    }
}
