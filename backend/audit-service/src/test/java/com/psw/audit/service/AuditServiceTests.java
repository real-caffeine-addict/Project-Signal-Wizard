package com.psw.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psw.audit.model.InsertAuditEventDb;
import com.psw.audit.repository.AuditRepository;
import com.psw.common.config.JacksonConfig;
import com.psw.common.dto.CreateAuditEventRequest;
import com.psw.common.enums.AuditableAction;
import com.psw.common.enums.TargetType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuditServiceTests {
    private final AuditRepository repository = mock(AuditRepository.class);
    private final ObjectMapper mapper = new JacksonConfig().objectMapper();
    private final AuditService service = new AuditService(repository, mapper);

    @Test
    void persistsAuditFieldsAndJsonPayloadWithOriginService() throws Exception {
        CreateAuditEventRequest input = event();
        when(repository.createAuditEvent(any())).thenReturn(63L);

        String result = service.createAuditEvent(input, "documents-service");

        ArgumentCaptor<InsertAuditEventDb> row = ArgumentCaptor.forClass(InsertAuditEventDb.class);
        verify(repository).createAuditEvent(row.capture());
        assertThat(row.getValue()).isEqualTo(new InsertAuditEventDb(
                9L, 7L, "documents-service", "CREATED", "DOCUMENT", 41L,
                input.occurredAt(), mapper.writeValueAsString(input)));
        assertThat(mapper.readValue(row.getValue().jsonPayload(), CreateAuditEventRequest.class)).isEqualTo(input);
        assertThat(result).isEqualTo("Audit event 63 logged");
    }

    @Test
    void serializationFailureKeepsCauseAndPreventsInsert() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        JsonProcessingException failure = new JsonProcessingException("cannot serialize") {};
        when(failingMapper.writeValueAsString(any())).thenThrow(failure);
        AuditService failingService = new AuditService(repository, failingMapper);

        assertThatThrownBy(() -> failingService.createAuditEvent(event(), "documents-service"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to serialize object")
                .hasCause(failure);

        verifyNoInteractions(repository);
    }

    @Test
    void failedPersistenceIsNotReportedAsSuccessfulAudit() {
        RuntimeException failure = new IllegalStateException("database unavailable");
        when(repository.createAuditEvent(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.createAuditEvent(event(), "documents-service")).isSameAs(failure);
    }

    private CreateAuditEventRequest event() {
        return new CreateAuditEventRequest(9L, 7L, AuditableAction.CREATED, TargetType.DOCUMENT,
                41L, LocalDateTime.of(2026, 7, 1, 12, 30));
    }
}
