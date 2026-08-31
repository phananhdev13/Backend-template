# UC-AGT-001 — Register an agent

| | |
|---|---|
| **Actor** | Platform engineer |
| **Trigger** | `POST /agents` |
| **Implemented by** | `RegisterAgentService` |
| **Publishes** | none |

## Intent
An engineer defines a new AI agent in the registry: a name and its first version, in `DRAFT`
status. Registering an agent does not put it in front of any caller - a draft is safe to iterate on
before anything depends on it.

## Preconditions
- The agent name is not already registered. Names are how humans and dashboards find an agent, and
  two agents sharing one would make every lookup ambiguous.
- The version declares at least one capability - a non-blank system prompt or at least one tool -
  or it cannot do anything once activated.

## Rules
1. Registration always creates exactly one version, and that version is always `DRAFT`. There is no
   way to register an agent that is immediately active - activation is a separate, auditable
   decision (see UC-AGT-003).
2. The model reference (provider and model id) is recorded but not validated against the provider -
   this registry is metadata, not a deployment target, and does not call out to verify a model
   exists.

## Success
`201 Created`, a `Location` header pointing at the agent, and a body carrying the agent identifier
and the identifier of its first version.

## Failures
| Condition | `ErrorKind` | Code | Status |
|---|---|---|---|
| Name already registered | CONFLICT | `agent.name-taken` | 409 |
| No system prompt and no tools | BUSINESS_RULE | `agent-version.no-capability` | 422 |
| Malformed request body | VALIDATION | `validation.failed` | 400 |
