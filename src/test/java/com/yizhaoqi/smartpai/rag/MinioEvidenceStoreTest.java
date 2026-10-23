package com.yizhaoqi.smartpai.rag;

import com.fasterxml.jackson.databind.*;
import com.yizhaoqi.smartpai.rag.trace.*;
import io.minio.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class MinioEvidenceStoreTest {
    JdbcTemplate jdbc; MinioClient minio=mock(MinioClient.class); MinioEvidenceStore store;
    ObjectMapper json=new ObjectMapper(); Map<String,byte[]> objects=new HashMap<>();
    String runId=UUID.randomUUID().toString();
    @BeforeEach void setup() throws Exception {
        var source=new JdbcDataSource(); source.setURL("jdbc:h2:mem:rag_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try(var connection=source.getConnection()) { ScriptUtils.executeSqlScript(connection,new FileSystemResource("docs/databases/ddl.sql")); }
        jdbc=new JdbcTemplate(source);
        when(minio.putObject(any(PutObjectArgs.class))).thenAnswer(call -> {
            PutObjectArgs args=call.getArgument(0); objects.put(args.object(),args.stream().readAllBytes()); return null;
        });
        when(minio.getObject(any(GetObjectArgs.class))).thenAnswer(call -> {
            GetObjectArgs args=call.getArgument(0);
            return new GetObjectResponse(new okhttp3.Headers.Builder().build(),"rag-evidence","",args.object(),new ByteArrayInputStream(objects.get(args.object())));
        });
        var config=new RagProperties(); config.setEvidenceBackend("minio");
        store=new MinioEvidenceStore(minio,jdbc,new TransactionTemplate(new DataSourceTransactionManager(source)),json,config);
    }
    com.fasterxml.jackson.databind.node.ObjectNode document(String status) {
        return json.createObjectNode().put("runId",runId).put("owner","id:1").put("kind","question")
            .put("status",status).put("evidenceStatus","COMPLETE").put("startedAt",System.currentTimeMillis()).set("events",json.createArrayNode());
    }
    @Test void publishesOnlyUploadedObjectsAndEnforcesOwnershipAndIntegrity() throws Exception {
        store.save(runId,document("RUNNING")); store.save(runId,document("SUCCEEDED"));
        assertEquals(2,objects.size()); assertEquals("SUCCEEDED",store.read(runId,"id:1").get("status").asText());
        assertThrows(FileNotFoundException.class,() -> store.read(runId,"id:2"));
        String current=jdbc.queryForObject("SELECT object_key FROM rag_run WHERE run_id=?",String.class,runId);
        objects.put(current,document("FAILED").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IOException.class,() -> store.read(runId,"id:1"));
    }
    @Test void failedUploadDoesNotPublishIndexAndExpiryRemovesEveryVersion() throws Exception {
        when(minio.putObject(any(PutObjectArgs.class))).thenThrow(new IOException("offline"));
        assertThrows(IOException.class,() -> store.save(runId,document("SUCCEEDED")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rag_run",Integer.class));
        reset(minio); store.save(runId,document("RUNNING")); store.save(runId,document("SUCCEEDED"));
        jdbc.update("UPDATE rag_run SET expires_at=0"); store.expire();
        verify(minio,times(2)).removeObject(any(RemoveObjectArgs.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rag_run",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM rag_artifact",Integer.class));
    }
}
