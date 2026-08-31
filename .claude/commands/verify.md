---
description: Run the full gate exactly as CI does, and report what failed with the principle behind it
---

Run the complete verification gate for this repository and report the result.

```bash
mvn -B -ntp spotless:apply
mvn -B -ntp verify
tools/check-doc-links.sh
tools/check-rule-references.sh
tools/check-error-codes.sh
tools/check-migrations.sh
```

Run every step even if an earlier one fails, so the report is complete in one pass — then summarise:

- what passed
- what failed, with the **shortest** excerpt that identifies the cause
- for each architecture failure, the principle document its message names, and one line on what that
  principle protects
- the smallest change that would fix each failure

Do not fix anything unless asked. Do not weaken a rule, lower a threshold, skip a test or add a
suppression to get to green — if a gate looks wrong, say so and explain why, and let the human
decide.

If everything passes, say so in one line and stop.
