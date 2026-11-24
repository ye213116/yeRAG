package com.yizhaoqi.smartpai.agent;

import com.yizhaoqi.smartpai.support.PlanMySqlDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.sql.DataSource;

/** Reuses the recovery contract on real InnoDB, with a JDBC/SQL timezone mismatch. */
@EnabledIfEnvironmentVariable(named = "PLAN_MYSQL_TEST_URL", matches = ".+")
class PlanRecoveryMySqlTest extends PlanRecoveryTest {
    private final PlanMySqlDatabase database = new PlanMySqlDatabase();
    @Override DataSource dataSource() { return database.create(); }
    @AfterEach void dropSchema() { database.close(); }
}
