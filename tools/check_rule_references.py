"""Verify that every ArchUnit rule the principles claim actually exists.

A principle that names an enforcing rule is making a checkable promise: read this document, and
the build will hold you to it. When the rule does not exist, the promise is false in the most
damaging way - the reader believes a class of mistake is caught, and stops looking for it.
"""

import os
import re
import sys

RULES_DIR = "libs/arch-test/src/main/java/com/acme/archtest"
DOCS_DIR = "docs/principles"
# A claim may be marked "(not implemented)" - an acknowledged gap is honest, an unqualified
# promise of enforcement that does not exist is not.
CLAIM = re.compile(
    r"\b([A-Z][A-Za-z]*Rules)\.([a-zA-Z][A-Za-z0-9]*)(?:\(\))?`?(?P<gap> \(not implemented\))?"
)


def implemented() -> dict[str, set[str]]:
    """Rule field and method names declared by each rule class."""
    rules: dict[str, set[str]] = {}
    for name in os.listdir(RULES_DIR):
        if not name.endswith("Rules.java"):
            continue
        source = open(os.path.join(RULES_DIR, name), encoding="utf-8").read()
        names = set(re.findall(r"ArchRule\s+([a-zA-Z][A-Za-z0-9]*)\s*=", source))
        names |= set(re.findall(r"static\s+void\s+([a-zA-Z][A-Za-z0-9]*)\s*\(", source))
        rules[name[: -len(".java")]] = names
    return rules


def main() -> int:
    rules = implemented()
    problems = []
    for name in sorted(os.listdir(DOCS_DIR)):
        if not name.endswith(".md"):
            continue
        path = os.path.join(DOCS_DIR, name)
        for number, line in enumerate(open(path, encoding="utf-8"), start=1):
            for match in CLAIM.finditer(line):
                if match.group("gap"):
                    continue
                cls, method = match.group(1), match.group(2)
                if cls not in rules:
                    problems.append(f"{path}:{number} claims {cls}.{method}, but {cls} does not exist")
                elif method not in rules[cls]:
                    problems.append(f"{path}:{number} claims {cls}.{method}, but that rule is not declared")

    for entry in sorted(set(problems)):
        print(f"UNBACKED  {entry}")
    if problems:
        print(f"\n{len(set(problems))} principle(s) claim enforcement that does not exist.")
        print("Either implement the rule in libs/arch-test, or change the principle's")
        print("'Enforced by' entry to name the real mechanism (checkstyle, CI script, or review).")
        return 1
    print("Every claimed rule exists.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
