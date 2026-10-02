#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
PACK_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
PYTHON_BIN=${IMAGE_PYTHON:-$SCRIPT_DIR/.venv/bin/python}
IMAGE_GEN=${IMAGE_GEN_CLI:-${CODEX_HOME:-$HOME/.codex}/skills/.system/imagegen/scripts/image_gen.py}
CHROMA_TOOL=${CHROMA_TOOL:-${CODEX_HOME:-$HOME/.codex}/skills/.system/imagegen/scripts/remove_chroma_key.py}

[[ $# -ge 2 ]] || { echo "Usage: $0 <asset-id> <edit instruction> [--apply]" >&2; exit 1; }
ASSET_ID=$1
INSTRUCTION=$2
APPLY=false
[[ "${3:-}" == "--apply" ]] && APPLY=true
filename="$ASSET_ID.png"
jq -e --arg out "$filename" 'select(.out == $out)' "$SCRIPT_DIR/prompts.jsonl" >/dev/null \
    || { echo "Unknown asset id: $ASSET_ID" >&2; exit 1; }
input="$SCRIPT_DIR/output/source/$filename"
[[ -f "$input" ]] || { echo "Generate the base asset first: $input" >&2; exit 1; }
version=$(date -u +%Y%m%dT%H%M%SZ)
source="$SCRIPT_DIR/output/source/${ASSET_ID}-edit-$version.png"
keyed="$SCRIPT_DIR/output/keyed/${ASSET_ID}-edit-$version.png"
final="$SCRIPT_DIR/output/final/${ASSET_ID}-edit-$version.png"

"$PYTHON_BIN" "$IMAGE_GEN" edit --model gpt-image-2 --image "$input" --out "$source" \
    --size 1024x1024 --quality medium --use-case precise-object-edit \
    --prompt "$INSTRUCTION" \
    --constraints "Change only the requested feature. Preserve the same subject, silhouette, strict pixel-art style, scale and palette. Keep a perfectly uniform #00ff00 background with no shadow, text, logo, watermark or extra objects."
"$PYTHON_BIN" "$CHROMA_TOOL" --input "$source" --out "$keyed" \
    --auto-key border --soft-matte --transparent-threshold 12 --opaque-threshold 220 --despill --force
"$PYTHON_BIN" "$SCRIPT_DIR/pixelize.py" --input "$keyed" --out "$final" --size 32 --force
if $APPLY; then
    cp "$final" "$PACK_DIR/assets/originalrpg/textures/item/$filename"
    "$PACK_DIR/build.sh"
fi
echo "$final"
