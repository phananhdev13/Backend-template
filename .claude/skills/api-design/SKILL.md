---
name: api-design
description: Design and change HTTP APIs in this repo - resource shape, status codes, RFC 9457 problem responses, pagination, idempotent writes, and Spring MVC 4.1's native API versioning. Use when adding or changing a controller or endpoint, choosing a status code, deciding how to evolve a published API without breaking clients, or writing an OpenAPI contract.
---

# HTTP APIs

A published API is a contract you no longer control the other side of. Design for the change you
will need to make later.

## Shape

Resources are nouns; the operation is the method. `POST /orders`, `GET /orders/{id}`,
`POST /orders/{id}/cancellation` for a state change that is not a plain update.

Request and response records live beside the controller, named `…Request` / `…Response`, and never
leave the adapter package - `NamingRules.edgeDataTypesStayInAdapters` enforces that. Returning the
domain model directly couples every client to your internal model and turns a refactor into a
breaking change.

## Status codes

| | |
|---|---|
| 200 / 201 | success; 201 with a `Location` header when something was created |
| 202 | accepted, work happens later - say where to look |
| 400 | malformed. Retrying unchanged will fail again |
| 401 / 403 | not identified / not permitted |
| 404 | absent, or the caller may not know it exists |
| 409 | valid but conflicts with current state - stale version, duplicate key |
| 422 | well-formed and understood, but a business rule forbids it |
| 429 | back off; include `Retry-After` |
| 502 / 504 | a dependency failed or timed out - not the caller's fault |

Controllers do not choose these. The use case throws a `DomainException` carrying an `ErrorKind`,
and `DomainExceptionHandler` in `libs/web-support` maps it. That is what keeps the same use case
usable from a message listener. See [P-050](../../../docs/principles/P-050-error-handling.md).

## Errors are RFC 9457 problem documents

```json
{
  "type": "https://errors.acme.example/order.already-cancelled",
  "title": "Conflict",
  "status": 409,
  "detail": "Order 8f2c… was cancelled at 2026-08-30T10:14:22Z",
  "instance": "/orders/8f2c…",
  "correlationId": "01J…"
}
```

The `code` on the exception becomes the `type` URI and is the part clients branch on - so it is API
surface, and renaming one is a breaking change. `detail` is for humans and may change freely.

Never leak an exception class, a stack trace or a SQL fragment. The handler's catch-all returns a
500 with nothing in it and logs the detail against the correlation id, so support can join the two
without the client seeing your internals.

## Versioning

Spring MVC 4.1 supports versioning natively. This repo uses a request header, `X-API-Version`:

```yaml
spring:
  mvc:
    apiversion:
      default: 1
      use:
        header: X-API-Version
```

```java
@GetMapping(path = "/{id}", version = "2")
OrderResponse getV2(@PathVariable String id) { … }
```

**Additive changes need no new version**: a new optional field, a new endpoint, a new enum value
clients are told to tolerate. Everything else does - removing or renaming a field, tightening
validation, changing a type or the meaning of a value.

Run versions in parallel and remove the old one when its traffic reaches zero, not when the new one
ships. Instrument per-version usage or you will never know.

## Writes that can be retried

Any client that retries a `POST` needs you to accept an idempotency key:

```
POST /orders
Idempotency-Key: 6f1b…
```

Store the key with the result and return the original response on a repeat. Without this, every
network blip between client and service is a duplicate order. The same reasoning as
[P-071](../../../docs/principles/P-071-idempotency.md), one hop earlier.

## Pagination

Cursor-based, not offset. Offset pagination re-scans rows and skips or repeats items when the
underlying data changes between pages. Return `nextCursor` and let the client pass it back; always
cap page size server-side.

## Contract

`springdoc-openapi` generates the specification from the controllers. Keep it generated - a
hand-maintained spec is wrong within a month. Consumers in other repositories generate from the
published contract, not from reading your source.
