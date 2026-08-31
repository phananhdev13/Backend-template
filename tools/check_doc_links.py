"""Verify that every repository path mentioned in docs, code and config resolves."""

import os
import re
import sys

REFERENCE = re.compile(
    r"(?:docs|contracts|build|tools|libs|services|platform)/[A-Za-z0-9._/-]+"
    r"\.(?:md|json|xml|sql|sh|yml|yaml|py)"
)
SCANNED_SUFFIXES = (".java", ".md", ".xml", ".sh", ".yml", ".yaml", ".py")
SKIPPED_DIRS = {"target", ".git", ".idea", "node_modules"}
SKIPPED_FILES = {".flattened-pom.xml"}
# A test fixture's job is to be a plausible, throwaway example, not a real published contract - an
# @EventContract in src/test/java/**/fixture/ names a schema path so the annotation compiles, not
# because a consumer will ever generate from it. Requiring a real file there would mean creating
# schema JSON with no reader just to satisfy this check, which is exactly the "looks checked but
# is not" failure this checker exists to catch everywhere else.
FIXTURE_PATH = re.compile(r"[\\/]src[\\/]test[\\/].*[\\/]fixtures?[\\/]")


def references_in(path: str) -> list[tuple[int, str]]:
    """Every path reference in a file, ignoring fenced code blocks in Markdown."""
    found: list[tuple[int, str]] = []
    in_fence = False
    is_markdown = path.endswith(".md")
    with open(path, encoding="utf-8", errors="ignore") as handle:
        for number, line in enumerate(handle, start=1):
            if is_markdown and line.lstrip().startswith("```"):
                in_fence = not in_fence
                continue
            if in_fence:
                continue
            found.extend((number, match.group(0)) for match in REFERENCE.finditer(line))
    return found


MARKDOWN_LINK = re.compile(r"\]\(([^)#\s]+)(?:#[^)\s]*)?\)")


def relative_links_in(path: str) -> list[tuple[int, str]]:
    """Relative markdown links, resolved against the linking file's directory."""
    if not path.endswith(".md"):
        return []
    found: list[tuple[int, str]] = []
    base = os.path.dirname(path)
    in_fence = False
    with open(path, encoding="utf-8", errors="ignore") as handle:
        for number, line in enumerate(handle, start=1):
            if line.lstrip().startswith("```"):
                in_fence = not in_fence
                continue
            if in_fence:
                continue
            for match in MARKDOWN_LINK.finditer(line):
                target = match.group(1)
                if target.startswith(("http://", "https://", "mailto:", "/")):
                    continue
                found.append((number, os.path.normpath(os.path.join(base, target))))
    return found


def main() -> int:
    broken = []
    for root, dirs, files in os.walk("."):
        dirs[:] = [d for d in dirs if d not in SKIPPED_DIRS]
        for name in files:
            if not name.endswith(SCANNED_SUFFIXES) or name in SKIPPED_FILES:
                continue
            path = os.path.join(root, name)
            if FIXTURE_PATH.search(path):
                continue
            for number, target in references_in(path):
                if not os.path.exists(target):
                    broken.append(f"{path}:{number} -> {target}")
            for number, target in relative_links_in(path):
                if not os.path.exists(target):
                    broken.append(f"{path}:{number} -> {target}")

    for entry in sorted(set(broken)):
        print(f"BROKEN  {entry}")
    if broken:
        print(f"\n{len(set(broken))} documentation reference(s) do not resolve.")
        print("Fix the reference or restore the document.")
        print("See docs/principles/P-000-repository-is-the-only-context.md")
        return 1
    print("All documentation references resolve.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
