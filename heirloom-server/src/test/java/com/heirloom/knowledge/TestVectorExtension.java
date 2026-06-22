package com.heirloom.knowledge;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class TestVectorExtension {
    @Autowired private DataSource ds;

    @PostConstruct
    void enablePgvector() {
        try {
            new JdbcTemplate(ds).execute("CREATE EXTENSION IF NOT EXISTS vector");
        } catch (Exception e) {
            // Ignore — H2 or non-PG databases won't support this
        }
    }
}
