# ADR-0008 — RFC 9457 problem details as the single error representation

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-30 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

Error representation drifts faster than any other part of an API, because every error is written
under time pressure by someone thinking about the happy path. The observed end state is three
shapes in one service: `{"error": "..."}` from a hand-written catch block, `{"message": "..."}`
from a `@ResponseStatus` annotation, and Boot's default error body from everything nobody
handled. Clients then parse all three.

The deeper problem is where the mapping happens. A use case that throws
`ResponseStatusException` has quietly become a web controller. It works until that use case is
reached from a Kafka consumer or a scheduled job, at which point the HTTP status is meaningless
and the error handling is written a second time, differently.

The kernel already separates the two concerns. `DomainException` carries three things and no
transport: an `ErrorKind` (`VALIDATION`, `NOT_FOUND`, `CONFLICT`, `BUSINESS_RULE`,
`UNAUTHENTICATED`, `FORBIDDEN`, `DEPENDENCY_FAILURE`, `TIMEOUT`, `RATE_LIMITED`) chosen for
*what the caller should do about it*; a stable `code()` such as `order.already-cancelled`; and
`details()` naming the offending values. The message is for humans reading logs, never parsed.

RFC 9457 (obsoleting RFC 7807) standardises the wire shape — `type`, `title`, `status`,
`detail`, `instance` plus extension members, served as `application/problem+json` — and Spring
Framework 6 and later support it natively via `ProblemDetail`, so Boot 4.1 needs no library.

## Decision

**Every error leaving an HTTP edge is an RFC 9457 problem document, produced in exactly one
place.**

- Domain and application code throws `DomainException` subtypes — `ValidationException`,
  `NotFoundException`, `ConflictException`, `BusinessRuleViolation` — and never anything
  transport-shaped. `ResponseStatusException` in a `@UseCase` is a build failure.
- `libs/web-support` owns the single translation point: one `@RestControllerAdvice` that maps
  `ErrorKind` to an HTTP status and `DomainException` to a `ProblemDetail`. Every service
  inherits it by depending on `web-support`; no service writes its own handler.
- The mapping is fixed and total: `VALIDATION` → 400, `UNAUTHENTICATED` → 401, `FORBIDDEN` →
  403, `NOT_FOUND` → 404, `CONFLICT` → 409, `BUSINESS_RULE` → 422, `RATE_LIMITED` → 429,
  `DEPENDENCY_FAILURE` → 502, `TIMEOUT` → 504. An unmapped exception is 500 with no detail
  leaked.
- `type` is a stable URI derived from `code()`, resolving under the service's documented error
  catalogue. `code()` is part of the API: changing one breaks clients that branch on it.
- `details()` is serialised as RFC 9457 extension members, and the `correlationId` from
  `observability-support` is added on every response — so the identifier a client quotes in a
  support ticket is the one that finds the log line.
- Bean Validation failures are translated into the same shape with a `violations` extension
  array, not into Boot's default binding-error body.
- Nothing goes into `details()` that must not appear in a client response *and* a log line.

## Consequences

**Good** — One shape for clients to parse, and one place to change it. The domain never mentions
HTTP, so a use case behaves identically behind a controller, a message handler or a scheduled
job — each edge maps `ErrorKind` in its own vocabulary. `code()` gives clients something stable
to branch on that is neither a status code nor a message string.

**Bad** — 422 for business rule violations is a real interoperability risk: some clients and
proxies treat anything other than 400 in the 4xx range as unexpected, and the 400-versus-422
argument has no settled answer. We chose 422 because `VALIDATION` and `BUSINESS_RULE` are
genuinely different for the caller — one means "fix the request", the other means "the
request is fine and the answer is no" — and collapsing them loses that. Teams integrating
with strict clients will feel it. Separately, the error catalogue at `type` is a documentation
artifact that must be maintained or the URIs become 404s, which is worse than no URI.

**Neutral** — Boot's `spring.mvc.problemdetails.enabled` produces problem documents for
framework-level errors without our advice. We enable it, so unhandled framework errors share
the media type even though they do not carry a `code`.

## Alternatives considered

### A bespoke error envelope (`{"code", "message", "details"}`)

Simpler to explain, and free of the 400-versus-422 debate. It lost because it is a private
standard: every client integration starts by explaining it, no gateway or generated SDK
understands it, and it will be reinvented slightly differently in the next service. RFC 9457
gives the same information in a shape with a specification to point at.

### Boot's default `ErrorAttributes`, customised per service

Zero code and no advice class to maintain. Rejected because "per service" is the failure: the
customisation diverges the first time two teams need slightly different fields, and the
default body carries `timestamp`, `path` and `exception` — fields that either leak internals
or duplicate what the trace already knows.

### GraphQL-style errors: HTTP 200 with an errors array

Coherent, and correct for transports where partial success is normal. Rejected for REST
because it breaks every intermediary that reasons about status codes — caches, retries,
gateway circuit breakers, monitoring — all of which would see a service that never fails.

### Mapping exceptions to statuses with `@ResponseStatus` on each exception class

Idiomatic Spring and very little code. It lost because it puts an HTTP concept on a domain
type, which is exactly the coupling `com.acme.kernel.error` exists to avoid, and because the
annotation cannot produce a body — the extension members, `code` and correlation identifier
all still need an advice.

## Revisit when

An error must be returned for a transport where problem documents have no meaning — a gRPC or
GraphQL edge added to a service — at which point the `ErrorKind`-to-status table gains a
sibling rather than being replaced. Also revisit the 422 choice if a real client integration
demonstrably breaks on it; that is a mapping change in one file, and the evidence is worth
more than the argument.
