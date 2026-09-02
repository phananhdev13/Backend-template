# P-040 — Inbound adapters translate, they do not decide

| | |
|---|---|
| **Layer** | adapter |
| **Enforced by** | `AdapterRules.inboundAdaptersOnlyCallInputPorts()`, `AdapterRules.inboundAdaptersContainNoBusinessLogic()`, `NamingRules.edgeDataTypesStayInAdapters()` in `libs/arch-test` |
| **Annotations** | `@InboundAdapter`, `@InputPort`, `@EventHandler` |
| **Guide** | [G-070](../guides/G-070-api.md) |

## Rule

An inbound adapter parses the request, checks its shape, maps it to a command, calls one
`@InputPort`, and maps the answer back. It takes no business decision, touches no
repository, and never returns a domain type to the wire.

## Why

**Every business decision taken in a controller is invisible to every other entry point.**
The service starts with a REST API. A rule — "an order over £10,000 needs an approval flag"
— is convenient to check in `OrderController` because the request DTO is right there. Six
months later a Kafka handler and a CSV import both create orders, neither goes through the
controller, and neither enforces the rule. The bug is not that someone forgot: it is that
the rule was never in a place the other entry points could reach. By the time it is noticed,
there are three subtly different implementations and no way to tell which is correct.

**Adapters are the layer most likely to be rewritten.** REST becomes gRPC, a controller
becomes a `@KafkaListener`, springdoc changes its annotation set, `spring-boot-starter-web`
is deprecated in favour of `spring-boot-starter-webmvc`. Every rewrite is safe exactly to
the degree that the adapter holds nothing but translation, and every rewrite is a fresh
opportunity for a rule to be lost exactly to the degree that it does not.

**Domain types on the wire couple your API to your model.** Returning `Order` directly means
adding a field to the aggregate changes the public JSON contract, renaming an enum constant
breaks a mobile client that has not been updated in a year, and lazy-loaded associations
serialise as either an exception or a surprise 40 kB payload. A DTO is a versioned contract
([P-080](P-080-api-versioning.md)) that you change on purpose.

**Two kinds of validation, only one of which is the adapter's.** Shape validation — is this
a well-formed UUID, is `quantity` present, is the JSON parseable — is genuinely the
adapter's job, because it is about the protocol. Meaning validation — may this customer
order this, is the total within the credit limit — is domain, and belongs behind the port
where all callers meet ([P-021](P-021-illegal-states-unrepresentable.md),
[P-022](P-022-domain-services-and-policies.md)).

**Error mapping belongs here and only here.** The use case throws `DomainException`
subtypes; `libs/web-support` turns `ErrorKind` into an RFC 9457 status. A controller that
catches and rethrows `ResponseStatusException` bypasses the shared mapping and produces a
response body in a different shape from every other endpoint
([P-050](P-050-error-handling.md)).

## In code

```java
package com.acme.orders.ordering.adapter.in.web;

@InboundAdapter(AdapterKind.REST)
@RestController
@RequestMapping("/api/v1/orders")
class OrderController {

    private final PlaceOrder placeOrder;                    // one @InputPort
    private final OrderSummaryQuery summaries;              // @ReadModel

    OrderController(PlaceOrder placeOrder, OrderSummaryQuery summaries) { … }

    @PostMapping
    ResponseEntity<PlaceOrderResponse> place(@Valid @RequestBody PlaceOrderRequest request,
                                             @RequestHeader("Idempotency-Key") String key) {
        OrderId id = placeOrder.place(request.toCommand(IdempotencyKey.of(key)));   // translate, call, done
        return ResponseEntity.created(URI.create("/api/v1/orders/" + id.value()))
                             .body(new PlaceOrderResponse(id.value()));
    }
}

/** Wire contract. Shape validation only — @NotNull is about the protocol, not the business. */
record PlaceOrderRequest(@NotNull UUID customerId, @NotEmpty List<Line> lines) {

    record Line(@NotBlank String sku, @Positive int quantity) {}

    PlaceOrderCommand toCommand(IdempotencyKey key) {
        return new PlaceOrderCommand(
                new CustomerId(customerId),
                lines.stream().map(l -> new RequestedLine(new Sku(l.sku()), Quantity.of(l.quantity()))).toList(),
                key);
    }
}

record PlaceOrderResponse(UUID orderId) {}
```

Wrong — three separate violations in six lines:

```java
@PostMapping
ResponseEntity<Order> place(@RequestBody PlaceOrderRequest request) {
    var customer = customerRepository.findById(request.customerId())      // 1. reaches past the port
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));   // 2. web exception for a domain fact
    if (request.total().compareTo(customer.creditLimit()) > 0) {          // 3. a business rule, here only
        return ResponseEntity.status(422).build();
    }
    return ResponseEntity.ok(placeOrder.place(request.toCommand()));      // 4. domain type on the wire
}
```

## Enforcement

`AdapterRules.inboundAdaptersOnlyCallInputPorts()` fails an `@InboundAdapter` or
`@EventHandler` that depends on an `@OutputPort`, an `@AggregateRoot`'s repository, or a
`@UseCase` implementation class rather than its port:

```
com.acme.orders.ordering.adapter.in.web.OrderController depends on
com.acme.orders.ordering.application.port.out.CustomerProfiles (an @OutputPort).
Inbound adapters call @InputPort and @ReadModel only.
See docs/principles/P-040-inbound-adapters-translate.md
```

`AdapterRules.inboundAdaptersContainNoBusinessLogic()` fails an `@InboundAdapter` calling
`compareTo`, `isGreaterThan`, `isLessThan`, `isGreaterThanOrEqualTo` or `isLessThanOrEqualTo` on
a `@ValueObject` - the exact shape of `request.total().compareTo(customer.creditLimit())` in this
principle's own "wrong" example, suppressible with `@Adr`. The other half this rule was
originally specified with - a cyclomatic-complexity threshold on adapter methods - is not
attempted: ArchUnit's imported model gives dependencies and calls, not a control-flow graph, so
"count the decision points in this method" is not something its API can answer honestly.
Checkstyle's own `CyclomaticComplexity` check (12, repo-wide) is the backstop for that half.

`NamingRules.edgeDataTypesStayInAdapters()` fails a controller method whose return type or
`@RequestBody` parameter is annotated `@AggregateRoot`, `@DomainEntity` or `@ValueObject`.

## Deviating

Presentation concerns are the adapter's: pagination defaults, `ETag` handling, content
negotiation, locale-dependent formatting, and the mapping from `ErrorKind` to status. None
of those are business decisions, and pushing them inwards would put HTTP in the use case.

An adapter that genuinely needs two ports — a composite endpoint returning a summary from
two read models — is acceptable. Two *input* ports in one method is not: that is a
distributed transaction across two use cases, and it needs an `@Adr` saying what happens
when the second one fails.
