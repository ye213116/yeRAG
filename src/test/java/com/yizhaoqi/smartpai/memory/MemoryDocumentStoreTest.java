package com.yizhaoqi.smartpai.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.yizhaoqi.smartpai.memory.MemoryTypes.*;

class MemoryDocumentStoreTest {
    JdbcTemplate db; MemoryStore store; MemoryProperties config; Turn latest;
    Turn completed(String question) {
        latest=store.begin(1,null,UUID.randomUUID().toString(),question).turn();
        assertTrue(store.complete(latest,"answer")); return latest;
    }
    @BeforeEach void documentsSchema() throws Exception {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:memory"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(ds);
        try(var c=ds.getConnection()) { ScriptUtils.executeSqlScript(c,new FileSystemResource("docs/databases/ddl.sql")); }
        db.update("INSERT INTO users(id,username,password,role) VALUES(1,'test-a','x','USER'),(123,'test-b','x','USER')");
        config=new MemoryProperties();store=new MemoryStore(db,new DataSourceTransactionManager(ds),config);
    }
    MemoryPatch patch(UUID id,String heading,String body) { return patch(MemoryType.PROFILE,id,heading,body); }
    MemoryPatch patch(MemoryType type,UUID id,String heading,String body) {
        var old=store.document(1,type).sections().stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
        return new MemoryPatch(type,id,heading,MemoryPatchOperation.UPSERT_SECTION,body,"用户相关背景和使用范围。",
                old==null ? MemoryDecision.CREATE : old.deleted() ? MemoryDecision.RESTORE : MemoryDecision.REFINE,
                "用户明确提供或修正",old==null ? 0:old.sectionVersion(),List.of(new MemorySource(latest.id(),latest.question())));
    }
    MemoryPatch delete(UUID id) {
        var old=store.document(1,MemoryType.PROFILE).sections().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
        return new MemoryPatch(MemoryType.PROFILE,id,old.heading(),MemoryPatchOperation.DELETE_SECTION,null,old.description(),MemoryDecision.DELETE,
                "用户明确要求忘记",old.sectionVersion(),List.of(new MemorySource(latest.id(),latest.question())));
    }
    @Test void deletedSourceRevokesValidInFlightExtraction() {
        Turn turn=completed("我是工程师");Task task=store.claim().orElseThrow();var patch=patch(null,"职业","工程师");
        store.deleteSession(1,turn.sessionId());store.applyPatches(task,List.of(patch));
        assertTrue(store.documents(1).isEmpty());assertTrue(store.claim().isEmpty());
    }
    @Test void startupRequiresPersistentHistoryMigration() {
        assertDoesNotThrow(store::verifySchema);
        db.execute("ALTER TABLE chat_turn MODIFY content_expires_at DATETIME(6) NOT NULL");
        assertThrows(IllegalStateException.class,store::verifySchema);
    }
    @Test void orderedClaimAllowsOtherUsersButBlocksLaterUserTasks() {
        completed("我是工程师");Task first=store.claim().orElseThrow();completed("我是工程师");assertTrue(store.claim().isEmpty());
        var other=store.begin(123,null,"other-user","我是工程师").turn();store.complete(other,"ok");
        assertEquals(other.id(),store.claim().orElseThrow().turnId());store.applyPatches(first,List.of());assertTrue(store.claim().isPresent());
    }
    @Test void versionsDescriptionsSourcesAndStableIdentityCommitTogether() {
        completed("我是工程师");store.applyPatches(store.claim().orElseThrow(),List.of(patch(null,"职业","工程师")));
        var original=store.document(1,MemoryType.PROFILE);UUID id=original.sections().get(0).id();
        assertEquals(1,original.version());assertEquals(1,original.sections().get(0).sectionVersion());
        completed("我现在主要做Java开发");store.applyPatches(store.claim().orElseThrow(),List.of(patch(id,"背景","Java工程师")));
        var current=store.document(1,MemoryType.PROFILE);assertEquals(2,current.version());assertEquals(id,current.sections().get(0).id());
        assertEquals(2,current.sections().get(0).sectionVersion());assertEquals(2,store.versions(1,MemoryType.PROFILE,Long.MAX_VALUE).size());
        assertTrue(store.version(1,MemoryType.PROFILE,1).content().contains("工程师"));
        assertTrue(store.version(1,MemoryType.PROFILE,2).patches().contains("我现在主要做Java开发"));
        assertTrue(store.provenance(1,MemoryType.PROFILE,id).containsKey("sources"));
        assertTrue(store.documents(123).isEmpty());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->store.version(123,MemoryType.PROFILE,1));
    }
    @Test void invalidSecondDocumentRollsBackFirstAndTaskAndSnapshots() {
        config.setMaxDocumentBytes(100);completed("我是工程师");Task task=store.claim().orElseThrow();
        var a=patch(null,"职业","工程师");var b=patch(MemoryType.PREFERENCE,null,"偏好","X".repeat(200));
        assertThrows(IllegalArgumentException.class,()->store.applyPatches(task,List.of(a,b)));
        assertTrue(store.documents(1).isEmpty());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM memory_document_version",Integer.class));
        assertEquals("RUNNING",db.queryForObject("SELECT status FROM memory_extraction_task WHERE id=?",String.class,task.id()));
    }
    @Test void staleLeaseCannotWrite() {
        completed("我是工程师");Task old=store.claim().orElseThrow();
        db.update("UPDATE memory_extraction_task SET lease_until=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),old.id());
        Task fresh=store.claim().orElseThrow();store.applyPatches(old,List.of(patch(null,"职业","工程师")));assertTrue(store.documents(1).isEmpty());
        store.applyPatches(fresh,List.of(patch(null,"职业","工程师")));assertEquals(1,store.document(1,MemoryType.PROFILE).version());
    }
    @Test void staleVersionRejectsWholeBatchInsteadOfOverwriting() {
        completed("我是工程师");store.applyPatches(store.claim().orElseThrow(),List.of(patch(null,"职业","工程师")));
        UUID id=store.document(1,MemoryType.PROFILE).sections().get(0).id();
        completed("我主要用Java");Task task=store.claim().orElseThrow();var stale=patch(id,"职业","旧草稿");
        db.update("UPDATE memory_document_section SET section_version=section_version+1 WHERE id=?",id.toString());
        assertThrows(MemoryVersionConflict.class,()->store.applyPatches(task,List.of(stale)));
        assertFalse(store.document(1,MemoryType.PROFILE).content().contains("旧草稿"));assertEquals(1,store.versions(1,MemoryType.PROFILE,Long.MAX_VALUE).size());
    }
    @Test void deletingAndRollbackNeverMoveVersionsOrBarriersBackward() {
        completed("我是工程师");store.applyPatches(store.claim().orElseThrow(),List.of(patch(null,"职业","工程师")));
        UUID id=store.document(1,MemoryType.PROFILE).sections().get(0).id();
        completed("请忘记我的职业");store.applyPatches(store.claim().orElseThrow(),List.of(delete(id)));
        assertTrue(store.document(1,MemoryType.PROFILE).sections().get(0).deleted());
        completed("我主要用Java");Task waiting=store.claim().orElseThrow();var oldPatch=patch(id,"职业","旧任务恢复");
        assertEquals(3,store.rollback(1,MemoryType.PROFILE,1,2,"用户主动恢复第一个版本"));
        var restored=store.document(1,MemoryType.PROFILE);assertEquals(3,restored.sections().get(0).sectionVersion());
        long barrier=restored.sections().get(0).lastAppliedTurnSeq();assertTrue(barrier>=latest.id());
        store.applyPatches(waiting,List.of(oldPatch));
        assertFalse(store.document(1,MemoryType.PROFILE).content().contains("旧任务恢复"));
        assertEquals(3,store.document(1,MemoryType.PROFILE).version());
    }
    @Test void exhaustedTaskUnblocksNextAndEmptyPatchCreatesNoVersion() {
        completed("我是工程师");Task first=store.claim().orElseThrow();Turn next=completed("我是工程师");
        db.update("UPDATE memory_extraction_task SET attempts=?,lease_until=? WHERE id=?",config.getMaxAttempts(),java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),first.id());
        Task task=store.claim().orElseThrow();assertEquals(next.id(),task.turnId());store.applyPatches(task,List.of());
        assertTrue(store.documents(1).isEmpty());assertEquals("FAILED",db.queryForObject("SELECT status FROM memory_extraction_task WHERE id=?",String.class,first.id()));
    }
    @Test void oldSectionBarrierCannotRestoreDeletedContent() {
        completed("我是工程师");store.applyPatches(store.claim().orElseThrow(),List.of(patch(null,"职业","工程师")));
        UUID id=store.document(1,MemoryType.PROFILE).sections().get(0).id();
        completed("请忘记我的职业");Task deletion=store.claim().orElseThrow();store.applyPatches(deletion,List.of(delete(id)));
        completed("我是工程师");Task task=store.claim().orElseThrow();var candidate=patch(id,"职业","过时恢复");
        db.update("UPDATE memory_document_section SET last_applied_turn_seq=? WHERE id=?",Long.MAX_VALUE,id.toString());
        store.applyPatches(task,List.of(candidate));assertTrue(store.document(1,MemoryType.PROFILE).sections().get(0).deleted());
    }
    @Test void sourceCannotPointOutsideProvidedRecentContextOrAnotherUser() {
        completed("我是工程师");Task task=store.claim().orElseThrow();var p=patch(null,"职业","工程师");
        var forged=new MemoryPatch(p.target(),p.sectionId(),p.heading(),p.operation(),p.content(),p.description(),p.decision(),p.reason(),p.expectedSectionVersion(),List.of(new MemorySource(999,"我是工程师")));
        assertThrows(IllegalArgumentException.class,()->store.applyPatches(task,List.of(forged)));assertTrue(store.documents(1).isEmpty());
    }
}
