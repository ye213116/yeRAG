package com.yizhaoqi.smartpai.coding;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="ai.coding.enabled",havingValue="true")
public class CodingStartupCheck implements ApplicationRunner {
    private final JdbcTemplate db;
    public CodingStartupCheck(JdbcTemplate db) { this.db=db; }
    @Override public void run(ApplicationArguments args) {
        try {
            db.queryForList("SELECT active_task_id,permissions_json,base_sha FROM coding_workspace WHERE 1=0");
            db.queryForList("SELECT request_hash,history_json,cancel_requested,publish_state FROM coding_task WHERE 1=0");
            db.queryForList("SELECT kind,data_json FROM coding_task_event WHERE 1=0");
        } catch(Exception missing) { throw new IllegalStateException("Apply 20261123_coding_tasks.sql before enabling Coding",missing); }
    }
}
