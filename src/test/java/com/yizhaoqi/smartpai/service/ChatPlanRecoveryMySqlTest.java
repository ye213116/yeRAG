package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.support.PlanMySqlDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.sql.DataSource;

/** Real MySQL: one original turn, no duplicated completed steps, no partial answer committed. */
@EnabledIfEnvironmentVariable(named = "PLAN_MYSQL_TEST_URL", matches = ".+")
class ChatPlanRecoveryMySqlTest extends ChatPlanRecoveryTest {
    private final PlanMySqlDatabase database = new PlanMySqlDatabase();
    @Override DataSource dataSource() { return database.create(); }
    @Override @AfterEach void shutdown() {
        try { super.shutdown(); }
        finally { database.close(); }
    }
}
