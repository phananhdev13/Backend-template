# ADR-0001 — Target Spring Boot 4.1 on Java 21

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-30 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

A template repository fixes a baseline that every service copied from it inherits for
years. Two questions had to be answered together: which Spring Boot line, and which JDK.

Spring Boot has no LTS designation. This is worth stating plainly because teams arriving
from the JDK world assume one exists and go looking for it. Every Spring Boot minor
release gets the same treatment: roughly twelve months of open-source support from its
general availability, after which security fixes are available only under commercial
subscription. There is no "stay on 3.5 for three years" option — 3.5.x reached OSS
end-of-life on 2026-06-30. Choosing Boot is choosing a twelve-month upgrade cadence; the
only decision left is which twelve months.

The candidates were Boot 4.0 (supported to 2026-12-31) and Boot 4.1.1, released
2026-08-20 and supported to roughly July 2027. Boot 4.0 would have given this template
four months of runway before the first forced upgrade.

Boot 4.x sits on Spring Framework 7.0.9 and Jakarta EE 11, and brings Spring Security
7.1.1, Hibernate 7.4.5, JUnit Jupiter 6.0.3 and Jackson 3.1.5 under the new
`tools.jackson` group id. It also removes things: `spring-boot-starter-web` is deprecated
in favour of `spring-boot-starter-webmvc`, `spring-boot-starter-aop` is gone in favour of
`spring-boot-starter-aspectj`, the OAuth2 starters are renamed under
`spring-boot-starter-security-*`, Undertow is dropped, `@MockBean` and `@SpyBean` are
replaced by `@MockitoBean` and `@MockitoSpyBean`, and `@SpringBootTest` no longer
auto-provides `MockMvc` or `TestRestTemplate`. These are one-time costs, and paying them
on 4.1 rather than 4.0 costs no more.

On the JDK side, Boot 4.1's own baseline is Java 17 and it is tested through Java 26.
Java 21 is the current widely-deployed LTS with virtual threads, pattern matching for
switch and records all generally available — the three features this codebase's style
actually leans on. Java 25 is available and is the next LTS, but base images, profilers,
bytecode tooling and the SpotBugs/ArchUnit chain lag it; picking it would trade a real
constraint (tooling that works today) for a benefit the code does not yet use.

## Decision

We target **Spring Boot 4.1.1 on Java 21**, declared once in the root `pom.xml` via the
`spring-boot-starter-parent` and `<java.version>21</java.version>`, and enforced by
`maven-enforcer-plugin` (`requireJavaVersion [21,)`, `requireMavenVersion [3.9.0,)`).
We accept the twelve-month upgrade cadence as an explicit operating commitment, not as an
event that will surprise us.

## Consequences

**Good** — The longest support window currently purchasable with zero money, and one
migration (3.x → 4.x) paid once instead of twice. Virtual threads, records and sealed
hierarchies are all baseline, so no code needs a compatibility fallback.

**Bad** — Every ecosystem library must be checked against Boot 4.1 individually; several
are not there yet. Spring Cloud in particular does not support 4.1 at all, which forced
ADR-0004. AOT processing ignores `-DskipTests` in 4.x, so a fast build must use
`-Dmaven.test.skip` instead — a footgun that costs someone an afternoon exactly once.

**Neutral** — Java 21 rather than 25 means no scoped values and no stable Structured
Concurrency API. Nothing here needs them, and the JDK bump is a property change plus a
build image change when it does.

## Alternatives considered

### Spring Boot 4.0.x

Four months older, and already past the point where its support window is shorter than a
typical service's time to first production release. The migration work from 3.x is
identical, so choosing 4.0 buys a second forced upgrade before 2026 ends for no
compensating benefit. The one honest argument for it — Spring Cloud's current train
declares `spring-boot.version` 4.0.8, so 4.0 is the only Boot line Spring Cloud supports
— was weighed and rejected in ADR-0004.

### Stay on Spring Boot 3.5.x

Past OSS end-of-life since 2026-06-30. Continuing would mean either running unpatched or
buying commercial support to stand still on a template that has not yet shipped anything.
The migration cost does not shrink by deferring it; it grows as more code is written
against the old APIs.

### Java 25

The next LTS, and the right target eventually. Rejected for now because the surrounding
toolchain — container base images, SpotBugs' bytecode analysis, agent-based coverage —
consistently trails a new class-file version by months, and this repository's value is
that its build is green on day one. Nothing in the code depends on a post-21 feature.

## Revisit when

Spring Boot 4.2 reaches general availability, **or** by 2027-04 at the latest — three
months before 4.1's OSS support window closes around July 2027, whichever comes first.
The upgrade spike is scheduled at that trigger, not when 4.1 goes red. Separately, revisit
the JDK line when a Java 25 base image, SpotBugs release and Testcontainers release are
all simultaneously available and green against Boot 4.x.
