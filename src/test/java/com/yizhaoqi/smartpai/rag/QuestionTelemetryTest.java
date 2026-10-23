package com.yizhaoqi.smartpai.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.AgentRunResult;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.rag.trace.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class QuestionTelemetryTest {
    @TempDir Path directory;
    @Test void fallbackSharesOriginalDeadlineAndTimeoutClosesRootOnce() throws Exception {
        var json=new ObjectMapper(); var rag=new RagProperties(); rag.setEvidenceDirectory(directory.toString());
        var store=new LocalEvidenceStore(json,rag); var ai=new AiProperties(); ai.getAgent().setTotalTimeout(Duration.ofMillis(200));
        var telemetry=new QuestionTelemetry(new EvidenceRecorder(store,json,rag),store,ai);
        var fallback=new AtomicInteger();
        StepVerifier.create(telemetry.observe("id:1","question","session","request",() ->
            Mono.delay(Duration.ofMillis(40)).then(Mono.<AgentRunResult>error(new IllegalStateException()))
                .onErrorResume(error -> {
                    fallback.incrementAndGet();
                    return Mono.delay(Duration.ofSeconds(1)).thenReturn(new AgentRunResult("late",Map.of()));
                })))
            .expectError(java.util.concurrent.TimeoutException.class).verify(Duration.ofSeconds(5));
        assertEquals(1,fallback.get());
        try(var files=Files.list(directory)) {
            var rows=files.toList(); assertEquals(1,rows.size());
            assertEquals("TIMED_OUT",json.readTree(rows.get(0).toFile()).path("status").asText());
        }
    }
    @Test void generationAndDatabasePersistenceHaveSeparateOutcomes() throws Exception {
        var json=new ObjectMapper(); var rag=new RagProperties(); rag.setEvidenceDirectory(directory.toString());
        var store=new LocalEvidenceStore(json,rag); var telemetry=new QuestionTelemetry(new EvidenceRecorder(store,json,rag),store,new AiProperties());
        var answer=telemetry.observe("id:1","q","s","r",() -> Mono.just(new AgentRunResult("answer",Map.of()))).block();
        telemetry.persisted(answer.runId(),"id:1",false);
        var trace=store.read(answer.runId(),"id:1");
        assertEquals("SUCCEEDED",trace.path("status").asText()); assertFalse(trace.path("answerPersisted").asBoolean());
    }
}
