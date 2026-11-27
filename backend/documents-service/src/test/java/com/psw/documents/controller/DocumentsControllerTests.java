package com.psw.documents.controller;

import com.psw.documents.enums.DocumentType;
import com.psw.documents.model.Document;
import com.psw.documents.model.InsertDocumentApiContract;
import com.psw.documents.service.DocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentsControllerTests {
    private final DocumentService service = mock(DocumentService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DocumentsController(service)).build();

    @Test
    void postBindsDocumentFieldsAndUsesCurrentDevelopmentUser() throws Exception {
        when(service.createNewDocument(any(), any())).thenReturn("created");

        mvc.perform(post("/documents").contentType("application/json").content("""
                        {"projectId":7,"title":"Drawing","documentNumber":"DOC-001",
                         "type":"DRAWING","issuedAt":"2026-07-01T12:30:00"}
                        """))
                .andExpect(status().isOk())
                .andExpect(content().string("created"));

        verify(service).createNewDocument(new InsertDocumentApiContract(
                7L, null, "Drawing", "DOC-001", DocumentType.DRAWING, null,
                null, null, LocalDateTime.of(2026, 7, 1, 12, 30)), 1L);
    }

    @Test
    void getSerializesDocumentIdentityAndNullableMetadata() throws Exception {
        when(service.getDocumentById(41L)).thenReturn(new Document(
                41L, 7L, null, "שרטוט", "DOC-001", DocumentType.DRAWING, 52L, null));

        mvc.perform(get("/documents/41"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"id":41,"projectId":7,"dossierId":null,"title":"שרטוט",
                         "documentNumber":"DOC-001","type":"DRAWING",
                         "currentRevisionId":52,"originatingCompanyId":null}
                        """));
    }

    @Test
    void unknownDocumentTypeIsRejectedBeforeServiceInvocation() throws Exception {
        mvc.perform(post("/documents").contentType("application/json")
                        .content("{\"type\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
