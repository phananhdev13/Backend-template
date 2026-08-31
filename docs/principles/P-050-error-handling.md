# P-050 — Failures carry domain meaning, not HTTP status

| | |
|---|---|
| **Layer** | cross-cutting |
| **Enforced by** | `ErrorRules.domainNeverThrowsWebExceptions()`, `ErrorRules.errorCodesAreStableAndUnique()` (not implemented), `checkstyle:IllegalCatch`, `checkstyle:RegexpSingleline` (printStackTrace) |
| **Annotations** | `@UseCase`, `@InboundAdapter`, `@OutboundAdapter` |
| **Guide** | [G-070](../guides/G-070-api.md) |

## Rule

Domain and application code throw `DomainException` subtypes carrying an `ErrorKind`, a
stable machine-readable `code` and structured `details`. Only `libs/web-support` maps
`ErrorKind` to an HTTP status, once, for every entry point.

## Why

**A use case that throws `ResponseStatusException` has become a web controller.** It works
until the same use case is reached from a Kafka handler, where the status code means
nothing, or from a scheduled job, where the framework that would have translated it is not
in the call stack. What the consumer gets instead is a 500-shaped stack trace in a log, and
the actual failure — "this order was already cancelled" — is lost.

**Status codes are a lossy encoding of what went wrong.** `409` covers a stale version, a
duplicate idempotency key and a uniqueness violation; a client that must retry the first,
ignore the second and surface the third cannot tell them apart. That is what `code` is for:
`order.stale-version`, `order.duplicate-request`, `order.sku-not-unique`. Because clients
branch on it, the code is part of the API — renaming one breaks a caller exactly as
renaming a JSON field would, and `ErrorRules` treats it accordingly.

**`ErrorKind` is chosen for what the caller should do, not for taxonomy.** The distinctions
that matter are: retry unchanged (`TIMEOUT`, `DEPENDENCY_FAILURE`, `RATE_LIMITED`), fix the
request (`VALIDATION`), give up (`NOT_FOUND`, `FORBIDDEN`, `BUSINESS_RULE`), or retry later
(`CONFLICT`). `TIMEOUT` is the subtle one and deserves its own kind because the work may
have happened — a caller that treats it as failure and retries without an idempotency key
double-charges ([P-071](P-071-idempotency.md)).

**Catching broadly destroys the information.** `catch (Exception ex) { throw new
RuntimeException("failed"); }` erases the kind, the code and the details, and replaces a
409 with a 500 — which pages someone. Catching `Throwable` additionally swallows
`OutOfMemoryError` and `StackOverflowError`, so a JVM in an unrecoverable state keeps
serving traffic and returning wrong answers. Checkstyle rejects both.

**`details` crosses two boundaries at once.** It is serialised to the client and written to
logs, so it must never carry a card number, a token or an email address. This is the most
common way personal data escapes into log storage where it is retained for a year and is not
covered by any erasure process.

## In code

Thrown where the knowledge is — inside the aggregate:

```java
@AggregateRoot
public final class Order {

    public Order cancel(CancellationReason reason, Clock clock) {
        if (status == OrderStatus.SHIPPED) {
            throw new BusinessRuleViolation(
                    "order.already-shipped",
                    "Order %s has shipped and cannot be cancelled".formatted(id.value()),
                    Map.of("orderId", id.value().toString(), "status", status.name()));
        }
        …
    }
}
```

Translated at the edge, once, by shared infrastructure:

```java
package com.acme.support.web;

@RestControllerAdvice
public class DomainExceptionHandler {

    @ExceptionHandler(DomainException.class)
    ProblemDetail handle(DomainException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(statusFor(ex.kind()), ex.getMessage());
        problem.setType(URI.create("https://errors.acme.com/" + ex.code()));
        problem.setProperty("code", ex.code());
        problem.setProperty("details", ex.details());
        problem.setProperty("traceId", Span.current().getSpanContext().getTraceId());
        return problem;
    }

    private static HttpStatus statusFor(ErrorKind kind) {
        return switch (kind) {                       // exhaustive: a new kind breaks the build here
            case VALIDATION          -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND           -> HttpStatus.NOT_FOUND;
            case CONFLICT            -> HttpStatus.CONFLICT;
            case BUSINESS_RULE       -> HttpStatus.UNPROCESSABLE_ENTITY;
            case UNAUTHENTICATED     -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN           -> HttpStatus.FORBIDDEN;
            case DEPENDENCY_FAILURE  -> HttpStatus.BAD_GATEWAY;
            case TIMEOUT             -> HttpStatus.GATEWAY_TIMEOUT;
            case RATE_LIMITED        -> HttpStatus.TOO_MANY_REQUESTS;
        };
    }
}
```

Adapters translate technology failures inwards rather than letting them through:

```java
} catch (DataIntegrityViolationException ex) {
    throw new ConflictException("order.duplicate-reference",
            "An order with this reference already exists",
            Map.of("reference", reference.value()));
}
```

Wrong — three losses in one line:

```java
@Transactional
public OrderId place(PlaceOrderCommand command) {
    try {
        …
    } catch (Exception ex) {                                  // kind, code and details erased
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "could not place order");
    }                                                          // and HTTP in the application layer
}
```

## Enforcement

`ErrorRules.domainNeverThrowsWebExceptions()` fails any `Layer.DOMAIN` or
`Layer.APPLICATION` class referencing `ResponseStatusException`, `HttpStatus`,
`ErrorResponseException` or `ProblemDetail`:

```
com.acme.orders.ordering.application.PlaceOrderUseCase throws
org.springframework.web.server.ResponseStatusException. Application code states what
went wrong (DomainException + ErrorKind); libs/web-support decides the status.
See docs/principles/P-050-error-handling.md
```

`ErrorRules.errorCodesAreStableAndUnique()` (not implemented) collects every `DomainException` code literal,
fails on duplicates across modules, and diffs against `contracts/errors/registry.json` so a
renamed code is a deliberate, reviewed API change ([P-080](P-080-api-versioning.md)).

Checkstyle `IllegalCatch` rejects `catch (Throwable | Error)` with the message *"Catching
''{0}'' hides JVM failures. Catch the exception you can actually handle."*, and the
`printStackTrace` regexp rejects the other way of losing an exception.

## Deviating

Framework-level concerns legitimately throw framework exceptions: a security filter, a
content negotiation failure, a servlet-level rejection. That code lives in adapters or in
`libs/web-support`, where HTTP is the vocabulary.

A third-party SDK whose exception hierarchy you must propagate for a legal audit trail can
be wrapped rather than translated — put the original in `cause`, and say why in an `@Adr`.
