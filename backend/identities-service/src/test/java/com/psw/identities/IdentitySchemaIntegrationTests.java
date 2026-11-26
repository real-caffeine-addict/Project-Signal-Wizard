package com.psw.identities;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.*;

class IdentitySchemaIntegrationTests {
    private static Flyway flyway;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateIsolatedSchema() {
        String password = System.getProperty("psw.test.db.password");
        assertThat(password).as("Supply an isolated database with backend/test.py").isNotBlank();
        var dataSource = new DriverManagerDataSource(System.getProperty("psw.test.db.url"),
                System.getProperty("psw.test.db.user", "psw_test"), password);
        flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void cleanIsolatedSchema() {
        if (flyway != null) flyway.clean();
    }

    @BeforeEach
    void clearUsers() { jdbc.update("DELETE FROM users"); }

    @Test
    void usernameRemainsUniqueWhenUserIsDisabled() {
        jdbc.update("INSERT INTO users(username, status) VALUES ('tester', 'ACTIVE')");
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE username = 'tester'", Long.class);

        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", id);

        assertThat(id).isPositive();
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, id)).isEqualTo("DISABLED");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO users(username, status) VALUES ('tester', 'ACTIVE')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class)).isEqualTo(1);
    }

    @Test
    void missingUsernameAndUnknownStatusDoNotCreateUsers() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO users(username, status) VALUES (NULL, 'ACTIVE')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO users(username, status) VALUES ('tester', 'UNKNOWN')"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class)).isZero();
    }
}
