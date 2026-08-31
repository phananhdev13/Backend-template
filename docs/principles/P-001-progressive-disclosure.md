# P-001 — Progressive disclosure over exhaustive instruction

| | |
|---|---|
| **Layer** | cross-cutting |
| **Enforced by** | _review only_ (plus `TraceabilityRules.everyPrincipleHasAnImplementation()` (not implemented) for dead documents) |
| **Annotations** | `@ImplementsPrinciple`, `@Adr` |
| **Guide** | [G-090](../guides/G-090-adopting.md) |

## Rule

Layer the repository's instructions so that each reader loads only what the task needs:
`AGENTS.md` states the invariants that apply to every change, skills carry procedures for
one kind of task, `docs/principles/` explains why a constraint exists, and
`docs/guides/` shows how to satisfy it. Never inline into a higher layer what the lower
layer already holds.

## Why

Context is a budget, not a container. An agent that reads a 4,000-line `AGENTS.md` before
touching a one-line change has spent most of its attention on rules irrelevant to the
task, and attention spent is attention unavailable for the code. Worse, relevance decays:
in a long undifferentiated document the instruction that actually governs this change is
statistically indistinguishable from the ninety that do not, so it gets ignored exactly as
often as the others.

The concrete failure mode is *instruction dilution*. A team notices agents forgetting to
add `@Idempotent`, so they add a line to `AGENTS.md`. Then another for outbox publishing.
Then another for read models. Six months later `AGENTS.md` is a changelog of everything
anyone has ever got wrong, agents follow roughly the first third of it, and the team's
response is to add more lines. Every addition makes the earlier lines less likely to be
read.

The reverse failure is under-specification: instructions so terse that the reader must
guess, guesses plausibly, and produces something that compiles and is wrong. Progressive
disclosure resolves both by making depth available on demand rather than up front: the
top layer states the constraint and names where the detail lives, and the reader descends
only along the branch it is on.

This is also why constraints are mechanised wherever possible. A rule in `libs/arch-test`
costs zero context to obey — you do not need to have read it, only to have run the build.
Prose is the fallback for what cannot be checked, and prose has a per-word price.

## In code

The layers, and what belongs in each:

```
AGENTS.md                     invariants for every change; ~1 screen. Points outwards.
.claude/skills/<task>/        procedure for one task shape: add-use-case, add-event.
docs/principles/P-0NN-*.md    why a constraint exists, and what breaks without it.
docs/guides/G-0NN-*.md        the worked walkthrough: files to create, in order.
docs/adr/NNNN-*.md            one decision, its alternatives, and its consequences.
libs/arch-test                the constraints that need no reader at all.
```

The pointer is the payload. `AGENTS.md` carries the constraint and the address, never the
explanation:

```markdown
- Every class carries an `@ArchRole`-meta-annotated role. `RoleRules` enforces it.
  Why: docs/principles/P-010-annotated-architecture.md · How: docs/guides/G-010-new-service.md
```

Code descends the same ladder — a class cites the principle, the principle cites the
guide:

```java
@ImplementsPrinciple(value = {"P-051"}, note = "bulkhead around the payment provider")
@OutboundAdapter(port = PaymentGateway.class, kind = AdapterKind.HTTP_CLIENT)
final class HttpPaymentGateway implements PaymentGateway { … }
```

## Enforcement

Review only, in both directions.

Downwards: a reviewer rejects an addition to `AGENTS.md` that restates something a
principle, a guide or a rule already covers. The question asked in review is "does every
change need this sentence?" — if not, it belongs one layer down.

Upwards: `TraceabilityRules.everyPrincipleHasAnImplementation()` (not implemented) catches documents that
have become unreachable, which is the other half of the problem — a layer nothing points
into rots silently.

This cannot be mechanised because relevance is a judgement about tasks, not a property of
text. What can be mechanised is size: keep `AGENTS.md` under roughly 120 lines and treat
growth beyond that as a signal that something needs pushing down, not as a formatting
problem.

## Deviating

Safety-critical and legally mandated constraints go in the top layer regardless of how
narrow they are — data residency, PII handling, licence obligations. The cost of one
extra line read on every task is trivial next to the cost of one omission.

Related: [P-000](P-000-repository-is-the-only-context.md) puts the material in git;
this principle decides how it is stacked.
