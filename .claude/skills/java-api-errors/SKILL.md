---
name: java-api-errors
description: Use whenever defining how a service responds to errors — exception types, validation failures, HTTP error bodies, problem details, error codes. Enforces one universal error contract (RFC 7807 Problem Details) across every service in the framework, plus Jakarta Validation conventions and the global exception-translation layer.
---

# API Error Contract — RFC 7807 + Validation

Every service in this framework returns errors in **one shape**, so any client (UI, mobile, another service) parses errors the same way regardless of which service produced them. That shape is **RFC 7807 Problem Details** with a small set of standard extensions.

## 1. The Response Shape — Always

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json
Trace-Id: 8af6d2c0…

{
  "type":     "https://errors.example.com/validation-failed",
  "title":    "Validation failed",
  "status":   400,
  "detail":   "Request body has 2 invalid fields.",
  "instance": "/v1/orders/abc-123",
  "code":     "ORDER_VALIDATION_FAILED",
  "traceId":  "8af6d2c0a3b1...",
  "tenantId": "acme",
  "errors": [
    { "field": "lines[0].qty",       "code": "min",      "message": "must be ≥ 1" },
    { "field": "customer.email",     "code": "email",    "message": "must be a valid email" }
  ]
}
```

Standard fields (RFC 7807): `type`, `title`, `status`, `detail`, `instance`.
Framework extensions: `code` (machine-readable error code), `traceId` (correlation), `tenantId` (when known), `errors[]` (per-field for validation).

**Never** invent a different shape per endpoint. Never return raw stack traces. Never return Spring's default `{"timestamp":...,"status":...,"error":...}` body.

## 2. Error Code Catalog

Every service maintains `src/main/resources/error-codes.md` (and the matching `enum ErrorCode`) listing every code it can emit:

```
ORDER_VALIDATION_FAILED   400  Request body failed validation.
ORDER_NOT_FOUND           404  No order with that id for this tenant.
ORDER_ALREADY_PLACED      409  Order is already placed; can't modify.
INVENTORY_RESERVATION_FAILED  503  Downstream inventory unavailable.
TENANT_UNRESOLVED         401  No active tenant could be resolved.
RATE_LIMIT_EXCEEDED       429  Per-tenant rate limit hit.
INTERNAL                  500  Unexpected error.
```

Codes are stable contracts; clients build behavior on them. **Never rename a code; add a new one and deprecate.**

## 3. The Global Exception Handler

One `@RestControllerAdvice` per service, in `api/error/`:

```java
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
class GlobalExceptionHandler {
    private final Tracer tracer;                 // OpenTelemetry

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.code(), ex.getMessage(), null);
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    ProblemDetail handleBusiness(BusinessRuleViolationException ex) {
        return problem(HttpStatus.CONFLICT, ex.code(), ex.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        var fields = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> Map.of(
                "field",   fe.getField(),
                "code",    fe.getCode(),
                "message", fe.getDefaultMessage()))
            .toList();
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
            "Request body has " + fields.size() + " invalid field(s).", fields);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleForbidden() {
        return problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Not allowed.", null);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("unhandled", ex);              // log full stack server-side
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL",
            "An unexpected error occurred.", null);   // no internals leaked to client
    }

    private ProblemDetail problem(HttpStatus s, String code, String detail, Object errors) {
        var pd = ProblemDetail.forStatusAndDetail(s, detail);
        pd.setType(URI.create("https://errors.example.com/" + code.toLowerCase().replace('_','-')));
        pd.setTitle(prettyTitleFor(code));
        pd.setProperty("code", code);
        pd.setProperty("traceId", currentTraceId());
        TenantContext.currentOptional().ifPresent(t -> pd.setProperty("tenantId", t.value().toString()));
        if (errors != null) pd.setProperty("errors", errors);
        return pd;
    }
}
```

Spring Boot 3+ has built-in `ProblemDetail` support — use it. The `application/problem+json` content type is set automatically when returning `ProblemDetail`.

## 4. Domain Exception Hierarchy

```java
public sealed abstract class DomainException extends RuntimeException
    permits ResourceNotFoundException, BusinessRuleViolationException, ValidationException, ExternalDependencyException {
    private final String code;
    protected DomainException(String code, String message)             { super(message);     this.code = code; }
    protected DomainException(String code, String message, Throwable t){ super(message, t);  this.code = code; }
    public String code() { return code; }
}
public final class ResourceNotFoundException extends DomainException { ... }
public final class BusinessRuleViolationException extends DomainException { ... }
```

Rules:
- Domain throws domain exceptions, **never** Spring or HTTP-specific exceptions.
- `code` is the stable contract; `message` is for humans/logs.
- **Never** `throw new RuntimeException("foo")` in business code — pick a concrete domain type.
- **Never** swallow exceptions (`catch(Exception e) { /* nothing */ }`) — at minimum log + metric + rethrow or translate.

## 5. Validation — Jakarta + Layered

Constraints belong on **DTOs**, not on JPA entities (which the controller never sees).

```java
public record PlaceOrderRequest(
    @NotNull UUID customerId,
    @NotEmpty @Size(max = 100) List<@Valid OrderLineRequest> lines,
    @Size(max = 255) String notes
) {
    public record OrderLineRequest(
        @NotNull UUID productId,
        @Min(1) @Max(1000) int qty
    ) {}
}
```

- `@Valid` on the controller arg triggers validation; `@RestControllerAdvice` translates `MethodArgumentNotValidException` (see §3).
- Constraint groups for create vs update (`Default.class`, `OnUpdate.class`).
- For cross-field rules that don't fit annotations, write a `@AssertTrue boolean isXValid()` method on the DTO, or a dedicated `Validator` bean.
- **Never** use `@Valid` on a JPA entity to validate persistence — DB constraints do that.

## 6. Error Code → HTTP Status Mapping

| Status | When                                                                          |
| ------ | ----------------------------------------------------------------------------- |
| 400    | Request shape is wrong (validation, malformed JSON, bad query param)          |
| 401    | Missing or invalid auth token                                                 |
| 403    | Token valid but caller lacks permission                                       |
| 404    | Resource does not exist for the active tenant                                 |
| 409    | Business rule violation that can be retried with different data (state transition, unique constraint) |
| 412    | Precondition failed (`If-Match`/ETag mismatch)                                |
| 422    | Semantically invalid (e.g., line items reference unknown products) — distinct from 400 (shape) |
| 429    | Rate limit                                                                    |
| 5xx    | Server-side or downstream issue. **Detail message must never include stack trace.** |

Prefer 422 over 400 when the *meaning* is wrong; 400 only for *shape*.

## 7. Idempotency Errors

When an `Idempotency-Key` is reused with a *different* request body:
- Status: `422 Unprocessable Entity`
- `code: "IDEMPOTENCY_KEY_CONFLICT"`
- `detail: "The provided Idempotency-Key was used with a different request body."`

When the same key + body comes again → return the **stored response verbatim** with `Idempotency-Replayed: true` header.

## 8. Downstream Errors — Translate, Don't Forward

If a downstream service returns a 503, your client must not return a 503 with their problem body. **Translate**:

```java
catch (ExternalDependencyException ex) {
    log.warn("inventory unavailable", ex);
    throw new BusinessRuleViolationException("INVENTORY_UNAVAILABLE",
        "Inventory service unavailable. Please retry.");
}
```

The client sees *your* contract. Internal failures stay internal.

## 9. Sensitive Data — Never in Errors

- No stack traces in `detail`.
- No SQL errors verbatim.
- No "user with email foo@bar.com not found" (account enumeration).
- No internal IDs from other tenants.
- For auth errors, return the same `401` body for "user not found" and "bad password" — don't reveal which.

## 10. Logging Errors

Every error logged exactly once, at the right level:

| Level | When                                                              |
| ----- | ----------------------------------------------------------------- |
| WARN  | 4xx caused by client input (validation, not-found, business rule) |
| ERROR | 5xx (your bug, downstream failure, unexpected exception)          |

```java
log.warn("validation_failed code={} traceId={} fields={}", code, traceId, fieldCount);
log.error("unhandled traceId={}", traceId, ex);    // pass throwable LAST for stack trace
```

**Never** log the request body if it may contain PII. Mask before logging. See `java-observability`.

## 11. Pre-Merge Checklist

- [ ] Every endpoint throws domain exceptions, never raw `RuntimeException`.
- [ ] No controller has its own `try/catch` mapping to HTTP status — that belongs in `GlobalExceptionHandler`.
- [ ] New error code added to `error-codes.md` + `ErrorCode` enum.
- [ ] Validation runs on DTOs, not entities.
- [ ] No leaked stack traces or PII in responses.
- [ ] OpenAPI spec updated to declare the error response.

## 12. Reference (deep dive)

- `.claude/skills/lib/jabrena/126-java-exception-handling/references/126-java-exception-handling.md` — exception types, try-with-resources, chaining, async failure, retry semantics, suppressed exceptions
- `.claude/skills/lib/jabrena/303-frameworks-spring-boot-validation/references/303-frameworks-spring-boot-validation.md` — Jakarta Validation deep dive, custom constraints, groups, programmatic validation
- `.claude/skills/lib/jabrena/302-frameworks-spring-boot-rest/references/302-frameworks-spring-boot-rest.md` — REST error body shape and `@ControllerAdvice` patterns
- `.claude/skills/lib/jabrena/143-java-functional-exception-handling/references/143-java-functional-exception-handling.md` — Result types / `Optional` over exceptions in pure domain logic
