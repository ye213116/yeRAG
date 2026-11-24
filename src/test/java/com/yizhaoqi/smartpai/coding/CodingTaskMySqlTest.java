package com.yizhaoqi.smartpai.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.SourceMode;
import com.yizhaoqi.smartpai.agent.profile.WorkspacePermission;
import com.yizhaoqi.smartpai.coding.CodingTypes.*;
import com.yizhaoqi.smartpai.coding.task.CodingTaskStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: creates and drops a random schema, with JDBC in Shanghai and the database clock in UTC. */
@EnabledIfEnvironmentVariable(named="CODING_MYSQL_TEST_URL",matches=".+")
class CodingTaskMySqlTest {
    JdbcTemplate admin,db;
    CodingTaskStore store;
    String schema,taskId;
    @BeforeEach void setup() throws Exception {
        String url=System.getenv("CODING_MYSQL_TEST_URL"),user=System.getenv("CODING_MYSQL_TEST_USER"),password=System.getenv("CODING_MYSQL_TEST_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        schema="coding_test_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        String base=url.substring(0,url.indexOf('/',"jdbc:mysql://".length()));
        var ds=new DriverManagerDataSource(base+"/"+schema+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&sessionVariables=time_zone=%27%2B00:00%27",user,password);
        db=new JdbcTemplate(ds);
        try(var c=ds.getConnection()) { ScriptUtils.executeSqlScript(c,new FileSystemResource("docs/databases/ddl.sql")); }
        db.update("INSERT INTO users(id,username,password,role) VALUES(1,'coding-test','x','USER')");
        store=new CodingTaskStore(db,new ObjectMapper(),new DataSourceTransactionManager(ds));
        var workspace=store.createWorkspace(1,"demo","main","a".repeat(40),Set.of(WorkspacePermission.READ));
        taskId=UUID.randomUUID().toString();
        store.transaction(() -> {
            store.enqueue(new Task(taskId,1,workspace.id(),UUID.randomUUID().toString(),"request","b".repeat(64),"test",SourceMode.AUTO,
                    Status.QUEUED,workspace.permissions(),List.of(),null,null,null,"NONE",null));return null;
        });
        store.claim().orElseThrow();
    }
    @AfterEach void cleanup() {
        if(admin!=null && schema!=null) admin.execute("DROP DATABASE "+schema);
    }
    @Test void freshHeartbeatSurvivesDifferentJdbcAndDatabaseTimezonesButStaleHeartbeatExpires() {
        store.started(taskId,"run");store.heartbeat(taskId);
        assertTrue(store.interruptStale().isEmpty(),"Fresh task must not be stopped by a timezone offset");
        db.update("UPDATE coding_task SET heartbeat_at=TIMESTAMPADD(SECOND,-61,CURRENT_TIMESTAMP) WHERE id=?",taskId);
        assertEquals(List.of(taskId),store.interruptStale());
    }
    @Test void publishingUsesDatabaseClockForBothFreshAndExpiredAttempts() {
        store.finish(taskId,Status.REVIEW_READY,"done",null);
        assertTrue(store.beginPublish(1,taskId));
        store.recoverPublishing();
        assertEquals("PUBLISHING",store.internal(taskId).publishState());
        db.update("UPDATE coding_task SET heartbeat_at=TIMESTAMPADD(SECOND,-301,CURRENT_TIMESTAMP) WHERE id=?",taskId);
        store.recoverPublishing();
        assertEquals("FAILED",store.internal(taskId).publishState());
    }
}
