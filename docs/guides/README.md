# Guides

Task-shaped instructions. A principle says what must be true and why; a guide says how to do a
particular job here.

| Guide | For |
|---|---|
| [G-010](G-010-new-service.md) | Adding a deployable service |
| [G-020](G-020-use-case.md) | Adding or changing a use case |
| [G-030](G-030-events.md) | Publishing or consuming an event |

Each has a matching skill under `.claude/skills/` carrying the same procedure in the form an agent
loads on demand. When you change one, change the other: `tools/check-doc-links.sh` will catch a
broken pointer, but nothing catches a stale paragraph.
