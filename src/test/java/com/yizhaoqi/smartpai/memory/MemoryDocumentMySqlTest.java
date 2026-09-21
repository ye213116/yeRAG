package com.yizhaoqi.smartpai.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real MySQL suite. Each test creates and drops only its own random schema. */
@EnabledIfEnvironmentVariable(named="MEMORY_MYSQL_TEST_URL", matches=".+")
class MemoryDocumentMySqlTest extends MemoryDocumentStoreTest {
    private JdbcTemplate admin;
    private String schema;
    @Override @BeforeEach void documentsSchema() throws Exception {
        String url=System.getenv("MEMORY_MYSQL_TEST_URL");
        String user=System.getenv("MEMORY_MYSQL_TEST_USER");
        String password=System.getenv("MEMORY_MYSQL_TEST_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        schema="memory_test_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        String base=url.substring(0,url.indexOf('/',"jdbc:mysql://".length()));
        var ds=new DriverManagerDataSource(base+"/"+schema+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone="+java.util.TimeZone.getDefault().getID(),user,password);
        db=new JdbcTemplate(ds);
        try(var c=ds.getConnection()) {
            ScriptUtils.executeSqlScript(c,new FileSystemResource("docs/databases/ddl.sql"));
        }
        db.update("INSERT INTO users(id,username,password,role) VALUES(1,'test-a','test','USER'),(123,'test-b','test','USER')");
        config=new MemoryProperties(); config.setEnabled(true);
        store=new MemoryStore(db,new DataSourceTransactionManager(ds),config);
    }
    @AfterEach void cleanupSchema() {
        if(admin!=null && schema!=null) admin.execute("DROP DATABASE "+schema);
    }
    @Test void twoConnectionsClaimDifferentUsersWhileSameUserWaits() throws Exception {
        completed("我是工程师"); completed("我是工程师");
        var other=store.begin(123,null,"other","我是工程师").turn(); store.complete(other,"answer");
        var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<MemoryTypes.Task> claim=()->{start.await();return store.claim().orElseThrow();};
            var first=pool.submit(claim);var second=pool.submit(claim);start.countDown();
            var a=first.get(10,TimeUnit.SECONDS);var b=second.get(10,TimeUnit.SECONDS);
            assertNotEquals(store.turn(a.turnId()).userId(),store.turn(b.turnId()).userId());
            assertTrue(store.claim().isEmpty());
        } finally { pool.shutdownNow(); }
    }
}
