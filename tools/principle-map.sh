#!/usr/bin/env bash
# Regenerates docs/reference/principle-map.md from the rules and annotations actually present.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 tools/principle_map.py "$@"
