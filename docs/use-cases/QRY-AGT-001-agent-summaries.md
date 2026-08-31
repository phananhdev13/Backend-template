# QRY-AGT-001 — Agent summaries

| | |
|---|---|
| **Actor** | Platform engineer, an internal dashboard |
| **Trigger** | `GET /agents`, `GET /agents/{agentId}` |
| **Implemented by** | `AgentSummaryQuery` |

## Intent
List registered agents with their currently active version, or fetch one - enough to populate a
dashboard without hydrating every version of every agent to compute it.

## Shape
`agentId`, `name`, `activeVersion` (nullable - an agent with no activated version yet has none),
`versionCount`.

## Why this bypasses the domain
Listing agents with their active version is one indexed join; hydrating every `AgentDefinition`
aggregate - every version of every agent - to read one field off each would be the N+1 this
principle exists to avoid. See
[P-032](../principles/P-032-reads-and-writes-shaped-separately.md).

## Constraints
- Read-only: `ReadModelRules.readModelsHaveNoSideEffects` holds this to it.
- Page size is capped server-side, matching the convention in `OrderSummaryQuery`.
