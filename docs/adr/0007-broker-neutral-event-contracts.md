# ADR-0007 — Broker-neutral event contracts

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-30 |
| **Deciders** | Platform |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

The recurring failure in event-driven systems is not that the wrong event was published. It is
that the decisions a consumer depends on — what the partition key is, whether history is kept
or collapsed, how many times a message may arrive — live in broker configuration, far from the
code relying on them.

The failure mode is specific and slow. A topic is recreated by a Terraform module without
`cleanup.policy=compact`, or with a different key, and nothing in the codebase notices. Six
weeks later a consumer replays from the beginning and rebuilds the wrong state — and whoever
made the change had no way to know some Java class assumed otherwise.

Spring Cloud Stream's binder abstraction was the ecosystem's answer, and it is unavailable on
Boot 4.1 (ADR-0004). That removal was clarifying: Stream abstracted the *transport API* while
leaving the *semantics* in configuration — the half that actually causes incidents.

The kernel already carries the alternative. `@EventContract` is declared on the event record
and states `stream`, `version`, `partitionKey`, `payload` kind, `retention`, `retentionDays`,
`delivery`, `ordering`, `schema` and `containsPersonalData`. The supporting enums are not
decorative: `PayloadKind.FACT` cannot be compacted, because dropping superseded facts destroys
the sequence; `OrderingGuarantee.GLOBAL` needs a single partition and is almost always a
modelling mistake standing in for `PER_KEY`.

## Decision

**Event semantics are declared in Java, on the event type, and the broker configuration is
derived from that declaration.**

- Every published event is a domain-layer record implementing `DomainEvent` and annotated
  `@EventContract`. Its `occurredAt()` is the domain's clock — when the fact became true.
- `libs/messaging-support` is the **only** code that knows a broker exists. It reads
  `@EventContract` and provisions a Kafka topic (`cleanup.policy`, `retention.ms`, partitions)
  or a RabbitMQ stream (`max-age`) from it. Physical topic names are derived there, per broker
  and environment, from the logical `<context>.<event>` name — never constructed elsewhere.
- `EventEnvelope` carries the transport metadata the payload must not: `eventId` (stable across
  publish retries, so it can key de-duplication), `stream`, `version`, `partitionKey`,
  `correlationId`, trace headers, and a nullable payload for tombstones.
- Consumers are `@EventHandler(consumes = …, group = …)`, and the build checks the *pair*: a
  handler of an `AT_LEAST_ONCE` stream must be `@Idempotent`, and one relying on order must
  consume a stream that promises it. `EventContractRules` also rejects a compacted stream of
  facts, a `PER_KEY` promise with no key, and a `partitionKey` naming a record component that
  does not exist — which silently degrades to random partitioning, taking ordering with it.
- A published event is an API. Its schema is checked in at the `schema()` path, so a consumer
  elsewhere has something to generate from. **Adding an optional field is compatible; anything
  else is a new `version()` on a new stream**, run in parallel, the old deleted when it has no
  consumers — not when the new one works.
- Where a broker cannot honour a declaration, `messaging-support` **fails at startup**: RabbitMQ
  Streams have no compaction, so `COMPACTED` there is a boot failure, not a silent degradation.

## Consequences

**Good** — The decisions a consumer depends on are visible in review, where they are depended
upon, and contradictions are caught by a test rather than by a replay months later. Switching a
stream from Kafka to AMQP is a `messaging-support` concern; handlers do not change.

**Bad** — We own the binder code. Broker features `@EventContract` does not model are unreachable
without extending it, and every new broker means new translation code. Provisioning from the
application is also a privilege question — hence verify-only mode, another path to keep honest.

**Neutral** — `EFFECTIVELY_ONCE` is declarable, and stops at the broker boundary: once a handler
writes to a database, the guarantee is the handler's to keep, and `@Idempotent` still applies.

## Alternatives considered

### Spring Cloud Stream binders

The purpose-built abstraction, with a mature Kafka binder and years of production use. It is
unavailable on Boot 4.1 — Stream 5.0.3 ships in the 2025.1.3 train, which declares
`spring-boot.version` 4.0.8. On the merits it would still lose the central point: it abstracts
the API and leaves retention, compaction and ordering in configuration, so the drift this ADR
exists to prevent remains possible under it. Binder-specific properties are not reviewable.

### AsyncAPI documents as the source of truth, with code generated from them

The contract-first position, and strong: one artifact humans and machines both read, plus tooling
that generates consumers in other languages. It lost on the same ground as any external model
(ADR-0005) — a document in a separate directory drifts, and nothing fails when it does. The
compromise: the annotation is the source of truth, the schema file beside it is the published
artifact, and a test asserts its existence. Generating AsyncAPI *from* it would extend this.

### Broker configuration owned entirely by Terraform, with no application involvement

Correct on the privilege boundary — applications arguably should not create topics — and the
operational reality in many organisations. It lost because it leaves the semantics unverifiable
from the code depending on them, the incident this ADR is written against. The reconciliation is
verify-only mode: infrastructure creates the topic, the application refuses to start on mismatch.

### Schema registry with Avro or Protobuf, and registry-enforced compatibility

Genuinely better compatibility checking than a checked-in JSON schema, enforced at publish time
rather than at review. Rejected as the default because it adds a registry to every environment,
local development included, and because it checks *payload shape* only — it says nothing about
keys, retention or ordering, which is most of what `@EventContract` pins down.

## Revisit when

A third broker is needed, or a stream needs semantics `@EventContract` cannot express and the
workaround is broker-specific configuration outside `messaging-support` — either means the
vocabulary is short of the domain. Revisit the schema mechanism when a consumer outside this
monorepo appears and JSON-schema-in-git proves too weak for a boundary we do not control.
