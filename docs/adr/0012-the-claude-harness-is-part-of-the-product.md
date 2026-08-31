# ADR-0012 — The `.claude` harness is part of the product

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-30 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

A meaningful share of the code written in this repository will be written by an agent. That
is a statement about how the work happens, not an aspiration, and it changes what the
repository has to contain.

An agent arrives with no institutional memory. It was not in the design discussion, it cannot
ask the person who wrote `messaging-support` why the RabbitMQ path rejects compaction, and it
does not carry forward what it learned yesterday. What OpenAI's engineers have called
*harness engineering* is the recognition that follows: context is the scarce resource, and the
harness — the instructions, tools and scaffolding surrounding the model — determines the
quality of the output more reliably than the prompt does. In a software repository, **the repo
is the only context an agent has.** Anything that lives in a wiki, a chat thread or someone's
head is, for the agent, not merely inconvenient — it does not exist.

This reframes several things that already exist here. The annotations in ADR-0005 are not only
for structural tests; they put a class's architectural role in the same context window as the
class. The principle identifiers quoted in ArchUnit failure messages are not only for humans;
they turn a red build into a pointer to the document that explains it. Those choices were
made because they help both readers.

But instructions in a repository decay exactly like code. A skill that describes a Maven
command that no longer exists, or a `CLAUDE.md` that names Spring Cloud, does not fail
loudly — it produces confidently wrong work, at speed, and the damage is discovered in review
if at all. Treating those files as scratch notes, exempt from review, is how that happens.

## Decision

**The agent harness is source. It is versioned, reviewed and maintained to the same standard
as Java code, and a change to it is a change to the product.**

Concretely:

- `AGENTS.md` and `.claude/` are committed, never git-ignored, and never in a personal global
  configuration when the instruction is about *this repository*. The distinction is
  ownership: how this codebase works belongs to the repository; how an individual likes their
  terminal does not.
- `.claude/skills/` holds procedures an agent needs and cannot infer — adding a service,
  adding an event contract, upgrading the Boot line. A skill is written to be *loaded on
  demand*, because loading everything is the failure mode context scarcity produces. Skills
  point at `docs/principles/` and `docs/guides/` rather than restating them; two copies of a
  rule is one copy that will be wrong.
- `.claude/agents/` holds subagents for work with a bounded remit and a large intermediate
  footprint — a dependency audit, a broad codebase search, a review pass. The point is not
  parallelism, it is that the intermediate reading never enters the main context window.
- `.claude/hooks/` and `.claude/settings.json` are the deterministic half. A hook runs
  regardless of what the model decided to do, which makes it the right place for the things
  that must always happen: formatting after an edit, the build gate before a commit. Anything
  that must be true is a hook or a build gate, never an instruction — an instruction is a
  request, and a request can be declined.
- Harness changes go through pull request review, with the same question asked of them as of
  code: is this still true? A skill referencing a removed Maven profile is a defect.
- **The build is the ground truth, and the harness only ever points at it.** ADR-0009's four
  gates, the ArchUnit rules and the Testcontainers suite are what actually enforce the
  architecture. The harness makes an agent likely to produce compliant work on the first
  attempt; it is never what makes the work compliant.

## Consequences

**Good** — An agent starting cold reaches a correct first attempt, because the constraints,
the vocabulary and the procedures are in files it will read. Rules improve in one place for
every contributor at once, human or otherwise. The harness is reviewable, so a wrong
instruction is caught the way a wrong function is. And writing the harness forces the tacit
knowledge out of people's heads into the repository, which has value entirely independent of
agents.

**Bad** — A second body of documentation to keep true, with the same rot risk as any
documentation and a worse failure mode: stale prose in `docs/` misleads a reader who can
question it, while a stale skill is executed. Harness files are consumed by a specific
tool, so the investment is not portable in the way a `README` is. And there is a real
temptation to encode in a skill what should have been a build gate — a rule that lives only
in `.claude/` is a rule that holds only when an agent is driving.

**Neutral** — Reviewers must read Markdown with the same care as Java. That is a habit, and
it takes a while.

## Alternatives considered

### No committed harness — each contributor configures their own agent

The default, and it respects that tooling preferences are personal. It lost on the same
ground as any per-developer convention: the knowledge does not accumulate. Ten contributors
independently discover that Testcontainers 2.x renamed its artifacts, and none of those
discoveries outlive the session. Worse, each agent operates from slightly different rules, so
the codebase drifts in ten directions and the drift is invisible until a review catches it.

### A `CLAUDE.md` only, with no skills, subagents or hooks

Simple, and it captures most of the value: one file, always loaded, stating the conventions.
It is a reasonable starting point and this repository began there. It lost as the content
grew, for the reason harness engineering identifies — a single always-loaded file competes for
the context that the actual task needs, and past a certain size the instructions crowd out the
code. Skills exist so that the procedure for adding an event contract is present when someone
is adding an event contract and absent otherwise.

### Instructions in the harness in place of build gates

Faster to write and far more flexible than an ArchUnit rule: "always annotate use cases with
`@UseCase`" is one line. Rejected as a substitute — kept only as a supplement — because an
instruction is advisory and a build gate is not. Every rule that can be checked mechanically
is checked mechanically (ADR-0005, ADR-0009); the harness explains those rules and speeds up
compliance, and does not stand in for them.

### Generating the harness from `docs/principles/` at build time

Attractive, and it would guarantee the two never diverge. Rejected for now because the
audiences genuinely differ: a principle document argues a position for a human, while a skill
is a procedure with commands and file paths. Mechanical generation would produce a bad version
of both. The link is maintained instead by reference — skills cite principle identifiers, and
`docs/reference/principle-map.md` is generated from `@ImplementsPrinciple`.

## Revisit when

A harness file is found to be materially wrong in review, or an agent produces work that
contradicts a committed instruction — either says the harness is being maintained as notes
rather than as source, and the review discipline needs tightening. Also revisit the structure
when the agent tooling's own configuration format changes incompatibly, or when a second
agent tool is adopted alongside this one; the second adoption is the point at which
tool-specific files should be reconsidered in favour of a shared, tool-neutral `AGENTS.md`
carrying the substance.
