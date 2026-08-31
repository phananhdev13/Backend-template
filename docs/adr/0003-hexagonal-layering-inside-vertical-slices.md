# ADR-0003 — Hexagonal layering inside vertical feature slices

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-30 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

Two package layouts dominate Spring codebases, and each fails in a way the other fixes.

The **layer-first** layout — `controller`, `service`, `repository`, `dto` — makes the
technical role of a class obvious and the feature it belongs to invisible. Adding one field
to one feature touches four packages, and nothing in the structure stops the ordering
service from calling the billing repository directly, because they are neighbours in the
same `repository` package. Deleting a feature becomes archaeology.

The **feature-first** layout — a package per feature, everything inside — makes the feature
obvious and the direction of dependencies invisible. Without an internal discipline, the
JPA entity becomes the domain model, the controller reaches into the repository when a
deadline is close, and the feature quietly becomes untestable without a database.

Neither problem is hypothetical. Both are the observed steady state of Spring codebases at
around the two-year mark.

The kernel already encodes the answer to the second problem. `com.acme.kernel.arch.Layer`
declares four layers — `DOMAIN`, `APPLICATION`, `ADAPTER`, `CONFIGURATION` — and a single
method `mayDependOn(Layer)` that is the sole statement of the dependency rule.
`LayeringRules.dependenciesPointInwards()` asks that method rather than restating the
matrix, so rule and documentation cannot diverge.

## Decision

**Feature slices at the top, hexagon inside each one.**

A service's package tree is `com.acme.<service>.<feature>`, one package per bounded
capability (`orders`, `pricing`, `fulfilment`). Inside each feature package, classes carry
a role annotation from `com.acme.kernel.arch`, and that annotation's `@ArchRole(layer = …)`
places it in one of the four layers. There are no `controller` or `service` packages.

The dependency rule is exactly `Layer.mayDependOn`:

- `DOMAIN` depends only on `DOMAIN`. No Spring, no JPA, no HTTP, no broker.
- `APPLICATION` depends on `DOMAIN` and itself. It declares `@InputPort` and `@OutputPort`
  interfaces and orchestrates `@UseCase` classes against them.
- `ADAPTER` depends on anything except `CONFIGURATION`. It translates a technology into a
  port call (`@InboundAdapter`) or a port into a technology (`@OutboundAdapter`).
- `CONFIGURATION` sees everything and is seen by nothing. `@ArchConfig` classes wire; they
  do not decide.

Between features, the boundary is `@PublicApi` versus `@Internal`. Java's `public` means
"reachable", not "supported"; `@PublicApi` means supported, and a cross-feature reference
to an `@Internal` type fails the build. Spring Modulith enforces the same boundary
(ADR-0006). `@ReadModel` is the deliberate exception to the layering: a list screen may
project straight from the database, in exchange for a rule the build checks — it must be
side-effect free.

## Consequences

**Good** — A feature is a directory: readable, reviewable and deletable in one operation.
The domain layer compiles without Spring on the classpath, so domain tests are plain JUnit
and run in milliseconds. Swapping Postgres for something else, or adding a Kafka entry
point beside the REST one, touches only `ADAPTER` classes because the use case never knew
which one was calling.

**Bad** — More types. A read that a `JdbcTemplate` call could satisfy in eight lines becomes
a port, an adapter and a use case unless it is honestly declared a `@ReadModel`. Mapping
between the domain and persistence models is written by hand and is genuinely tedious.
Newcomers ask why the domain entity cannot simply be the JPA entity; the answer is in
`docs/principles/P-020`, worth re-reading before granting an exception.

**Neutral** — A class's layer is discoverable only through its annotation, not its package
path. Package-based layering cannot express "this feature's adapter"; annotations can.

## Alternatives considered

### Layer-first packages with ArchUnit package rules

Simplest to enforce — ArchUnit's `layeredArchitecture()` takes package patterns directly,
with no annotations to maintain. It lost on feature cohesion: it enforces the direction of
dependencies while doing nothing about the coupling between features, which is the coupling
that actually prevents extraction. A layer-first codebase looks compliant right up to the
day someone tries to split it.

### Feature slices with no internal layering ("vertical slice architecture")

A real position, well argued, and correct for CRUD-shaped systems: put the whole slice in
one class, accept the coupling to the framework, and move fast. It lost here because this
template's target is services with domain invariants worth protecting, where the domain
must be testable and expressible without a database. Where a slice genuinely is CRUD, the
`@ReadModel` escape hatch gives most of the benefit without opening the rule.

### Modules-as-JARs — one Maven module per feature

Enforces boundaries with the strongest tool there is: the compiler cannot see what is not
on the classpath. Rejected because the granularity is wrong. Features are created, merged
and deleted far more often than Maven modules should be, and a pom per feature turns a
routine refactor into a build change. Spring Modulith gives boundary verification at the
package level, which is the level features actually live at.

## Revisit when

A single feature package exceeds roughly fifty types, or two features share more `@PublicApi`
surface than they keep `@Internal` — both signal that the boundary was drawn in the wrong
place and the slices need resplitting, not that the rule is wrong. Also revisit if Spring
Modulith gains first-class support for layer roles, which would let `Layer` and `mayDependOn`
be expressed in its vocabulary rather than in ArchUnit rules of our own.
