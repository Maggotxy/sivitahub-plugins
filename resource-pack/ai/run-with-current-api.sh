#!/usr/bin/env bash
set -euo pipefail

CODEX_DIR=${CODEX_DIR:-${CODEX_HOME:-$HOME/.codex}}
AUTH_FILE="$CODEX_DIR/auth.json"
CONFIG_FILE="$CODEX_DIR/config.toml"

[[ -f "$AUTH_FILE" ]] || { echo "Codex auth file not found: $AUTH_FILE" >&2; exit 1; }
[[ -f "$CONFIG_FILE" ]] || { echo "Codex config not found: $CONFIG_FILE" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

export OPENAI_API_KEY=${OPENAI_API_KEY:-$(jq -er '.OPENAI_API_KEY' "$AUTH_FILE")}
if [[ -z "${OPENAI_BASE_URL:-}" ]]; then
    OPENAI_BASE_URL=$(python3 - "$CONFIG_FILE" <<'PY'
import pathlib
import sys
import tomllib

config = tomllib.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
provider = config.get("model_provider")
print(config["model_providers"][provider]["base_url"])
PY
)
    export OPENAI_BASE_URL
fi

PROXY_URL=${IMAGE_PROXY:-http://127.0.0.1:7890}
unset ALL_PROXY all_proxy
export HTTP_PROXY=$PROXY_URL
export HTTPS_PROXY=$PROXY_URL
export http_proxy=$PROXY_URL
export https_proxy=$PROXY_URL
exec "$@"
