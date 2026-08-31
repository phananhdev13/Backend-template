# G-070 — Designing and changing an HTTP API

A published API is a contract whose other side you do not control. The full detail is in the
`api-design` skill.

## Shape

Resources are nouns; the operation is the method. Request and response records live beside the
controller, named `…Request` / `…Response`, and never leave the adapter package —
`NamingRules.edgeDataTypesStayInAdapters` enforces it. Returning the domain model couples every
client to your internal model and turns a refactor into a breaking change.

## Status codes are not chosen in the controller

The use case throws a `DomainException` carrying an `ErrorKind`; `DomainExceptionHandler` in
`libs/web-support` maps it. That is what keeps the same use case usable from a message listener,
where a 404 would mean nothing.

| Kind | Status |
|---|---|
| VALIDATION | 400 |
| UNAUTHENTICATED / FORBIDDEN | 401 / 403 |
| NOT_FOUND | 404 |
| CONFLICT | 409 |
| BUSINESS_RULE | 422 |
| RATE_LIMITED | 429 |
| DEPENDENCY_FAILURE / TIMEOUT | 502 / 504 |

## Errors are RFC 9457 problem documents

The exception's `code` becomes the `type` URI, which is the part clients branch on — so it is API
surface, and renaming one is breaking. `tools/check-error-codes.sh` diffs every raised code against
`contracts/errors/registry.json` so a rename cannot happen by accident.

The catch-all returns a 500 with nothing in it and logs the detail against the correlation id.

## Versioning

Header-based, `X-API-Version`, native to Spring MVC 4.1. Additive changes need no new version;
removing or renaming a field, tightening validation, or changing a type does. Run versions in
parallel and retire the old one when its traffic reaches zero — instrument per-version usage, or you
will never know.

## Retryable writes

Accept an `Idempotency-Key` header on any `POST` a client may retry, store it with the result, and
return the original response on a repeat. Without it, every network blip is a duplicate order.

## Pagination

Cursor-based. Offset pagination re-scans rows and skips or repeats items when data changes between
pages. Cap page size server-side.
