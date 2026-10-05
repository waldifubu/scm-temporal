package com.supplychainmanagement.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

//@Component
@RequiredArgsConstructor
public class UserTypeConstraintInitializer {
    private static final List<String> EXPECTED_USER_TYPES = List.of(
            "customer",
            "manager",
            "supplier",
            "warehouse",
            "logistics",
            "distributor",
            "admin"
    );

    private final JdbcTemplate jdbcTemplate;

    //@EventListener(ApplicationReadyEvent.class)
    public void alignUserTypeCheckConstraint() {
        String columnType = jdbcTemplate.queryForObject("""
                SELECT LOWER(COLUMN_TYPE)
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'users'
                  AND COLUMN_NAME = 'user_type'
                """, String.class);

        if (columnType == null || isAlignedEnum(columnType)) {
            return;
        }

        if (!columnType.startsWith("enum(")) {
            return;
        }

        jdbcTemplate.execute("""
                ALTER TABLE users
                MODIFY COLUMN user_type ENUM('customer','manager','supplier','warehouse','logistics','distributor','admin') NOT NULL
                """);
    }

    private boolean isAlignedEnum(String columnType) {
        String normalized = columnType.toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("enum(")) {
            return false;
        }

        return EXPECTED_USER_TYPES.stream()
                .allMatch(type -> normalized.contains("'" + type + "'"));
    }
}
