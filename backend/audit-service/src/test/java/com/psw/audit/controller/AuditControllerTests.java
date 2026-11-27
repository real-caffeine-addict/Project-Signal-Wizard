package com.psw.audit.controller;

import com.psw.audit.service.AuditService;
import com.psw.common.dto.CreateAuditEventRequest;
import com.psw.common.enums.AuditableAction;
import com.psw.common.enums.TargetType;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuditControllerTests {
    private final AuditService service = mock(AuditService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuditController(service)).build();
    private final String payload = """
            {"userId":9,"projectId":7,"action":"CREATED","targetType":"DOCUMENT",
             "targetId":41,"occurredAt":"2026-07-01T12:30:00"}
            """;

    @Test
    void postPassesOriginAndBoundEventToService() throws Exception {
        when(service.createAuditEvent(any(), any())).thenReturn("Audit event 63 logged");

        mvc.perform(post("/audit-events").header("X-Origin-Service", "documents-service")
                        .contentType("application/json").content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("Audit event 63 logged"));

        verify(service).createAuditEvent(new CreateAuditEventRequest(
                9L, 7L, AuditableAction.CREATED, TargetType.DOCUMENT, 41L,
                LocalDateTime.of(2026, 7, 1, 12, 30)), "documents-service");
    }

    @Test
    void missingOriginIsRejectedBeforeCreatingEvent() throws Exception {
        mvc.perform(post("/audit-events").contentType("application/json").content(payload))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void getReturnsStoredAuditPayload() throws Exception {
        when(service.getAuditEventById(63L)).thenReturn("{\"targetId\":41}");

        mvc.perform(get("/audit-events/63"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"targetId\":41}"));
    }
}
