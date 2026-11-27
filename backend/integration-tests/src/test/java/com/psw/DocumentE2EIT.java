package com.psw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.testcontainers.mariadb.MariaDBContainer;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DocumentE2EIT {

    private static final String BASE_URL = "http://127.0.0.1:18000";

    private static final int AUDIT_PORT = 18004;
    private static final int DOCUMENTS_PORT = 18003;
    private static final int GATEWAY_PORT = 18000;

    private static final Pattern DOCUMENT_ID_PATTERN =
            Pattern.compile("document id:\\s*(\\d+)");

    private final Path configPath = Path.of(
            Objects.requireNonNull(
                    getClass().getResource("/e2e.yaml")
            ).toURI()
    );

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    private MariaDBContainer testDb;

    private Process audit;
    private Process documents;
    private Process gw;

    private TestDbDetails testDbDetails;
    private Path gatewayLog;

    public DocumentE2EIT() throws URISyntaxException {
    }

    private record TestDbDetails(
            String url,
            String username,
            String password
    ) {}

    // =========================================================
    // STARTUP
    // =========================================================

    @BeforeAll
    void startup() throws Exception {
        try {
            testDbDetails = startTestDb();

            startAudit();
            waitForServiceReady(
                    audit,
                    AUDIT_PORT,
                    "flyway_schema_history_audit"
            );

            startDocuments();
            waitForServiceReady(
                    documents,
                    DOCUMENTS_PORT,
                    "flyway_schema_history_documents"
            );

            startGateway();
            waitForServiceReady(
                    gw,
                    GATEWAY_PORT,
                    null
            );

        } catch (Exception startupError) {
            try {
                shutdown();
            } catch (Exception cleanupError) {
                startupError.addSuppressed(cleanupError);
            }
            throw startupError;
        }
    }

    private TestDbDetails startTestDb() {
        testDb = new MariaDBContainer("mariadb:11.4")
                .withDatabaseName("psw_e2e")
                .withUsername("psw_test")
                .withPassword("testPassword");

        testDb.start();

        return new TestDbDetails(
                testDb.getJdbcUrl(),
                testDb.getUsername(),
                testDb.getPassword()
        );
    }

    // =========================================================
    // PROCESS STARTUP
    // =========================================================

    private void startAudit() throws IOException {
        ProcessBuilder builder = createServiceBuilder(
                "audit-service"
        );

        builder.redirectErrorStream(true);
        builder.redirectOutput(Files.createTempFile("psw-e2e-audit-", ".log").toFile());

        audit = builder.start();
    }

    private void startDocuments() throws IOException {
        ProcessBuilder builder = createServiceBuilder(
                "documents-service"
        );

        builder.redirectErrorStream(true);
        builder.redirectOutput(Files.createTempFile("psw-e2e-documents-", ".log").toFile());

        documents = builder.start();
    }

    private void startGateway() throws IOException {
        ProcessBuilder builder = createServiceBuilder(
                "gateway"
        );

        gatewayLog = Files.createTempFile(
                "psw-e2e-gateway-",
                ".log"
        );

        builder.redirectErrorStream(true);
        builder.redirectOutput(gatewayLog.toFile());

        gw = builder.start();
    }

    private ProcessBuilder createServiceBuilder(String serviceName) {
        Path jar = Path.of(
                "..",
                serviceName,
                "target",
                serviceName + "-0.0.1-SNAPSHOT-exec.jar"
        ).toAbsolutePath().normalize();

        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException(
                    "Service JAR not found: " + jar
            );
        }

        String javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                "java"
        ).toString();

        ProcessBuilder builder = new ProcessBuilder(
                javaExecutable,
                "-jar",
                jar.toString()
        );

        Map<String, String> env = builder.environment();

        env.put("PSW_CONFIG_FILE", configPath.toString());

        env.put("PSW_E2E_DB_URL", testDbDetails.url());
        env.put("PSW_E2E_DB_USER", testDbDetails.username());
        env.put("PSW_E2E_DB_PASSWORD", testDbDetails.password());

        return builder;
    }

    // =========================================================
    // READINESS
    // =========================================================

    private void waitForServiceReady(
            Process process,
            int port,
            String flywayTable
    ) throws InterruptedException {

        Exception lastError = null;

        for (int attempt = 1; attempt <= 3; attempt++) {

            // First check after 10 seconds.
            // Retries after 20 and 30 seconds.
            Thread.sleep(10_000);

            if (process == null || !process.isAlive()) {
                throw new IllegalStateException(
                        "Service on port " + port
                                + " terminated during startup"
                );
            }

            boolean dbReady = flywayTable == null;

            if (flywayTable != null) {
                try {
                    dbReady = isFlywayReady(flywayTable);
                } catch (SQLException e) {
                    lastError = e;
                }
            }

            boolean httpReady = false;

            try {
                httpReady = port == GATEWAY_PORT
                        ? isGatewayReady()
                        : isHttpReady(port);
            } catch (IOException e) {
                lastError = e;
            }

            if (dbReady && httpReady) {
                return;
            }

            System.out.printf(
                    "Service port %d not ready. Attempt %d/3%n",
                    port,
                    attempt
            );
        }

        throw new IllegalStateException(
                "Service on port " + port
                        + " failed readiness checks after 3 attempts",
                lastError
        );
    }

    private boolean isGatewayReady()
            throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/app/health-check"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();

        HttpResponse<Void> response = client.send(
                request,
                HttpResponse.BodyHandlers.discarding()
        );

        return response.statusCode() == 204;
    }

    private boolean isFlywayReady(String historyTable)
            throws SQLException {

        // Only internally defined table names are passed here.
        String sql =
                "SELECT COUNT(*) FROM " + historyTable
                        + " WHERE success = 1";

        try (
                Connection connection = openConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)
        ) {
            return result.next() && result.getInt(1) > 0;
        }
    }

    private boolean isHttpReady(int port)
            throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(
                        "http://127.0.0.1:" + port + "/"
                ))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();

        HttpResponse<Void> response = client.send(
                request,
                HttpResponse.BodyHandlers.discarding()
        );

        // A 404 is acceptable: the web server responded.
        int status = response.statusCode();

        return status >= 200 && status < 500;
    }

    // =========================================================
    // TESTS
    // =========================================================

    @Test
    void createDocumentPersistsDocumentRevisionAndAudit()
            throws Exception {

        String documentNumber = uniqueDocumentNumber();

        long logsBefore = countRequestLogs(
                "POST",
                "/app/documents"
        );

        long documentId = createDocument(documentNumber);

        assertDocumentPersisted(documentId, documentNumber);
        assertRevisionPersisted(documentId);
        assertAuditPersisted(documentId);

        assertEquals(
                logsBefore + 1,
                countRequestLogs("POST", "/app/documents"),
                "Gateway must log the POST request exactly once"
        );

        assertRequestLogExists(
                "POST",
                "/app/documents",
                201
        );
    }

    @Test
    void readDocumentThroughGateway() throws Exception {

        // Independent fixture: this test does not depend
        // on createDocumentPersistsDocumentRevisionAndAudit.
        String documentNumber = uniqueDocumentNumber();
        long documentId = createDocument(documentNumber);

        String path = "/app/documents/" + documentId;

        long logsBefore = countRequestLogs("GET", path);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());

        JsonNode body = mapper.readTree(response.body());

        assertEquals(documentId, body.path("id").asLong());
        assertEquals(
                documentNumber,
                body.path("documentNumber").asText()
        );
        assertEquals(
                "DRAWING",
                body.path("type").asText()
        );

        assertEquals(
                logsBefore + 1,
                countRequestLogs("GET", path),
                "Gateway must log the GET request exactly once"
        );

        assertRequestLogExists("GET", path, 200);
    }

    // =========================================================
    // HTTP HELPERS
    // =========================================================

    private long createDocument(String documentNumber)
            throws Exception {

        String payload = """
                {
                  "projectId": 1,
                  "dossierId": 1,
                  "title": "E2E document",
                  "documentNumber": "%s",
                  "type": "DRAWING",
                  "originatingCompanyId": 1,
                  "revisionCode": "A",
                  "status": "DRAFT",
                  "issuedAt": "2026-11-20T10:00:00"
                }
                """.formatted(documentNumber);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/app/documents"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(
                201,
                response.statusCode(),
                "Document creation failed: " + response.body()
        );

        Matcher matcher = DOCUMENT_ID_PATTERN.matcher(
                response.body()
        );

        assertTrue(
                matcher.find(),
                "Document ID not found in response: "
                        + response.body()
        );

        return Long.parseLong(matcher.group(1));
    }

    private String uniqueDocumentNumber() {
        return "E2E-" + UUID.randomUUID()
                .toString()
                .substring(0, 8);
    }

    // =========================================================
    // DATABASE ASSERTIONS
    // =========================================================

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                testDbDetails.url(),
                testDbDetails.username(),
                testDbDetails.password()
        );
    }

    private void assertDocumentPersisted(
            long documentId,
            String documentNumber
    ) throws SQLException {

        String sql = """
                SELECT document_number, type, current_revision_id
                FROM documents
                WHERE id = ?
                """;

        try (
                Connection connection = openConnection();
                PreparedStatement statement =
                        connection.prepareStatement(sql)
        ) {
            statement.setLong(1, documentId);

            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "Document not persisted");

                assertEquals(
                        documentNumber,
                        result.getString("document_number")
                );

                assertEquals(
                        "DRAWING",
                        result.getString("type")
                );

                assertNotNull(
                        result.getObject("current_revision_id"),
                        "Current revision was not assigned"
                );

                assertFalse(
                        result.next(),
                        "Unexpected duplicate document ID"
                );
            }
        }
    }

    private void assertRevisionPersisted(long documentId)
            throws SQLException {

        String sql = """
                SELECT COUNT(*)
                FROM document_revisions r
                JOIN documents d
                  ON d.current_revision_id = r.id
                WHERE d.id = ?
                  AND r.document_id = d.id
                  AND r.revision_code = 'A'
                  AND r.status = 'DRAFT'
                """;

        assertEquals(
                1,
                countRows(sql, documentId),
                "Expected exactly one current document revision"
        );
    }

    private void assertAuditPersisted(long documentId)
            throws SQLException {

        String sql = """
                SELECT COUNT(*)
                FROM audit_events
                WHERE target_id = ?
                  AND target_type = 'DOCUMENT'
                  AND action = 'CREATED'
                  AND origin_service = 'documents-service'
                  AND project_id = 1
                  AND user_id = 1
                """;

        assertEquals(
                1,
                countRows(sql, documentId),
                "Expected exactly one audit event"
        );
    }

    private long countRows(String sql, long id)
            throws SQLException {

        try (
                Connection connection = openConnection();
                PreparedStatement statement =
                        connection.prepareStatement(sql)
        ) {
            statement.setLong(1, id);

            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    // =========================================================
    // GATEWAY LOG ASSERTIONS
    // =========================================================

    private long countRequestLogs(
            String method,
            String path
    ) throws IOException {

        return findRequestLogs(method, path).size();
    }

    private void assertRequestLogExists(
            String method,
            String path,
            int expectedStatus
    ) throws IOException {

        List<JsonNode> matchingLogs =
                findRequestLogs(method, path).stream()
                        .filter(node ->
                                node.path("status").asInt()
                                        == expectedStatus
                        )
                        .toList();

        assertFalse(
                matchingLogs.isEmpty(),
                "No matching Gateway request log found"
        );

        JsonNode lastLog = matchingLogs.getLast();

        String requestId = lastLog.path("requestId").asText();

        assertFalse(
                requestId.isBlank(),
                "Gateway request log is missing requestId"
        );

        assertDoesNotThrow(() -> UUID.fromString(requestId));
    }

    private List<JsonNode> findRequestLogs(
            String method,
            String path
    ) throws IOException {

        if (gatewayLog == null || !Files.exists(gatewayLog)) {
            return List.of();
        }

        try (var lines = Files.lines(gatewayLog)) {
            return lines
                    .map(this::parseJsonLog)
                    .filter(Objects::nonNull)
                    .filter(node ->
                            "logRequest".equals(
                                    node.path("logger_name").asText()
                            )
                    )
                    .filter(node ->
                            method.equals(
                                    node.path("method").asText()
                            )
                    )
                    .filter(node ->
                            path.equals(
                                    node.path("path").asText()
                            )
                    )
                    .toList();
        }
    }

    private JsonNode parseJsonLog(String line) {
        try {
            return mapper.readTree(line);
        } catch (Exception ignored) {
            // Spring startup logs are not necessarily JSON.
            return null;
        }
    }

    // =========================================================
    // SHUTDOWN
    // =========================================================

    @AfterAll
    void shutdown() throws InterruptedException {

        try {
            stopProcess(gw);
        } finally {
            try {
                stopProcess(documents);
            } finally {
                try {
                    stopProcess(audit);
                } finally {
                    if (testDb != null) {
                        testDb.stop();
                    }
                }
            }
        }
    }

    private void stopProcess(Process process)
            throws InterruptedException {

        if (process == null || !process.isAlive()) {
            return;
        }

        process.destroy();

        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();

            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Failed to terminate process: "
                                + process.pid()
                );
            }
        }
    }
}
