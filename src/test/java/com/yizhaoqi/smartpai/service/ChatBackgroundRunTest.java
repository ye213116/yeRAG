package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.*;
import com.yizhaoqi.smartpai.config.*;
import com.yizhaoqi.smartpai.memory.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.socket.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Sinks;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatBackgroundRunTest {
    MemoryStore store;ChatHandler handler;KnowledgeAgentService agent;
    Map<String,Sinks.One<AgentRunResult>> results=new ConcurrentHashMap<>();
    Map<String,AgentEventListener> listeners=new ConcurrentHashMap<>();
    List<String> frames=new CopyOnWriteArrayList<>();
    @BeforeEach void setup() throws Exception {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:background_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var db=new JdbcTemplate(ds);try(var c=ds.getConnection()) {ScriptUtils.executeSqlScript(c,new FileSystemResource("docs/databases/ddl.sql"));}
        db.update("INSERT INTO users(id,username,password,role) VALUES(1,'alice','x','USER'),(2,'bob','x','USER')");
        store=new MemoryStore(db,new DataSourceTransactionManager(ds),new MemoryProperties());
        agent=mock(KnowledgeAgentService.class);var context=mock(MemoryContextService.class);
        when(context.build(any())).thenReturn(new MemoryTypes.Context(List.of(),false));
        when(agent.run(anyString(),anyString(),anyList(),any(SourceMode.class),anyString(),any())).thenAnswer(call -> {
            String question=call.getArgument(1);var sink=Sinks.<AgentRunResult>one();results.put(question,sink);listeners.put(question,call.getArgument(5));return sink.asMono();
        });
        handler=new ChatHandler(agent,mock(RagFallbackService.class),new AiProperties(),new ObjectMapper(),store,context);
    }
    WebSocketSession socket(String id) throws Exception {
        var socket=mock(WebSocketSession.class);when(socket.getId()).thenReturn(id);when(socket.isOpen()).thenReturn(true);
        doAnswer(call -> {frames.add(((TextMessage)call.getArgument(0)).getPayload());return null;}).when(socket).sendMessage(any());return socket;
    }
    void await(BooleanSupplier test) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!test.getAsBoolean() && System.nanoTime()<end) Thread.sleep(10);
        assertTrue(test.getAsBoolean());
    }
    @AfterEach void stop() {handler.cancelSession("old");handler.cancelSession("new");}
    @Test void detachedSocketDoesNotCancelAndNewSubscriberRecoversOrderedSnapshot() throws Exception {
        String a=store.current(1,false);var old=socket("old");handler.processMessage("1","A",a,"a",old);
        await(() -> listeners.containsKey("A"));listeners.get("A").onChunk("first ");
        long seq=((Number)handler.snapshot(1,a).get("seq")).longValue();
        handler.detachSession("1","old");listeners.get("A").onChunk("second");
        assertEquals("first second",handler.snapshot(1,a).get("text"));
        assertTrue(((Number)handler.snapshot(1,a).get("seq")).longValue()>seq);
        assertThrows(ResponseStatusException.class,() -> handler.snapshot(2,a));
        handler.attachSession("1",socket("new"));results.get("A").tryEmitValue(new AgentRunResult("full answer",Map.of()));
        await(() -> store.history(1,a).size()==1);
        assertEquals("full answer",store.history(1,a).get(0).answer());
        await(() -> frames.stream().anyMatch(f -> f.contains("completion") && f.contains("finished")));
    }
    @Test void parallelConversationsRemainIsolatedAndStoppingOneDoesNotStopTheOther() throws Exception {
        String a=store.current(1,false);var socket=socket("old");handler.processMessage("1","A",a,"a",socket);
        await(() -> listeners.containsKey("A"));String b=store.current(1,true);
        handler.processMessage("1","B",b,"b",socket);await(() -> listeners.containsKey("B"));
        listeners.get("A").onChunk("A draft");listeners.get("B").onChunk("B draft");
        assertEquals("A draft",handler.snapshot(1,a).get("text"));assertEquals("B draft",handler.snapshot(1,b).get("text"));
        handler.stop(1,a,"a");results.get("A").tryEmitValue(new AgentRunResult("late",Map.of()));
        results.get("B").tryEmitValue(new AgentRunResult("B full",Map.of()));
        await(() -> store.history(1,b).size()==1);
        assertTrue(store.history(1,a).isEmpty());assertEquals("CANCELLED",store.latestRun(1,a).get("state"));
        assertEquals("B full",store.history(1,b).get(0).answer());
    }
    @Test void admissionRejectsExtraRunAndDeletedConversationCannotBeResurrected() throws Exception {
        var limits=new ChatRuntimeProperties();limits.setMaxConcurrentRunsPerUser(1);handler.configureRuntime(limits);
        String a=store.current(1,false);var socket=socket("old");handler.processMessage("1","A",a,"a",socket);await(() -> listeners.containsKey("A"));
        String b=store.current(1,true);handler.processMessage("1","B",b,"b",socket);
        assertTrue(frames.stream().anyMatch(f -> f.contains("并发上限")));assertFalse(listeners.containsKey("B"));
        store.deleteSession(1,a);handler.cancelClosedMemoryRuns(1);results.get("A").tryEmitValue(new AgentRunResult("late",Map.of()));
        assertThrows(ResponseStatusException.class,() -> handler.snapshot(1,a));
    }
}
