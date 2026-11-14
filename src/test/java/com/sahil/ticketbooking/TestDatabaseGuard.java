package com.sahil.ticketbooking;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Safety net: the tests truncate tables, so refuse to start if the connected
 * database is not a test database.
 */
@Component
public class TestDatabaseGuard {

    public TestDatabaseGuard(JdbcTemplate jdbcTemplate) {
        String databaseName = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        if (databaseName == null || !databaseName.endsWith("_test")) {
            throw new IllegalStateException("Refusing to run tests against database '" + databaseName
                    + "'. The tests wipe their tables, so the database name must end with _test.");
        }
    }
}
