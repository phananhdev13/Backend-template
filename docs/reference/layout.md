# Where code goes

The decision, level by level. Each answer is checked by a rule, so getting it wrong is a build
failure with a message rather than a review comment.

## 1. Module

| The code is… | Module |
|---|---|
| business behaviour for one service | `services/<service>/` |
| an architectural annotation, or a type every layer shares | `libs/kernel` — **no framework imports, ever** |
| a rule the build should enforce | `libs/arch-test` |
| HTTP edge behaviour every service needs | `libs/web-support` |
| anything that names a broker | `libs/messaging-support` |
| persistence conventions every service needs | `libs/persistence-support` |
| correlation, logging or metric conventions | `libs/observability-support` |

There is no `common` module, and adding one is not the fix. A helper with no home usually means the
concept it serves has not been named yet.

## 2. Feature slice

A service is a set of vertical slices, one package each, each a Spring Modulith application module.
Code goes in the slice that owns **the data it changes**.

Slices communicate in exactly two ways: synchronously through a type marked `@PublicApi`, or
asynchronously by publishing an event. Reaching into another slice's `@Internal` types fails
`BoundaryRules.internalTypesStayInTheirModule`.

## 3. Layer

```
com.acme.<service>.<feature>/
  domain/                    the rules. No Spring, no JPA, no serialiser.
    Order.java               @AggregateRoot
    Money.java               @ValueObject
    DiscountPolicy.java      @DomainPolicy
    OrderPlaced.java         @EventContract record
  application/
    PlaceOrderService.java   @UseCase — the transaction boundary
    OrderSummaryQuery.java   @ReadModel
    port/in/
      PlaceOrderUseCase.java   @InputPort — one method
      PlaceOrderCommand.java   @Command
    port/out/
      OrderRepository.java     @OutputPort — domain language only
  adapter/in/web/            @InboundAdapter(REST) — translate, do not decide
  adapter/in/messaging/      @EventHandler + @Idempotent
  adapter/out/persistence/   @OutboundAdapter(port = …, kind = PERSISTENCE)
  adapter/out/messaging/     @OutboundAdapter(kind = MESSAGING)
  config/                    @ArchConfig — wiring only
```

Ask what the class **knows**:

- a business rule and nothing else → `domain`
- the steps of one user-visible operation → `application`
- a protocol, a schema, a driver, a broker → `adapter`
- how the pieces are assembled → `config`

A class that knows two of these is two classes.

## 4. Naming

Enforced by `NamingRules`, so a search finds every instance of a kind.

| Role | Name |
|---|---|
| `@InputPort` | `PlaceOrderUseCase` |
| `@UseCase` | `PlaceOrderService` |
| `@ReadModel` | `OrderSummaryQuery` |
| `@InboundAdapter(REST)` | `OrderController` |
| `@OutboundAdapter` | `JpaOrderRepositoryAdapter` |
| `@DomainPolicy` | `DiscountPolicy` |
| edge data | `PlaceOrderRequest`, `PlaceOrderResponse` — adapter packages only |

## 5. Sizing

The shape is identical at every size; only the file count changes.

- a field on an existing request → DTO, command, aggregate, migration
- a new operation on an existing concept → new port, use case, adapter method
- a new concept in the same bounded context → **new slice**
- a new bounded context with its own data ownership and release cadence → **new service**

Prefer a new slice. A service buys isolation and costs a network boundary, an eventual-consistency
problem and a deployment. A slice costs a package.
