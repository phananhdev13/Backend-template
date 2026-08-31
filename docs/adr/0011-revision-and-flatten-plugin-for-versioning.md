# ADR-0011 — `${revision}` and flatten-maven-plugin for monorepo versioning

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-30 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

A Maven monorepo (ADR-0002) has to answer a mundane question badly at its peril: where does
the version number live?

Written literally into every pom, a release becomes a mechanical edit of n files — which is
what `versions:set` and `maven-release-plugin` automate, and why both are ubiquitous. Both
also *rewrite the poms in the working tree* and commit the result, which means a release
produces commits whose only content is version churn, merge conflicts on every long-lived
branch, and a build whose output depends on which commit you are standing on.

Maven's CI-friendly versions feature answers this: `${revision}` in the parent's `<version>`
and in every child's `<parent>` block, defined once as a property in the root pom, and
overridable at the command line with `-Drevision=…`. One place to change, and no pom is
rewritten to release.

It has one sharp edge, and it is not optional to handle. A pom containing `${revision}` is
installed and deployed *literally*, placeholder included, because Maven does not interpolate
the version when writing the artifact's pom. A consumer resolving that artifact gets a pom
with an unresolvable version and a build that fails in a way that points nowhere useful. This
is the well-known failure of naive `${revision}` adoption.

`flatten-maven-plugin` exists precisely for this: it produces a resolved pom and substitutes
it as the artifact's pom before install or deploy.

## Decision

- `<revision>` is a property in the root `pom.xml`, currently `0.1.0-SNAPSHOT`. It is the
  single source of truth for the version of every module in this repository.
- Every module declares `<version>${revision}</version>` on its parent and inherits its own
  version. No module declares a literal version. **All modules share one version** — for a
  repository whose deployables are container images and whose libraries are consumed only
  from within the same reactor, the version is a build coordinate, not a compatibility claim.
- `flatten-maven-plugin` 1.8.0 runs in the root build for every module with
  `flattenMode=resolveCiFriendliesOnly` and `updatePomFile=true`, bound to `process-resources`,
  with its `clean` goal bound to `clean`. `resolveCiFriendliesOnly` resolves `${revision}`,
  `${sha1}` and `${changelist}` and **nothing else** — the flattened pom keeps its parent, its
  dependency management and its exclusions intact, which the more aggressive flatten modes do
  not.
- `.flattened-pom.xml` is a build artifact, git-ignored, and never edited.
- CI releases by passing `-Drevision=1.4.0` on the command line. **No commit changes a
  version number**; the tag is the record of what was released.
- `platform/bom` re-declares each library at `${revision}` for consumers outside the reactor.
  It cannot import the root pom's dependency management, because it inherits from it — hence
  the duplication, and hence `PlatformBomCoversAllLibrariesTest`, which fails the build when
  the two lists drift.

## Consequences

**Good** — Releasing is a build parameter, not a commit. Long-lived branches never conflict on
version numbers, because no branch contains one. A developer can build any version of any
module from any commit by setting one property. New modules inherit correct versioning by
existing.

**Bad** — `${revision}` remains a genuine footgun for anyone who removes flatten-maven-plugin
or adds a module outside the root build's plugin inheritance: the artifact installs with an
unresolved placeholder and the failure surfaces in the *consumer's* build, far from the cause.
Some tooling — older IDE importers, a few dependency scanners, occasional Maven plugins that
read the pom on disk rather than the resolved model — still handles `${revision}` poorly.
Debugging requires knowing that `.flattened-pom.xml` exists and is the file that was actually
published.

**Neutral** — Every module carries the same version, so a library that has not changed still
gets a new number. Independently versioned libraries would need a different mechanism, and
that need would be the signal to extract them (ADR-0002).

## Alternatives considered

### `maven-release-plugin`

The canonical answer, and it does more than versioning: tagging, the release-then-next-snapshot
dance, and deployment in one goal. It lost because its model assumes one artifact per
repository and a linear release history. In a monorepo it rewrites every pom twice per release
and produces two commits of pure noise, and its interaction with CI-driven pipelines — which
already own tagging and deployment — is a fight rather than a fit. Its prepare/perform split
also means a release runs the build twice.

### `versions:set` in CI before the build

Pragmatic and widely used: keep literal versions, and have the pipeline rewrite them. It
avoids the `${revision}` footgun entirely. It lost because it makes the working tree during a
CI build differ from the committed tree, so a build failure cannot be reproduced by checking
out the same commit. It also rewrites files the build then reads, which makes incremental and
cached builds unreliable.

### `${revision}` with no flatten plugin

Occasionally suggested on the grounds that Maven 4 resolves CI-friendly versions natively when
publishing. It is true that Maven 4 improves this, but the enforcer here requires only Maven
3.9+, and any consumer resolving from a repository populated by a 3.x build would receive the
literal placeholder. Removing the plugin is a change that breaks other people's builds, not
ours, which makes it the worst kind of simplification.

### Independent versions per module, managed by hand

The right answer for libraries with external consumers on independent release cadences. It
lost because this repository has none: `libs/*` exist to be consumed by `services/*` in the
same reactor. Adopting per-module versions would create a compatibility matrix to maintain in
exchange for flexibility nobody is asking for.

## Revisit when

This repository publishes a library that a consumer outside the monorepo upgrades on its own
schedule — that consumer needs a version number that means something, and shared `${revision}`
stops being honest at that moment. Separately, revisit the flatten plugin when the build's
minimum Maven version moves to 4.x across all environments including release CI, since Maven 4
resolves CI-friendly versions when publishing and the plugin becomes removable — but only when
no 3.x build can still publish.
