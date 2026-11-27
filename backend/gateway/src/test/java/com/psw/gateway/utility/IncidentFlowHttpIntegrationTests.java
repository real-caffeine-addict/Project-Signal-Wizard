package com.psw.gateway.utility;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psw.common.dto.IncidentDto;
import com.psw.common.enums.AlertSeverity;
import com.psw.common.enums.Services;
import com.psw.gateway.GatewayApplication;
import com.psw.gateway.model.AlertRecord;
import com.psw.gateway.model.RequestContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = GatewayApplication.class, properties = {
        "spring.config.name=incident-http-test",
        "server.port=0", "spring.cloud.compatibility-verifier.enabled=false",
        "routes.documents=http://127.0.0.1:9"
})
class IncidentFlowHttpIntegrationTests {
    private static HttpServer server;
    private static final AtomicReference<String> body = new AtomicReference<>();
    private static final AtomicReference<String> method = new AtomicReference<>();
    private static final AtomicReference<String> contentType = new AtomicReference<>();
    private static volatile int status = 200;
    private static volatile String response = "42";

    @Autowired
    private GwLogger logger;
    @Autowired
    private ObjectMapper mapper;
    private final ListAppender<ILoggingEvent> requestLogs = new ListAppender<>();
    private final ListAppender<ILoggingEvent> alertLogs = new ListAppender<>();

    @DynamicPropertySource
    static void startIsolatedHttpEndpoint(DynamicPropertyRegistry properties) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/incidents", exchange -> {
            try (exchange) {
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                method.set(exchange.getRequestMethod());
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                if (status == 0) return; // Close without an HTTP response to simulate a broken connection.
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        properties.add("routes.incident", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @BeforeEach
    void captureLogsAndResetEndpoint() {
        status = 200;
        response = "42";
        body.set(null);
        requestLogs.start();
        alertLogs.start();
        requestLogger().addAppender(requestLogs);
        alertLogger().addAppender(alertLogs);
    }

    @AfterEach
    void detachLogs() {
        requestLogger().detachAppender(requestLogs);
        alertLogger().detachAppender(alertLogs);
    }

    @AfterAll
    static void stopEndpoint() {
        if (server != null) server.stop(0);
    }

    @Test
    void realFeignRequestPreservesIncidentFieldsAndCorrelatesReturnedId() throws Exception {
        AlertRecord alert = alert();

        Long id = logger.logRequest(context(alert));

        assertThat(id).isEqualTo(42L);
        assertThat(method.get()).isEqualTo("POST");
        assertThat(contentType.get()).startsWith("application/json");
        assertThat(mapper.readValue(body.get(), IncidentDto.class)).isEqualTo(new IncidentDto(
                alert.logId().toString(), alert.requestId().toString(), alert.timestamp(), "MAJOR",
                Services.GATEWAY.name(), alert.description(), alert.debuggingData().toString()));
        assertThat(requestLogs.list).hasSize(1);
        assertThat(alertLogs.list).hasSize(1);
        assertThat(argument(alertLogs.list.getFirst()))
                .contains(alert.requestId().toString(), alert.logId().toString(), "incidentId=42");
        assertThat(argument(requestLogs.list.getFirst())).contains(alert.requestId().toString());
    }

    @Test
    void httpFailureStillProducesCorrelatedFallbackLogsWithoutIncidentId() {
        status = 500;
        response = "{\"error\":\"database unavailable\"}";

        assertFallbackLogs();
    }

    @Test
    void brokenConnectionStillProducesCorrelatedFallbackLogsWithoutIncidentId() {
        status = 0;

        assertFallbackLogs();
    }

    @Test
    void emptySuccessfulResponseDoesNotInventIncidentId() {
        response = "";
        AlertRecord alert = alert();

        assertThat(logger.logRequest(context(alert))).isNull();

        assertThat(requestLogs.list).hasSize(1);
        assertThat(alertLogs.list).hasSize(1);
        assertThat(argument(alertLogs.list.getFirst()))
                .contains(alert.requestId().toString(), "incidentId=null");
    }

    private void assertFallbackLogs() {
        AlertRecord alert = alert();

        assertThat(logger.logRequest(context(alert))).isNull();

        assertThat(requestLogs.list).hasSize(1);
        assertThat(alertLogs.list).hasSize(1);
        assertThat(argument(requestLogs.list.getFirst())).contains(alert.requestId().toString());
        assertThat(argument(alertLogs.list.getFirst()))
                .contains(alert.requestId().toString(), "Failed to log incident", "incidentId=null");
    }

    private AlertRecord alert() {
        return new AlertRecord(UUID.randomUUID(), UUID.randomUUID(), null,
                Instant.parse("2026-07-01T12:30:00Z"), AlertSeverity.MAJOR,
                Services.GATEWAY, "Downstream service is unavailable", Map.of("diagnostic", "שרטוט\nsecond line"));
    }

    private RequestContext context(AlertRecord alert) {
        return RequestContext.builder().requestId(alert.requestId()).peerIp("127.0.0.1")
                .importantHeaders(Map.of()).otherHeaders(Map.of()).path("/http-test").method("GET")
                .status(503).startedAt(Instant.now()).elapsed(1L)
                .alerts(new ArrayList<>(List.of(alert))).build();
    }

    private String argument(ILoggingEvent event) { return event.getArgumentArray()[0].toString(); }
    private Logger requestLogger() { return (Logger) LoggerFactory.getLogger("logRequest"); }
    private Logger alertLogger() { return (Logger) LoggerFactory.getLogger("alertLogger"); }
}
