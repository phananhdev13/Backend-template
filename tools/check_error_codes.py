"""Domain error codes are API surface, so they are registered and diffed like any other contract.

A client branches on the `type` URI of a problem response, which is derived from the code. Renaming
one is exactly as breaking as renaming a JSON field, and exactly as invisible in a diff unless
something is watching.
"""

import json
import os
import re
import sys

REGISTRY = "contracts/errors/registry.json"
# Matches the first constructor argument of a DomainException subclass: a dotted, lower-case code.
CODE = re.compile(r'new\s+(?:NotFoundException|ConflictException|BusinessRuleViolation|ValidationException)\s*\(\s*"([a-z0-9.-]+)"')
CODE_LITERAL = re.compile(r'super\s*\(\s*ErrorKind\.[A-Z_]+\s*,\s*"([a-z0-9.-]+)"')


def discovered() -> dict[str, list[str]]:
    found: dict[str, list[str]] = {}
    for root, dirs, files in os.walk("."):
        dirs[:] = [d for d in dirs if d not in {".git", "target"}]
        for name in files:
            if not name.endswith(".java"):
                continue
            path = os.path.join(root, name)
            text = open(path, encoding="utf-8", errors="ignore").read()
            for pattern in (CODE, CODE_LITERAL):
                for code in pattern.findall(text):
                    found.setdefault(code, []).append(path)
    return found


def main() -> int:
    found = discovered()
    if not os.path.exists(REGISTRY):
        print(f"MISSING  {REGISTRY} does not exist.")
        return 1
    registry = json.load(open(REGISTRY, encoding="utf-8"))
    registered = {entry["code"] for entry in registry["codes"]}

    problems = []
    for code in sorted(set(found) - registered):
        problems.append(f"'{code}' is raised in {found[code][0]} but is not in {REGISTRY}. Add it, with its meaning.")
    for code in sorted(registered - set(found)):
        problems.append(
            f"'{code}' is registered but nothing raises it. If it was renamed, that is a breaking API "
            "change: keep the old code until clients migrate, or remove it deliberately."
        )

    for entry in problems:
        print(f"ERROR-CODE  {entry}")
    if problems:
        print(f"\n{len(problems)} error code(s) drifted from the registry.")
        print("See docs/principles/P-050-error-handling.md")
        return 1
    print(f"Checked {len(registered)} error code(s) against {REGISTRY}: in step.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
