# From `@EventContract` to broker configuration

`libs/messaging-support` is the only code that reads `@EventContract` at runtime, and the only code
that names a broker. This page is the mapping it implements.

Physical stream name: `<acme.messaging.stream-prefix>.<stream>.v<version>`. Nothing outside
`messaging-support` constructs one, so the prefix can differ per environment without touching a
single event class.

## Retention

| `StreamRetention` | Kafka topic config | RabbitMQ stream arguments |
|---|---|---|
| `TIME_WINDOW` | `cleanup.policy=delete`, `retention.ms=<retentionDays>` | `x-max-age=<retentionDays>D` |
| `COMPACTED` | `cleanup.policy=compact`, `retention.ms=-1`, `min.compaction.lag.ms`, `delete.retention.ms` | **unsupported — startup fails** |
| `COMPACTED_AND_WINDOWED` | `cleanup.policy=compact,delete`, `retention.ms=<retentionDays>` | **unsupported — startup fails** |
| `INFINITE` | `cleanup.policy=delete`, `retention.ms=-1` | `x-max-age` unset (bounded only by disk) |

### Why Rabbit refuses rather than approximates

A RabbitMQ stream is an append-only log with age and size bounds. It has no notion of "keep the
latest message per key". Mapping `COMPACTED` to `x-max-age` would satisfy the type system and
produce a stream that loses the newest value for a quiet key the moment it ages out - exactly the
data a compacted stream promises to keep. `RabbitStreamProvisioner` therefore fails at startup,
naming the event class and the two ways out: publish to Kafka, or model the stream as
`TIME_WINDOW` facts and let consumers maintain their own view.

### `delete.retention.ms` and tombstones

Deleting a key from a compacted stream means publishing the key with a null payload. Consumers only
see that tombstone if they read within `delete.retention.ms` of it being written; after that,
compaction may remove the tombstone itself and a consumer rebuilding from zero will never learn the
key was deleted. This is the mechanism behind the erasure obligation on
`containsPersonalData = true`.

## Ordering

| `OrderingGuarantee` | Kafka | RabbitMQ |
|---|---|---|
| `NONE` | configured partition count, key still set | single stream, concurrent consumers |
| `PER_KEY` | configured partition count, key from the contract | single-active-consumer per stream |
| `GLOBAL` | **forced to 1 partition** | single stream, one consumer |

`GLOBAL` caps throughput at a single consumer for the life of the stream, which is why it needs an
`@Adr`. It is almost always `PER_KEY` with the key chosen wrongly.

## Delivery

| `DeliveryGuarantee` | Producer side | Consumer obligation |
|---|---|---|
| `AT_MOST_ONCE` | `acks=0`, no producer retries | none |
| `AT_LEAST_ONCE` | `acks=all`, `enable.idempotence=true`, retries | **`@Idempotent` required** |
| `EFFECTIVELY_ONCE` | transactional producer, `min.insync.replicas` where replication allows | **`@Idempotent` still required** |

`EFFECTIVELY_ONCE` holds inside Kafka's own boundary. The moment a handler writes to a database or
calls another service, the guarantee is the handler's to keep - which is why the build requires
idempotency for it too.

## Failure handling

Retries are bounded and end in a dead-letter destination named `<physical-name>.dlq`. A consumer
that retries forever is a consumer that has stopped: the partition stops advancing and every key
behind the poison message stalls with it.

This is wired once, for every `@KafkaListener` in the service, by `MessagingSupportAutoConfiguration`
- not per listener. It registers a `DefaultErrorHandler` backed by a `DeadLetterPublishingRecoverer`,
with a `FixedBackOff` built from two properties:

```yaml
acme:
  messaging:
    kafka:
      max-delivery-attempts: 4   # first try plus three retries
      retry-backoff-ms: 1000
```

Boot's `ConcurrentKafkaListenerContainerFactoryConfigurer` applies whichever `CommonErrorHandler`
bean is present to every listener container it builds, which is why declaring the bean once in
`messaging-support` is enough - no listener method configures its own retry policy.

A message that fails to **deserialise** - the wrong schema, a class outside
`acme.messaging.kafka.trusted-packages`, truncated bytes - is not a handler exception and would
otherwise kill the consumer thread outright before the error handler ever runs. The consumer
factory wraps its `JacksonJsonDeserializer` in `ErrorHandlingDeserializer` so that failure surfaces
as a normal `DeserializationException` instead, and goes through the same retry-then-dead-letter
path as any other failure.

Configure the backoff to be longer than a plausible dependency blip and shorter than a page.
Something has to read the dead-letter destination; a DLQ nobody monitors is a delete with extra
steps.

## What is deliberately not configurable per contract

Broker addresses, credentials, TLS, partition counts beyond the ordering constraint, and consumer
concurrency are deployment concerns and live in `application.yml`. The contract carries only what a
**consumer** depends on - if changing a setting cannot break a consumer, it does not belong on the
contract.
