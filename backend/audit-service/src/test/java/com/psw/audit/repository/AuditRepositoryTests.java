package com.psw.audit.repository;

import com.psw.audit.model.InsertAuditEventDb;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AuditRepositoryTests {
    private final NamedParameterJdbcTemplate namedJdbc = mock(NamedParameterJdbcTemplate.class);
    private final AuditRepository repository = new AuditRepository(namedJdbc, mock(JdbcTemplate.class));

    @Test
    void missingGeneratedIdDoesNotReportAuditAsPersisted() {
        ReflectionTestUtils.setField(repository, "insertAuditEvent", new ClassPathResource("db/sql/insertAuditEvent.sql"));
        ReflectionTestUtils.invokeMethod(repository, "initVerifySql");

        assertThatThrownBy(() -> repository.createAuditEvent(new InsertAuditEventDb(
                9L, 7L, "documents-service", "CREATED", "DOCUMENT", 41L,
                LocalDateTime.of(2026, 7, 1, 12, 30), "{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to retrieve generated audit event id");
    }

    @Test
    void unreadableSqlPreventsRepositoryInitialization() throws Exception {
        Resource unreadable = mock(Resource.class);
        when(unreadable.getInputStream()).thenThrow(new IOException("unreadable"));
        ReflectionTestUtils.setField(repository, "insertAuditEvent", unreadable);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(repository, "initVerifySql"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to initiate sql strings at audit repository");
        verifyNoInteractions(namedJdbc);
    }
}
