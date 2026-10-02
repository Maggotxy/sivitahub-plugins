#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
PROXY_URL=${IMAGE_PROXY:-http://127.0.0.1:7890}

python3 -m venv "$SCRIPT_DIR/.venv"
"$SCRIPT_DIR/.venv/bin/python" -m pip install \
    --proxy "$PROXY_URL" \
    --requirement "$SCRIPT_DIR/requirements.txt"
