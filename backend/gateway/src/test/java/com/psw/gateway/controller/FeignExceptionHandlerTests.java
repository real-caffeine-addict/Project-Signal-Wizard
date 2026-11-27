package com.psw.gateway.controller;

import com.psw.common.enums.AlertSeverity;
import com.psw.common.enums.Services;
import com.psw.gateway.model.RequestContext;
import com.psw.gateway.utility.ExceptionParserUtil;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FeignExceptionHandlerTests {
    private final FeignExceptionHandler handler = new FeignExceptionHandler(new ExceptionParserUtil());

    @Test
    void downstreamFailureRetainsContextAndProvidedLogReference() {
        UUID logId = UUID.randomUUID();
        MockHttpServletRequest request = request();
        RequestContext context = (RequestContext) request.getAttribute("context");

        handler.FeignCleanupHandler(failure(Map.of("X-log-ID", List.of(logId.toString()))), request);

        assertThat(request.getAttribute("context")).isSameAs(context);
        assertThat(context.getStatus()).isEqualTo(500);
        assertThat(context.getResponseBody()).isEqualTo("Internal server error");
        assertThat(context.getAlerts()).singleElement().satisfies(alert -> {
            assertThat(alert.logId()).isEqualTo(logId);
            assertThat(alert.requestId()).isEqualTo(context.getRequestId());
            assertThat(alert.severity()).isEqualTo(AlertSeverity.MAJOR);
            assertThat(alert.sourceComponent()).isEqualTo(Services.GATEWAY);
            assertThat(alert.description()).isEqualTo("Unknown exception thrown at downstream service");
            assertThat(alert.debuggingData()).containsEntry("depth", 1);
        });
    }

    @Test
    void downstreamFailureWithoutLogHeaderReceivesNewCorrelatedLogId() {
        MockHttpServletRequest request = request();
        RequestContext context = (RequestContext) request.getAttribute("context");

        handler.FeignCleanupHandler(failure(Map.of()), request);

        assertThat(context.getAlerts()).singleElement().satisfies(alert -> {
            assertThat(alert.logId()).isNotNull();
            assertThat(alert.requestId()).isEqualTo(context.getRequestId());
            assertThat(alert.incidentId()).isNull();
        });
        assertThat(context.getStatus()).isEqualTo(500);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/documents/41");
        request.setAttribute("context", RequestContext.builder().requestId(UUID.randomUUID())
                .status(200).responseBody("partial result").build());
        return request;
    }

    private FeignException failure(Map<String, Collection<String>> headers) {
        Request request = Request.create(Request.HttpMethod.GET, "http://documents/documents/41",
                headers, null, StandardCharsets.UTF_8);
        return FeignException.errorStatus("DocumentsClient#getDocumentById", Response.builder()
                .request(request).status(503).reason("Unavailable")
                .body("downstream failure", StandardCharsets.UTF_8).build());
    }
}
