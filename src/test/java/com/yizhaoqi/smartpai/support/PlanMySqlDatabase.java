package com.yizhaoqi.smartpai.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import java.util.UUID;

/** Owns only one randomly named schema; never runs application DDL against the supplied database. */
public final class PlanMySqlDatabase implements AutoCloseable {
    private JdbcTemplate admin;
    private String schema;

    public DataSource create() {
        String url = System.getenv("PLAN_MYSQL_TEST_URL");
        String user = System.getenv("PLAN_MYSQL_TEST_USER");
        String password = System.getenv("PLAN_MYSQL_TEST_PASSWORD");
        if (url == null || !url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException("A MySQL test URL is required");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "plan_acceptance_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        String base = url.substring(0, url.indexOf('/', "jdbc:mysql://".length()));
        var dataSource = new DriverManagerDataSource(base + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=Asia/Shanghai"
                + "&sessionVariables=time_zone='%2B00:00'", user, password);
        String timezone = new JdbcTemplate(dataSource).queryForObject("SELECT @@session.time_zone", String.class);
        if (!"+00:00".equals(timezone)) throw new IllegalStateException("Test database must use UTC session time");
        return dataSource;
    }
    @Override public void close() {
        if (admin != null && schema != null) admin.execute("DROP DATABASE " + schema);
    }
}
