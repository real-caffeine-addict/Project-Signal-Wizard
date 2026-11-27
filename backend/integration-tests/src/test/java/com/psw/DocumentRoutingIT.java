package com.psw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.psw.documents.controller.DocumentsController;
import com.psw.documents.enums.DocumentType;
import com.psw.documents.enums.RevisionStatus;
import com.psw.documents.model.Document;
import com.psw.documents.model.InsertDocumentApiContract;
import com.psw.documents.service.DocumentService;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;

import com.psw.gateway.client.DocumentsClient;
import com.psw.gateway.controller.ProtectedController;
import com.psw.gateway.filter.ContextInitFilter;
import com.psw.gateway.model.RequestContext;
import com.psw.gateway.utility.ContextInitUtil;
import com.psw.gateway.utility.ExceptionParserUtil;
import com.psw.gateway.utility.GwLogger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest(
        classes = DocumentRoutingIT.DocumentTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=isolated-routing-test"
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DocumentRoutingIT {
    private ConfigurableApplicationContext gatewayContext;
    private int gatewayPort;

    @BeforeAll
    void startGateway() {
        gatewayContext = SpringApplication.run(
                GwTestApp.class,
                "--server.port=0",
                "--spring.config.name=isolated-routing-test",
                "--routes.documents=http://localhost:" + documentsPort
        );
        gatewayPort = Objects.requireNonNull(
                ((WebServerApplicationContext) gatewayContext).getWebServer(),
                "Gateway web server failed to start"
        ).getPort();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            ServletWebSecurityAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class
    })
    @Import(DocumentsController.class)
    static class DocumentTestApp {}

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            ServletWebSecurityAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class,
            DataSourceAutoConfiguration.class,
            FlywayAutoConfiguration.class
    })
    @EnableFeignClients(clients = DocumentsClient.class)
    @Import({
            ProtectedController.class,
            ContextInitFilter.class,
            ContextInitUtil.class,
            ExceptionParserUtil.class
    })
    static class GwTestApp {
        @Bean
        GwLogger gwLogger() {
            return mock(GwLogger.class);
        }
    }

    @MockitoBean
    private DocumentService documentService;
    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int documentsPort;

    private final InsertDocumentApiContract testDoc = new InsertDocumentApiContract(
            1L,
            1L,
            "title",
            "do_id",
            DocumentType.DRAWING,
            1L,
            "rev_code",
            RevisionStatus.DRAFT,
            LocalDateTime.now());

    Document expectedDocument = new Document(
            1L,
            1L,
            1L,
            "title",
            "do_id",
            DocumentType.DRAWING,
            1L,
            1L
    );


    @Test
    public void PostDocumentHappyPath () throws IOException, InterruptedException {
        URI uri = URI.create("http://localhost:" + gatewayPort + "/app/documents");
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        String json = mapper.writeValueAsString(testDoc);
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        when(documentService.createNewDocument(
                any(InsertDocumentApiContract.class),
                eq(1L)
        )).thenReturn("Document created");

        HttpResponse<String> response = client.send(
                httpRequest,
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(201, response.statusCode());
        assertEquals("Document created", response.body());
    }

    @Test
    public void GetDocumentHappyPath () throws IOException, InterruptedException {
        URI uri = URI.create("http://localhost:" + gatewayPort + "/app/documents/1");
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .GET().
                build();

        when(documentService.getDocumentById(
                any(Long.class)
        )).thenReturn(expectedDocument);

        HttpResponse<String> response = client.send(
                httpRequest,
                HttpResponse.BodyHandlers.ofString()
        );

        JsonNode body = new ObjectMapper().readTree(response.body());

        assertEquals(1L, body.get("id").asLong());
        assertEquals("title", body.get("title").asText());
        assertEquals(200, response.statusCode());
    }

    @Test
    void FeignFailureReturns500() throws Exception {
        when(documentService.getDocumentById(999L))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Simulated downstream failure"
                ));

        URI uri = URI.create(
                "http://localhost:" + gatewayPort + "/app/documents/999"
        );

        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .build();

        HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(500, response.statusCode());
        assertEquals("Internal server error", response.body());

        verify(documentService).getDocumentById(999L);
    }

    @Test
    void GatewayLogsRequestOnce() throws Exception {
        GwLogger logger = gatewayContext.getBean(GwLogger.class);

        // The same Gateway context is shared between tests.
        clearInvocations(logger);

        Document expectedDocument = new Document(
                1L, 1L, 1L,
                "title", "do_id",
                DocumentType.DRAWING,
                1L, 1L
        );

        when(documentService.getDocumentById(1L))
                .thenReturn(expectedDocument);

        URI uri = URI.create(
                "http://localhost:" + gatewayPort + "/app/documents/1"
        );

        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .build();

        HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());

        ArgumentCaptor<RequestContext> captor =
                ArgumentCaptor.forClass(RequestContext.class);

        verify(logger, times(1)).logRequest(captor.capture());

        RequestContext ctx = captor.getValue();

        assertNotNull(ctx.getRequestId());
        assertEquals("GET", ctx.getMethod());
        assertEquals("/app/documents/1", ctx.getPath());
        assertEquals(200, ctx.getStatus());
    }

    @AfterAll
    void stopGateway() {
        if (gatewayContext != null) {
            gatewayContext.close();
        }
    }
}
