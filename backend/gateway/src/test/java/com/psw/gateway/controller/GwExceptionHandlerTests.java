package com.psw.gateway.controller;

import com.psw.common.enums.AlertSeverity;
import com.psw.common.enums.Services;
import com.psw.gateway.exceptions.MissingRequestContextException;
import com.psw.gateway.model.AlertRecord;
import com.psw.gateway.model.RequestContext;
import com.psw.gateway.utility.ExceptionParserUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GwExceptionHandlerTests {
    private final GwExceptionHandler handler = new GwExceptionHandler(new ExceptionParserUtil());

    @Test
    void unknownRouteReturnsNotFoundWithoutAddingAnIncidentAlert() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/unknown-route");
        RequestContext context = RequestContext.builder().requestId(UUID.randomUUID()).build();
        request.setAttribute("context", context);

        handler.unknownRoute(new NoResourceFoundException(HttpMethod.GET, "/unknown-route", "/unknown-route"), request);

        assertThat(request.getAttribute("context")).isSameAs(context);
        assertThat(context.getStatus()).isEqualTo(404);
        assertThat(context.getResponseBody()).isEqualTo("Not found");
        assertThat(context.getAlerts()).isEmpty();
    }

    @Test
    void missingContextIsRebuiltWithRequestDetailsAndOneCorrelatedCriticalAlert() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/app/documents");
        request.setRemoteAddr("192.0.2.4");

        handler.missingRequestContextException(new MissingRequestContextException("context missing"), request);

        RequestContext context = (RequestContext) request.getAttribute("context");
        assertThat(context.getRequestId()).isNotNull();
        assertThat(context.getPath()).isEqualTo("/app/documents");
        assertThat(context.getMethod()).isEqualTo("POST");
        assertThat(context.getPeerIp()).isEqualTo("192.0.2.4");
        assertThat(context.getStartedAt()).isNotNull();
        assertThat(context.getStatus()).isEqualTo(500);
        assertThat(context.getResponseBody()).isEqualTo("Internal server error");
        assertThat(context.getAlerts()).singleElement().satisfies(alert -> {
            assertThat(alert.requestId()).isEqualTo(context.getRequestId());
            assertThat(alert.severity()).isEqualTo(AlertSeverity.CRITICAL);
            assertThat(alert.sourceComponent()).isEqualTo(Services.GATEWAY);
            assertThat(alert.description()).isEqualTo("Missing request context at controller boundary");
            assertThat(alert.debuggingData()).containsEntry("depth", 1);
        });
    }

    @Test
    void cleanupKeepsExistingContextAndAlertsWhileReplacingPartialSuccess() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/documents/41");
        RequestContext context = RequestContext.builder().requestId(UUID.randomUUID())
                .path(request.getRequestURI()).status(200).responseBody("partial result").build();
        AlertRecord earlier = new AlertRecord(UUID.randomUUID(), context.getRequestId(), null,
                Instant.now(), AlertSeverity.INFO, Services.GATEWAY, "earlier event", null);
        context.getAlerts().add(earlier);
        request.setAttribute("context", context);

        handler.GwCleanupHandler(new IllegalStateException("unexpected failure"), request);

        assertThat(request.getAttribute("context")).isSameAs(context);
        assertThat(context.getPath()).isEqualTo("/app/documents/41");
        assertThat(context.getStatus()).isEqualTo(500);
        assertThat(context.getResponseBody()).isEqualTo("Internal server error");
        assertThat(context.getAlerts()).hasSize(2).startsWith(earlier);
        assertThat(context.getAlerts().getLast()).satisfies(alert -> {
            assertThat(alert.requestId()).isEqualTo(context.getRequestId());
            assertThat(alert.severity()).isEqualTo(AlertSeverity.MAJOR);
            assertThat(alert.sourceComponent()).isEqualTo(Services.GATEWAY);
            assertThat(alert.description()).isEqualTo("Unknown exception thrown");
            assertThat(alert.debuggingData()).containsEntry("depth", 1);
        });
    }
}
