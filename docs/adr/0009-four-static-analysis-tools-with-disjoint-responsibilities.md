# ADR-0009 — Four static analysis tools with disjoint responsibilities

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-30 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

Static analysis tools overlap. Checkstyle can check formatting; a formatter can be argued
with; SpotBugs and Error Prone both find null dereferences; ArchUnit and Checkstyle can both
enforce import restrictions. Left unmanaged, the overlap produces the two failure modes that
make teams switch analysis off: **contradiction**, where the formatter reformats what the
linter then rejects, and **duplication**, where the same issue is reported three times with
three different severities and three different suppression syntaxes.

The cost is not the tools. It is that a build failure must be immediately diagnosable. When
a build goes red, the person reading it — increasingly an agent with a limited context
window — needs to know from the failure message alone which tool spoke, what class of
problem it found and where the rule is configured. Overlapping responsibilities destroy
that.

## Decision

Four tools, each with a responsibility no other tool is permitted to touch.

| Tool | Owns | Configuration | Phase |
|---|---|---|---|
| **spotless** 3.10.1 with palantir-java-format 2.97.0 | **Format.** Whitespace, wrapping, import order, unused imports, `pom.xml` element order | inline in root `pom.xml` | `validate` |
| **checkstyle** 14.0.0 | **Semantics in source.** Naming, complexity, forbidden APIs, Javadoc on public API, import restrictions | `build/checkstyle/checkstyle.xml` | `validate` |
| **SpotBugs** (plugin 4.10.4.0) | **Bytecode.** Null dereference, resource leaks, exposed mutable state, concurrency hazards | `build/spotbugs/exclude.xml` | `verify`, `deep-analysis` profile |
| **ArchUnit** 1.4.2 via `libs/arch-test` | **Architecture.** Layering, roles, port-to-adapter mapping, event contract coherence, documentation traceability | Java rules in `libs/arch-test` | `test` |

The boundaries are enforced by omission: Checkstyle's whitespace and formatting modules are
off, because spotless owns format and an argument between them is unwinnable. Checkstyle does
not enforce layering, because ArchUnit can express it precisely and Checkstyle can only
approximate it with import patterns. ArchUnit does not check naming style, because Checkstyle
already does and reports it at the right phase.

**Ordering is deliberate.** Format and source semantics fail at `validate`, before compilation
— the cheapest failures come first. Architecture fails during `test`. Bytecode analysis, the
slowest, runs at `verify` and only under `deep-analysis`.

Two profiles keep the loop tolerable:

- `-Pfast` skips spotless, Checkstyle and SpotBugs for the inner development loop. **CI never
  uses it.**
- `-Pdeep-analysis` adds SpotBugs `check` and the enforcer's `dependencyConvergence` rule.

`maven-enforcer-plugin` runs in the default build with `requireMavenVersion [3.9.0,)`,
`requireJavaVersion [21,)` and `banDuplicatePomDependencyVersions` — the last catches the
same dependency declared twice in one pom, which resolves to whichever came last and is
never intentional.

**`dependencyConvergence` runs only in `deep-analysis`, on purpose.** Two versions of one
library on a classpath is a production incident waiting for the right request. But
convergence is a property of the whole transitive graph, and an upstream release can turn it
red with nothing in this repository having changed. A gate that fails on someone else's
release does not belong in the default build; it belongs where a human is already looking.

## Consequences

**Good** — A red build names its own owner. "Spotless found violations" means run
`mvn spotless:apply`; a Checkstyle failure means read `build/checkstyle/checkstyle.xml`; an
ArchUnit failure quotes the principle identifier that explains it (ADR-0005). No issue is
reported twice, so no suppression has to be written twice. Formatting is never discussed in
review, because it is not negotiable and not visible.

**Bad** — Four tools is four upgrade paths, four suppression syntaxes and four ways for a
transitive dependency to break the build. palantir-java-format is opinionated and
unconfigurable by design; teams with an existing house style must abandon it. SpotBugs
operates on bytecode and produces false positives on records and on Kotlin-style builder
patterns, which is why `build/spotbugs/exclude.xml` exists and why it needs review discipline
of its own.

**Neutral** — Coverage is measured, not gated: JaCoCo runs only under `-Pcoverage` and
`jacoco.line.coverage.min` currently sits at `0.00`. That number is meant to be raised as the
codebase matures, and the root `pom.xml` says so — but a coverage gate on a template with one
example service would measure the example, not the code.

## Alternatives considered

### Error Prone instead of, or alongside, SpotBugs

Better bug detection in several categories, and it runs as a compiler plugin so the feedback
arrives at compile time rather than at `verify`. It also refactors. Its cost is intrusive: it
requires `-XDcompilePolicy=simple` and `-Xplugin` javac arguments, interacts awkwardly with
annotation processors — of which this build already has Spring's configuration processor and
Lombok-free record handling — and its version must track the JDK closely. SpotBugs analyses
the bytecode Boot actually ships and requires nothing of the compiler. Error Prone remains
the strongest candidate for a fifth tool if bytecode analysis proves insufficient.

### SonarQube as a single tool replacing all four

One dashboard, one rule set, one quality gate, and genuinely good aggregation. It lost on two
grounds. First, it requires a server: a developer cannot reproduce a CI failure locally
without one, which is a hard rule in this repository. Second, its rules overlap heavily with
Checkstyle and SpotBugs and are configured in the server rather than in the repository, so
the build's behaviour would no longer be determined by the files a reader can see.

### PMD in place of Checkstyle

Similar scope with a better copy-paste detector (CPD) and a more expressive rule language.
Rejected on ecosystem gravity rather than on merit: Checkstyle's Spring-adjacent rule sets,
IDE integration and documentation are what an engineer or an agent will find when they search
for how to fix a violation. Where CPD would help, it can be added under `deep-analysis`
without disturbing this split.

### Fewer tools — spotless and ArchUnit only

Tempting, and it covers the two things this repository is most opinionated about. It lost
because Checkstyle and SpotBugs catch different classes of defect entirely: neither
formatting nor architecture has anything to say about a leaked `InputStream` or a
`compareTo` inconsistent with `equals`.

## Revisit when

Any two tools report the same violation — that is the invariant this ADR protects, and the
signal that the split has broken. Also revisit when the `deep-analysis` profile is skipped in
CI more than it is run (the gate has become theatre), or when `jacoco.line.coverage.min` is
raised above zero, which turns coverage into a fifth gate and needs its own owner.
