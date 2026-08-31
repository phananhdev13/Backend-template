# UC-AGT-003 — Activate an agent version

| | |
|---|---|
| **Actor** | Platform engineer |
| **Trigger** | `POST /agents/{agentId}/versions/{version}/activation` |
| **Implemented by** | `ActivateAgentVersionService` |
| **Publishes** | `AgentVersionActivated` v1 |

## Intent
An engineer promotes a specific version to be the one now in effect. Everything downstream that
deploys or serves this agent is expected to react to the version that is active, not to the newest
one that happens to exist.

## Preconditions
- The agent and the named version both exist.

## Rules
1. **At most one version is active at a time.** Activating a version deactivates whichever version
   was active before it, in the same transaction - an agent is never observed, even momentarily,
   with two active versions or with an activation that silently left a stale one live.
2. Activating the version that is already active is accepted and changes nothing; it is not an
   error. A caller retrying a request they cannot confirm the outcome of should get the same answer
   the first attempt would have, not a conflict.
3. Activating a version does not touch other versions' status beyond deactivating the one that was
   active - a `DRAFT` version stays `DRAFT` until it is either activated or explicitly deprecated.

## Success
`204 No Content`. `AgentVersionActivated` is published after the transaction commits, carrying the
agent id and the newly active version number, for anything downstream that deploys or serves this
agent to react to.

## Failures
| Condition | `ErrorKind` | Code | Status |
|---|---|---|---|
| Agent does not exist | NOT_FOUND | `agent.not-found` | 404 |
| Named version does not exist on this agent | NOT_FOUND | `agent-version.not-found` | 404 |

## Notes
Published through `ApplicationEventPublisher`, so the activation and the announcement commit
together. See [P-072](../principles/P-072-transactional-outbox.md).
