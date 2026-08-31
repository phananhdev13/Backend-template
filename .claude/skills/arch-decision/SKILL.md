---
name: arch-decision
description: Record an architecture decision as an ADR in docs/adr, and reference it from the code with @Adr. Use when making a choice with a real alternative someone will otherwise re-litigate - a library, a boundary, a trade-off, a deliberate deviation from a principle - or when a build rule demands an @Adr reference for an expensive guarantee.
---

# Recording a decision

Write an ADR when a choice has a **real alternative** that a reasonable person would pick, and when
the reasoning will not be recoverable from the code six months later. Not every choice qualifies:
if there was no alternative, there was no decision.

The build sometimes demands one. `EventContractRules.unboundedChoicesAreJustified` fails an
`@EventContract` declaring infinite retention, global ordering, or personal data on a compacted
stream without an `@Adr`, because each imposes a cost or an obligation that someone must own.

## Steps

1. Copy `docs/adr/0000-template.md` to `docs/adr/NNNN-<kebab-slug>.md` with the next free number.
2. Fill it in. The sections that matter are **Context** and **Alternatives considered**; the decision
   itself is usually a sentence.
3. Reference it from the code it explains: `@Adr("ADR-0007")`. `TraceabilityRules.adrReferencesResolve`
   fails if the file does not exist, so the pointer cannot rot.
4. Add the row to `docs/adr/README.md`.

## What makes an ADR worth having

**Context is the load-bearing section.** Write what was true about the world — versions, deadlines,
team size, traffic — that made this a decision rather than an obvious call. That is what a future
reader needs to judge whether the reasoning still holds.

**Be fair to the alternative.** An ADR whose rejected options are strawmen tells the next person
nothing, and they will reopen the question. Say what the alternative was genuinely better at, and
why that was not decisive.

**Consequences include the bad ones.** An ADR with only upsides was written to justify a decision
already made, and it will not be believed.

**"Revisit when" is an observable trigger, not a date.** "When Spring Cloud supports Boot 4.1" is a
trigger. "In six months" is a wish, and nobody will check.

## Superseding

Never edit an accepted ADR's decision. Write a new one, set its **Supersedes**, and set the old
one's **Superseded by** and status. The point of the record is that it shows what was believed at
the time — an edited ADR loses exactly the information that made it worth keeping.

## Deviating from a principle

The mechanism is the same. Write the ADR, then annotate the deviating class with `@Adr`, so a reader
who finds the odd-looking code finds the reason with it. Deviations are recorded, never argued in a
code comment.
