package com.psw.gateway.controller;

import com.psw.common.enums.AlertSeverity;
import com.psw.common.enums.Services;
import com.psw.gateway.model.AlertRecord;
import com.psw.gateway.model.RequestContext;
import com.psw.gateway.utility.ExceptionParserUtil;
import feign.FeignException;
import feign.Request;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

@ControllerAdvice
public class FeignExceptionHandler {
    private final ExceptionParserUtil parser;

    public FeignExceptionHandler (ExceptionParserUtil parser) { this.parser = parser; }

    //General Feign cleanup handler
    @ExceptionHandler(FeignException.class)
    public void FeignCleanupHandler (FeignException e,
                                     HttpServletRequest request){
        RequestContext ctx = (RequestContext) request.getAttribute("context");
        Request feignRequest = e.request();
        ctx.setStatus(500);
        ctx.setResponseBody("Internal server error");
        ctx.getAlerts().add(new AlertRecord(
                logHeaderResolver(feignRequest),
                ctx.getRequestId(),
                null,
                Instant.now(),
                AlertSeverity.MAJOR,
                Services.GATEWAY,
                "Unknown exception thrown at downstream service",
                parser.exceptionParser(e)
        ));
    }

    private UUID logHeaderResolver (Request request) {
        Map<String, Collection<String>> headers = request.headers();
        if (headers.containsKey("X-log-ID"))
            return UUID.fromString(headers.get("X-log-ID").toArray()[0].toString());
        return UUID.randomUUID();
    }
}
