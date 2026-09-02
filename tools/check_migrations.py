"""Flyway migration rules that live in files rather than in bytecode.

ArchUnit reads compiled classes, so it cannot see a migration at all. These checks are the part
of P-110 and P-112 that a machine can still hold you to.
"""

import os
import re
import subprocess
import sys

NAME = re.compile(r"^V(\d+)__[a-z0-9_]+\.sql$")
DESTRUCTIVE = re.compile(
    r"\b(drop\s+table|drop\s+column|alter\s+table\s+\S+\s+drop|rename\s+column|"
    r"alter\s+column\s+\S+\s+set\s+not\s+null)\b",
    re.IGNORECASE,
)
HYPERTABLE = re.compile(r"create_hypertable\s*\(|timescaledb\.hypertable\b", re.IGNORECASE)
RETENTION_POLICY = re.compile(r"add_retention_policy\s*\(", re.IGNORECASE)
RETENTION_OPT_OUT = re.compile(r"--\s*retention:", re.IGNORECASE)


def migration_files() -> list[str]:
    found = []
    for root, dirs, files in os.walk("."):
        dirs[:] = [d for d in dirs if d not in {".git", "target"}]
        if not root.replace(os.sep, "/").endswith(tuple(("db/migration",)) ) and "db/migration" not in root.replace(os.sep, "/"):
            continue
        found.extend(os.path.join(root, f) for f in files if f.endswith(".sql"))
    return sorted(found)


def merge_base() -> str | None:
    for base in ("origin/main", "origin/master", "main", "master"):
        result = subprocess.run(["git", "rev-parse", "--verify", base], capture_output=True, text=True)
        if result.returncode == 0:
            return base
    return None


def hypertables_without_a_retention_policy(files: list[str]) -> list[str]:
    # Grouped by directory, not by file: expand-migrate-contract means the hypertable and its
    # retention policy are often legitimately in separate migrations of the same service.
    by_directory: dict[str, list[str]] = {}
    for path in files:
        by_directory.setdefault(os.path.dirname(path), []).append(path)

    problems: list[str] = []
    for paths in by_directory.values():
        combined = ""
        first_match_path: str | None = None
        first_match_line: int | None = None
        for path in sorted(paths):
            text = open(path, encoding="utf-8").read()
            combined += text
            if first_match_path is None:
                match = HYPERTABLE.search(text)
                if match:
                    first_match_path = path
                    first_match_line = text[: match.start()].count("\n") + 1
        if first_match_path and not (RETENTION_POLICY.search(combined) or RETENTION_OPT_OUT.search(combined)):
            problems.append(
                f"{first_match_path}:{first_match_line}: creates a hypertable with no "
                "add_retention_policy anywhere in this service's migrations, and no '-- retention:' "
                "comment recording a deliberate decision to keep it unbounded. A hypertable with no "
                "stated retention grows forever by default. "
                "See docs/principles/P-112-time-series-hypertables.md"
            )
    return problems


def main() -> int:
    problems: list[str] = []
    files = migration_files()

    for path in files:
        name = os.path.basename(path)
        if not NAME.match(name):
            problems.append(
                f"{path}: name must be V<number>__snake_case_description.sql. "
                "Flyway orders migrations by that number and will not run this one."
            )

    # An applied migration is immutable: Flyway checksums it, and every environment that already
    # ran it refuses to start when the checksum changes.
    base = merge_base()
    if base and files:
        changed = subprocess.run(
            ["git", "diff", "--diff-filter=M", "--name-only", f"{base}...HEAD", "--"] + files,
            capture_output=True,
            text=True,
        )
        for path in filter(None, changed.stdout.splitlines()):
            problems.append(
                f"{path}: modified after being committed. Flyway checksums applied migrations, so "
                "every environment that already ran this one will refuse to start. Write a new migration."
            )

    # Destructive statements belong in the contract step, which is a separate deployment from the
    # expand step that introduced the replacement.
    for path in files:
        text = open(path, encoding="utf-8").read()
        if "contract" in os.path.basename(path).lower():
            continue
        for match in DESTRUCTIVE.finditer(text):
            line = text[: match.start()].count("\n") + 1
            problems.append(
                f"{path}:{line}: '{match.group(0).strip()}' is destructive. Old instances are still "
                "running when a migration lands. Split into expand, migrate and contract, and name "
                "the final one *_contract_*.sql."
            )

    problems.extend(hypertables_without_a_retention_policy(files))

    for entry in problems:
        print(f"MIGRATION  {entry}")
    if problems:
        print(f"\n{len(problems)} migration problem(s).")
        print("See docs/principles/P-110-expand-migrate-contract.md")
        return 1
    print(f"Checked {len(files)} migration(s): all well-formed, unmodified and non-destructive.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
