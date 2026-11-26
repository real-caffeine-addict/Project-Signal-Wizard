package com.psw.projects;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.*;

class ProjectSchemaIntegrationTests {
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
    void clearProjects() { jdbc.update("DELETE FROM projects"); }

    @Test
    void documentDossierNamesAndMembershipsAreUniqueWithinEachProject() {
        Long first = project("P-01");
        Long second = project("P-02");
        dossier(first, "Drawings");
        member(first);

        assertThatThrownBy(() -> dossier(first, "Drawings")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> member(first)).isInstanceOf(DataIntegrityViolationException.class);

        dossier(second, "Drawings");
        member(second);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dossiers", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM project_members", Long.class)).isEqualTo(2);
    }

    @Test
    void projectCodeCannotBeReusedForAnotherProject() {
        project("P-01");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO projects(name, code, status, start_date) "
                + "VALUES ('Different project', 'P-01', 'ACTIVE', '2026-07-01')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingProjectRemovesOnlyItsDossiersAndMemberships() {
        Long first = project("P-01");
        Long second = project("P-02");
        dossier(first, "Drawings");
        member(first);
        dossier(second, "Drawings");
        member(second);

        jdbc.update("DELETE FROM projects WHERE id = ?", first);

        assertThat(jdbc.queryForList("SELECT project_id FROM dossiers", Long.class)).containsExactly(second);
        assertThat(jdbc.queryForList("SELECT project_id FROM project_members", Long.class)).containsExactly(second);
        assertThatThrownBy(() -> member(first)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> dossier(first, "Orphan")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownStatusesRolesAndDisciplinesAreRejected() {
        Long project = project("P-01");

        assertThatThrownBy(() -> jdbc.update("UPDATE projects SET status = 'UNKNOWN' WHERE id = ?", project))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO project_members(user_id, project_id, project_role) VALUES (9, ?, 'UNKNOWN')", project))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO dossiers(project_id, name, code, discipline, system) VALUES (?, 'Drawings', 'D-01', 'UNKNOWN', 'S-01')", project))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM projects WHERE id = ?", String.class, project)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM project_members", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dossiers", Long.class)).isZero();
    }

    private Long project(String code) {
        jdbc.update("INSERT INTO projects(name, code, status, start_date) VALUES (?, ?, 'ACTIVE', '2026-07-01')", code, code);
        return jdbc.queryForObject("SELECT id FROM projects WHERE code = ?", Long.class, code);
    }

    private void dossier(Long projectId, String name) {
        jdbc.update("INSERT INTO dossiers(project_id, name, code, discipline, system) VALUES (?, ?, 'D-01', 'MECHANICAL', 'S-01')", projectId, name);
    }

    private void member(Long projectId) {
        jdbc.update("INSERT INTO project_members(user_id, project_id, project_role) VALUES (9, ?, 'VIEWER')", projectId);
    }
}
