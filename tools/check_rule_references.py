"""Verify that every ArchUnit rule the principles claim actually exists AND actually runs.

A principle that names an enforcing rule is making a checkable promise: read this document, and
the build will hold you to it. When the rule does not exist, the promise is false in the most
damaging way - the reader believes a class of mistake is caught, and stops looking for it.

Existence alone was not enough. A rule class that no service's `ArchitectureTest` registers is
declared, is named by a principle, passes this check - and executes nowhere. That is how
`WorkflowRules` and `BlobStorageRules` came to be the sole claimed enforcement for P-033 and
P-044 while never running once. So this file now also checks the wiring: every rule class must be
registered by every service, because a rule that holds in one service and not another is not an
architecture rule.
"""

import os
import re
import sys

RULES_DIR = "libs/arch-test/src/main/java/com/acme/archtest"
DOCS_DIR = "docs/principles"
SERVICES_DIR = "services"
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


def architecture_tests() -> list[str]:
    """Every service's ArchitectureTest, which is where rule classes are registered."""
    found = []
    for root, dirs, files in os.walk(SERVICES_DIR):
        dirs[:] = [d for d in dirs if d != "target"]
        if "ArchitectureTest.java" in files:
            found.append(os.path.join(root, "ArchitectureTest.java"))
    return sorted(found)


def unwired(rules: dict[str, set[str]]) -> list[str]:
    """Rule classes a service's ArchitectureTest does not register."""
    suites = architecture_tests()
    if not suites:
        return [f"No ArchitectureTest found under {SERVICES_DIR}/. No rule in {RULES_DIR} runs at all."]
    problems = []
    for suite in suites:
        registered = set(re.findall(r"ArchTests\.in\(([A-Za-z0-9_]+)\.class\)", open(suite, encoding="utf-8").read()))
        for rule_class in sorted(set(rules) - registered):
            problems.append(
                f"{suite} does not register {rule_class}. Add "
                f"`@ArchTest static final ArchTests … = ArchTests.in({rule_class}.class);` - "
                "a rule class no suite registers is declared but never executed."
            )
    return problems


def main() -> int:
    rules = implemented()
    problems = unwired(rules)
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
        print(f"\n{len(set(problems))} claim(s) of enforcement that does not hold.")
        print("Either implement and register the rule in libs/arch-test, or change the principle's")
        print("'Enforced by' entry to name the real mechanism (checkstyle, CI script, or review).")
        return 1
    print(f"Every claimed rule exists, and every rule class is registered by {len(architecture_tests())} suite(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
