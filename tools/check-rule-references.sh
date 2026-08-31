#!/usr/bin/env bash
# Every ArchUnit rule the principles claim must actually exist.
#
# A principle that names an enforcing rule makes a checkable promise: read this, and the build will
# hold you to it. When the rule is not there the promise is false in the most damaging way - the
# reader believes a class of mistake is caught and stops looking for it.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 tools/check_rule_references.py "$@"
