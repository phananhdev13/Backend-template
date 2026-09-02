# UC-AGT-004 — Record an activation in the audit trail

| | |
|---|---|
| **Actor** | The platform itself, reacting to a published fact |
| **Trigger** | `AgentVersionActivated` v1, consumed by `AgentActivationAuditListener` (consumer group `audit-log`) |
| **Implemented by** | `RecordActivationAuditService` |
| **Publishes** | — |

## Intent
Keep a permanent, append-only record of which version of an agent became active and when, so that
"what was this agent running on the third of the month" is answerable after the fact — including
for versions that have since been superseded or deprecated.

The audit trail is deliberately not derived from the agent aggregate: the aggregate holds current
state, and current state cannot answer a question about the past.

## Preconditions
- The activation actually happened. This use case records history; it never decides it.

## Rules
1. **One row per activation**, keyed by agent and version. The listener's delivery ledger, not this
   use case, is what makes a redelivery a no-op — see the note on the transaction below.
2. This use case writes and never reads. It resolves nothing, validates nothing and refuses
   nothing: an audit trail that can reject a fact that already happened is not an audit trail.
3. The recorded instant is the domain instant carried by the event (`occurredAt`), not the time the
   row was written. They differ by however long the event sat in the broker.

## Success
The row exists. There is no response: the caller is a broker.

## Why it is a use case rather than three lines in the listener
It was three lines in the listener, and that put a transaction boundary and a SQL statement inside
a class whose job is to translate a message ([P-040](../principles/P-040-inbound-adapters-translate.md),
[P-030](../principles/P-030-use-case-unit-of-application-logic.md)). The listener still opens the
transaction — that is the one thing an adapter may do here, because the delivery mark and this
write have to commit together or a rebalance between them loses the audit row
([P-071](../principles/P-071-idempotency.md)) — but the operation itself is named, metered under
`UC-AGT-004` and reachable from anything else that ever needs to record an activation.
