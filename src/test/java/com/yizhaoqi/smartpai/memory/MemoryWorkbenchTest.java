package com.yizhaoqi.smartpai.memory;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryWorkbenchTest {
    JdbcTemplate db;MemoryStore store;
    @BeforeEach void setup() throws Exception {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:workbench_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(ds);try(var c=ds.getConnection()) {ScriptUtils.executeSqlScript(c,new FileSystemResource("docs/databases/ddl.sql"));}
        db.update("INSERT INTO users(id,username,password,role) VALUES(1,'alice','x','USER'),(2,'bob','x','USER')");
        store=new MemoryStore(db,new DataSourceTransactionManager(ds),new MemoryProperties());
    }
    @Test void switchingAndCreatingAnotherConversationPreservesBothRunsAndCompletionOwnership() {
        var a=store.begin(1,null,"a","A question").turn();
        String b=store.current(1,true);var second=store.begin(1,b,"b","B question").turn();
        store.resume(1,a.sessionId());
        assertTrue(store.running(a));assertTrue(store.running(second));
        assertTrue(store.complete(second,"B answer"));assertTrue(store.complete(a,"A answer"));
        assertEquals("A answer",store.history(1,a.sessionId()).get(0).answer());
        assertEquals("B answer",store.history(1,b).get(0).answer());
        assertEquals(a.sessionId(),store.current(1,false));
    }
    @Test void titleSearchRenameAndCodingSessionsAreOwnerScopedAndDoNotChangeWorkFocus() {
        var a=store.begin(1,null,"a","第一条问题").turn();store.complete(a,"answer");
        store.renameSession(1,a.sessionId(),"我的会话");
        var b=store.begin(1,a.sessionId(),"b","新问题").turn();store.complete(b,"answer");
        String coding=store.current(1,true,"CODING");store.begin(1,coding,"c","Coding task");
        assertEquals(a.sessionId(),store.current(1,false));
        assertEquals(1,store.sessions(1,"WORK","我的",0,50).size());
        assertEquals("我的会话",store.sessions(1,"WORK","",0,50).get(0).get("title"));
        assertTrue(store.sessions(2,"WORK","",0,50).isEmpty());
        assertThrows(ResponseStatusException.class,() -> store.renameSession(2,a.sessionId(),"foreign"));
        assertThrows(ResponseStatusException.class,() -> store.requireWorkSession(1,coding));
    }
    @Test void extractionWaitsForAnEarlierRunningTurnAndCancellationUnblocksIt() {
        var a=store.begin(1,null,"a","first").turn();
        var b=store.begin(1,store.current(1,true),"b","second").turn();store.complete(b,"answer");
        assertTrue(store.claim().isEmpty());
        store.complete(a,"answer");var first=store.claim().orElseThrow();assertEquals(a.id(),first.turnId());
        assertTrue(store.claim().isEmpty());store.applyPatches(first,List.of());
        assertEquals(b.id(),store.claim().orElseThrow().turnId());
    }
    @Test void restartMarksOnlyWorkRunsInterruptedAndDeletedSessionsNeverExposeSnapshots() {
        var work=store.begin(1,null,"a","work").turn();
        var coding=store.begin(1,store.current(1,true,"CODING"),"c","coding").turn();
        store.recoverWorkRuns();
        assertEquals("FAILED",store.latestRun(1,work.sessionId()).get("state"));
        assertEquals("SERVER_RESTARTED",store.latestRun(1,work.sessionId()).get("failureCode"));
        assertTrue(store.running(coding));assertFalse(store.complete(work,"late"));
        store.deleteSession(1,work.sessionId());
        assertThrows(ResponseStatusException.class,() -> store.latestRun(1,work.sessionId()));
    }
}
