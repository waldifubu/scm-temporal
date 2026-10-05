package com.supplychainmanagement.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.DatabaseMetaData;

//@Component
public class RoleDataFixer {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public RoleDataFixer(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    //@EventListener(ApplicationReadyEvent.class)
    public void normalizeRoleStatuses() throws Exception {
        if (!hasLegacyNameColumn()) {
            return;
        }

        jdbcTemplate.update("""
                UPDATE roles
                SET status = name
                WHERE (status IS NULL OR status = '')
                  AND name IS NOT NULL
                  AND name <> ''
                """);
    }

    private boolean hasLegacyNameColumn() throws Exception {
        try (var connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (var resultSet = metaData.getColumns(null, null, "roles", "name")) {
                return resultSet.next();
            }
        }
    }
}
