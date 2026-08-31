#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 tools/check_error_codes.py "$@"
