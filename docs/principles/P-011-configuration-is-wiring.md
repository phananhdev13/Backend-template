# P-011 — Configuration is wiring, never logic

| | |
|---|---|
| **Layer** | configuration |
| **Enforced by** | `ConfigRules.configurationContainsNoBusinessLogic()`, `ConfigRules.nothingDependsOnConfiguration()` in `libs/arch-test` |
| **Annotations** | `@ArchConfig` |
| **Guide** | [G-010](../guides/G-010-new-service.md) |

## Rule

`@ArchConfig` classes construct beans, bind properties and register infrastructure. They
contain no branching on domain state, no calculation, and no rule a business person would
recognise. Nothing outside `config/` may reference a configuration class.

## Why

Configuration is the one place allowed to see every layer at once
([P-031](P-031-dependencies-point-inwards.md)), and that privilege is granted on the
condition that nothing important happens there.

A decision taken inside a configuration class is a decision no test reaches. Application
tests instantiate the use case directly and never load the context; slice tests load a
different subset of configuration than production does; the one test that does load
everything is the slow end-to-end suite that is quarantined after its third flake. So the
`if (properties.tier() == PREMIUM)` in a `@Bean` method runs in production and nowhere
else.

The second failure is profile drift. Logic in configuration is almost always guarded by
`@ConditionalOnProperty` or a profile, which means production runs a code path CI has
never executed. The classic shape: a `@Bean` that selects a `FeeCalculator` implementation
by property, a property that differs between staging and production, and a fee discrepancy
that surfaces in a month-end reconciliation rather than in a stack trace.

The third is invisibility to the reader. Someone debugging why an order was rejected reads
the aggregate, the policy and the use case. They do not read `OrderConfig`. A rule hidden
there is a rule that will be reimplemented — differently — by the next person who needs it,
because as far as the code was concerned it did not exist.

Keeping configuration inert also keeps it replaceable. Wiring that only wires can be
rearranged, split per module, or swapped for a test double without anybody asking what
behaviour moves with it.

## In code

Right — construction and binding only:

```java
package com.acme.orders.ordering.config;

@ArchConfig
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderingProperties.class)
class OrderingConfig {

    @Bean
    PlaceOrder placeOrder(OrderRepository orders, DiscountPolicy discounts, EventPublisher events) {
        return new PlaceOrderUseCase(orders, discounts, events);
    }

    @Bean
    DiscountPolicy discountPolicy(OrderingProperties properties) {
        return new VolumeDiscountPolicy(properties.discountThreshold());   // a value, not a choice
    }
}

@ConfigurationProperties("acme.ordering")
record OrderingProperties(Quantity discountThreshold, Duration reservationWindow) {}
```

Wrong — a business rule that no unit test will ever see:

```java
@Bean
DiscountPolicy discountPolicy(OrderingProperties properties) {
    // Which policy applies to whom is domain knowledge. It belongs in a @DomainPolicy
    // that takes the customer tier as an argument and is tested on its own.
    return properties.tier() == Tier.PREMIUM
            ? new FlatDiscountPolicy(Percentage.of(15))
            : new VolumeDiscountPolicy(properties.discountThreshold());
}
```

The fix is a single `TieredDiscountPolicy` in `domain/` that decides from the tier it is
given; configuration then has exactly one thing to construct.

## Enforcement

`ConfigRules.configurationContainsNoBusinessLogic()` fails a `@ArchConfig` class whose
methods exceed a cyclomatic complexity of 2, contain a `switch` or ternary over a domain
type, or reference a `@DomainPolicy` implementation by concrete type more than once:

```
com.acme.orders.ordering.config.OrderingConfig#discountPolicy branches on domain state.
Configuration wires; it does not decide. Move the choice into a @DomainPolicy.
See docs/principles/P-011-configuration-is-wiring.md
```

`ConfigRules.nothingDependsOnConfiguration()` enforces the other half — `Layer.CONFIGURATION`
may depend on everything and is depended on by nothing, per `Layer.mayDependOn`. An
adapter that injects `OrderingConfig` to reach a helper on it fails here.

Checkstyle's `CyclomaticComplexity` backstops the general case at 12; the rule above is
stricter because configuration should not branch at all.

## Deviating

Infrastructure genuinely does need conditional wiring: an in-memory publisher for tests, a
Kafka one in production; a different `RestClientHttpServiceGroupConfigurer` per environment.
That is technology selection, not business logic, and is fine — keep it expressed as
`@Profile` or `@ConditionalOnProperty` on whole `@Bean` methods rather than as branching
inside one, so the two paths are separately visible.

Anything that changes an outcome a customer would notice needs an `@Adr` and, almost
always, a different design.
