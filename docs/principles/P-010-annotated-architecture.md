# P-010 — Every class declares its architectural role

| | |
|---|---|
| **Layer** | cross-cutting |
| **Enforced by** | `RoleRules.everyClassDeclaresARole()`, `RoleRules.rolesMatchTheirPackage()`, `RoleRules.rolesAgreeOnTheirLayer()` in `libs/arch-test` |
| **Annotations** | `@ArchRole`, `@AggregateRoot`, `@DomainEntity`, `@ValueObject`, `@DomainPolicy`, `@DomainService`, `@UseCase`, `@InputPort`, `@OutputPort`, `@ReadModel`, `@InboundAdapter`, `@OutboundAdapter`, `@EventHandler`, `@ArchConfig` |
| **Guide** | [G-010](../guides/G-010-new-service.md) |

## Rule

Every type under `com.acme.<service>` carries exactly one role annotation from
`com.acme.kernel.arch` (or `com.acme.kernel.event` for `@EventHandler`). A class with no
role is a build failure, not an unclassified class.

## Why

Package conventions are advisory; annotations are data. That difference is the whole
principle.

Without a declared role, the layer a class belongs to must be inferred from its package,
its name, or its imports — and all three lie. `OrderValidator` in `application` might be
domain logic that drifted outwards. `OrderMapper` in `adapter/out/persistence` might be
quietly deciding which orders are visible. A reviewer who has to reconstruct intent from
naming reconstructs it differently each time, and the architecture erodes one plausible
class at a time until "hexagonal" describes only the directory names.

With a declared role, three consumers read the same answer. Structural tests select
classes by annotation rather than by package pattern, so moving a package does not
silently disable a rule — the failure mode where someone refactors `..application..` to
`..app..` and forty ArchUnit rules start matching nothing and passing vacuously. Readers
know which constraints apply before reading the body. And generated documentation is
derived from the code rather than maintained beside it.

The forcing function matters more than the labelling. Being unable to add a class without
choosing a role means the modelling question — *is this a policy, a domain service, or
logic that belongs on the aggregate?* — is asked at the moment it is cheap to answer,
instead of six months later when four callers depend on the wrong answer.

Roles also compose upwards. Because `@ArchRole` is a meta-annotation carrying the `Layer`
and the governing principle, adding a new role to the kernel makes it enforceable without
editing a single rule, and a rule failure can quote the principle document that explains
itself.

## In code

```java
package com.acme.orders.ordering.domain;

@AggregateRoot                                  // Layer.DOMAIN, principle P-020
public final class Order { … }

@ValueObject                                    // Layer.DOMAIN, principle P-021
public record Money(BigDecimal amount, Currency currency) { … }

@DomainPolicy(decides = "the discount applied to an order line")
public interface DiscountPolicy { … }
```

```java
package com.acme.orders.ordering.application;

@UseCase(id = "UC-ORD-001", value = "A customer places an order")
public final class PlaceOrderUseCase implements PlaceOrder { … }

@OutputPort                                     // Layer.APPLICATION, principle P-031
public interface OrderRepository { … }
```

Wrong — reachable, plausible, unclassifiable:

```java
package com.acme.orders.ordering.application;

// No role. Which layer is this? May it touch JDBC? May the domain call it?
// Nobody can answer, so everybody assumes the answer that suits them.
public class OrderHelper { … }
```

Test fixtures, `package-info.java` and records nested inside an annotated type are
exempt; nothing else is.

## Enforcement

`RoleRules.everyClassDeclaresARole()` scans `com.acme.<service>..` for types with no
annotation that is itself meta-annotated `@ArchRole`, and fails with:

```
com.acme.orders.ordering.application.OrderHelper declares no architectural role.
Add one of @UseCase, @InputPort, @OutputPort, @ReadModel (application layer),
or move it to the layer it belongs to.
See docs/principles/P-010-annotated-architecture.md
```

`RoleRules.rolesMatchTheirPackage()` cross-checks the two: a `@UseCase` under
`adapter/in/web` fails, because one of the two statements is a lie and the annotation is
the one that rules are written against.

Roles are mutually exclusive — `RoleRules` rejects a class carrying two.

## Deviating

Do not. A class that resists classification is a design smell, not an exception: it is
usually two classes, or logic that belongs on a domain object it currently operates on.

The one legitimate escape is third-party integration scaffolding that must live in the
service module — a generated client, a framework callback with a fixed shape. Keep it in
`shared/`, mark it `@Internal`, and add an `@Adr` explaining why it cannot sit behind an
`@OutboundAdapter` ([P-041](P-041-outbound-adapters-one-port.md)).
