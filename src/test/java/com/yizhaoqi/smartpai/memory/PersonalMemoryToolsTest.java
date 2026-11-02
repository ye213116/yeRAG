package com.yizhaoqi.smartpai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.SourceMode;
import com.yizhaoqi.smartpai.agent.tool.*;
import com.yizhaoqi.smartpai.rag.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.util.*;
import static com.yizhaoqi.smartpai.memory.MemoryTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class PersonalMemoryToolsTest {
    MemoryStore store;MemoryProperties config=new MemoryProperties();ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    MemoryReadTool read;MemoryListTool list;ToolExecutionContext owner=new ToolExecutionContext("1");UUID first,second;
    String body="先讲业务，然后解释Java实现。🙂\n".repeat(90);
    @BeforeEach void setup() throws Exception {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:personal_tools_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");var db=new JdbcTemplate(ds);
        try(var c=ds.getConnection()) {ScriptUtils.executeSqlScript(c,new FileSystemResource("docs/databases/ddl.sql"));}
        db.update("INSERT INTO users(id,username,password,role) VALUES(1,'alice','x','USER'),(2,'bob','x','USER')");store=new MemoryStore(db,new DataSourceTransactionManager(ds),config);
        var turn=store.begin(1,null,"r","以后解释先讲业务，代码用Java").turn();store.complete(turn,"ok");
        store.applyPatches(store.claim().orElseThrow(),List.of(
                new MemoryPatch(MemoryType.PREFERENCE,null,"解释偏好",MemoryPatchOperation.UPSERT_SECTION,body,"解释顺序与代码示例范围。",MemoryDecision.CREATE,"持续偏好",0L,List.of(new MemorySource(turn.id(),turn.question()))),
                new MemoryPatch(MemoryType.PREFERENCE,null,"学习背景",MemoryPatchOperation.UPSERT_SECTION,"只在需要时加载这个背景正文。","学习背景的适用范围。",MemoryDecision.CREATE,"用户背景",0L,List.of(new MemorySource(turn.id(),turn.question())))));
        var sections=store.document(1,MemoryType.PREFERENCE).sections();first=sections.stream().filter(s -> s.heading().equals("解释偏好")).findFirst().orElseThrow().id();second=sections.stream().filter(s -> s.heading().equals("学习背景")).findFirst().orElseThrow().id();
        read=new MemoryReadTool(store,json,config,new RagProperties());list=new MemoryListTool(store,json,config,new RagProperties());
    }
    @Test void defaultIndexAndListingDoNotIncludeAnyBody() throws Exception {
        var index=new MemoryIndexService(config,new RagProperties(),json).build(store.catalog(1));
        assertFalse(index.content().contains("然后解释Java实现"));assertFalse(index.content().contains("只在需要时加载"));
        assertTrue(TokenBudget.count(index.content())<=1024);
        var result=list.execute("{\"documentType\":\"PREFERENCE\"}",owner);assertTrue(result.success());
        assertFalse(result.content().contains("然后解释Java实现"));assertTrue(result.content().contains(first.toString()));
        assertTrue(json.readTree(result.content()).path("documentVersion").asLong()>0);
    }
    @Test void batchReadAndContinuationRecoverOnlyRequestedBodyWithProvenance() throws Exception {
        String arguments=json.writeValueAsString(Map.of("documentType","PREFERENCE","sectionIds",List.of(first)));
        StringBuilder recovered=new StringBuilder();int pages=0;
        while(true) {
            assertTrue(++pages<20);var result=read.execute(arguments,owner);assertTrue(result.success(),result.content());
            assertTrue(TokenBudget.count(result.content())<=2048);assertFalse(result.content().contains("只在需要时加载"));
            var output=json.readTree(result.content());
            for(var section:output.path("sections")) { recovered.append(section.path("untrusted_content").asText());assertTrue(section.path("provenance").has("sources")); }
            if(output.path("nextCursor").isNull()) break;
            arguments=json.writeValueAsString(Map.of("documentType","PREFERENCE","cursor",output.path("nextCursor").asText()));
        }
        assertEquals(body.strip(),recovered.toString());
        var batch=read.execute(json.writeValueAsString(Map.of("documentType","PREFERENCE","sectionIds",List.of(second,first))),owner);
        assertTrue(batch.success());assertTrue(batch.content().contains("只在需要时加载"));
    }
    @Test void identityUnknownArgumentsAndCrossDocumentReferencesAreRejected() {
        assertFalse(read.execute(jsonString(Map.of("documentType","PREFERENCE","sectionIds",List.of(first))),new ToolExecutionContext("2")).success());
        assertFalse(read.execute("{\"documentType\":\"PREFERENCE\",\"userId\":1}",owner).success());
        assertFalse(list.execute("{\"documentType\":\"PROJECT\"}",owner).success());
        assertFalse(read.execute("{\"documentType\":\"PREFERENCE\"}",null).success());
        for(var mode:SourceMode.values()) {assertTrue(mode.allows(read));assertTrue(mode.allows(list));}
    }
    @Test void cursorVersionChangesForceFreshRead() throws Exception {
        config.setReadTokens(512);
        var initial=read.execute(jsonString(Map.of("documentType","PREFERENCE","sectionIds",List.of(first))),owner);assertTrue(initial.success(),initial.content());
        String cursor=json.readTree(initial.content()).path("nextCursor").asText();assertFalse(cursor.isEmpty());
        var doc=store.document(1,MemoryType.PREFERENCE);store.rollback(1,MemoryType.PREFERENCE,1,doc.version(),"显式回滚用于版本隔离测试");
        var result=read.execute(jsonString(Map.of("documentType","PREFERENCE","cursor",cursor)),owner);
        assertFalse(result.success());assertTrue(result.content().contains("memory_version_changed"));
    }
    String jsonString(Object value) {try{return json.writeValueAsString(value);}catch(Exception invalid){throw new IllegalStateException(invalid);}}
}
