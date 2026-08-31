# P-000 — The repository is the only context

| | |
|---|---|
| **Layer** | cross-cutting |
| **Enforced by** | `TraceabilityRules.everyUseCaseIsDocumented()`, `TraceabilityRules.everyPrincipleHasAnImplementation()` (not implemented) in `libs/arch-test` |
| **Annotations** | `@UseCase`, `@ImplementsPrinciple`, `@Adr` |
| **Guide** | [G-090](../guides/G-090-adopting.md) |

## Rule

Everything needed to change this system correctly — principles, decisions, use case
specifications, execution plans, contracts — lives in git beside the code it governs,
and is reachable from the code by identifier. If a fact only exists in a ticket, a chat
thread, or a person's head, it does not exist.

## Why

An agent starts every session with no memory. So, functionally, does a new engineer, and
so does the same engineer eight months later. Whatever is not in the checkout is not in
the room.

The failure this prevents is specific and expensive: someone deletes the "redundant"
manual mapper, the "unnecessary" second lookup, the `retentionDays = 90` that looked
arbitrary — and reintroduces the outage that put it there. The reason was known once. It
was in a Slack thread in a channel that has since been archived, or in a Jira comment on
a ticket in a project that was migrated. Code review cannot catch it because the reviewer
does not know either. The build cannot catch it because nothing links the code to the
reason.

The second failure is drift. When the design lives in a wiki, the wiki and the code
diverge from the first commit, and there is no moment at which anyone is forced to
notice. When the design lives in `docs/` in the same commit as the code, divergence
appears in the diff, and a rule can fail the build when a use case has no specification
or a principle has no implementation.

The third is agent throughput. An agent asked to add a use case with the repository as
its only input either finds the pattern in `docs/guides/G-020-use-case.md` and follows
it, or invents one. Inventing one is not a small cost: it produces plausible code that
violates the layering, passes review because it looks like everything else, and is found
by `LayeringRules` at best or by an incident at worst.

## In code

The link runs both ways. Code points at documents by identifier:

```java
@UseCase(id = "UC-ORD-001", value = "A customer places an order and receives its identifier")
@Adr({"ADR-0021"})               // why placement reserves stock synchronously
@ImplementsPrinciple(value = {"P-072"}, note = "publishes OrderPlaced through the outbox")
public final class PlaceOrderUseCase implements PlaceOrder { … }
```

…and documents resolve to files at fixed paths:

```
docs/use-cases/UC-ORD-001-place-order.md
docs/adr/0021-synchronous-stock-reservation.md
docs/principles/P-072-transactional-outbox.md
```

A dangling identifier is a build failure, not a broken link somebody notices later.

## Enforcement

`TraceabilityRules.everyUseCaseIsDocumented()` resolves `@UseCase.id()` to
`docs/use-cases/<id>-*.md` and fails with:

```
UC-ORD-014 declared by com.acme.orders.ordering.application.CancelOrderUseCase
has no specification. Expected a file matching docs/use-cases/UC-ORD-014-*.md.
See docs/principles/P-000-repository-is-the-only-context.md
```

`TraceabilityRules.everyPrincipleHasAnImplementation()` (not implemented) runs the reverse check: each
`P-0NN` file must be cited by at least one role annotation or `@ImplementsPrinciple`, or
be listed as deliberately unimplemented in `docs/reference/principle-map.md`. A principle
nothing implements is either aspirational or obsolete, and both are worth knowing.

`@Adr` identifiers are resolved the same way against `docs/adr/`.

## Deviating

Genuinely external context — a vendor's API documentation, a regulator's specification —
stays external. Link to it from an ADR, pin the version or date you read, and summarise
in the ADR the part the code depends on, so the code survives the link rotting.

Secrets, personal data and anything under legal hold do not go in git. Reference them by
name and location (`vault:kv/orders/psp-key`) so the shape of the dependency is still
visible in the repository even when the value is not.

Related: [P-001](P-001-progressive-disclosure.md) governs how this material is layered so
it can be read a slice at a time.
