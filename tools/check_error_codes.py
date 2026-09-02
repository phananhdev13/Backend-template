"""Domain error codes are API surface, so they are registered and diffed like any other contract.

A client branches on the `type` URI of a problem response, which is derived from the code. Renaming
one is exactly as breaking as renaming a JSON field, and exactly as invisible in a diff unless
something is watching.

Discovery is STRUCTURAL, not an allowlist of class names. An earlier version of this file matched a
hard-coded list of four kernel exception types, which made it blind to the two patterns the
principles actually recommend: a service-local `DomainException` subtype (P-120 tells you to write
one) and a convenience factory that synthesises its code (`NotFoundException.of`). Both raised
client-visible codes that never reached the registry while this checker reported "no drift" - a
false negative is worse than no check, because it is trusted. So:

* the set of exception types is derived by walking `extends` to `DomainException`, and
* a code that this file cannot resolve statically is a hard ERROR, never a silent skip.

That second rule is what keeps the blind spot closed: adding a new way to build a code fails the
build until this checker is taught to see it.
"""

import json
import os
import re
import sys

REGISTRY = "contracts/errors/registry.json"
ROOT_EXCEPTION = "DomainException"

# Must stay in step with libs/web-support/src/main/java/com/acme/web/ErrorKindStatus.java, which is
# the single mapping the edge actually applies. A disagreement here means the registry documents a
# status clients will never see.
ERROR_KIND_STATUS = {
    "VALIDATION": 400,
    "NOT_FOUND": 404,
    "CONFLICT": 409,
    "BUSINESS_RULE": 422,
    "UNAUTHENTICATED": 401,
    "FORBIDDEN": 403,
    "DEPENDENCY_FAILURE": 502,
    "TIMEOUT": 504,
    "RATE_LIMITED": 429,
}

# Static factories on an exception type that BUILD a code out of their arguments instead of taking
# one. Each entry says how to reproduce that construction from the call site's first string literal.
# A static factory NOT listed here is an error: it could synthesise a code nothing here can see,
# which is precisely the hole this file exists to close.
SYNTHESISING_FACTORIES = {
    # NotFoundException.of("Agent", id) -> "agent.not-found"
    ("NotFoundException", "of"): lambda literal: f"{literal.lower()}.not-found",
    # ValidationException.field("name", "must not be blank") -> the one code, whatever the field.
    ("ValidationException", "field"): lambda literal: "validation.failed",
}

CLASS_DECLARATION = re.compile(r"\bclass\s+(\w+)\s+extends\s+(\w+)")
SUPER_ERROR_KIND = re.compile(r"\bsuper\s*\(\s*ErrorKind\.([A-Z_]+)")
STRING_LITERAL = re.compile(r'^\s*"([^"\\]*)"')
CODE = re.compile(r"^[a-z0-9.-]+$")


def java_files() -> list[str]:
    paths = []
    for root, dirs, files in os.walk("."):
        dirs[:] = [d for d in dirs if d not in {".git", "target", "node_modules"}]
        paths.extend(os.path.join(root, name) for name in files if name.endswith(".java"))
    return sorted(paths)


def read(path: str) -> str:
    return open(path, encoding="utf-8", errors="ignore").read()


def exception_types(paths: list[str]) -> tuple[dict[str, str], dict[str, str]]:
    """Every transitive subtype of DomainException, mapped to its ErrorKind and declaring file."""
    parents: dict[str, str] = {}
    declared_in: dict[str, str] = {}
    for path in paths:
        for name, parent in CLASS_DECLARATION.findall(read(path)):
            parents[name] = parent
            declared_in[name] = path

    subtypes = set()
    for name in parents:
        seen, cursor = set(), name
        while cursor in parents and cursor not in seen:
            seen.add(cursor)
            cursor = parents[cursor]
            if cursor == ROOT_EXCEPTION:
                subtypes.add(name)
                break

    kinds: dict[str, str] = {}
    for name in subtypes:
        found = SUPER_ERROR_KIND.search(read(declared_in[name]))
        # A subtype whose constructor takes the kind as a parameter cannot be pinned to one kind;
        # its codes are still discovered, they just carry no kind to cross-check.
        if found:
            kinds[name] = found.group(1)
    return kinds, {name: declared_in[name] for name in subtypes}


def first_literal(text: str, open_paren: int) -> str | None:
    """The first argument at `open_paren`, if it is a plain string literal."""
    match = STRING_LITERAL.match(text[open_paren + 1 :])
    return match.group(1) if match else None


def discover(paths: list[str], kinds: dict[str, str], declared_in: dict[str, str]):
    """Returns (code -> (kind, first path)) and a list of unresolvable-construction errors."""
    found: dict[str, tuple[str | None, str]] = {}
    problems: list[str] = []
    names = "|".join(sorted(declared_in, key=len, reverse=True))
    construction = re.compile(rf"\bnew\s+({names})\s*\(")
    factory_call = re.compile(rf"\b({names})\s*\.\s*(\w+)\s*\(")

    for path in paths:
        text = read(path)

        for match in construction.finditer(text):
            type_name = match.group(1)
            literal = first_literal(text, match.end() - 1)
            if literal is not None and CODE.match(literal):
                found.setdefault(literal, (kinds.get(type_name), path))
            elif os.path.abspath(path) != os.path.abspath(declared_in[type_name]):
                # Inside its own file a type may legitimately build a code (that is what a
                # synthesising factory is). Anywhere else, a computed code is invisible here.
                problems.append(
                    f"{path} constructs {type_name} with a code this checker cannot read. "
                    "An error code is API surface: pass a string literal so it can be registered."
                )

        for match in factory_call.finditer(text):
            type_name, method = match.group(1), match.group(2)
            if os.path.abspath(path) == os.path.abspath(declared_in[type_name]):
                continue
            recipe = SYNTHESISING_FACTORIES.get((type_name, method))
            if recipe is None:
                problems.append(
                    f"{path} calls {type_name}.{method}(...), which tools/check_error_codes.py does "
                    "not know how to resolve to an error code. Add it to SYNTHESISING_FACTORIES so "
                    "the code it builds stays visible to this check."
                )
                continue
            literal = first_literal(text, match.end() - 1)
            if literal is None:
                problems.append(
                    f"{path} calls {type_name}.{method}(...) with a non-literal first argument, so "
                    "the error code it builds cannot be registered. Pass a literal."
                )
                continue
            found.setdefault(recipe(literal), (kinds.get(type_name), path))

    return found, problems


def main() -> int:
    if not os.path.exists(REGISTRY):
        print(f"MISSING  {REGISTRY} does not exist.")
        return 1

    paths = java_files()
    kinds, declared_in = exception_types(paths)
    if not declared_in:
        print(f"ERROR-CODE  Found no subtype of {ROOT_EXCEPTION}. Discovery is broken, not the code.")
        return 1

    found, problems = discover(paths, kinds, declared_in)
    registry = json.load(open(REGISTRY, encoding="utf-8"))
    entries = {entry["code"]: entry for entry in registry["codes"]}

    for code in sorted(set(found) - set(entries)):
        problems.append(f"'{code}' is raised in {found[code][1]} but is not in {REGISTRY}. Add it, with its meaning.")
    for code in sorted(set(entries) - set(found)):
        problems.append(
            f"'{code}' is registered but nothing raises it. If it was renamed, that is a breaking API "
            "change: keep the old code until clients migrate, or remove it deliberately."
        )

    # The registry's `kind` and `status` are what a client integration is written against, so they
    # are checked too rather than being prose nobody verifies.
    for code in sorted(set(found) & set(entries)):
        kind, path = found[code]
        if kind is None:
            continue
        entry = entries[code]
        if entry.get("kind") != kind:
            problems.append(
                f"'{code}' is raised as ErrorKind.{kind} in {path} but {REGISTRY} records "
                f"kind '{entry.get('kind')}'."
            )
        expected = ERROR_KIND_STATUS[kind]
        if entry.get("status") != expected:
            problems.append(
                f"'{code}' is ErrorKind.{kind}, which ErrorKindStatus maps to {expected}, but "
                f"{REGISTRY} records status {entry.get('status')}."
            )

    for entry in problems:
        print(f"ERROR-CODE  {entry}")
    if problems:
        print(f"\n{len(problems)} error code problem(s).")
        print("See docs/principles/P-050-error-handling.md")
        return 1
    print(
        f"Checked {len(entries)} error code(s) from {len(declared_in)} {ROOT_EXCEPTION} subtype(s) "
        f"against {REGISTRY}: no drift."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
