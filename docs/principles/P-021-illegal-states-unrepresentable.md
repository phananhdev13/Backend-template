# P-021 — Make illegal states unrepresentable

| | |
|---|---|
| **Layer** | domain |
| **Enforced by** | `ValueObjectRules.valueObjectsAreImmutable()`, `ValueObjectRules.valueObjectsValidateInTheirConstructor()` (not implemented), `NamingRules.domainSignaturesUseValueObjects()` (not implemented) in `libs/arch-test` |
| **Annotations** | `@ValueObject`, `@AggregateRoot`, `@DomainEntity` |
| **Guide** | [G-020](../guides/G-020-use-case.md) |

## Rule

Encode constraints in types, not in checks. A value object validates in its canonical
constructor and is immutable thereafter; a state machine is a sealed hierarchy or an enum
with explicit transitions. If a combination of values is not legal, it must not be
constructible.

## Why

A check protects one call site. A type protects every call site that will ever exist,
including the ones written by someone who has never read the check.

The failure this prevents is validation asymmetry. `BigDecimal amount` can be negative, can
carry 17 decimal places, and says nothing about currency. So every use case that touches it
re-checks — and one of them does not, or checks slightly differently, or checks before a
transformation rather than after. The bug that reaches production is a refund of `-£0.00499`
that rounds to zero in the ledger and to a penny in the statement, and the reconciliation
job that finds it three weeks later cannot say which of the eleven entry points let it in.

Primitive parameters cause the other classic: argument transposition. `reserve(String
orderId, String customerId)` compiles perfectly when called with the arguments the wrong way
round, and the failure appears as a `NotFoundException` from a lookup that should never have
been reachable. `reserve(OrderId, CustomerId)` makes it a compile error. This is not a
stylistic preference — it is the cheapest bug class to eliminate entirely, and Java 21
records make the eliminating free.

Boolean fields are the third source. `boolean cancelled` plus `boolean shipped` describes
four states, of which one — cancelled *and* shipped — is nonsense that the type permits and
some code path will eventually produce. A sealed interface or an enum with declared
transitions gives the compiler the exhaustiveness check and makes adding a state a
compile-time survey of everywhere it matters.

Mutability defeats all of this. A value object with a setter can be validated at
construction and invalid a line later, and because value objects are shared freely, the
mutation is observed somewhere that never saw the assignment.

## In code

```java
package com.acme.orders.ordering.domain;

@ValueObject
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (amount.scale() > 2) {
            throw ValidationException.field("amount", "must have at most 2 decimal places");
        }
        amount = amount.stripTrailingZeros().setScale(2, RoundingMode.UNNECESSARY);
    }

    public static Money of(String amount, Currency currency) { … }

    public Money plus(Money other) {
        if (!currency.equals(other.currency)) {
            throw new BusinessRuleViolation("money.currency-mismatch",
                    "Cannot add %s to %s".formatted(other.currency, currency));
        }
        return new Money(amount.add(other.amount), currency);
    }
}

@ValueObject
public record OrderId(UUID value) {
    public OrderId { Objects.requireNonNull(value, "value"); }
    public static OrderId newId() { return new OrderId(UUID.randomUUID()); }
}
```

State as a sealed hierarchy, so the compiler enumerates the cases:

```java
public sealed interface OrderState {
    record Draft(List<OrderLine> lines) implements OrderState {}
    record Placed(Instant placedAt, Money total) implements OrderState {}
    record Shipped(Instant placedAt, TrackingNumber tracking) implements OrderState {}
    record Cancelled(Instant cancelledAt, CancellationReason reason) implements OrderState {}
}

// Exhaustive: adding a state breaks this switch at compile time, which is the point.
Money refundable = switch (state) {
    case OrderState.Draft ignored      -> Money.zero();
    case OrderState.Placed p           -> p.total();
    case OrderState.Shipped s          -> Money.zero();
    case OrderState.Cancelled c        -> Money.zero();
};
```

Wrong — four representable states, three legal:

```java
@AggregateRoot
public final class Order {
    private boolean cancelled;
    private boolean shipped;      // cancelled && shipped is unreachable by intent only
}
```

## Enforcement

`ValueObjectRules.valueObjectsAreImmutable()` fails any `@ValueObject` that is not a record
or a final class, has a non-final field, exposes a setter, or holds a mutable collection
without defensive copying:

```
com.acme.orders.ordering.domain.Money is annotated @ValueObject but declares a
non-final field 'amount'. Value objects are records or final classes with final state.
See docs/principles/P-021-illegal-states-unrepresentable.md
```

`ValueObjectRules.valueObjectsValidateInTheirConstructor()` (not implemented) fails a `@ValueObject` with a
constrained-sounding component (`amount`, `email`, `quantity`, `percentage`) whose canonical
constructor body is empty — a value object that validates nothing is a type alias, and its
callers will keep checking.

`NamingRules.domainSignaturesUseValueObjects()` (not implemented) fails public methods on `@AggregateRoot`,
`@DomainService` and `@DomainPolicy` that take `String`, `BigDecimal`, `int` or `UUID`
where a `@ValueObject` of the same name exists in the module.

## Deviating

Boundary types are exempt: a web request DTO or a persistence entity may hold primitives,
because that is where external strings enter and are converted
([P-040](P-040-inbound-adapters-translate.md)). Convert once, at the edge, then work in
value objects inwards.

A genuinely unconstrained value — a free-text note, a vendor's opaque token — stays a
`String`. Wrapping it buys nothing, so do not.
