package com.psw.documents.repository;

import com.psw.documents.model.InsertDocumentDb;
import com.psw.documents.model.InsertRevisionDb;
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

class DocumentsRepositoryTests {
    private final NamedParameterJdbcTemplate namedJdbc = mock(NamedParameterJdbcTemplate.class);
    private final DocumentsRepository repository = new DocumentsRepository(namedJdbc, mock(JdbcTemplate.class));

    @Test
    void missingGeneratedDocumentIdFailsInsteadOfReturningAnUnusableReference() {
        initializeSql();

        assertThatThrownBy(() -> repository.createDocument(new InsertDocumentDb(
                7L, null, "Drawing", "DOC-001", "DRAWING", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to retrieve generated document id");
    }

    @Test
    void missingGeneratedRevisionIdFailsInsteadOfReturningAnUnusableReference() {
        initializeSql();

        assertThatThrownBy(() -> repository.createRevision(new InsertRevisionDb(
                41L, "A", "DRAFT", LocalDateTime.of(2026, 7, 1, 12, 30), "file", 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to retrieve generated document id");
    }

    @Test
    void unreadableSqlPreventsRepositoryInitialization() throws Exception {
        Resource unreadable = mock(Resource.class);
        when(unreadable.getInputStream()).thenThrow(new IOException("unreadable"));
        ReflectionTestUtils.setField(repository, "createDoc", unreadable);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(repository, "initVerifySql"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to initiate sql strings at documents repository");
        verifyNoInteractions(namedJdbc);
    }

    private void initializeSql() {
        ReflectionTestUtils.setField(repository, "createDoc", new ClassPathResource("db/sql/createDoc.sql"));
        ReflectionTestUtils.setField(repository, "createRev", new ClassPathResource("db/sql/createRev.sql"));
        ReflectionTestUtils.invokeMethod(repository, "initVerifySql");
    }
}
