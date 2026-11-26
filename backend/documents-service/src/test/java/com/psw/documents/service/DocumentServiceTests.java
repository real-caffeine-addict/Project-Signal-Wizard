package com.psw.documents.service;

import com.psw.common.dto.CreateAuditEventRequest;
import com.psw.common.enums.AuditableAction;
import com.psw.common.enums.TargetType;
import com.psw.documents.clients.AuditServiceClient;
import com.psw.documents.enums.DocumentType;
import com.psw.documents.enums.RevisionStatus;
import com.psw.documents.model.InsertDocumentApiContract;
import com.psw.documents.model.InsertDocumentDb;
import com.psw.documents.model.InsertRevisionDb;
import com.psw.documents.repository.DocumentsRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DocumentServiceTests {
    private final DocumentsRepository repository = mock(DocumentsRepository.class);
    private final AuditServiceClient audit = mock(AuditServiceClient.class);
    private final DocumentService service = new DocumentService(repository, audit);

    @Test
    void createsInitialRevisionAndCurrentPointerBeforeAuditingDocument() {
        InsertDocumentApiContract input = document(null, null);
        when(repository.createDocument(any())).thenReturn(41L);
        when(repository.createRevision(any())).thenReturn(52L);
        when(audit.createAuditEvent(any(), any())).thenReturn("Audit event 63 logged");
        LocalDateTime before = LocalDateTime.now();

        String result = service.createNewDocument(input, 9L);

        InOrder order = inOrder(repository, audit);
        order.verify(repository).createDocument(new InsertDocumentDb(
                7L, 8L, "Drawing", "DOC-001", "DRAWING", 6L));
        order.verify(repository).createRevision(new InsertRevisionDb(
                41L, "A", "DRAFT", input.issuedAt(), "7/8/DOC-001", 0));
        order.verify(repository).updateCurrentRev(52L, 41L);
        ArgumentCaptor<CreateAuditEventRequest> event = ArgumentCaptor.forClass(CreateAuditEventRequest.class);
        order.verify(audit).createAuditEvent(eq("documents-service"), event.capture());
        assertThat(event.getValue()).satisfies(value -> {
            assertThat(value.userId()).isEqualTo(9L);
            assertThat(value.projectId()).isEqualTo(7L);
            assertThat(value.action()).isEqualTo(AuditableAction.CREATED);
            assertThat(value.targetType()).isEqualTo(TargetType.DOCUMENT);
            assertThat(value.targetId()).isEqualTo(41L);
            assertThat(value.occurredAt()).isBetween(before, LocalDateTime.now());
        });
        assertThat(result).isEqualTo("Successfully created document id: 41 revision id: 52\nAudit event 63 logged");
    }

    @Test
    void preservesSuppliedRevisionCodeAndStatus() {
        InsertDocumentApiContract input = document("B", RevisionStatus.IFC);
        when(repository.createDocument(any())).thenReturn(41L);

        service.createNewDocument(input, 9L);

        verify(repository).createRevision(new InsertRevisionDb(
                41L, "B", "IFC", input.issuedAt(), "7/8/DOC-001", 0));
    }

    @Test
    void documentInsertFailureStopsRevisionAndAuditWork() {
        RuntimeException failure = new IllegalStateException("document insert failed");
        when(repository.createDocument(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.createNewDocument(document(null, null), 9L)).isSameAs(failure);

        verify(repository, never()).createRevision(any());
        verify(repository, never()).updateCurrentRev(any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void revisionInsertFailureStopsCurrentPointerAndAuditWork() {
        RuntimeException failure = new IllegalStateException("revision insert failed");
        when(repository.createDocument(any())).thenReturn(41L);
        when(repository.createRevision(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.createNewDocument(document(null, null), 9L)).isSameAs(failure);

        verify(repository, never()).updateCurrentRev(any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void currentPointerFailurePreventsAuditCall() {
        RuntimeException failure = new IllegalStateException("pointer update failed");
        when(repository.createDocument(any())).thenReturn(41L);
        when(repository.createRevision(any())).thenReturn(52L);
        doThrow(failure).when(repository).updateCurrentRev(52L, 41L);

        assertThatThrownBy(() -> service.createNewDocument(document(null, null), 9L)).isSameAs(failure);

        verifyNoInteractions(audit);
    }

    @Test
    void auditFailureIsPropagatedToTheTransactionBoundary() {
        RuntimeException failure = new IllegalStateException("audit unavailable");
        when(repository.createDocument(any())).thenReturn(41L);
        when(repository.createRevision(any())).thenReturn(52L);
        when(audit.createAuditEvent(any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> service.createNewDocument(document(null, null), 9L)).isSameAs(failure);
    }

    private InsertDocumentApiContract document(String revision, RevisionStatus status) {
        return new InsertDocumentApiContract(7L, 8L, "Drawing", "DOC-001", DocumentType.DRAWING,
                6L, revision, status, LocalDateTime.of(2026, 7, 1, 12, 30));
    }
}
