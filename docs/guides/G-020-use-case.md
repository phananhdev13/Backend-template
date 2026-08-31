# G-020 — Adding a use case

The full procedure, with code, is in the `use-case` skill. This page is the shape of the work and
the decisions that are easy to get wrong.

## Order of work

Outside-in. Decide what the caller gets, then what the domain must guarantee, then what the use case
needs from the world, then wire it.

1. **Specification** — `docs/use-cases/UC-<CONTEXT>-<NNN>-<slug>.md`. Not optional: the build fails
   if `@UseCase(id = …)` points at a file that does not exist. Write the failures the caller must be
   able to tell apart; the happy path writes itself.
2. **Input port** — one interface, one method, in `application/port/in/`.
3. **Command** — a `@Command` record carrying domain types, not the strings they arrived as.
4. **Domain** — the rule goes in the object that owns the data. Test: if the rule lived elsewhere,
   could a second caller reach the same data and skip it?
5. **Output ports** — what the use case needs, in the application's own words. No driver types.
6. **The use case** — load, decide, save, announce. `@Transactional` here and nowhere below.
7. **Adapters** — translate only.
8. **Migration** — additive; see [P-110](../principles/P-110-expand-migrate-contract.md).
9. **Tests** — rules in the domain test, orchestration in the use case test, mapping in a slice
   test, the real path in one integration test.

## The three decisions that go wrong

**Where the rule lives.** A rule in the use case is invisible to every other caller of the
aggregate. If placing and importing an order both have to respect it, and only one of them does,
the bug appears months later in a batch job.

**What the command carries.** A command holding `String currency` pushes parsing into the use case,
and every future caller has to remember it. A command holding `Money` makes the invalid case
unrepresentable past the adapter.

**Where the event is published.** Through `ApplicationEventPublisher`, never straight to a broker:
the state change and the announcement have to commit together
([P-072](../principles/P-072-transactional-outbox.md)).

## Changing an existing use case

Changing what an operation does is a change to its specification first. If the failure table in the
`docs/use-cases` file does not change, check whether you have quietly changed behaviour a client
depends on — and if you have, that is an API version question ([P-080](../principles/P-080-api-versioning.md)).
