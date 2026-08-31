# UC-AGT-002 — Add a version to an existing agent

| | |
|---|---|
| **Actor** | Platform engineer |
| **Trigger** | `POST /agents/{agentId}/versions` |
| **Implemented by** | `AddAgentVersionService` |
| **Publishes** | none |

## Intent
An engineer proposes a new configuration for an agent that already exists - a different model, a
revised prompt, an added tool - without disturbing whichever version is currently active.

## Preconditions
- The agent exists.
- The new version declares at least one capability, exactly as at registration.

## Rules
1. A new version is always `DRAFT`. Adding a version never changes what is active; only UC-AGT-003
   does that, and only one version at a time.
2. Version numbers are assigned by the aggregate, monotonically, and are never reused - even for a
   version that is later abandoned. A reused number would let two different configurations answer
   to the same identifier at different points in the agent's history.

## Success
`201 Created`, a body carrying the new version's identifier and number.

## Failures
| Condition | `ErrorKind` | Code | Status |
|---|---|---|---|
| Agent does not exist | NOT_FOUND | `agent.not-found` | 404 |
| No system prompt and no tools | BUSINESS_RULE | `agent-version.no-capability` | 422 |
| Malformed request body | VALIDATION | `validation.failed` | 400 |
