# Principles

The rules this codebase is built on, and the reasons behind them. Each principle has an
identifier that appears in three places: on the role annotations in
`libs/kernel` (`@ArchRole(principle = "P-020")`), in the failure message of the rule that
enforces it, and on the classes that claim it via `@ImplementsPrinciple`. A red build points
at its own explanation.

Read a principle when a rule fails, when you are about to deviate, or before adding a kind of
thing you have not added before. Read the linked guide when you need the steps.

| ID | Title | Layer | Enforced by | Guide |
|---|---|---|---|---|
| [P-000](P-000-repository-is-the-only-context.md) | The repository is the only context | cross-cutting | `TraceabilityRules.everyUseCaseIsDocumented()` | [G-090](../guides/G-090-adopting.md) |
| [P-001](P-001-progressive-disclosure.md) | Progressive disclosure over exhaustive instruction | cross-cutting | _review only_ | [G-090](../guides/G-090-adopting.md) |
| [P-010](P-010-annotated-architecture.md) | Every class declares its architectural role | cross-cutting | `RoleRules.everyClassDeclaresARole()` | [G-010](../guides/G-010-new-service.md) |
| [P-011](P-011-configuration-is-wiring.md) | Configuration is wiring, never logic | configuration | `ConfigRules.configurationContainsNoBusinessLogic()` | [G-010](../guides/G-010-new-service.md) |
| [P-020](P-020-aggregate-consistency-boundaries.md) | Aggregates are consistency boundaries | domain | `AggregateRules.oneRepositoryPerAggregateRoot()` | [G-020](../guides/G-020-use-case.md) |
| [P-021](P-021-illegal-states-unrepresentable.md) | Make illegal states unrepresentable | domain | `ValueObjectRules.valueObjectsAreImmutable()` | [G-020](../guides/G-020-use-case.md) |
| [P-022](P-022-domain-services-and-policies.md) | Domain services and policies hold logic no aggregate owns | domain | `DomainRules.domainDependsOnlyOnDomain()` | [G-020](../guides/G-020-use-case.md) |
| [P-030](P-030-use-case-unit-of-application-logic.md) | The use case is the unit of application logic | application | `UseCaseRules.useCasesImplementExactlyOneInputPort()` | [G-020](../guides/G-020-use-case.md) |
| [P-031](P-031-dependencies-point-inwards.md) | Dependencies point inwards through ports | application | `LayeringRules.dependenciesPointInwards()` | [G-020](../guides/G-020-use-case.md) |
| [P-032](P-032-reads-and-writes-shaped-separately.md) | Reads and writes are shaped separately | application | `ReadModelRules.readModelsHaveNoSideEffects()` | [G-040](../guides/G-040-persistence.md) |
| [P-040](P-040-inbound-adapters-translate.md) | Inbound adapters translate, they do not decide | adapter | `AdapterRules.inboundAdaptersOnlyCallInputPorts()` | [G-070](../guides/G-070-api.md) |
| [P-041](P-041-outbound-adapters-one-port.md) | Outbound adapters implement exactly one port | adapter | `AdapterRules.outboundAdaptersImplementTheirDeclaredPort()` | [G-040](../guides/G-040-persistence.md) |
| [P-042](P-042-event-handlers-delivery-contract.md) | Event handlers are adapters with a delivery contract | adapter | `EventContractRules.atLeastOnceHandlersAreIdempotent()` | [G-030](../guides/G-030-events.md) |
| [P-050](P-050-error-handling.md) | Failures carry domain meaning, not HTTP status | cross-cutting | `ErrorRules.domainNeverThrowsWebExceptions()` | [G-070](../guides/G-070-api.md) |
| [P-051](P-051-remote-call-resilience.md) | Every remote call has a timeout, a retry policy, and a bulkhead | adapter | `ResilienceRules.remoteCallsDeclareTimeouts()` | [G-050](../guides/G-050-resilience.md) |
| [P-060](P-060-observability.md) | A request is followable end to end | cross-cutting | `ObservabilityRules.useCasesEmitTheirIdentifier()` | [G-060](../guides/G-060-observability.md) |
| [P-070](P-070-event-semantics.md) | Event semantics are declared, not configured | domain | `EventContractRules.compactedStreamsCarryStateSnapshots()` | [G-030](../guides/G-030-events.md) |
| [P-071](P-071-idempotency.md) | At-least-once delivery makes idempotency mandatory | adapter | `EventContractRules.atLeastOnceHandlersAreIdempotent()` | [G-030](../guides/G-030-events.md) |
| [P-072](P-072-transactional-outbox.md) | State changes and their events commit together | application | `OutboxRules.noBrokerCallInsideATransaction()` | [G-030](../guides/G-030-events.md) |
| [P-080](P-080-api-versioning.md) | APIs are versioned contracts | adapter | `tools/check-error-codes.sh` | [G-070](../guides/G-070-api.md) |
| [P-090](P-090-layered-tests.md) | Tests are layered to match the architecture | cross-cutting | `checkstyle:IllegalImport` | [G-080](../guides/G-080-testing.md) |
| [P-100](P-100-vertical-slice-modules.md) | Feature modules are vertical slices with sealed internals | cross-cutting | `BoundaryRules.internalTypesStayInTheirModule()` | [G-010](../guides/G-010-new-service.md) |
| [P-110](P-110-expand-migrate-contract.md) | Schema changes are expand-migrate-contract | adapter | `tools/check-migrations.sh` | [G-040](../guides/G-040-persistence.md) |
| [P-120](P-120-security-at-use-case-boundary.md) | Security decisions happen at the use case boundary | application | `SecurityRules.noAuthorisationInAdapters()` | [G-020](../guides/G-020-use-case.md) |

The **Enforced by** column names the primary rule; several principles are enforced by more
than one, and each principle file lists the full set with its failure message.

## Numbering

| Range | Concern |
|---|---|
| `P-00x` | How the repository itself is organised for humans and agents |
| `P-01x` | Architectural vocabulary and wiring |
| `P-02x` | Domain modelling |
| `P-03x` | Application layer and the hexagon |
| `P-04x` | Adapters |
| `P-05x` | Failure and resilience |
| `P-06x` | Observability |
| `P-07x` | Events and delivery |
| `P-08x` | Contracts with the outside world |
| `P-09x` | Testing |
| `P-10x`+ | Modularity, data, security |

Identifiers are permanent. A superseded principle keeps its number, gains a
`> Superseded by P-0NN` banner, and stays in place — an `@ImplementsPrinciple` in a service
that has not migrated must still resolve.

## Adding or changing a principle

1. A principle earns its place by naming a failure that has happened, or that the enforcing
   rule can demonstrate. If the **Why** section has no specific failure mode in it, the
   principle is a preference and belongs in a guide.
2. Add the file using the fixed section order: **Rule**, **Why**, **In code**,
   **Enforcement**, **Deviating**.
3. Add the row to the table above.
4. Write the rule in `libs/arch-test`, or state plainly in **Enforcement** why it cannot be
   mechanised. `tools/principle-map.sh` regenerates
   [reference/principle-map.md](../reference/principle-map.md), which lists every principle
   nothing enforces so the gap is visible rather than assumed.
5. Deviations are recorded, never argued in a comment: an `@Adr` on the class, and an ADR in
   `docs/adr/` that says what was traded for what.
