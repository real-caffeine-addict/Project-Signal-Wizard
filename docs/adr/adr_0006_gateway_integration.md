# ADR-0006: Gateway Integration

## Status

Accepted

## Context

Phase 1 is moving from Gateway infrastructure work into integration with real application endpoints.

The Gateway already owns the external HTTP boundary. Internal services are called through Feign and return normal internal HTTP responses.

The Gateway should not duplicate business response DTOs or become coupled to the response models of internal services when no transformation is required.

At the same time, internal service failures may contain technical or sensitive information that must never be returned directly to the client.

The integration strategy therefore needs a clear distinction between successful responses and error responses.

## Decision

For successful requests, the Gateway will act as a relay.

The internal service response will be treated as an opaque payload. The Gateway will forward the response status, approved headers, and body without interpreting or mapping the business JSON structure.

For failed requests, the Gateway will not relay the internal error response directly.

`GwControllerAdvice` will act as the error-disclosure boundary. It will separate internal diagnostic information from the client-facing response.

Detailed failure information will be retained for technical logging and incident handling, while the client will receive a controlled generic error message appropriate to the error category.

## Resolved Decisions

### 1. Successful Responses

Successful internal service responses will be handled as relay responses.

The Gateway does not need service-specific response DTOs when it does not inspect or transform the payload.

The expected flow is:

```text
Client
→ Gateway
→ Feign
→ Internal service
→ HTTP success response
→ Gateway relay
→ Client
```

The Gateway may copy:

* HTTP status;
* approved response headers;
* response body.

The response body may remain an opaque JSON string.

Business payload structure remains owned by the internal service.

### 2. Gateway Coupling to Business DTOs

The Gateway will not define or depend on business response DTOs solely for forwarding successful responses.

A typed response model should only be introduced in the Gateway if the Gateway later needs to:

* inspect business fields;
* transform the payload;
* combine responses;
* perform policy decisions based on response content;
* remove or add business fields.

Until such a requirement exists, opaque payload relay is preferred.

### 3. Error Responses

Internal error responses will not be forwarded directly to external clients.

`GwControllerAdvice` will translate internal failures into controlled external responses.

Example:

```text
Internal service:
Wrong password
Password fingerprint: ...

Gateway ControllerAdvice:
Client response:
Authentication failure

Technical log:
Wrong password
Password fingerprint: ...
```

The client-facing response must contain only information intentionally approved for external disclosure.

Detailed internal information remains available to technical logging and incident handling.

### 4. Error Disclosure Boundary

`GwControllerAdvice` is responsible for deciding what information crosses the external API boundary during failure handling.

Its responsibilities include:

* classifying the error;
* selecting the client-facing status;
* selecting a safe generic client message;
* preserving detailed internal information for logging;
* preventing accidental exposure of credentials, internal implementation details, stack traces, database errors, connector details, or other sensitive diagnostics.

### 5. Internal Service Responses

Internal services continue to behave as normal HTTP services toward the Gateway.

They may return structured success responses and detailed internal failures through their Feign-facing HTTP interface.

The restriction described in this ADR applies to what the Gateway exposes externally, not to the internal HTTP communication between services.

### 6. Response Headers

The Gateway will not blindly forward all internal response headers.

Only headers intentionally approved for external forwarding will be copied to the client response.

Internal, infrastructure-specific, security-sensitive, and hop-by-hop headers must remain internal.

## Consequences

### Positive

* The Gateway remains independent of internal business response models.
* Internal services retain ownership of their API payload structure.
* Successful request handling remains simple and low-coupling.
* Business DTO changes do not automatically require Gateway changes.
* Error disclosure is centralized in one explicit boundary.
* Detailed technical diagnostics can be retained without exposing them to clients.
* Sensitive internal failure information is less likely to leak through external responses.

### Risks

* Opaque relay means the Gateway cannot validate successful business payload semantics.
* Header forwarding requires an explicit allowlist or equivalent policy.
* Error classification in `GwControllerAdvice` must remain consistent as new failure types are introduced.
* Overly generic client errors may reduce usability if external error categories are defined too broadly.
* Overly detailed client errors may expose internal or sensitive information.
* If the Gateway later needs business-aware response transformation, typed response contracts may need to be introduced at that time.
