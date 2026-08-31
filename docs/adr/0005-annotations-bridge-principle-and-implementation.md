# ADR-0005 — Annotations are the bridge between principle and implementation

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-30 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

Every codebase has architectural intent. Almost none can answer, mechanically, "which classes
implement this rule?" — the intent lives in a wiki page, a diagram and the memory of whoever
wrote it, and the code is at best consistent with it by habit.

The usual bridge is naming and packaging: classes ending in `Service` go in a `service`
package, and ArchUnit rules match on those strings. A name is not a declaration —
`OrderProcessingService` might be a use case, a domain service or a misplaced adapter, and the
rule matching `..service..` cannot tell. Renaming a class, or moving a package, silently
changes which rules apply. The architecture becomes a property of string patterns, and string
patterns drift.

There is a second reader now, and it changes the calculus. An agent has no institutional
memory, no colleague to ask and a scarce context window; it sees what it reads in the files it
opens. If a class's role is implied by its package path plus a rule file three directories
away, the agent must read both to know what is legal — and often reads neither.

The kernel already carries this design. `@ArchRole` is a meta-annotation applied to
annotations, declaring the `Layer` its classes live in and the `principle` that justifies the
constraints on them. `@UseCase`, `@ValueObject`, `@InboundAdapter`, `@OutputPort` and the rest
all carry it, and structural tests discover roles *through* the meta-annotation, so adding a
role does not mean editing a rule.

## Decision

**The architectural role of every class is declared on the class, as an annotation, and
that declaration is what the build reads.**

Concretely:

- Every class in a service carries exactly one role annotation from `com.acme.kernel.arch`.
  A class with no role is not "unclassified" — it is a build failure.
- Roles are meta-annotated `@ArchRole(layer = …, principle = "P-0NN")`. The layer drives
  `LayeringRules.dependenciesPointInwards()` via `Layer.mayDependOn`; the principle
  identifier is quoted in the failure message, so a red build points at its own explanation.
- Roles carry the metadata the build needs, not just a label. `@UseCase` has an `id()`
  resolving to a file under `docs/use-cases`, checked by
  `TraceabilityRules.everyUseCaseIsDocumented()`; `@OutboundAdapter` names the `port()` it
  satisfies, so an unimplemented port and an undeclared adapter are both detectable.
- Cross-cutting claims use `@ImplementsPrinciple`, answering the reverse question — "what
  actually implements P-051?" — with a test rather than a search.
  `docs/reference/principle-map.md` is generated from these, and a principle with no
  implementation and no waiver fails the build.
- `@Adr` points at the decision record explaining code that looks wrong until you know the
  context — the hand-written mapper, the second cache, the deliberate duplication. Without it,
  the next reader deletes the code and rediscovers the reason in production.
- **The kernel depends on no framework.** `@UseCase` carries no Spring meaning; services turn
  those classes into beans with a component-scan include filter, which is what lets the domain
  compile and be tested with Spring absent from the classpath (ADR-0003). The cost is one
  filter per service.

## Consequences

**Good** — The architecture is queryable: "show me every at-least-once event handler that is
not idempotent" is a test, not a code review. Rules select by declaration rather than by string
pattern, so renaming a class or moving a package cannot change which rules apply. A reader —
human or agent — learns a class's constraints from its first three lines, without leaving the
file. New roles are enforced by existing rules, which discover them via `@ArchRole`.

**Bad** — Ceremony. Every class carries an annotation, and reviewers must check that the
declared role is the true one; an `@ValueObject` on a mutable class is a lie the compiler
cannot catch (though ArchUnit can, and does). The annotations are `RUNTIME`-retained, so they
ship in the artifact, and the vocabulary is ours, so nothing outside this repo understands it.

**Neutral** — Role annotations resemble Spring stereotypes and deliberately are not composed
with them; keeping `@Component` off `@UseCase` is what preserves framework independence.

## Alternatives considered

### Package and naming conventions with ArchUnit pattern rules

The mainstream approach, needing no kernel at all, and cheap and readable besides. It lost on
precision and on drift. A package pattern cannot express "this is an aggregate root and
therefore the only type a repository may be declared for", and a name carries no structured
metadata: `@UseCase(id = "UC-ORD-001")` links code to a specification, `PlaceOrderUseCase`
links code to a hope. Worse, reorganise packages and the rules stop matching while the build
stays green — the worst possible failure mode for an enforcement mechanism.

### Java modules (JPMS) and `module-info.java`

The strongest boundary available — enforced by the compiler and the runtime, not by a test.
Rejected because JPMS expresses one thing (which packages are exported to whom) where this
repository needs several: layer, principle, port-to-adapter mapping, documentation
traceability, event semantics. It also fits Spring Boot's fat-jar packaging poorly. Where JPMS
overlaps — cross-feature visibility — `@PublicApi`/`@Internal` covers it at the granularity
features actually have.

### A separate machine-readable model (YAML or a DSL describing the architecture)

Fully expressive, and decoupled from the code. It lost on the one property that matters: a
model in a separate file drifts. An annotation cannot be moved away from its class.

## Revisit when

The annotation set stops paying for itself: a role is added that no rule checks, or a rule
needs metadata no annotation carries and falls back on a name. Either is a hole in the bridge.
Revisit the framework-independence choice if Spring Modulith or Spring Framework gains a
supported way to declare a stereotype that is architecture-aware and free of `@Component`
semantics — that would let the two vocabularies merge instead of coexisting.
