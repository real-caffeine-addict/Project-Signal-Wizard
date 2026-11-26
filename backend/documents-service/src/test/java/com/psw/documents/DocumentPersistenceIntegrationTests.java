package com.psw.documents;

import com.psw.documents.clients.AuditServiceClient;
import com.psw.documents.enums.DocumentType;
import com.psw.documents.model.Document;
import com.psw.documents.model.InsertDocumentApiContract;
import com.psw.documents.model.InsertDocumentDb;
import com.psw.documents.model.InsertRevisionDb;
import com.psw.documents.repository.DocumentsRepository;
import com.psw.documents.service.DocumentService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DocumentPersistenceIntegrationTests {
    private static AnnotationConfigApplicationContext context;
    private static Flyway flyway;
    private static JdbcTemplate jdbc;
    private static DocumentsRepository repository;
    private static DocumentService service;
    private static AuditServiceClient audit;
    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 7, 1, 12, 30);

    @BeforeAll
    static void migrateIsolatedSchemaAndCreateTransactionalService() {
        String password = System.getProperty("psw.test.db.password");
        assertThat(password).as("Supply an isolated database with backend/test.py").isNotBlank();
        DataSource dataSource = new DriverManagerDataSource(
                System.getProperty("psw.test.db.url"), System.getProperty("psw.test.db.user", "psw_test"), password);
        flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        audit = mock(AuditServiceClient.class);
        context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfig.class);
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(NamedParameterJdbcTemplate.class, () -> new NamedParameterJdbcTemplate(dataSource));
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(AuditServiceClient.class, () -> audit);
        context.register(DocumentsRepository.class, DocumentService.class);
        context.refresh();
        repository = context.getBean(DocumentsRepository.class);
        service = context.getBean(DocumentService.class);
    }

    @AfterAll
    static void cleanIsolatedSchema() {
        if (context != null) context.close();
        if (flyway != null) flyway.clean();
    }

    @BeforeEach
    void clearDocumentsAndAuditStub() {
        jdbc.update("UPDATE documents SET current_revision_id = NULL");
        jdbc.update("DELETE FROM document_revisions");
        jdbc.update("DELETE FROM documents");
        reset(audit);
    }

    @Test
    void creationCommitsDocumentInitialRevisionAndCurrentPointer() {
        service.createNewDocument(input("DOC-001", ISSUED_AT), 9L);

        Long id = jdbc.queryForObject("SELECT id FROM documents WHERE document_number = 'DOC-001'", Long.class);
        Document document = service.getDocumentById(id);
        assertThat(document.getTitle()).isEqualTo("שרטוט");
        assertThat(document.getType()).isEqualTo(DocumentType.DRAWING);
        assertThat(document.getCurrentRevisionId()).isPositive();
        assertThat(jdbc.queryForMap("SELECT document_id, revision_code, status, issued_at, number_of_files "
                + "FROM document_revisions WHERE id = ?", document.getCurrentRevisionId()))
                .containsEntry("document_id", id).containsEntry("revision_code", "A")
                .containsEntry("status", "DRAFT").containsEntry("number_of_files", 0L);
        LocalDateTime issuedAt = jdbc.queryForObject("SELECT issued_at FROM document_revisions WHERE id = ?",
                (row, index) -> row.getTimestamp(1).toLocalDateTime(), document.getCurrentRevisionId());
        assertThat(issuedAt).isEqualTo(ISSUED_AT);
        verify(audit).createAuditEvent(any(), any());
    }

    @Test
    void nullableForeignIdsRemainNullWhenReadingDocument() {
        Long id = repository.createDocument(new InsertDocumentDb(7L, null, "Drawing", "DOC-001", "DRAWING", null));

        Document document = repository.getDocumentById(id);

        assertThat(document.getDossierId()).isNull();
        assertThat(document.getCurrentRevisionId()).isNull();
        assertThat(document.getOriginatingCompanyId()).isNull();
        assertThat(document.getProjectId()).isEqualTo(7L);
    }

    @Test
    void nonNullForeignIdsSurviveReadMapping() {
        Long id = repository.createDocument(new InsertDocumentDb(7L, 8L, "Drawing", "DOC-001", "DRAWING", 6L));

        Document document = repository.getDocumentById(id);

        assertThat(document.getDossierId()).isEqualTo(8L);
        assertThat(document.getOriginatingCompanyId()).isEqualTo(6L);
    }

    @Test
    void auditFailureRollsBackDocumentRevisionAndCurrentPointer() {
        RuntimeException failure = new IllegalStateException("audit unavailable");
        when(audit.createAuditEvent(any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> service.createNewDocument(input("DOC-001", ISSUED_AT), 9L)).isSameAs(failure);

        assertNoDocumentsOrRevisions();
    }

    @Test
    void invalidRevisionRollsBackTheAlreadyInsertedDocument() {
        InsertDocumentApiContract invalid = new InsertDocumentApiContract(7L, null, "Drawing", "DOC-001",
                DocumentType.DRAWING, null, "x".repeat(21), null, ISSUED_AT);

        assertThatThrownBy(() -> service.createNewDocument(invalid, 9L))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertNoDocumentsOrRevisions();
        verifyNoInteractions(audit);
    }

    @Test
    void duplicateDocumentNumberWithinProjectLeavesOriginalUnchanged() {
        service.createNewDocument(input("DOC-001", ISSUED_AT), 9L);
        clearInvocations(audit);

        assertThatThrownBy(() -> service.createNewDocument(input("DOC-001", ISSUED_AT), 9L))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM documents", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document_revisions", Long.class)).isEqualTo(1);
        verifyNoInteractions(audit);
    }

    @Test
    void currentPointerCanChangeWithoutOverwritingRevisionHistory() {
        Long id = repository.createDocument(new InsertDocumentDb(7L, null, "Drawing", "DOC-001", "DRAWING", null));
        Long first = repository.createRevision(new InsertRevisionDb(id, "A", "DRAFT", ISSUED_AT, "rev-a", 1));
        repository.updateCurrentRev(first, id);
        Long second = repository.createRevision(new InsertRevisionDb(id, "B", "IFC", ISSUED_AT, "rev-b", 2));

        repository.updateCurrentRev(second, id);

        assertThat(repository.getDocumentById(id).getCurrentRevisionId()).isEqualTo(second);
        assertThat(second).isNotEqualTo(first);
        assertThat(jdbc.queryForList("SELECT revision_code FROM document_revisions WHERE document_id = ? ORDER BY id",
                String.class, id)).containsExactly("A", "B");
        assertThatThrownBy(() -> repository.createRevision(new InsertRevisionDb(id, "B", "DRAFT", ISSUED_AT, "duplicate", 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void absentDocumentPropagatesCurrentRepositoryNotFoundBehavior() {
        assertThatThrownBy(() -> service.getDocumentById(-1L)).isInstanceOf(EmptyResultDataAccessException.class);
    }

    private InsertDocumentApiContract input(String number, LocalDateTime issuedAt) {
        return new InsertDocumentApiContract(7L, null, "שרטוט", number, DocumentType.DRAWING, null, null, null, issuedAt);
    }

    private void assertNoDocumentsOrRevisions() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM documents", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document_revisions", Long.class)).isZero();
    }

    @Configuration
    @EnableTransactionManagement
    static class TransactionConfig {}
}
