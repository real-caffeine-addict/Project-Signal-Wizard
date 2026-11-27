package com.psw.gateway.controller;

import com.psw.common.enums.AlertSeverity;
import com.psw.common.enums.Services;
import com.psw.gateway.exceptions.MissingRequestContextException;
import com.psw.gateway.model.AlertRecord;
import com.psw.gateway.model.RequestContext;
import com.psw.gateway.utility.ExceptionParserUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.UUID;

@ControllerAdvice
public class GwExceptionHandler {
    private final ExceptionParserUtil parser;

    public GwExceptionHandler (ExceptionParserUtil parser) { this.parser = parser; }

    @ExceptionHandler(NoResourceFoundException.class)
    public void unknownRoute(NoResourceFoundException e, HttpServletRequest request) {
        RequestContext ctx = (RequestContext) request.getAttribute("context");
        ctx.setStatus(404);
        ctx.setResponseBody("Not found");
    }

    @ExceptionHandler(MissingRequestContextException.class)
    public void missingRequestContextException (MissingRequestContextException e,
                                                HttpServletRequest request){
        RequestContext ctx = RequestContext.builder()
                .requestId(UUID.randomUUID())
                .peerIp(request.getRemoteAddr())
                .path(request.getRequestURI())
                .method(request.getMethod())
                .status(500)
                .responseBody("Internal server error")
                .startedAt(Instant.now())
                .build();

        AlertRecord alert = new AlertRecord(
                UUID.randomUUID(),
                ctx.getRequestId(),
                null,
                Instant.now(),
                AlertSeverity.CRITICAL,
                Services.GATEWAY,
                "Missing request context at controller boundary",
                parser.exceptionParser(e)
        );
        ctx.getAlerts().add(alert);
        request.setAttribute("context", ctx);
    }

    // Global cleanup for GW originated unexpected errors
    @ExceptionHandler(Exception.class)
    public void GwCleanupHandler (Exception e,
                                  HttpServletRequest request){
        RequestContext ctx = (RequestContext) request.getAttribute("context");
        ctx.setStatus(500);
        ctx.setResponseBody("Internal server error");
        ctx.getAlerts().add(new AlertRecord(
                UUID.randomUUID(),
                ctx.getRequestId(),
                null,
                Instant.now(),
                AlertSeverity.MAJOR,
                Services.GATEWAY,
                "Unknown exception thrown",
                parser.exceptionParser(e)
        ));
    }
}
