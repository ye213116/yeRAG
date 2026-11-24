package com.yizhaoqi.smartpai.agent;

import com.yizhaoqi.smartpai.support.PlanMySqlDatabase;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Incremental upgrade must preserve old data and match the actual parent-key collation. */
@EnabledIfEnvironmentVariable(named = "PLAN_MYSQL_TEST_URL", matches = ".+")
class PlanMigrationMySqlTest {
    @ParameterizedTest
    @ValueSource(strings = {"utf8mb4_unicode_ci", "utf8mb4_bin", "utf8mb4_0900_ai_ci"})
    void migrationMatchesParentCollationWithoutChangingExistingRows(String collation) throws Exception {
        try (var database = new PlanMySqlDatabase()) {
            var ds = database.create();
            var db = new JdbcTemplate(ds);
            db.execute("CREATE TABLE users(id BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB");
            db.execute("CREATE TABLE chat_session(id CHAR(36) NOT NULL PRIMARY KEY) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=" + collation);
            db.execute("CREATE TABLE chat_turn(id BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB");
            db.update("INSERT INTO users(id) VALUES(1)");
            db.update("INSERT INTO chat_session(id) VALUES('original-session')");
            db.update("INSERT INTO chat_turn(id) VALUES(1)");
            try (var connection = ds.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("docs/databases/migrations/20261124_agent_plan_recovery.sql"));
            }
            assertEquals(collation, db.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='agent_plan_run' AND COLUMN_NAME='conversation_id'", String.class));
            assertEquals(collation, db.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chat_session' AND COLUMN_NAME='id'", String.class));
            assertEquals("original-session", db.queryForObject("SELECT id FROM chat_session", String.class));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM chat_turn", Integer.class));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM agent_plan_run", Integer.class));
            assertEquals(4, db.queryForObject("SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()", Integer.class));
        }
    }
}
