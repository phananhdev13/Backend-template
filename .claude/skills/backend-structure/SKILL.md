---
name: backend-structure
description: Decide where new backend code belongs in this monorepo - which module, which feature slice, which layer, and whether a change needs a new slice or a new service at all. Use when starting any non-trivial change, when a class does not obviously fit, when tempted to add a shared "common" or "util" package, or when a build failure says a class declares no role or sits in the wrong package.
---

# Where code goes

The layout answers one question at each level, and each answer is checkable. Work down.

## 1. Which module?

| The code is… | Module |
|---|---|
| business behaviour for one service | `services/<service>/` |
| an architectural annotation or a type every layer shares | `libs/kernel` — **no framework imports, ever** |
| a rule the build should enforce | `libs/arch-test` |
| HTTP edge behaviour every service needs | `libs/web-support` |
| broker-facing anything | `libs/messaging-support` — the only module that names Kafka or Rabbit |
| persistence conventions every service needs | `libs/persistence-support` |
| correlation, logging or metric conventions | `libs/observability-support` |

There is no `common` or `util` module, and adding one is not the fix. A helper with no home
usually means the concept it serves has no name yet. Name the concept and put it with the code
that owns it.

## 2. Which feature slice?

A service is a set of vertical slices, one package each, each a Spring Modulith application
module. A slice owns a capability end to end - its domain, its use cases, its adapters.

Put the code in the slice that owns the **data it changes**. If a change needs to write two
slices' data, that is the signal to look at, not an inconvenience to route around: either the
boundary is drawn wrongly, or the second write should be a reaction to an event. See
[P-100](../../../docs/principles/P-100-vertical-slice-modules.md).

Slices talk to each other in exactly two ways:

- synchronously, through a type the other slice marked `@PublicApi`
- asynchronously, by publishing an event the other slice subscribes to

Reaching into another slice's `@Internal` types fails the build (`BoundaryRules`).

## 3. Which layer?

```
<feature>/
  domain/         the rules. No Spring, no JPA, no serialiser. Testable with `new`.
  application/    orchestration of one use case, expressed against ports.
    port/in/      @InputPort - one method
    port/out/     @OutputPort - what the use case needs, in domain language
  adapter/in/     translate an inbound protocol into an input port call
  adapter/out/    implement an output port against a real technology
  config/         wiring only
```

Ask what the class **knows**:

- knows a business rule and nothing else → `domain`
- knows the steps of one user-visible operation → `application`
- knows a protocol, a schema, a driver, a broker → `adapter`
- knows how the pieces are assembled → `config`

A class that knows two of these is two classes.

## 4. Declare the role

Every class carries the annotation for its role - `@AggregateRoot`, `@ValueObject`,
`@DomainPolicy`, `@UseCase`, `@InputPort`, `@OutputPort`, `@ReadModel`, `@InboundAdapter`,
`@OutboundAdapter`, `@EventHandler`, `@ArchConfig`. Unannotated is a build failure
(`RoleRules.everyClassDeclaresARole`), not a default.

Naming follows the role, and is enforced: port `PlaceOrderUseCase`, implementation
`PlaceOrderService`, controller `OrderController`, outbound adapter `JpaOrderRepositoryAdapter`,
read model `OrderSummaryQuery`, policy `DiscountPolicy`.

## Sizing the change

The structure is the same at every size; only the number of files differs.

- **A field on an existing request** — touch the DTO, the command, the aggregate, the migration.
  No new slice.
- **A new operation on an existing concept** — new input port, new use case, new inbound adapter
  method. Reuse the aggregate and the repository port. This is the common case; follow the
  `use-case` skill.
- **A new concept in an existing bounded context** — new slice inside the same service.
- **A new bounded context, its own release cadence or its own data ownership** — new service.
  Follow the `new-service` skill. Note that a new service buys isolation and costs you a network
  boundary, an eventual-consistency problem and a deployment; a new slice costs a package.

Prefer a new slice. Split a service out when there is a reason a package cannot give you.

## When it does not fit

If a class genuinely resists every layer, the model is probably wrong rather than the layout.
Before inventing a package: write the sentence "this class knows …" and see which layer that
sentence names. If it names none, the concept is missing a name. If it names two, split it.

Deliberate deviations are recorded as an ADR and referenced from the code with `@Adr`, which the
build checks resolves. See the `arch-decision` skill.
