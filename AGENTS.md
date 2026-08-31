# AGENTS.md

Spring Boot 4.1 monorepo. Several deployable services, shared platform libraries, one build.

This file is a table of contents, not a manual. It stays short so that it never crowds out the
task, the code, or the document you actually need. Read the linked page for the work you are
doing; do not read all of them.

## The idea in one paragraph

Architectural rules here are not conventions to remember. Every class declares its role with an
annotation from `libs/kernel`; every rule that can be checked is an ArchUnit rule in
`libs/arch-test`; every rule points at the principle document that explains it. So a violation is
a red build with a message telling you what to read, and "what actually implements P-071?" is a
question with an answer. If you find yourself about to write a convention down in prose, check
whether it can be a rule instead.

## Commands

| | |
|---|---|
| Full gate, as CI runs it | `mvn verify` |
| Fast inner loop (skips lint) | `mvn -Pfast verify` |
| Format everything | `mvn spotless:apply` |
| One module and its dependencies | `mvn -pl services/order-service -am verify` |
| Architecture rules only | `mvn -pl services/order-service -am test -Dtest=ArchitectureTest` |
| Deep static analysis (CI, pull requests) | `mvn verify -Pdeep-analysis` |
| Coverage report | `mvn verify -Pcoverage` |

Run `mvn spotless:apply` before you commit. The formatter owns formatting; do not hand-format.

## Layout

```
platform/bom          version contract for services extracted from this repo
libs/kernel           annotations + shared types. No framework dependencies, ever.
libs/arch-test        the ArchUnit rules that make the annotations load-bearing
libs/web-support      RFC 9457 problem responses, one exception translation point
libs/persistence-support   auditable entities, optimistic locking, migration layout
libs/messaging-support     the only module that knows Kafka or RabbitMQ exists
libs/observability-support correlation identifier, log and metric conventions
services/<name>       one deployable service per module
contracts/events      published event schemas
docs/                 principles, decisions, guides, plans
build/                checkstyle, spotbugs and formatter configuration
tools/                scripts used by hooks and CI
```

Inside a service, a feature is a vertical slice and a Spring Modulith application module:

```
com.acme.<service>.<feature>/
  domain/         @AggregateRoot @ValueObject @DomainPolicy, event records
  application/    @UseCase, port/in (@InputPort), port/out (@OutputPort), @ReadModel
  adapter/in/     @InboundAdapter(REST), @EventHandler
  adapter/out/    @OutboundAdapter(port = …, kind = …)
  config/         @ArchConfig
```

Dependencies point inwards: adapter → application → domain. `Layer.mayDependOn` in
`libs/kernel` is the single definition of that rule, and `LayeringRules` enforces it.

## Where to go next

| If you are… | Read |
|---|---|
| deciding where new code belongs | [docs/reference/layout.md](docs/reference/layout.md) |
| adding a use case | skill `use-case` |
| publishing or consuming an event | skill `events`, [P-070](docs/principles/P-070-event-semantics.md) |
| adding a new service | skill `new-service` |
| touching persistence or a migration | skill `persistence` |
| calling another service | skill `resilience` |
| designing or changing an HTTP API | skill `api-design` |
| writing tests | skill `testing` |
| **writing any Spring code at all** | skill `spring-boot-4` — this repo is Boot 4, not 3 |
| making a decision worth keeping | skill `arch-decision` |
| looking for the rule behind a build failure | [docs/principles/README.md](docs/principles/README.md) |
| looking for why something is the way it is | [docs/adr/README.md](docs/adr/README.md) |

## Non-negotiables

These cost the most when missed, because the build catches them late or not at all.

1. **This is Spring Boot 4, not 3.** `spring-boot-starter-webmvc` (not `-web`),
   `spring-boot-starter-aspectj` (not `-aop`), `@MockitoBean` (not `@MockBean`), Jackson 3 under
   `tools.jackson`, JUnit Jupiter 6, Testcontainers 2 (`testcontainers-postgresql`, not
   `postgresql`). `@SpringBootTest` no longer gives you MockMvc — add `@AutoConfigureMockMvc`.
   Read the `spring-boot-4` skill before writing Spring code from memory.
2. **Spring Cloud is not available here** and adding it will not work: its current release train
   targets Boot 4.0, not 4.1. See [ADR-0004](docs/adr/0004-do-not-adopt-spring-cloud.md) for what to use
   instead of OpenFeign, Stream binders and circuit breakers.
3. **The domain imports no framework.** Not Spring, not Jakarta Persistence, not a serialiser.
4. **Every class in a service declares a role annotation.** Unannotated is a build failure, not a
   default.
5. **An event's semantics live on the event.** Key, retention, ordering and delivery go in
   `@EventContract`, never only in broker configuration.
6. **Never lower a quality gate to make a build pass.** If a rule is wrong, change the rule and
   the principle behind it in the same commit, and say why.

## Working agreements

- Plans and specifications live in `docs/exec-plans/` and `docs/use-cases/`. Anything an agent
  cannot read in the repository does not exist — see
  [P-000](docs/principles/P-000-repository-is-the-only-context.md).
- Prefer making a rule enforceable over documenting it. A rule nobody checks decays silently.
- When you deviate from a principle deliberately, record it as an ADR and reference it from the
  code with `@Adr`. The build checks that the reference resolves.
