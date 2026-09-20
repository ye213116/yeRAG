package com.yizhaoqi.smartpai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.yizhaoqi.smartpai.memory.MemoryTypes.*;

class MemoryStoreTest {
    JdbcTemplate db;
    MemoryStore store;
    MemoryProperties config;
    @BeforeEach void setup() throws Exception {
        JdbcDataSource ds=new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:memory"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(ds);
        db.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, username VARCHAR(255) UNIQUE)");
        db.update("INSERT INTO users VALUES(1,'123'),(123,'another')");
        String ddl=Files.readString(Path.of("docs/databases/migrations/2026-09-09-memory-v1.sql"))
                .replace(" CHARACTER SET utf8mb4 COLLATE utf8mb4_bin", "")
                .replace("value_json JSON", "value_json VARCHAR(4096)");
        try(var c=ds.getConnection()) { ScriptUtils.executeSqlScript(c,new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8))); }
        config=new MemoryProperties(); config.setEnabled(true);
        store=new MemoryStore(db,new DataSourceTransactionManager(ds),config,new ObjectMapper());
    }
    Turn completed(String q) {
        Turn t=store.begin(1,null,UUID.randomUUID().toString(),q).turn(); assertTrue(store.complete(t,"answer")); return t;
    }
    Change occupation(String value) { return new Change("profile.occupation","profile","occupation",value,"UPDATE","我是"+value,null); }
    @Test void readsMySqlAndH2DateTimeRepresentations() {
        Instant expected = Instant.parse("2026-09-10T03:04:05.123456Z");
        assertEquals(expected, MemoryStore.instant(Timestamp.from(expected)));
        LocalDateTime local = LocalDateTime.of(2026, 9, 10, 11, 4, 5, 123_456_000);
        assertEquals(Timestamp.valueOf(local).toInstant(), MemoryStore.instant(local));
    }
    @Test void persistentSessionBoundaryOwnershipIdempotencyAndCancellation() {
        String sid=store.current(1,false);
        Begin first=store.begin(1,sid,"req1","你好");
        assertEquals(first.turn().id(),store.begin(1,sid,"req1","你好").turn().id());
        assertThrows(ResponseStatusException.class,()->store.begin(1,sid,"req1","不同内容"));
        assertThrows(ResponseStatusException.class,()->store.begin(123,sid,"other","你好"));
        assertThrows(ResponseStatusException.class,()->store.begin(1,sid,"req2","并发"));
        String next=store.current(1,true);
        assertNotEquals(sid,next);
        assertFalse(store.complete(first.turn(),"late answer"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM memory_extraction_task",Integer.class));
    }
    @Test void completedAnswerAndTaskCommitTogetherAndCanRecoverRedis() {
        Turn t=completed("我是工程师");
        assertFalse(store.complete(t,"duplicate"));
        assertEquals("answer",store.turn(t.id()).answer());
        assertEquals(1,store.pendingSync().size());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM memory_extraction_task",Integer.class));
        store.syncFailed(t.id());
        store.synced(t.sessionId(),t.id());
        assertTrue(store.pendingSync().isEmpty());
    }
    @Test void newerSameFactAdvancesBarrierAndDeletedSlotCannotBeResurrected() {
        completed("我是工程师"); Task first=store.claim().orElseThrow(); store.apply(first,List.of(occupation("工程师")));
        completed("我是产品经理"); Task older=store.claim().orElseThrow();
        completed("我还是工程师"); Task newer=store.claim().orElseThrow();
        store.apply(newer,List.of(occupation("工程师")));
        store.apply(older,List.of(occupation("产品经理")));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM memory_version",Integer.class));
        completed("我是产品经理"); Task delayed=store.claim().orElseThrow();
        completed("忘记我的职业"); Task deletion=store.claim().orElseThrow();
        store.apply(deletion,List.of(new Change("profile.occupation","profile","occupation","工程师","DELETE","忘记我的职业",null)));
        store.apply(delayed,List.of(occupation("产品经理")));
        assertTrue(store.memories(1).isEmpty());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM memory_slot",Integer.class));
    }
    @Test void updateRetainsAuditAndOtherUsersCannotRecallIt() {
        completed("我是工程师"); store.apply(store.claim().orElseThrow(),List.of(occupation("工程师")));
        completed("我是产品经理"); store.apply(store.claim().orElseThrow(),List.of(occupation("产品经理")));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM memory_version",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM memory_version WHERE status='ACTIVE'",Integer.class));
        assertEquals(1,store.memories(1).size()); assertTrue(store.memories(123).isEmpty());
    }
    @Test void deleteAddsTombstoneAndSupersedesPriorVersion() {
        completed("我是工程师"); store.apply(store.claim().orElseThrow(),List.of(occupation("工程师")));
        completed("忘记我的职业"); store.apply(store.claim().orElseThrow(),List.of(
                new Change("profile.occupation","profile","occupation","工程师","DELETE","忘记我的职业",null)));
        assertTrue(store.memories(1).isEmpty());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM memory_version WHERE status='SUPERSEDED'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM memory_version WHERE status='DELETED' AND operation='DELETE'",Integer.class));
    }
    @Test void expiredContextIsNotReportedAsSynced() {
        Turn t=completed("你好");
        store.contextExpired(t.id());
        assertEquals("EXPIRED",db.queryForObject("SELECT context_sync_status FROM chat_turn WHERE id=?",String.class,t.id()));
    }
    @Test void expiredWorkerCannotCommitAfterTakeover() {
        completed("我是工程师"); Task old=store.claim().orElseThrow();
        db.update("UPDATE memory_extraction_task SET lease_until=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),old.id());
        Task replacement=store.claim().orElseThrow();
        store.apply(old,List.of(occupation("旧worker")));
        assertTrue(store.memories(1).isEmpty());
        store.apply(replacement,List.of(occupation("工程师")));
        assertEquals(1,store.memories(1).size());
        store.taskFailed(old,"late_failure");
        assertEquals("DONE",db.queryForObject("SELECT status FROM memory_extraction_task WHERE id=?",String.class,old.id()));
    }
    @Test void duplicateRequestRaceCreatesOneTurn() throws Exception {
        String sid=store.current(1,false);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start=new CountDownLatch(1);
            Callable<Begin> call=()->{ start.await(); return store.begin(1,sid,"race","我是工程师"); };
            Future<Begin> a=pool.submit(call), b=pool.submit(call); start.countDown();
            assertEquals(a.get(5,TimeUnit.SECONDS).turn().id(),b.get(5,TimeUnit.SECONDS).turn().id());
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM chat_turn",Integer.class));
        } finally { pool.shutdownNow(); }
    }
    @Test void expiredContentIsNotExtractedAndIdleSessionSwitches() {
        Turn t=completed("我是工程师"); Task task=store.claim().orElseThrow();
        db.update("UPDATE chat_turn SET content_expires_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),t.id());
        store.maintain(); store.apply(task,List.of(occupation("工程师")));
        assertTrue(store.memories(1).isEmpty()); assertNull(store.turn(t.id()).question());
        db.update("UPDATE chat_session SET last_activity_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(3600)),t.sessionId());
        assertNotEquals(t.sessionId(),store.current(1,false));
    }
    @Test void legacyImportIsIdempotentAndDoesNotExtract() {
        List<Map<String,String>> old=List.of(Map.of("role","user","content","问题"),Map.of("role","assistant","content","回答"));
        store.importLegacy(1,"old",old); store.importLegacy(1,"old",old);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM chat_turn",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM memory_extraction_task",Integer.class));
    }
}
