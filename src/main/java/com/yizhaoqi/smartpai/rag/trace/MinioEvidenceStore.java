package com.yizhaoqi.smartpai.rag.trace;

import com.fasterxml.jackson.databind.*;
import com.yizhaoqi.smartpai.rag.RagProperties;
import io.minio.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.util.*;

/** Immutable content-addressed object; relational manifest is published only after upload succeeds. */
@Component
@ConditionalOnProperty(name="ai.rag.evidence-backend",havingValue="minio")
public class MinioEvidenceStore implements EvidenceStore {
    private final MinioClient minio;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final RagProperties config;
    public MinioEvidenceStore(MinioClient minio,JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json,RagProperties config) {
        this.minio=minio; this.jdbc=jdbc; this.tx=tx; this.json=json; this.config=config; config.validate();
    }
    @Override public void save(String id,JsonNode document) throws Exception {
        UUID.fromString(id);
        byte[] bytes=json.writeValueAsBytes(document);
        if(bytes.length>config.getMaxEvidenceBytes()) throw new IOException("EVIDENCE_SIZE_LIMIT");
        String hash=EvidenceRecorder.hash(document.toString());
        String key=id+"/"+hash+".json";
        // The bucket is provisioned by the operator, including a lifecycle rule for orphan objects.
        minio.putObject(PutObjectArgs.builder().bucket(config.getEvidenceBucket()).object(key)
                .stream(new ByteArrayInputStream(bytes),bytes.length,-1).contentType("application/json").build());
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO rag_run (run_id,owner,parent_run_id,kind,status,evidence_status,started_at,expires_at,object_key,sha256) VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE status=VALUES(status),evidence_status=VALUES(evidence_status),object_key=VALUES(object_key),sha256=VALUES(sha256)",
                id,document.path("owner").asText(),document.path("parentRunId").asText(null),document.path("kind").asText(),
                document.path("status").asText(),document.path("evidenceStatus").asText(),document.path("startedAt").asLong(),
                document.path("startedAt").asLong()+config.getRetentionDays()*86400000L,key,hash);
            jdbc.update("DELETE FROM rag_stage_run WHERE run_id=?",id);
            int ordinal=0;
            for(JsonNode event:document.path("events")) jdbc.update(
                "INSERT INTO rag_stage_run (run_id,event_ordinal,stage,elapsed_ms,sha256) VALUES (?,?,?,?,?)",
                id,ordinal++,event.path("name").asText(),event.path("elapsedMs").asLong(),event.path("sha256").asText());
            jdbc.update("INSERT IGNORE INTO rag_artifact (run_id,object_key,sha256,byte_count) VALUES (?,?,?,?)",id,key,hash,bytes.length);
        });
    }
    @Override public JsonNode read(String id,String owner) throws Exception {
        UUID.fromString(id);
        var rows=jdbc.queryForList("SELECT object_key,sha256 FROM rag_run WHERE run_id=? AND owner=? AND expires_at>?",id,owner,System.currentTimeMillis());
        if(rows.isEmpty()) throw new FileNotFoundException("Evidence not found");
        var row=rows.get(0);
        try(var stream=minio.getObject(GetObjectArgs.builder().bucket(config.getEvidenceBucket()).object(row.get("object_key").toString()).build())) {
            byte[] bytes=stream.readNBytes(Math.toIntExact(config.getMaxEvidenceBytes()+1));
            if(bytes.length>config.getMaxEvidenceBytes()) throw new IOException("EVIDENCE_SIZE_LIMIT");
            JsonNode result=json.readTree(bytes);
            if(!EvidenceRecorder.hash(result.toString()).equals(row.get("sha256"))) throw new IOException("EVIDENCE_HASH_MISMATCH");
            if(!owner.equals(result.path("owner").asText())) throw new SecurityException("Evidence not found");
            return result;
        }
    }
    @Override public synchronized void expire() throws Exception {
        for(String id:jdbc.queryForList("SELECT run_id FROM rag_run WHERE expires_at<=? LIMIT 1000",String.class,System.currentTimeMillis())) {
            for(String key:jdbc.queryForList("SELECT object_key FROM rag_artifact WHERE run_id=?",String.class,id))
                minio.removeObject(RemoveObjectArgs.builder().bucket(config.getEvidenceBucket()).object(key).build());
            tx.executeWithoutResult(status -> {
                jdbc.update("DELETE FROM rag_stage_run WHERE run_id=?",id);
                jdbc.update("DELETE FROM rag_artifact WHERE run_id=?",id);
                jdbc.update("DELETE FROM rag_run WHERE run_id=?",id);
            });
        }
    }
}
