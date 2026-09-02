# UC-AGT-005 — Provision the deployment for an activated version

| | |
|---|---|
| **Actor** | The platform itself, working a queued task |
| **Trigger** | `ProvisionAgentDeploymentTask` on `agents.provision-deployment`, consumed by `ProvisionAgentDeploymentWorker` |
| **Implemented by** | `ProvisionAgentDeploymentService` |
| **Publishes** | — |

## Intent
Record that serving infrastructure exists for a specific version of an agent. Activation
([UC-AGT-003](UC-AGT-003-activate-agent-version.md)) decides *which* version should serve; this is
the slower, retryable work of making that true, which is why it travels as a queued task rather
than happening inside the activation request.

## Preconditions
- The version was activated. The task carries everything needed; this use case loads no aggregate.

## Rules
1. **Idempotent by (agent, version).** Provisioning the same version twice records it once. The
   guarantee is the table's own conflict clause rather than a deduplication store, so it still
   holds for a replay that arrives long after any retention window has expired
   ([P-071](../principles/P-071-idempotency.md)).
2. The recorded instant is when the task was submitted, not when it was worked. The gap between
   them is queue latency and is exactly what an operator wants to be able to see.
3. Failure propagates. The worker does not catch and acknowledge — a swallowed failure here is an
   agent that is active and unserved, which is worse than a redelivery
   ([P-042](../principles/P-042-event-handlers-delivery-contract.md)).

## Success
The row exists. There is no response: the caller is a queue.

## Why it is a use case rather than the worker's own body
The worker is an adapter: it turns a message into a command and delegates. Keeping the write here
means an operator retrying a failed provision, or a backfill over versions activated before this
queue existed, reaches the same code with the same metric (`UC-AGT-005`) and the same log line —
rather than a second copy of the insert that drifts from this one.
