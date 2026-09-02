#!/usr/bin/env bash
# The half of the gate that cannot live in Maven, in one command.
#
# These read the documentation tree and git history rather than the compiled classes, so they are
# CI steps rather than Maven plugins. That split had a cost nobody had priced: AGENTS.md called
# `mvn verify` "the full gate, as CI runs it" while CI ran five more checks, so being green
# locally said nothing about being green in CI. This script is the missing half, named once and
# runnable by anyone - including an agent following AGENTS.md.
#
# Every check runs even after one fails, so a single run reports everything that is wrong.
set -uo pipefail
cd "$(dirname "$0")/.."

status=0
for check in check-doc-links check-rule-references check-error-codes check-migrations; do
  echo "== $check"
  "tools/$check.sh" || status=1
done

echo "== principle map is up to date"
# Compared against itself across a regeneration, not against git HEAD: the CI step does the
# latter, which is correct there because the tree is clean, but locally it reports every
# uncommitted regeneration as staleness and trains you to ignore it.
map=docs/reference/principle-map.md
before=$(mktemp)
cp "$map" "$before"
tools/principle-map.sh >/dev/null
if ! diff -q "$before" "$map" >/dev/null; then
  echo "$map was stale; it has been regenerated. Commit the result."
  status=1
else
  echo "$map is current."
fi
rm -f "$before"

if [ "$status" -ne 0 ]; then
  echo
  echo "Documentation gate failed. Nothing here is optional: each of these is a promise the"
  echo "repository makes to its next reader."
fi
exit "$status"
