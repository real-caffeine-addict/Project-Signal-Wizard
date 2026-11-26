package com.psw.incidents.repository;

import com.psw.common.dto.IncidentDto;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class IncidentsRepositoryTests {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final IncidentsRepository repository = new IncidentsRepository(jdbc);

    @Test
    void missingGeneratedIdDoesNotReportIncidentAsPersisted() {
        ReflectionTestUtils.setField(repository, "insertInc", new ClassPathResource("db/sql/insertIncident.sql"));
        ReflectionTestUtils.invokeMethod(repository, "initVerifySql");

        assertThatThrownBy(() -> repository.insertIncident(new IncidentDto(
                "log-1", "request-1", Instant.parse("2026-07-01T12:30:00Z"), "MAJOR", "gateway", "failure", "{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to retrieve generated incident id");
    }

    @Test
    void unreadableSqlPreventsRepositoryInitialization() throws Exception {
        Resource unreadable = mock(Resource.class);
        when(unreadable.getInputStream()).thenThrow(new IOException("unreadable"));
        ReflectionTestUtils.setField(repository, "insertInc", unreadable);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(repository, "initVerifySql"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to initiate sql strings at incidents repository");
        verifyNoInteractions(jdbc);
    }
}
