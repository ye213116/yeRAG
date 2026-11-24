package com.yizhaoqi.smartpai.memory;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.util.UUID;

/** Runs the lifecycle contract against an isolated real MySQL schema. */
@EnabledIfEnvironmentVariable(named="WORKBENCH_MYSQL_TEST_URL",matches=".+")
class MemoryWorkbenchMySqlTest extends MemoryWorkbenchTest {
    JdbcTemplate admin;String schema;
    @Override @BeforeEach void setup() throws Exception {
        String url=System.getenv("WORKBENCH_MYSQL_TEST_URL"),user=System.getenv("WORKBENCH_MYSQL_TEST_USER"),password=System.getenv("WORKBENCH_MYSQL_TEST_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));schema="workbench_test_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        String base=url.substring(0,url.indexOf('/',"jdbc:mysql://".length()));
        var ds=new DriverManagerDataSource(base+"/"+schema+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",user,password);
        db=new JdbcTemplate(ds);try(var c=ds.getConnection()) {ScriptUtils.executeSqlScript(c,new FileSystemResource("docs/databases/ddl.sql"));}
        db.update("INSERT INTO users(id,username,password,role) VALUES(1,'alice','x','USER'),(2,'bob','x','USER')");
        store=new MemoryStore(db,new DataSourceTransactionManager(ds),new MemoryProperties());
    }
    @AfterEach void drop() {if(admin!=null && schema!=null) admin.execute("DROP DATABASE "+schema);}
}
