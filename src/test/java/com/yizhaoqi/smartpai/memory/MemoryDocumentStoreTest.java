package com.yizhaoqi.smartpai.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import java.util.UUID;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.yizhaoqi.smartpai.memory.MemoryTypes.*;

class MemoryDocumentStoreTest {
    org.springframework.jdbc.core.JdbcTemplate db;
    MemoryStore store;
    MemoryProperties config;
    Turn completed(String q) {
        Turn t=store.begin(1,null,UUID.randomUUID().toString(),q).turn();
        assertTrue(store.complete(t,"answer")); return t;
    }
    @BeforeEach void documentsSchema() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:memory" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        try (var c = db.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(c, new FileSystemResource("docs/databases/ddl.sql"));
        }
        db.update("INSERT INTO users(id,username,password,role) VALUES(1,'123','test','USER'),(123,'another','test','USER')");
        config = new MemoryProperties();
        store = new MemoryStore(db, new DataSourceTransactionManager(ds), config);
    }
    MemoryPatch patch(UUID id, String heading, String content) {
        return new MemoryPatch(MemoryType.PROFILE, id, heading, MemoryPatchOperation.UPSERT_SECTION, content, "我是工程师");
    }
    @Test void orderedClaimAllowsOtherUsersButBlocksLaterUserTasks() {
        completed("我是工程师"); Task first = store.claim().orElseThrow();
        completed("我是工程师");
        assertTrue(store.claim().isEmpty());
        var other = store.begin(123,null,"other-user","我是工程师").turn(); store.complete(other,"ok");
        assertEquals(other.id(),store.claim().orElseThrow().turnId());
        store.applyPatches(first,List.of());
        assertTrue(store.claim().isPresent());
    }
    @Test void updatesCurrentMarkdownAndPreservesIdentityAndTombstone() {
        completed("我是工程师"); store.applyPatches(store.claim().orElseThrow(),List.of(patch(null,"职业","用户是工程师。")));
        var doc = store.documents(1).get(0); var id = doc.sections().get(0).id();
        assertTrue(doc.content().contains("## 职业")); assertFalse(doc.content().contains(id.toString()));
        completed("我是工程师"); store.applyPatches(store.claim().orElseThrow(),List.of(patch(id,"背景","用户是工程师。")));
        assertEquals(id,store.documents(1).get(0).sections().get(0).id());
        completed("忘记我的职业"); store.applyPatches(store.claim().orElseThrow(),List.of(new MemoryPatch(MemoryType.PROFILE,id,null,MemoryPatchOperation.DELETE_SECTION,null,"忘记我的职业")));
        assertTrue(store.documents(1).get(0).sections().get(0).deleted());
        assertFalse(store.documents(1).get(0).content().contains("工程师"));
        assertTrue(store.documents(123).isEmpty());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM memory_document_section",Integer.class));
    }
    @Test void invalidSecondDocumentRollsBackFirstDocumentAndTask() {
        completed("我是工程师"); Task task = store.claim().orElseThrow();
        var invalid = new MemoryPatch(MemoryType.PREFERENCE,UUID.randomUUID(),"偏好",MemoryPatchOperation.UPSERT_SECTION,"简洁","我是工程师");
        assertThrows(IllegalArgumentException.class,()->store.applyPatches(task,List.of(patch(null,"职业","用户是工程师。"),invalid)));
        assertTrue(store.documents(1).isEmpty());
        assertEquals("RUNNING",db.queryForObject("SELECT status FROM memory_extraction_task WHERE id=?",String.class,task.id()));
    }

    @Test void staleLeaseCannotWriteAndSameValueAdvancesSectionBarrier() {
        completed("我是工程师"); Task old=store.claim().orElseThrow();
        db.update("UPDATE memory_extraction_task SET lease_until=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),old.id());
        Task fresh=store.claim().orElseThrow();
        store.applyPatches(old,List.of(patch(null,"职业","用户是工程师。")));
        assertTrue(store.documents(1).isEmpty());
        store.applyPatches(fresh,List.of(patch(null,"职业","用户是工程师。")));
        var doc=store.documents(1).get(0); var id=doc.sections().get(0).id();
        var updated=db.queryForObject("SELECT updated_at FROM memory_document WHERE id=?",java.sql.Timestamp.class,doc.id());
        Turn next=completed("我是工程师");
        store.applyPatches(store.claim().orElseThrow(),List.of(patch(id,"职业","用户是工程师。")));
        assertEquals(updated,db.queryForObject("SELECT updated_at FROM memory_document WHERE id=?",java.sql.Timestamp.class,doc.id()));
        assertEquals(next.id(),store.documents(1).get(0).sections().get(0).lastAppliedTurnSeq());
    }
    @Test void exhaustedEarlierTaskUnblocksNextAndEmptyPatchCreatesNoDocument() {
        completed("我是工程师"); Task first=store.claim().orElseThrow();
        Turn next=completed("我是工程师");
        db.update("UPDATE memory_extraction_task SET attempts=?,lease_until=? WHERE id=?",config.getMaxAttempts(),java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),first.id());
        Task task=store.claim().orElseThrow(); assertEquals(next.id(),task.turnId());
        store.applyPatches(task,List.of()); assertTrue(store.documents(1).isEmpty());
        assertEquals("FAILED",db.queryForObject("SELECT status FROM memory_extraction_task WHERE id=?",String.class,first.id()));
    }

    @Test void staleSectionDoesNotPreventUpdatingAnotherSection() {
        completed("我是工程师"); store.applyPatches(store.claim().orElseThrow(),List.of(patch(null,"A","原始 A"),patch(null,"B","原始 B")));
        var sections=store.documents(1).get(0).sections(); UUID a=sections.get(0).id(),b=sections.get(1).id();
        db.update("UPDATE memory_document_section SET last_applied_turn_seq=? WHERE id=?",Long.MAX_VALUE,a.toString());
        completed("我是工程师"); store.applyPatches(store.claim().orElseThrow(),List.of(patch(a,"A","过期 A"),patch(b,"B","新 B")));
        String content=store.documents(1).get(0).content();
        assertTrue(content.contains("原始 A")); assertFalse(content.contains("过期 A")); assertTrue(content.contains("新 B"));
    }
    @Test void secondDocumentSizeFailureRollsBackAlreadyWrittenFirstDocument() {
        config.setMaxDocumentBytes(100);
        completed("我是工程师"); Task task=store.claim().orElseThrow();
        var large=new MemoryPatch(MemoryType.PREFERENCE,null,"偏好",MemoryPatchOperation.UPSERT_SECTION,"X".repeat(200),"我是工程师");
        assertThrows(IllegalArgumentException.class,()->store.applyPatches(task,List.of(patch(null,"职业","工程师"),large)));
        assertTrue(store.documents(1).isEmpty());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM memory_document_section",Integer.class));
        assertEquals("RUNNING",db.queryForObject("SELECT status FROM memory_extraction_task WHERE id=?",String.class,task.id()));
    }
    @Test void freshNullIdentityDoesNotReuseDeletedHeading() {
        completed("我是工程师"); store.applyPatches(store.claim().orElseThrow(),List.of(patch(null,"职业","工程师")));
        UUID original=store.documents(1).get(0).sections().get(0).id();
        completed("忘记我的职业"); store.applyPatches(store.claim().orElseThrow(),List.of(new MemoryPatch(MemoryType.PROFILE,original,null,MemoryPatchOperation.DELETE_SECTION,null,"忘记我的职业")));
        completed("我是工程师"); store.applyPatches(store.claim().orElseThrow(),List.of(patch(null,"职业","工程师")));
        var sections=store.documents(1).get(0).sections(); assertEquals(2,sections.size());
        assertTrue(sections.stream().anyMatch(s -> s.id().equals(original) && s.deleted()));
        assertTrue(sections.stream().anyMatch(s -> !s.id().equals(original) && !s.deleted()));
    }
}
