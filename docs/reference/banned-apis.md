# Banned APIs

Each of these is rejected by checkstyle or by the edit hook in `tools/hooks/java-guardrails.sh`.
They are banned because there is a supported replacement in this codebase and because mixing the
two produces failures that are hard to attribute.

| Banned | Use instead | Why |
|---|---|---|
| `java.util.Date`, `java.util.Calendar`, `java.sql.Date` | `java.time.*` | Mutable, not thread-safe, and silently timezone-dependent. `Date` is a timestamp pretending to be a date. |
| `System.out`, `System.err` | SLF4J | Console output carries no correlation id, no level and no structure, and is invisible to the log pipeline. |
| `.printStackTrace()` | `log.error("…", ex)` | Writes to stderr with no context and no correlation id, so the trace cannot be joined to the request that produced it. |
| `java.util.logging`, `log4j`, `commons-logging` | SLF4J | Output through a second facade misses the MDC, so it loses the correlation identifier the rest of the logs carry. |
| `org.junit.Assert`, `org.junit.jupiter.api.Assertions` | AssertJ `assertThat` | One assertion vocabulary. AssertJ failure messages say what the value was, not only that it was wrong. |
| `catch (Throwable)`, `catch (Error)` | Catch what you can handle | Swallows `OutOfMemoryError` and `StackOverflowError`, turning a JVM failure into a silent wrong answer. |
| `com.fasterxml.jackson.*` | `tools.jackson.*` | Jackson 2. Spring Boot 4 defaults to Jackson 3, and mixing the two gives two ObjectMappers with different configuration. |
| `@MockBean`, `@SpyBean` | `@MockitoBean`, `@MockitoSpyBean` | Removed in Spring Boot 4. |
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` | Deprecated in Boot 4. |
| `spring-boot-starter-aop` | `spring-boot-starter-aspectj` | Removed in Boot 4; the coordinate does not resolve. |
| `org.springframework.cloud:*` | See [ADR-0004](../adr/0004-do-not-adopt-spring-cloud.md) | The current release train targets Boot 4.0, not 4.1. |
| `org.testcontainers:postgresql` (and other 1.x names) | `org.testcontainers:testcontainers-postgresql` | Testcontainers 2 renamed every module artifact. |
| `sun.*`, `com.sun.*` | A public API | Internal JDK classes; unavailable or different on the next JDK. |

## Adding to this list

A ban is worth adding when the wrong choice is easy to make, hard to spot in review, and has a
single right answer. If the answer is "it depends", it belongs in a principle or a skill, not here.

Add the pattern to `build/checkstyle/checkstyle.xml` with a message naming the replacement, add a
row above, and — if the mistake is worth catching before the build runs — add it to
`tools/hooks/java-guardrails.sh` too.
