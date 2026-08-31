# G-090 — Adopting this template

## Rename it

`com.acme` is a placeholder. Replace it everywhere before the first real commit — it is far cheaper
now than after a second service exists.

```bash
NEW_GROUP=com.yourcompany
NEW_PATH=${NEW_GROUP//./\/}

grep -rl 'com\.acme' --include='*.java' --include='*.xml' --include='*.yml' --include='*.md' . \
  | xargs sed -i "s/com\.acme/${NEW_GROUP}/g"

for module in libs/*/src/*/java services/*/src/*/java; do
  [ -d "$module/com/acme" ] || continue
  mkdir -p "$module/$(dirname "$NEW_PATH")"
  git mv "$module/com/acme" "$module/$NEW_PATH"
done

mvn spotless:apply && mvn verify
```

Also update `acme.messaging.base-packages` and `acme.ordering` in each `application.yml`, the
`ProblemTypes.BASE` URI in `libs/web-support`, and the stream prefix.

## Decide what to keep

| Piece | Keep it if | Drop it if |
|---|---|---|
| `libs/messaging-support` | you publish events | no broker; delete the module and its dependants |
| Spring Modulith | you want module verification and the outbox | a single-slice service — the registry needs a table |
| `order-service` | as a reference to copy from | you have a real service; delete it once a second exists |
| The `@Command` / `@ReadModel` roles | CQRS-shaped services | you never separate reads — but the rules cost nothing |

Delete `services/order-service` only after your first real service passes its architecture test.
Until then it is the worked example every rule was validated against.

## Tune the rules before you tune the code

The rules encode opinions. If one is wrong for you, change it — but change the principle in the same
commit and say why, because the failure message points at that document and a rule whose explanation
no longer matches is worse than no rule.

The two most likely to need adjusting: the naming suffixes in `NamingRules`, and
`ResilienceRules.remoteCallsDeclareTimeouts`, which demands a written claim rather than reading a
timeout it cannot see.

**Never relax a gate to make a build pass.** That is the one rule with no exception: it is how every
codebase that used to have standards stopped having them.

## Set the version and the gates

`revision` in the root `pom.xml` is the version of everything. `jacoco.line.coverage.min` starts at
zero — raise it as coverage grows, and never lower it.

## Wire CI

`.github/workflows/ci.yml` runs the same gate as `mvn verify` plus the documentation and migration
checks. If you use something other than GitHub Actions, the contract is: format, lint, architecture
tests, unit tests, integration tests, doc-link check, rule-reference check, migration check.
