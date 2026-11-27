package com.psw.incidents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.psw.common.dto.IncidentDto;
import com.psw.common.enums.Services;
import com.psw.incidents.controller.IncidentController;
import com.psw.incidents.repository.IncidentsRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
class IncidentPersistenceIT {
    @Container
    private static final MariaDBContainer database = new MariaDBContainer("mariadb:11.4");
    private static Flyway flyway;
    private static JdbcTemplate jdbc;
    private IncidentsRepository repository;

    @BeforeAll
    static void migrateIsolatedSchema() {
        flyway = Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void cleanIsolatedSchema() {
        if (flyway != null) {
            flyway.clean();
        }
    }

    @BeforeEach
    void createRepositoryAndClearRows() {
        jdbc.update("DELETE FROM incidents");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        repository = new IncidentsRepository(new NamedParameterJdbcTemplate(dataSource));
        ReflectionTestUtils.setField(repository, "insertInc",
                new ClassPathResource("db/sql/insertIncident.sql"));
        ReflectionTestUtils.invokeMethod(repository, "initVerifySql");
    }

    @Test
    void validDtoInsertsExactlyOneRowAndReturnsItsGeneratedId() {
        IncidentDto dto = incident("log-1", "request-1", Instant.parse("2026-08-11T12:00:00Z"));

        Long id = repository.insertIncident(dto);

        assertThat(id).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incidents", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT id FROM incidents WHERE log_id = 'log-1'", Long.class)).isEqualTo(id);
    }

    @Test
    void multipleInsertsReceiveDistinctDatabaseGeneratedIds() {
        Long first = repository.insertIncident(
                incident("log-a", "request-a", Instant.parse("2026-08-11T12:00:00Z")));
        Long second = repository.insertIncident(
                incident("log-b", "request-b", Instant.parse("2026-08-11T12:00:01Z")));

        assertThat(first).isNotEqualTo(second);
        assertThat(first).isPositive();
        assertThat(second).isPositive();
    }

    @Test
    void nullableFieldsAndDebuggingDataAreStoredCorrectly() {
        IncidentDto dto = new IncidentDto(
                "log-nullable", "request-nullable", Instant.parse("2026-08-11T12:00:00Z"),
                "MAJOR", null, null, "{\"cause\":\"timeout\"}");

        Long id = repository.insertIncident(dto);

        assertThat(jdbc.queryForMap(
                "SELECT source_component, description, additional_data FROM incidents WHERE id = ?",
                id))
                .containsEntry("source_component", null)
                .containsEntry("description", null)
                .containsEntry("additional_data", "{\"cause\":\"timeout\"}");
    }

    @Test
    void constraintFailureDoesNotCreatePartialRow() {
        IncidentDto invalid = new IncidentDto(
                null, "request-invalid", Instant.now(), "MAJOR", Services.GATEWAY.name(), "invalid", "{}");

        assertThatThrownBy(() -> repository.insertIncident(invalid))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incidents", Long.class)).isZero();
    }

    @Test
    void dtoCannotOverrideDatabaseGeneratedId() {
        Long id = repository.insertIncident(
                incident("12345", "request-id", Instant.parse("2026-08-11T12:00:00Z")));

        assertThat(id).isNotEqualTo(12345L);
        assertThat(jdbc.queryForObject(
                "SELECT id FROM incidents WHERE log_id = '12345'", Long.class)).isEqualTo(id);
    }

    @Test
    void timestampSurvivesRoundTripWithoutTimezoneShift() {
        Instant timestamp = Instant.parse("2026-08-11T12:34:56.789Z");
        Long id = repository.insertIncident(incident("log-time", "request-time", timestamp));

        Instant stored = jdbc.queryForObject(
                "SELECT time_created FROM incidents WHERE id = ?",
                (resultSet, row) -> resultSet.getTimestamp(1).toInstant(), id);

        assertThat(stored).isEqualTo(timestamp.truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    void controllerPostReturnsIdMatchingPersistedRow() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new IncidentController(repository)).build();
        IncidentDto dto = incident(
                "log-controller", "request-controller", Instant.parse("2026-08-11T12:00:00Z"));

        String response = mvc.perform(post("/incidents")
                        .contentType("application/json")
                        .content(new ObjectMapper().findAndRegisterModules().writeValueAsBytes(dto)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Long id = Long.valueOf(response);
        assertThat(jdbc.queryForObject(
                "SELECT id FROM incidents WHERE log_id = 'log-controller'", Long.class))
                .isEqualTo(id);
    }

    @Test
    void largeAdditionalDataWithinTextCapacityRoundTripsUnchanged() {
        String additionalData = "דיאגנוסטיקה\n" + "x".repeat(60_000);
        IncidentDto dto = new IncidentDto(
                "log-large", "request-large", Instant.parse("2026-08-11T12:00:00Z"),
                "MAJOR", Services.GATEWAY.name(), "large diagnostic payload", additionalData);

        Long id = repository.insertIncident(dto);

        assertThat(jdbc.queryForObject(
                "SELECT additional_data FROM incidents WHERE id = ?", String.class, id))
                .isEqualTo(additionalData);
    }

    private IncidentDto incident(String logId, String requestId, Instant timestamp) {
        return new IncidentDto(
                logId, requestId, timestamp, "CRITICAL", Services.GATEWAY.name(),
                "Downstream service is unavailable", "{\"service\":\"projects\"}");
    }
}
