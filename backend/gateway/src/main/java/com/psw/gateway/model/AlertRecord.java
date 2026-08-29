package com.psw.gateway.model;

import com.psw.common.enums.AlertSeverity;
import com.psw.common.enums.Services;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AlertRecord(
        UUID logId,
        UUID requestId,
        Long incidentId,
        Instant timestamp,
        AlertSeverity severity,
        Services sourceComponent,
        String description,
        Map<String, Object> debuggingData
) {}
