#!/usr/bin/env bash
# Every documentation path this repository points at must resolve.
#
# The pointers are the mechanism: a rule failure names the principle that explains it, a class
# cites the ADR that justifies it, a skill links the guide that expands it. A pointer that resolves
# to nothing is worse than none, because it looks checked. This runs in CI so renaming a document
# breaks the build rather than the next reader's train of thought.
#
# Fenced code blocks in Markdown are skipped: an illustrative snippet may name a file that is
# deliberately hypothetical, and a checker that cannot tell the difference teaches people to write
# worse examples.
set -uo pipefail
cd "$(dirname "$0")/.."
exec python3 tools/check_doc_links.py "$@"
