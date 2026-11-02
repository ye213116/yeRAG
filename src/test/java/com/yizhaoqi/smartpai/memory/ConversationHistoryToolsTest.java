package com.yizhaoqi.smartpai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.tool.*;
import com.yizhaoqi.smartpai.rag.RagProperties;
import com.yizhaoqi.smartpai.rag.TokenBudget;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationHistoryToolsTest {
    MemoryStore store;
    ConversationHistorySearchTool search;
    ConversationTurnsReadTool read;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    MemoryProperties config=new MemoryProperties();
    ToolExecutionContext context;
    long first;
    String original="Lua 第二个条件: count >= 17 && enabled == true；🙂\n".repeat(180);
    @BeforeEach void setup() throws Exception {
        var source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:history_tools_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var db=new JdbcTemplate(source);
        try(var connection=source.getConnection()) { ScriptUtils.executeSqlScript(connection,new FileSystemResource("docs/databases/ddl.sql")); }
        db.update("INSERT INTO users(id,username,password,role) VALUES(1,'alice','x','USER'),(2,'bob','x','USER')");
        store=new MemoryStore(db,new DataSourceTransactionManager(source),config);
        search=new ConversationHistorySearchTool(store,json,config,new RagProperties());
        read=new ConversationTurnsReadTool(store,json,config,new RagProperties());
        var turn=store.begin(1,null,"first",original).turn(); first=turn.id(); store.complete(turn,"历史助手猜测，未核实。");
        context=new ToolExecutionContext("1",0,"call",turn.sessionId());
    }
    @Test void findsLiteralKeywordsWithBoundedOutputAndNoCrossConversationAccess() throws Exception {
        var result=search.execute("{\"query\":\"count >= 17\"}",context);
        assertTrue(result.success());
        var body=json.readTree(result.content());
        assertEquals(first,body.path("results").get(0).path("turnId").asLong());
        assertTrue(body.path("results").get(0).path("untrusted_content").asText().contains("count >= 17"));
        assertTrue(TokenBudget.count(result.content())<=config.getHistoryToolTokens());
        assertEquals(0,json.readTree(search.execute("{\"query\":\"%\"}",context).content()).path("results").size());
        assertFalse(search.execute("{\"query\":\"Lua\"}",new ToolExecutionContext("2",0,null,context.conversationId())).success());
        String other=store.current(1,true);
        assertFalse(read.execute("{\"turnId\":"+first+"}",new ToolExecutionContext("1",0,null,other)).success());
    }
    @Test void continuationRecoversEveryCodePointOfLongOriginal() throws Exception {
        Map<String,Object> args=new LinkedHashMap<>(); args.put("turnId",first);
        StringBuilder question=new StringBuilder(); StringBuilder answer=new StringBuilder();
        int pages=0;
        while (true) {
            assertTrue(++pages<100,"continuation must make progress");
            var result=read.execute(json.writeValueAsString(args),context);
            assertTrue(result.success(),result.content());
            assertTrue(TokenBudget.count(result.content())<=config.getHistoryToolTokens());
            var body=json.readTree(result.content());
            for (var item:body.path("results")) {
                if (item.path("role").asText().equals("user")) question.append(item.path("untrusted_content").asText());
                else answer.append(item.path("untrusted_content").asText());
            }
            if (body.path("next").isNull()) break;
            args=json.convertValue(body.path("next"),new com.fasterxml.jackson.core.type.TypeReference<>() {});
        }
        assertTrue(pages>1);
        assertEquals(original,question.toString());
        assertEquals("历史助手猜测，未核实。",answer.toString());
    }
    @Test void strictShapeIdentityAndDeletionAreEnforced() {
        for (String invalid:List.of("[]","{\"query\":\"Lua\",\"userId\":2}","{\"query\":\"a\",\"query\":\"b\"}","{\"query\":\"x\"} {}","{\"query\":5}"))
            assertFalse(search.execute(invalid,context).success(),invalid);
        assertFalse(read.execute("{\"turnId\":1.1}",context).success());
        assertFalse(search.execute("{\"query\":\"Lua\"}",new ToolExecutionContext("1")).success());
        store.deleteSession(1,context.conversationId());
        assertFalse(search.execute("{\"query\":\"Lua\"}",context).success());
        assertFalse(read.execute("{\"turnId\":"+first+"}",context).success());
    }
    @Test void cursorSearchDoesNotStopAtSqlPageOrSkipResults() throws Exception {
        for (int i=0;i<23;i++) {
            var turn=store.begin(1,context.conversationId(),"more-"+i,"needle "+i).turn(); store.complete(turn,"saved");
        }
        Set<Long> ids=new HashSet<>(); long before=Long.MAX_VALUE;
        while(true) {
            var result=search.execute("{\"query\":\"needle\",\"limit\":3,\"before\":"+before+"}",context);
            assertTrue(result.success()); var body=json.readTree(result.content());
            for(var item:body.path("results")) assertTrue(ids.add(item.path("turnId").asLong()));
            if(!body.path("hasMore").asBoolean()) break;
            long next=body.path("nextBefore").asLong(); assertTrue(next<before); before=next;
        }
        assertEquals(23,ids.size());
    }
}
