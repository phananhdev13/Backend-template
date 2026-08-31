# ADR-0002 — One Maven monorepo, one module per deployable service

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-30 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

This template exists to make a set of services share conventions that are enforced rather
than merely documented. That goal constrains the repository layout more than it first
appears.

The enforcement mechanism is a set of ArchUnit rules in `libs/arch-test` that read the
annotations declared in `libs/kernel` (ADR-0005). For a rule change to be meaningful, it
must be possible to change the rule and every affected service in one commit. In a
multi-repository setup, tightening a rule means publishing a new `arch-test`, then opening
n pull requests, then waiting — during which the rule is true in some repositories and
false in others, and there is no build that can tell you which.

The same argument applies to the shared libraries. `kernel`, `web-support`,
`persistence-support`, `messaging-support` and `observability-support` are not
independently interesting artifacts; they are the extracted commonality of the services
here. Versioning them separately would create a compatibility matrix nobody wants to own.

Against that: a monorepo makes every build wider than any single change. A one-line fix in
`order-service` runs the full reactor unless CI is taught otherwise, and every service
shares one dependency management block, so upgrading a library upgrades it everywhere at
once.

There is also an exit to plan for. A service that outgrows this repository — different
release cadence, different team, different compliance boundary — must be able to leave
without a rewrite.

## Decision

One Maven reactor at the repository root. Beneath it:

- `platform/bom` — a published BOM that re-declares every `com.acme` library at
  `${revision}`, so a service extracted from this repository can depend on the platform
  without pinning six versions by hand.
- `libs/*` — shared libraries. Not products; the extracted commonality of the services.
- `services/*` — **one Maven module per deployable unit**, each producing exactly one
  Spring Boot executable jar and one container image.

The mapping between a `services/*` module and a deployable is one-to-one in both
directions. A module that deploys twice with different configuration is one service; two
things that deploy independently are two modules, even if they share most of their code —
the shared part moves to `libs/`.

`platform/bom` duplicates the dependency management list in the root `pom.xml` because it
inherits from that root and so cannot import it. The ArchUnit test
`PlatformBomCoversAllLibrariesTest` fails the build when the two lists drift, which is the
only reason the duplication is tolerable.

## Consequences

**Good** — An architectural rule and its consequences land in one atomic commit; there is
never a window where the rule is half-applied. Cross-service refactors are ordinary
refactors. The `order-service` module can serve as the worked example every rule is
demonstrated against, because it is compiled by the same build as the rules.

**Bad** — The reactor grows with the number of services, and a naive `mvn verify` gets
slower for everyone. CI must use `-pl … -am` or the Maven build cache to stay tolerable.
One dependency management block means one upgrade cadence: a service cannot sit on an
older Hibernate because it is not ready.

**Neutral** — All services share a version number (ADR-0011). For a template whose
artifacts are deployed as images rather than consumed as libraries, the version is a build
coordinate, not a compatibility claim.

## Alternatives considered

### A repository per service, plus a shared platform repository

The conventional microservice layout, and genuinely better on team autonomy: each service
owns its release cadence and its upgrade timing, and CI runs only what changed. It lost on
the enforcement point. Every rule in `libs/arch-test` becomes advisory the moment it can be
adopted at different times in different places, and the coordination cost of a platform
upgrade across n repositories is paid by the platform team, repeatedly, forever. For a
template whose central claim is "the architecture is checked", that is the wrong trade.

### Gradle with composite builds

Better incremental build performance, a configuration cache that actually works, and
first-class support for including builds. Rejected because Spring Boot's own reference
documentation, starters, `spring-boot-starter-parent` and the overwhelming majority of
examples an agent or a new engineer will encounter are Maven-shaped. This repository
optimises for a reader — human or agent — being able to predict what the build does from
first principles. Maven's verbosity is a feature in that light: the whole build is
declarative and greppable.

### One module containing all services, split by profile at packaging time

Occasionally proposed to keep the reactor small. It makes the dependency graph a lie —
every service compiles against every other service's dependencies — and it removes the
build's ability to tell you that `order-service` accidentally reached into a sibling.

## Revisit when

A `services/*` module needs a release cadence the shared reactor cannot serve — a
regulated component with its own audit trail, or a service whose team no longer overlaps
with the platform team — **or** when a full-reactor CI run on a warm cache exceeds fifteen
minutes and `-pl … -am` scoping no longer brings it back. Either is the signal to extract
that module against `platform/bom` rather than to abandon the monorepo.
