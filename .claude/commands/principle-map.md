---
description: Regenerate the principle map and report what nothing enforces
---

Run `tools/principle-map.sh` to regenerate `docs/reference/principle-map.md` from the rules and
annotations actually present.

Then report:

- how many principles are mechanically enforced, and by how many rules
- which are not enforced at all, and — reading each one — whether that is a deliberate choice the
  principle document explains, or a gap nobody decided on
- any principle whose **Enforced by** row disagrees with what the map found

For a genuine gap, propose the rule that would close it and where it belongs. Do not implement it
without being asked.
