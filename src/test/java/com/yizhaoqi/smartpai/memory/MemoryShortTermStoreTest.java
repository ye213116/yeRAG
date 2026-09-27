package com.yizhaoqi.smartpai.memory;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MemoryShortTermStoreTest {
    private JdbcTemplate db;
    private MemoryStore store;

    @BeforeEach void setup() throws Exception {
        JdbcDataSource source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:short_term_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(source);
        try (var connection=source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new FileSystemResource("docs/databases/ddl.sql"));
        }
        db.update("INSERT INTO users(id,username,password,role) VALUES(1,'alice','x','USER'),(2,'bob','x','USER')");
        store=new MemoryStore(db,new DataSourceTransactionManager(source),new MemoryProperties());
    }

    @Test void summarySurvivesNewStoreAndRejectsStaleWrites() {
        var turn=store.begin(1,null,"r1","question").turn();
        assertTrue(store.complete(turn,"answer"));
        assertTrue(store.saveSummary(1,turn.sessionId(),0,"durable summary",turn.id()));
        assertFalse(store.saveSummary(1,turn.sessionId(),0,"stale summary",turn.id()+1));
        assertEquals("durable summary",store.session(1,turn.sessionId()).get("context_summary"));
        assertEquals(1L,((Number)store.session(1,turn.sessionId()).get("summary_revision")).longValue());
        assertThrows(ResponseStatusException.class,()->store.session(2,turn.sessionId()));
    }
    @Test void legacySummaryImportRequiresOwnershipAndCannotOverwriteNewSummary() {
        var turn=store.begin(1,null,"r1","question").turn(); store.complete(turn,"answer");
        assertFalse(store.importSummary(2,turn.sessionId(),"foreign",turn.id()));
        assertTrue(store.importSummary(1,turn.sessionId(),"legacy",turn.id()));
        assertFalse(store.importSummary(1,turn.sessionId(),"stale",turn.id()));
        assertEquals("legacy",store.session(1,turn.sessionId()).get("context_summary"));
    }

    @Test void completedAnswerRenewsRetentionAndExpiryClearsSummary() {
        var turn=store.begin(1,null,"r1","question").turn();
        db.update("UPDATE chat_turn SET content_expires_at=? WHERE id=?",Timestamp.from(Instant.now().plusSeconds(1)),turn.id());
        assertTrue(store.complete(turn,"answer"));
        Instant retained=MemoryStore.instant(db.queryForObject("SELECT content_expires_at FROM chat_turn WHERE id=?",Object.class,turn.id()));
        assertTrue(retained.isAfter(Instant.now().plusSeconds(6*86400)));
        store.saveSummary(1,turn.sessionId(),0,"summary",turn.id());
        db.update("UPDATE chat_session SET context_expires_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),turn.sessionId());
        assertFalse(store.saveSummary(1,turn.sessionId(),1,"later",turn.id()+1));
        store.maintain();
        assertNull(store.session(1,turn.sessionId()).get("context_summary"));
    }
    @Test void expiredIdempotentResultCannotReplayAsACompletedAnswer() {
        var turn=store.begin(1,null,"r1","question").turn(); store.complete(turn,"answer");
        db.update("UPDATE chat_turn SET content_expires_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),turn.id());
        assertThrows(ResponseStatusException.class,()->store.begin(1,turn.sessionId(),"r1","question"));
    }

    @Test void legacyImportIsIdempotentAndNeverReplacesAnOpenSession() {
        String id=UUID.randomUUID().toString();
        Instant now=Instant.now();
        List<MemoryStore.LegacyTurn> turns=List.of(new MemoryStore.LegacyTurn("old question","old answer",now));
        assertTrue(store.importLegacySession(1,id,turns,now.plusSeconds(3600)));
        assertFalse(store.importLegacySession(1,id,turns,now.plusSeconds(3600)));
        assertEquals(1,store.history(1,id).size());
        assertThrows(ResponseStatusException.class,()->store.history(2,id));
        String second=UUID.randomUUID().toString();
        assertTrue(store.importLegacySession(1,second,turns,now.plusSeconds(3600)));
        assertEquals("CLOSED",store.session(1,second).get("status"));
        assertEquals(id,store.current(1,false));
    }
    @Test void uncoveredHistoryStartsAtTheSummaryBoundary() {
        var first=store.begin(1,null,"r1","one").turn(); store.complete(first,"a1");
        var second=store.begin(1,first.sessionId(),"r2","two").turn(); store.complete(second,"a2");
        var third=store.begin(1,first.sessionId(),"r3","three").turn(); store.complete(third,"a3");
        assertEquals(List.of(second.id(),third.id()),store.uncoveredHistory(1,first.sessionId(),first.id())
                .stream().map(MemoryTypes.Turn::id).toList());
        assertTrue(store.hasContextGap(1,first.sessionId(),first.id(),third.id()));
        assertFalse(store.hasContextGap(1,first.sessionId(),second.id(),third.id()));
    }
}
