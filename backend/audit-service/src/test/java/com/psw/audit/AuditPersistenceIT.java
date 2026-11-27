package com.psw.audit;

import com.psw.audit.model.InsertAuditEventDb;
import com.psw.audit.repository.AuditRepository;
import com.psw.audit.service.AuditService;
import com.psw.common.config.JacksonConfig;
import com.psw.common.dto.CreateAuditEventRequest;
import com.psw.common.enums.AuditableAction;
import com.psw.common.enums.TargetType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class AuditPersistenceIT {
    @Container
    private static final MariaDBContainer database = new MariaDBContainer("mariadb:11.4");
    private static Flyway flyway;
    private static JdbcTemplate jdbc;
    private static AuditRepository repository;
    private static AuditService service;

    @BeforeAll
    static void migrateIsolatedSchema() {
        var dataSource = new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword());
        flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new AuditRepository(new NamedParameterJdbcTemplate(dataSource), jdbc);
        ReflectionTestUtils.setField(repository, "insertAuditEvent", new ClassPathResource("db/sql/insertAuditEvent.sql"));
        ReflectionTestUtils.invokeMethod(repository, "initVerifySql");
        service = new AuditService(repository, new JacksonConfig().objectMapper());
    }

    @AfterAll
    static void cleanIsolatedSchema() {
        if (flyway != null) flyway.clean();
    }

    @BeforeEach
    void clearEvents() {
        jdbc.update("DELETE FROM audit_events");
    }

    @Test
    void auditPayloadAndCorrelationFieldsSurviveDatabaseRoundTrip() throws Exception {
        CreateAuditEventRequest input = new CreateAuditEventRequest(9L, 7L, AuditableAction.CREATED,
                TargetType.DOCUMENT, 41L, LocalDateTime.of(2026, 7, 1, 12, 30));

        String result = service.createAuditEvent(input, "documents-service");

        Long id = jdbc.queryForObject("SELECT id FROM audit_events", Long.class);
        assertThat(id).isPositive();
        assertThat(result).isEqualTo("Audit event " + id + " logged");
        assertThat(new JacksonConfig().objectMapper().readValue(service.getAuditEventById(id), CreateAuditEventRequest.class))
                .isEqualTo(input);
        assertThat(jdbc.queryForMap("SELECT user_id, project_id, origin_service, action, target_type, target_id FROM audit_events WHERE id = ?", id))
                .containsEntry("user_id", 9L).containsEntry("project_id", 7L)
                .containsEntry("origin_service", "documents-service").containsEntry("action", "CREATED")
                .containsEntry("target_type", "DOCUMENT").containsEntry("target_id", 41L);
        assertThat(jdbc.queryForObject("SELECT logged_at FROM audit_events WHERE id = ?", LocalDateTime.class, id)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM audit_events WHERE id = ?", LocalDateTime.class, id))
                .isEqualTo(input.occurredAt());
    }

    @Test
    void unicodeAndMultilineSummaryRoundTripsUnchanged() {
        String payload = "{\"note\":\"שרטוט\\nsecond line\"}";

        Long first = repository.createAuditEvent(row("CREATED", payload));
        Long second = repository.createAuditEvent(row("UPDATED", "{}"));

        assertThat(first).isPositive().isNotEqualTo(second);
        assertThat(repository.readAuditEventById(first)).isEqualTo(payload);
    }

    @Test
    void invalidActionIsRejectedWithoutPersistingAnEvent() {
        assertThatThrownBy(() -> repository.createAuditEvent(row("UNKNOWN", "{}")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events", Long.class)).isZero();
    }

    @Test
    void absentEventPropagatesCurrentRepositoryNotFoundBehavior() {
        assertThatThrownBy(() -> service.getAuditEventById(-1L)).isInstanceOf(EmptyResultDataAccessException.class);
    }

    private InsertAuditEventDb row(String action, String payload) {
        return new InsertAuditEventDb(9L, 7L, "documents-service", action, "DOCUMENT", 41L,
                LocalDateTime.of(2026, 7, 1, 12, 30), payload);
    }
}
