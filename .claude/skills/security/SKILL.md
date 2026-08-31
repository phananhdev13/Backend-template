---
name: security
description: Authentication and authorisation in this repo - where the check belongs, how to model permissions, handling secrets, and avoiding data leaks through errors and logs. Use when adding an endpoint that is not public, when a use case must be restricted to certain callers or tenants, when handling tokens or secrets, or when reviewing a change for security impact.
---

# Security

## The check belongs at the use case, not the controller

A controller-level check protects one entry point. The same use case reached from a message
listener, a scheduled job or another controller is then unprotected, and nothing about the code
says so.

```java
@UseCase(id = "UC-ORD-004", value = "An operator cancels an order")
@PreAuthorize("hasAuthority('ORDER_CANCEL')")
public class CancelOrderService implements CancelOrderUseCase { }
```

Authorisation is part of the use case's contract, so it belongs in the same place as its
transaction boundary. See [P-120](../../../docs/principles/P-120-security-at-use-case-boundary.md).

## Authorise on data, not only on role

Most real failures are not "could this role do this?" but "could this caller do this **to this
record**?". A permission check that passes for any order lets an authenticated customer read every
other customer's orders by changing an identifier.

Scope the query rather than filtering the result: load by `(orderId, callerId)` so a mismatch
returns nothing. Filtering after loading leaks through timing, through error messages, and through
the next developer who reuses the loader.

In a multi-tenant service, the tenant identifier comes from the authenticated principal, **never**
from the request body or a header a client controls.

## Failures must not distinguish what the caller may not know

Return 404 rather than 403 when the caller is not allowed to know a resource exists - otherwise the
status code becomes an enumeration oracle. Authentication failures should not reveal whether the
account exists.

Use `ErrorKind.UNAUTHENTICATED` and `ErrorKind.FORBIDDEN`; `libs/web-support` maps them and, for
unexpected exceptions, returns a body containing nothing at all. Never let an exception message,
stack frame or SQL fragment reach a client.

## Input

Validate at the edge with Jakarta Validation, and make the domain refuse invalid values anyway - a
value object that cannot hold a bad value removes the check from every path at once
([P-021](../../../docs/principles/P-021-illegal-states-unrepresentable.md)).

Parameterised queries only. Never build SQL or JPQL by concatenation, including for sort columns
and dynamic filters - allowlist those against a fixed set instead.

## Secrets

Never in the repository, never in `application.yml`, never in a log line, never in an exception
message. They arrive as environment variables or from a secret manager. `.gitignore` covers
`.env`, `*.p12` and `*.jks`, and `.claude/settings.json` denies reading them.

If a secret is committed, rotating it is the fix. Removing it from history is cleanup, not
remediation - assume it is already copied.

## Dependencies

`mvn verify -Pdeep-analysis` runs the deeper static analysis; dependency vulnerability scanning runs
in CI. A finding in a transitive dependency is fixed by upgrading the direct dependency that pulls
it, not by pinning the transitive one - pinning silently diverges from what the direct dependency
was tested against.

## Before merging anything security-relevant

- [ ] the check is on the use case, and covers the specific record, not only the role
- [ ] the tenant or owner identifier comes from the principal, not the request
- [ ] errors reveal nothing about existence the caller may not know
- [ ] no personal data, token or secret in logs or in a problem response
- [ ] new endpoints are authenticated by default; public ones are deliberate and listed
