# P-022 — Domain services and policies hold logic no aggregate owns

| | |
|---|---|
| **Layer** | domain |
| **Enforced by** | `DomainRules.domainDependsOnlyOnDomain()`, `DomainRules.policiesAreSideEffectFree()`, `NamingRules.policiesDeclareWhatTheyDecide()` in `libs/arch-test` |
| **Annotations** | `@DomainService`, `@DomainPolicy` |
| **Guide** | [G-020](../guides/G-020-use-case.md) |

## Rule

Business logic lives on the aggregate that owns the data it uses. Logic spanning several
aggregates goes in a `@DomainService`; a rule that changes on a different schedule from the
objects it judges goes in a `@DomainPolicy` with `decides` stating the decision in business
language. Neither may touch a port, a repository or a framework.

## Why

The two escape hatches exist because forcing everything onto aggregates produces worse code
than allowing them — but they are the most abused constructs in a domain model, so they
carry the tightest constraints.

**Why not the use case.** A rule implemented in `PlaceOrderUseCase` is reachable only by
placing an order. When "orders over £10,000 need manual approval" also has to apply to order
amendment and to the bulk import job, it is copied. The copies diverge — one is updated when
the threshold changes, one is not — and the discrepancy surfaces as a compliance finding,
because the import path has been approving what the API path escalates. Naming the rule as a
policy gives it one home and one test.

**Why not a fat aggregate.** Pricing depends on the customer's tier, the campaign calendar
and the order. Putting it on `Order` forces the aggregate to import the other two, which
collapses the boundaries [P-020](P-020-aggregate-consistency-boundaries.md) exists to keep
apart. A domain service that takes all three as arguments and returns a `Money` has the
knowledge without the coupling.

**Why the ban on ports.** A domain service that injects `OrderRepository` re-enables
everything the layering prevents: the service now performs I/O, so it cannot be unit-tested
without a stub, its cost is invisible at the call site, and it will eventually issue a query
per loop iteration. Take the data as parameters. The use case loads; the domain decides.

**Why they must stay small.** A `@DomainService` that accretes methods faster than the
aggregates around it is the anaemic-domain-model failure in progress: logic drifting out of
objects into a procedural shell until the aggregates are data holders and the service is a
five-hundred-line transaction script that nobody can change safely. Watch the ratio, not the
class.

**Why `decides` is mandatory.** `DiscountPolicy` names a subject; `decides = "the discount
applied to an order line"` names a decision, and a decision can be checked against what the
business actually asked for. It is also what makes the policy findable by someone who knows
the rule but not the class name.

## In code

A policy — pure, injected as an interface, swappable:

```java
package com.acme.orders.ordering.domain;

@DomainPolicy(decides = "the discount applied to an order line")
public interface DiscountPolicy {
    Money discountFor(OrderLine line, CustomerTier tier);
}

@DomainPolicy(decides = "the discount applied to an order line")
public final class VolumeDiscountPolicy implements DiscountPolicy {

    private final Quantity threshold;

    public VolumeDiscountPolicy(Quantity threshold) { this.threshold = threshold; }

    @Override
    public Money discountFor(OrderLine line, CustomerTier tier) {
        if (line.quantity().isLessThan(threshold)) {
            return Money.zero(line.subtotal().currency());
        }
        return switch (tier) {
            case STANDARD -> line.subtotal().percentage(5);
            case PREMIUM  -> line.subtotal().percentage(12);
        };
    }
}
```

A domain service — spans aggregates, takes them as arguments:

```java
@DomainService
public final class CreditAssessment {

    public Decision assess(Order order, CreditProfile profile, List<Order> openOrders) {
        Money exposure = openOrders.stream()
                .map(Order::total)
                .reduce(order.total(), Money::plus);
        return exposure.isGreaterThan(profile.limit())
                ? Decision.refer("credit.limit-exceeded")
                : Decision.accept();
    }
}
```

Wrong — a domain service that fetches:

```java
@DomainService
public final class CreditAssessment {
    private final OrderRepository orders;          // fails DomainRules: DOMAIN may depend only on DOMAIN

    public Decision assess(Order order, CustomerId customerId) {
        var openOrders = orders.findOpenFor(customerId);   // N+1 waiting to happen, untestable without a stub
        …
    }
}
```

## Enforcement

`DomainRules.domainDependsOnlyOnDomain()` derives its matrix from `Layer.mayDependOn` and
fails on any reference from a domain-layer class to `@OutputPort`, `@UseCase`, Spring,
Jakarta Persistence or `java.sql`:

```
com.acme.orders.ordering.domain.CreditAssessment (DOMAIN) depends on
com.acme.orders.ordering.application.port.out.OrderRepository (APPLICATION).
DOMAIN may depend only on DOMAIN. Pass the data in as a parameter.
See docs/principles/P-022-domain-services-and-policies.md
```

`DomainRules.policiesAreSideEffectFree()` fails a `@DomainPolicy` with mutable instance
state or a `void` public method — a policy returns a decision; changing something is
somebody else's job.

`NamingRules.policiesDeclareWhatTheyDecide()` fails a blank `decides`, or one that merely
repeats the class name.

## Deviating

A policy that genuinely needs remote data — a live FX rate, a third-party credit score —
does not become a port-holding domain policy. Model the input as a value object
(`ExchangeRate`, `CreditScore`), let the use case fetch it through an `@OutputPort`, and
pass it in. If the fetch must happen mid-decision (a rule engine, a lookup table too large
to pass), that is an `@Adr` and the class belongs in the application layer, not the domain.
