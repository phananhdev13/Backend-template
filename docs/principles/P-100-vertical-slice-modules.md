# P-100 — Feature modules are vertical slices with sealed internals

| | |
|---|---|
| **Layer** | cross-cutting |
| **Enforced by** | `BoundaryRules.internalTypesStayInTheirModule()`, `BoundaryRules.crossModuleTypesArePublicApi()`, `BoundaryRules.noCyclesBetweenModules()`, Spring Modulith `ApplicationModules.verify()` |
| **Annotations** | `@PublicApi`, `@Internal`, `@ArchRole` |
| **Guide** | [G-010](../guides/G-010-new-service.md) |

## Rule

A feature is one package containing its whole stack — domain, application, adapters, config —
and is one Spring Modulith application module. Types another module uses are `@PublicApi`;
everything else is `@Internal` and unreachable from outside, enforced rather than agreed.

## Why

**Layer-first packaging spreads every change across the codebase.** With `domain/`,
`application/` and `adapter/` at the top level, adding a field to an order touches four
packages that also contain shipping, billing and inventory. Every feature branch conflicts
with every other. Nobody can tell what a feature consists of, so nothing is ever deleted, and
the "shared" `domain` package accumulates types that two features half-use in different ways.

**Feature-first packaging makes the unit of change equal the unit of code.** Everything
`ordering` needs is under `ordering/`; a diff for an ordering change is confined to it;
deleting the feature is deleting a directory. That property — deletability — is the honest
test of whether a boundary exists, and it is what makes the template's slices safe to copy
and adapt.

**Java `public` is the wrong tool for a monorepo.** Adapters, use cases and config all need
to see each other within the feature, so almost everything is `public` for wiring reasons.
That makes `public` meaningless as a statement of support: any module can reach any type, and
a repository implementation intended for one feature quietly acquires callers in three
others. `@Internal` restores the distinction — public for the compiler, off-limits by
contract, checked by a test.

**Uncontrolled coupling is what stops a module ever being extracted.** The service starts as
a modular monolith with the intention of splitting `billing` out later. Six months of
convenient direct calls into `ordering`'s internals later, the split requires untangling
forty references, so it does not happen. Sealing internals from the first commit keeps the
option alive at near-zero ongoing cost; adding the seal afterwards is the expensive project
nobody funds.

**Cycles between modules mean there are no modules.** `ordering` calling `billing` calling
`ordering` cannot be built, tested, reasoned about or deployed independently, and a change to
either can loop back through the other. Modulith detects cycles at verification time, before
they are load-bearing.

**Cross-module communication is a design choice, made explicitly.** Two mechanisms are
allowed: call another module's `@PublicApi` port synchronously, or publish an event and let
it converge ([P-072](P-072-transactional-outbox.md)). Reaching into another module's
repository is neither, and it is the one that couples storage schemas together — after which
neither module can migrate its own tables.

## In code

The slice holds its whole stack:

```
com.acme.orders/
  OrderServiceApplication.java
  shared/                                  service-local shared types (small, and it stays small)
  ordering/                                one Spring Modulith application module
    package-info.java                      @ApplicationModule(displayName = "Ordering")
    domain/            Order, OrderId, Money, DiscountPolicy, OrderPlaced
    application/       PlaceOrderUseCase, port/in/PlaceOrder, port/out/OrderRepository, OrderSummaryQuery
    adapter/in/web/    OrderControllerV1
    adapter/in/messaging/  PaymentCapturedHandler
    adapter/out/persistence/ JdbcOrderRepository, OrderRow
    adapter/out/messaging/   OutboxEventPublisher
    config/            OrderingConfig
  billing/
    …
```

The module declares what it allows in, and what it offers out:

```java
@ApplicationModule(
        displayName = "Ordering",
        allowedDependencies = {"shared", "billing::api"})     // narrow, explicit
package com.acme.orders.ordering;

import org.springframework.modulith.ApplicationModule;
```

```java
package com.acme.orders.ordering.application.port.in;

@PublicApi(since = 1)                     // billing may call this
@InputPort
public interface PlaceOrder {
    OrderId place(PlaceOrderCommand command);
}
```

```java
package com.acme.orders.ordering.adapter.out.persistence;

@Internal                                 // public for wiring; unsupported outside ordering
record OrderRow(UUID id, UUID customerId, String status, BigDecimal totalAmount, long version) {}
```

Verification is a test, so a breach fails the build:

```java
class ModularityTest {

    static final ApplicationModules MODULES = ApplicationModules.of(OrderServiceApplication.class);

    @Test
    void modules_are_acyclic_and_respect_declared_dependencies() {
        MODULES.verify();
    }

    @Test
    void documentation_is_regenerated() {
        new Documenter(MODULES).writeDocumentation();     // docs/reference/modules/
    }
}
```

Wrong — a direct reach into another module's storage:

```java
package com.acme.orders.billing.application;

import com.acme.orders.ordering.adapter.out.persistence.JdbcOrderRepository;   // @Internal

public final class ChargeOrderUseCase implements ChargeOrder {
    private final JdbcOrderRepository orders;      // billing now depends on ordering's schema
}
```

## Enforcement

`BoundaryRules.internalTypesStayInTheirModule()` fails any reference to an `@Internal` type
from a different feature package:

```
com.acme.orders.billing.application.ChargeOrderUseCase references
com.acme.orders.ordering.adapter.out.persistence.JdbcOrderRepository, which is @Internal
to module 'ordering'. Depend on a @PublicApi port, or subscribe to an event.
See docs/principles/P-100-vertical-slice-modules.md
```

`BoundaryRules.crossModuleTypesArePublicApi()` runs the converse: a type actually referenced
across modules but carrying neither `@PublicApi` nor `@Internal` fails, so the decision is
made rather than defaulted.

`BoundaryRules.noCyclesBetweenModules()` and Spring Modulith's `ApplicationModules.verify()`
overlap deliberately — Modulith checks the declared `allowedDependencies`, the ArchUnit rule
adds the `@Internal`/`@PublicApi` semantics Modulith does not know about.

## Deviating

`shared/` exists for genuinely service-wide types — a tenant identifier, a clock abstraction.
It has no owner, so it only ever grows; keep it under about ten types and treat additions as
a design smell to be argued for. If two modules need a type, ask whether one of them should
own it and expose it as `@PublicApi`.

A deliberately shared kernel between two modules (a shared value object with identical
semantics on both sides) is legitimate with an `@Adr` naming both owners and the change
protocol. Without one, the type diverges in meaning while staying identical in shape, which
is the bug that is hardest to see in review.
