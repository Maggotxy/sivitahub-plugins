#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
PACK_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
PYTHON_BIN=${IMAGE_PYTHON:-$SCRIPT_DIR/.venv/bin/python}
IMAGE_GEN=${IMAGE_GEN_CLI:-${CODEX_HOME:-$HOME/.codex}/skills/.system/imagegen/scripts/image_gen.py}
CHROMA_TOOL=${CHROMA_TOOL:-${CODEX_HOME:-$HOME/.codex}/skills/.system/imagegen/scripts/remove_chroma_key.py}
SOURCE_DIR="$SCRIPT_DIR/output/source"
KEYED_DIR="$SCRIPT_DIR/output/keyed"
FINAL_DIR="$SCRIPT_DIR/output/final"
DRY_RUN=false
APPLY=false

for argument in "$@"; do
    case "$argument" in
        --dry-run) DRY_RUN=true ;;
        --apply) APPLY=true ;;
        *) echo "Unknown argument: $argument" >&2; exit 1 ;;
    esac
done

[[ -x "$PYTHON_BIN" ]] || { echo "Run $SCRIPT_DIR/setup.sh first" >&2; exit 1; }
[[ -f "$IMAGE_GEN" ]] || { echo "Image CLI not found: $IMAGE_GEN" >&2; exit 1; }
mkdir -p "$SOURCE_DIR" "$KEYED_DIR" "$FINAL_DIR"

arguments=(
    generate-batch
    --input "$SCRIPT_DIR/prompts.jsonl"
    --out-dir "$SOURCE_DIR"
    --model gpt-image-2
    --size 1024x1024
    --quality low
    --concurrency 3
    --fail-fast
    --force
    --use-case stylized-concept
    --scene "perfectly flat solid #00ff00 chroma-key background"
    --style "strict original 32x32 pixel-art game sprite enlarged with nearest-neighbor blocks; hard clusters; no antialiasing; Minecraft-compatible visual language without copying official textures"
    --composition "one centered isolated object, fully visible, square silhouette, four pixels of visual padding"
    --lighting "flat readable sprite lighting with one upper-left highlight and one lower-right shadow"
    --palette "charcoal, moonsteel gray, cyan, gold, crimson and violet; maximum 24 colors; never use green in the subject"
    --constraints "background must be uniform #00ff00 with no shadow, gradient, floor, reflection or texture; crisp edges; no text; no logo; no watermark; no extra objects"
    --negative "photorealism, smooth vector curves, 3D render, blur, soft shadow, glow outside the silhouette, scenery, frame, letters, numbers"
)
if $DRY_RUN; then arguments+=(--dry-run); fi
"$PYTHON_BIN" "$IMAGE_GEN" "${arguments[@]}"
if $DRY_RUN; then exit 0; fi

while IFS= read -r filename; do
    source="$SOURCE_DIR/$filename"
    keyed="$KEYED_DIR/$filename"
    final="$FINAL_DIR/$filename"
    [[ -f "$source" ]] || { echo "Missing generated source: $source" >&2; exit 1; }
    "$PYTHON_BIN" "$CHROMA_TOOL" --input "$source" --out "$keyed" \
        --auto-key border --soft-matte --transparent-threshold 12 --opaque-threshold 220 --despill --force
    "$PYTHON_BIN" "$SCRIPT_DIR/pixelize.py" --input "$keyed" --out "$final" --size 32 --force
    if $APPLY; then
        cp "$final" "$PACK_DIR/assets/originalrpg/textures/item/$filename"
    fi
done < <(jq -r '.out' "$SCRIPT_DIR/prompts.jsonl")

if $APPLY; then
    "$PYTHON_BIN" "$SCRIPT_DIR/contact_sheet.py" \
        --textures "$PACK_DIR/assets/originalrpg/textures/item" \
        --out "$SCRIPT_DIR/output/contact-sheet.png"
    "$PACK_DIR/build.sh"
fi
