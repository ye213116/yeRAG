package com.yizhaoqi.smartpai.rag.trace;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yizhaoqi.smartpai.rag.RagProperties;
import com.yizhaoqi.smartpai.rag.model.RagTypes.Status;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Component
public class EvidenceRecorder {
    private final EvidenceStore store;
    private final ObjectMapper json;
    private final RagProperties properties;
    public EvidenceRecorder(EvidenceStore store, ObjectMapper json, RagProperties properties) {
        this.store=store; this.json=json; this.properties=properties;
    }
    public Run start(String owner, String parent, String kind, Object request) {
        return new Run(owner, parent, kind, request);
    }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public final class Run {
        private final String id = UUID.randomUUID().toString();
        private final ObjectNode document = json.createObjectNode();
        private final long started = System.nanoTime();
        private boolean finished;
        private boolean incomplete;
        private long size;
        private Run(String owner, String parent, String kind, Object request) {
            document.put("schemaVersion",1).put("runId",id).put("owner",owner).put("parentRunId",parent)
                .put("kind",kind).put("startedAt",System.currentTimeMillis()).put("status","RUNNING")
                .put("codeVersion",properties.getCodeVersion()).put("corpusVersion",properties.getCorpusVersion())
                .put("evidenceStatus","COMPLETE");
            document.set("events",json.createArrayNode());
            event("request", request); flush();
        }
        public String id() { return id; }
        public synchronized void event(String name, Object data) {
            if (finished) return;
            try {
                JsonNode snapshot=json.valueToTree(data);
                String encoded=snapshot.toString();
                ObjectNode event=json.createObjectNode().put("name",name).put("elapsedMs",elapsed()).put("sha256",hash(encoded));
                event.set("data",snapshot);
                long bytes=event.toString().getBytes(StandardCharsets.UTF_8).length+1;
                if(size+bytes>properties.getMaxEvidenceBytes()-2048) {
                    incomplete=true; document.put("evidenceStatus","INCOMPLETE"); return;
                }
                size+=bytes; ((ArrayNode)document.get("events")).add(event);
            } catch(RuntimeException failure) { incomplete=true; document.put("evidenceStatus","INCOMPLETE"); }
        }

        public long elapsed() { return (System.nanoTime()-started)/1_000_000; }
        public synchronized void finish(Status status) {
            if (finished) return;
            finished=true;
            document.put("status",status.name()).put("endedAt",System.currentTimeMillis()).put("elapsedMs",elapsed());
            flush();
        }
        private void flush() {
            document.put("evidenceStatus",incomplete ? "INCOMPLETE" : "COMPLETE");
            JsonNode snapshot=document.deepCopy();
            if(reactor.core.scheduler.Schedulers.isInNonBlockingThread())
                reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> persist(snapshot));
            else persist(snapshot);
        }
        private void persist(JsonNode snapshot) {
            try { store.save(id,snapshot); }
            catch(Exception error) {
                synchronized(this) { incomplete=true; document.put("evidenceStatus","INCOMPLETE"); }
                LoggerFactory.getLogger(EvidenceRecorder.class).warn("Cannot persist RAG evidence run={}: {}",id,error.getClass().getSimpleName());
            }
        }
        public synchronized JsonNode snapshot() { return document.deepCopy(); }
    }
}
