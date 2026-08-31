# Architecture decision records

Decisions that shaped this repository, and the reasoning that would otherwise be lost. An
ADR is written when a choice is expensive to reverse, when a reasonable engineer would pick
differently, or when the code looks wrong until you know the context.

An ADR is never edited to change its decision. It is superseded by a new one, and both stay.

## Index

| # | Title | Status | Date | Superseded by |
|---|---|---|---|---|
| [0001](0001-target-spring-boot-4-1-on-java-21.md) | Target Spring Boot 4.1 on Java 21 | Accepted | 2026-08-30 | — |
| [0002](0002-one-maven-monorepo-one-module-per-service.md) | One Maven monorepo, one module per deployable service | Accepted | 2026-08-30 | — |
| [0003](0003-hexagonal-layering-inside-vertical-slices.md) | Hexagonal layering inside vertical feature slices | Accepted | 2026-08-30 | — |
| [0004](0004-do-not-adopt-spring-cloud.md) | Do not adopt Spring Cloud | Accepted | 2026-08-30 | — |
| [0005](0005-annotations-bridge-principle-and-implementation.md) | Annotations are the bridge between principle and implementation | Accepted | 2026-08-30 | — |
| [0006](0006-spring-modulith-for-boundaries-and-events.md) | Spring Modulith for module boundaries and event publication | Accepted | 2026-08-30 | — |
| [0007](0007-broker-neutral-event-contracts.md) | Broker-neutral event contracts | Accepted | 2026-08-30 | — |
| [0008](0008-rfc-9457-problem-details-as-single-error-representation.md) | RFC 9457 problem details as the single error representation | Accepted | 2026-08-30 | — |
| [0009](0009-four-static-analysis-tools-with-disjoint-responsibilities.md) | Four static analysis tools with disjoint responsibilities | Accepted | 2026-08-30 | — |
| [0010](0010-testcontainers-for-every-integration-test.md) | Testcontainers for every integration test | Accepted | 2026-08-30 | — |
| [0011](0011-revision-and-flatten-plugin-for-versioning.md) | `${revision}` and flatten-maven-plugin for monorepo versioning | Accepted | 2026-08-30 | — |
| [0012](0012-the-claude-harness-is-part-of-the-product.md) | The `.claude` harness is part of the product | Accepted | 2026-08-30 | — |

Statuses are `Proposed`, `Accepted`, `Superseded` or `Rejected`. A rejected ADR stays in the
index: the fact that an option was considered and refused is the part worth keeping.

## How to add an ADR

1. Copy `0000-template.md` to `NNNN-<kebab-slug>.md`, taking the next free number — numbers
   are never reused, even for an abandoned draft.
2. Fill in every section. An empty **Bad** consequence means the decision was not a decision.
3. Make **Revisit when** an observable trigger — a release, a support date, a threshold.
   Never "periodically".
4. Add a row to the table above, and set **Superseded by** on any ADR this replaces.
5. Reference it from the code it explains with `@Adr("ADR-NNNN")` from
   `com.acme.kernel.arch`, so the next reader finds it without knowing to look.
6. Open a pull request. The discussion belongs in the review; the outcome belongs in the ADR.
