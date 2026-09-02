# P-080 — APIs are versioned contracts

| | |
|---|---|
| **Layer** | adapter |
| **Enforced by** | `NamingRules.edgeDataTypesStayInAdapters()`, `EventContractRules.everyContractHasASchemaFile()` in `libs/arch-test`; `tools/check-error-codes.sh` in CI; each service's own `OpenApiContractTest` diffs its generated OpenAPI document against `contracts/api/`. Judging whether a change is additive is still _review only_ |
| **Annotations** | `@InboundAdapter`, `@PublicApi`, `@EventContract` |
| **Guide** | [G-070](../guides/G-070-api.md) |

## Rule

Every externally reachable surface — REST path, event stream, error code — carries a version
and its own DTO types. Within a version, changes are additive only. Anything else is a new
version served in parallel until the old one has no callers.

## Why

**You cannot deploy your clients.** A mobile app installed on a phone that has not been
opened in eight months will one day make a request against whatever is live. A partner's
integration was written once, passed acceptance testing, and has not been touched since.
Removing a field, tightening a validation, or renaming an enum constant breaks those callers
at a moment you did not choose and cannot roll back, because the failing code is not yours.

**"Additive only" has a precise definition, and the violations are subtle.** Adding an
optional response field is safe. Adding a *required request* field is not — old clients omit
it. Tightening validation is not — requests that were accepted yesterday are rejected today.
Adding an enum constant is not, for consumers that switch exhaustively or deserialise
strictly; this is the one that surprises teams, because it looks additive. Changing a field's
type from string to number breaks parsers that were never lenient. Making a nullable field
non-nullable is safe for readers and breaking for writers.

**Sharing DTOs with the domain makes every model change an API change.** Return `Order`
directly and adding a field to the aggregate silently extends the public JSON; renaming one
silently breaks it. The domain then cannot be refactored without a client survey, which in
practice means it stops being refactored ([P-040](P-040-inbound-adapters-translate.md)).
Separate DTOs make the contract change visible in the diff, which is the entire point.

**Error codes are part of the contract.** Clients branch on `order.already-shipped` to decide
whether to show a retry button. Renaming it to `order.shipped` is a breaking change with no
compiler anywhere to catch it, which is why the code registry is diffed in CI
([P-050](P-050-error-handling.md)).

**Events are APIs with more consumers and less visibility.** An HTTP client that breaks
returns a 4xx someone notices. A consumer deserialising a changed event either dies in a
retry loop or — worse, with a lenient parser — silently reads a default value for the field
you renamed and writes wrong data. `@EventContract.version` and the checked-in schema
([P-070](P-070-event-semantics.md)) are the same discipline applied to the asynchronous edge.

**`@PublicApi` distinguishes reachable from supported.** Java's `public` means "the compiler
allows it". Inside a monorepo, another module can reach anything; `@PublicApi` says what you
have promised to keep, and `@Internal` says what you have not
([P-100](P-100-vertical-slice-modules.md)).

**Parallel versions end by deletion, and deletion needs evidence.** Retire `v1` when its
traffic is zero and its consumers are known to have migrated — not when `v2` looks finished.
Instrument the old version by version tag so "who still uses this" is a query rather than a
guess.

## In code

Version in the path, DTOs owned by the adapter:

```java
package com.acme.orders.ordering.adapter.in.web.v1;

@InboundAdapter(AdapterKind.REST)
@RestController
@RequestMapping("/api/v1/orders")
class OrderControllerV1 {

    private final PlaceOrder placeOrder;

    @PostMapping
    ResponseEntity<PlaceOrderResponseV1> place(@Valid @RequestBody PlaceOrderRequestV1 request) { … }
}

/** v1 wire contract. Frozen: fields may be added, never removed or retyped. */
record PlaceOrderRequestV1(@NotNull UUID customerId, @NotEmpty List<LineV1> lines) {
    record LineV1(@NotBlank String sku, @Positive int quantity) {}
}

record PlaceOrderResponseV1(UUID orderId, String status) {}
```

An additive change inside v1 — safe, because old clients ignore what they do not know:

```java
record PlaceOrderResponseV1(UUID orderId, String status,
                            @Nullable Instant estimatedDispatch) {}   // optional, added
```

A breaking change gets a new version, served alongside:

```java
package com.acme.orders.ordering.adapter.in.web.v2;

@InboundAdapter(AdapterKind.REST)
@RestController
@RequestMapping("/api/v2/orders")
class OrderControllerV2 {
    // Money is now an object rather than a bare decimal: not additive, hence v2.
    record PlaceOrderResponseV2(UUID orderId, String status, MoneyV2 total) {}
    record MoneyV2(BigDecimal amount, String currency) {}
}
```

Cross-module surface is declared, not assumed:

```java
@PublicApi(since = 2)
public record OrderSummary(UUID orderId, String status, BigDecimal total) {}

@Internal
record OrderRow(UUID id, String status, BigDecimal totalAmount, long version) {}
```

Wrong — the aggregate is the contract, and the path has no version:

```java
@RestController
@RequestMapping("/orders")
class OrderController {
    @GetMapping("/{id}")
    Order get(@PathVariable UUID id) { … }        // every domain refactor is now a client-visible change
}
```

## Enforcement

`tools/check-error-codes.sh` collects every domain error code raised in the codebase and diffs it
against `contracts/errors/registry.json`. A code that appears without being registered fails; so
does a registered code that nothing raises, because that is what a rename looks like from the
outside — and a rename changes the problem `type` URI clients branch on:

```
ERROR-CODE  'order.already-cancelled' is registered but nothing raises it. If it was renamed,
that is a breaking API change: keep the old code until clients migrate, or remove it deliberately.
See docs/principles/P-050-error-handling.md
```

`EventContractRules.everyContractHasASchemaFile()` does the same job for published events: a
contract whose schema is missing from `contracts/events/` fails, because a consumer in another
repository has nothing to generate from.

`NamingRules.edgeDataTypesStayInAdapters()` keeps `…Request` and `…Response` types out of the
domain, which is what stops the wire format becoming the model.

**Every service's `OpenApiContractTest` fails when `contracts/api/<service>.openapi.json` stops
matching what the controllers actually produce.** `springdoc` inspects the live request mappings,
so the document cannot be derived statically the way `contracts/errors/registry.json` is - the
test fetches `/v3/api-docs` from a real `MockMvc`-driven Spring context and compares it,
pretty-printed, against the checked-in file:

```
contracts/api/order-service.openapi.json is stale: the controllers now produce a different
contract. A freshly generated copy was written to target/openapi/order-service.openapi.json -
review the diff, then replace contracts/api/order-service.openapi.json with it.
```

That failure is not itself a verdict on whether the change is additive - it only proves the file
was stale and needed a human to look at the diff before it could be accepted, which is what makes
the diff a reliable place to look. `libs/web-support`'s `OpenApiInfoAutoConfiguration` names the
document after the service (`info.title`) instead of springdoc's identical, useless default
("OpenAPI definition", version "v0") for every service, which is what makes two services'
contracts distinguishable at all once both are checked in.

**Two things the generated document does not tell you, confirmed by generating one for real
rather than assumed from springdoc's own documentation:**

- A response's status code defaults to `200` regardless of what the controller actually returns
  at runtime - `ResponseEntity.created(...)` still documents as `200` unless the method carries
  an explicit `@ApiResponse(responseCode = "201", ...)`. The checked-in contract is only as
  accurate as the annotations on the controller; an unannotated `201` or `404` is invisible to it.
- Spring MVC 4.1's native endpoint versioning (`@GetMapping(version = "2")`) collapses every
  version of one path into a *single* OpenAPI operation, adding an `X-API-Version` header
  parameter enumerating the supported values rather than emitting one operation per version -
  confirmed by generating a document for two `@GetMapping` methods on the same path with
  different `version` values. If two versions genuinely return different response shapes, the
  merged operation's response schema reflects only one of them; the contract test cannot catch
  that, because both versions are equally "the generated document" as far as it can tell. Review
  a versioned endpoint's diff with this in mind.

**Deciding whether a change is additive is still review-only**, and stays that way: it needs the
previous published shape and a judgement about what clients tolerate, which is exactly what the
contract test's diff puts in front of a reviewer rather than deciding for them.

## Deviating

Internal endpoints consumed only by things you deploy together — a health probe, an admin
action behind the mesh — may skip versioning. Mark the controller `@Internal` and keep it off
the public gateway; the exemption is about deployability, so it evaporates the moment
something you do not deploy calls it.

A security fix that must break a contract breaks it. Ship it, record the decision in an
`@Adr` with the compatibility analysis and the notification you sent, and treat the ADR as
the audit trail.

A service with no HTTP surface at all - a pure event consumer, a worker with only a task queue -
has no `springdoc` dependency and no `OpenApiContractTest`; `OpenApiInfoAutoConfiguration` stays
inert (`@ConditionalOnClass`) and there is no `contracts/api/<service>.openapi.json` to maintain,
because there is no REST contract to maintain it against.
