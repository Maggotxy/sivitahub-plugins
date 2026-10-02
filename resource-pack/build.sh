#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)

OUTPUT_DIR="${RESOURCE_PACK_OUTPUT_DIR:-$PROJECT_DIR/server/web/resourcepacks}"
OUTPUT="$OUTPUT_DIR/sivita-hub-26.2.zip"
TEMP_OUTPUT="$OUTPUT.tmp"
MODEL_MANIFEST="$SCRIPT_DIR/runtime-item-models.txt"
PACK_ID="6f27f0c8-802e-4b4a-aee9-18084ff12d62"
PACK_FILENAME="sivita-hub-26.2.zip"
HUD_GLYPH_COUNT=11
HUD_GLYPH_SIZE=9
CLASS_SKILL_GLYPH_COUNT=48
JOURNEY_GLYPH_COUNT=8
# 右侧战斗轨尺寸契约。唯一的真相源是 docs/reference/hud-layout.json，
# 下面这些常量由 resource-pack/tools/hud-layout.mjs 在打包时逐条比对；
# 改尺寸必须同时改唯一来源，单边改动会让打包直接失败。
HUD_LAYOUT_JSON="$PROJECT_DIR/docs/reference/hud-layout.json"
HUD_PANEL_WIDTH=26
HUD_PANEL_HEIGHT=91
HUD_ICON_SIZE=18
HUD_ICON_COUNT=49
HUD_BADGE_WIDTH=29
HUD_BADGE_HEIGHT=26
HUD_MANA_WIDTH=1
HUD_MANA_HEIGHT=87
HUD_SLOT_COUNT=4
MOB_MODEL="$PROJECT_DIR/server/config-templates/BetterModel/models/blood_moon_resonant_husk.bbmodel"
MOB_REVIEW="$SCRIPT_DIR/ai/output/review/mobs/blood_moon_resonant_husk-turnaround.png"
DUNGEON_MODEL_IDS=(
    boss_ruins_inscription_warden
    boss_ruins_echo_adjudicator
    boss_sulfur_crystal_devourer
    boss_cinnabar_reborn_core
    boss_storm_vault_keykeeper
    boss_silent_thunder_oathguard
)
DUNGEON_WAVE_MODEL_IDS=(
    dungeon_ruins_runebound
    dungeon_ruins_echo_sentinel
    dungeon_sulfur_fume_crawler
    dungeon_sulfur_slag_mote
    dungeon_storm_conductor
    dungeon_storm_gust_wisp
)
HUD_PANEL_SOURCE="$PROJECT_DIR/server/config-templates/BetterHud/assets/sivita_skill_panel.png"
HUD_PANEL_PACK="$SCRIPT_DIR/assets/betterhud/textures/image_sivita_skill_panel.png"

# Replacing the live ZIP while Paper still advertises the hash loaded at boot
# makes every reconnect fail the mandatory resource-pack check.  Build to an
# explicit candidate directory for online validation, then deploy the ZIP and
# properties together while Paper is stopped.  The override is reserved for a
# caller that already owns such a maintenance window.
if [[ "$OUTPUT_DIR" == "$PROJECT_DIR/server/web/resourcepacks" \
        && "${ALLOW_LIVE_RESOURCE_PACK_REPLACE:-0}" != "1" ]] \
        && pgrep -u "$(id -u)" -f '[p]aper[.]jar([[:space:]]|$)' >/dev/null; then
    echo "Refusing to replace the live resource pack while Paper is running." >&2
    echo "Use RESOURCE_PACK_OUTPUT_DIR with SKIP_PROPERTY_UPDATE=1 for an online candidate build." >&2
    exit 1
fi

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
command -v zip >/dev/null || { echo "zip is required" >&2; exit 1; }
command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 1; }
command -v sha1sum >/dev/null || { echo "sha1sum is required" >&2; exit 1; }
command -v rg >/dev/null || { echo "ripgrep is required" >&2; exit 1; }
command -v node >/dev/null || { echo "node is required for the reproducible mob-model source" >&2; exit 1; }
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
elif [[ -x "$PROJECT_DIR/server/runtime/java/bin/java" ]]; then
    JAVA_BIN="$PROJECT_DIR/server/runtime/java/bin/java"
else
    JAVA_BIN=$(command -v java) || { echo "java is required" >&2; exit 1; }
fi

node "$SCRIPT_DIR/tools/generate-mob-model.mjs"
python3 "$SCRIPT_DIR/tools/generate_blood_moon_models.py"
python3 "$SCRIPT_DIR/tools/generate_dungeon_boss_models.py"
python3 "$SCRIPT_DIR/tools/generate_dungeon_wave_models.py"
jq -e '
    .meta.model_format == "free"
    and .name == "blood_moon_resonant_husk"
    and (.elements | length) <= 12
    and (.groups | length) == 7
    and ([.animations[].name] | sort) == ["idle", "spawn", "walk"]
    and (.textures | length) == 1
    and .textures[0].width == 64
    and .textures[0].height == 64
    and (.textures[0].source | startswith("data:image/png;base64,"))' "$MOB_MODEL" >/dev/null || {
    echo "blood-moon mob model exceeds the reviewed geometry/animation contract" >&2
    exit 1
}
[[ -s "$MOB_REVIEW" \
    && "$(od -An -tx1 -j16 -N8 "$MOB_REVIEW" | tr -d ' \n')" == "0000048000000200" ]] || {
    echo "blood-moon mob three-view engineering review must be an exact 1152x512 PNG" >&2
    exit 1
}
for dungeon_model_id in "${DUNGEON_MODEL_IDS[@]}"; do
    dungeon_model="$PROJECT_DIR/server/config-templates/BetterModel/models/$dungeon_model_id.bbmodel"
    jq -e --arg id "$dungeon_model_id" '
        .meta.model_format == "free"
        and .name == $id
        and (.elements | length) >= 8
        and (.elements | length) <= 16
        and ([.animations[].name] | sort)
            == ["attack", "cast", "death", "idle", "phase", "spawn", "walk"]
        and (.textures | length) == 1
        and .textures[0].width == 64
        and .textures[0].height == 64
        and (.textures[0].source | startswith("data:image/png;base64,"))' \
        "$dungeon_model" >/dev/null || {
        echo "dungeon boss model violates the reviewed geometry/animation contract: $dungeon_model_id" >&2
        exit 1
    }
    [[ -s "$SCRIPT_DIR/assets/bettermodel/textures/item/${dungeon_model_id}_${dungeon_model_id}.png" ]] || {
        echo "missing BetterModel-generated boss texture: $dungeon_model_id" >&2
        exit 1
    }
    compgen -G "$SCRIPT_DIR/assets/bettermodel/models/modern_item/${dungeon_model_id}_*.json" >/dev/null || {
        echo "missing BetterModel-generated boss geometry: $dungeon_model_id" >&2
        exit 1
    }
    jq -e --arg prefix "bettermodel:modern_item/${dungeon_model_id}_" '
        any(.model.entries[]; (.model.model? // "") | startswith($prefix))' \
        "$SCRIPT_DIR/assets/bettermodel/items/bm_models.json" >/dev/null || {
        echo "BetterModel item dispatch does not contain boss model: $dungeon_model_id" >&2
        exit 1
    }
done
for dungeon_wave_model_id in "${DUNGEON_WAVE_MODEL_IDS[@]}"; do
    dungeon_wave_model="$PROJECT_DIR/server/config-templates/BetterModel/models/$dungeon_wave_model_id.bbmodel"
    jq -e --arg id "$dungeon_wave_model_id" '
        .meta.model_format == "free"
        and .name == $id
        and (.elements | length) >= 7
        and (.elements | length) <= 12
        and ([.animations[].name] | sort)
            == ["attack", "cast", "death", "idle", "phase", "spawn", "walk"]
        and (.textures | length) == 1
        and .textures[0].width == 64
        and .textures[0].height == 64
        and (.textures[0].source | startswith("data:image/png;base64,"))' \
        "$dungeon_wave_model" >/dev/null || {
        echo "dungeon wave model violates the reviewed geometry/animation contract: $dungeon_wave_model_id" >&2
        exit 1
    }
    [[ -s "$SCRIPT_DIR/assets/bettermodel/textures/item/${dungeon_wave_model_id}_${dungeon_wave_model_id}.png" ]] || {
        echo "missing BetterModel-generated dungeon wave texture: $dungeon_wave_model_id" >&2
        exit 1
    }
    compgen -G "$SCRIPT_DIR/assets/bettermodel/models/modern_item/${dungeon_wave_model_id}_*.json" >/dev/null || {
        echo "missing BetterModel-generated dungeon wave geometry: $dungeon_wave_model_id" >&2
        exit 1
    }
    jq -e --arg prefix "bettermodel:modern_item/${dungeon_wave_model_id}_" '
        any(.model.entries[]; (.model.model? // "") | startswith($prefix))' \
        "$SCRIPT_DIR/assets/bettermodel/items/bm_models.json" >/dev/null || {
        echo "BetterModel item dispatch does not contain dungeon wave model: $dungeon_wave_model_id" >&2
        exit 1
    }
done
[[ -s "$SCRIPT_DIR/ai/output/review/mobs/dungeon_wave_concepts.png" \
    && "$(od -An -tx1 -j16 -N8 "$SCRIPT_DIR/ai/output/review/mobs/dungeon_wave_concepts.png" | tr -d ' \n')" \
        == "0000060000000400" ]] || {
    echo "dungeon wave concept review must be an exact 1536x1024 PNG" >&2
    exit 1
}
[[ -s "$SCRIPT_DIR/ai/output/review/mobs/dungeon_wave_model_review.png" \
    && "$(od -An -tx1 -j16 -N8 "$SCRIPT_DIR/ai/output/review/mobs/dungeon_wave_model_review.png" | tr -d ' \n')" \
        == "0000048000000300" ]] || {
    echo "dungeon wave engineering review must be an exact 1152x768 PNG" >&2
    exit 1
}
for generated_model_asset in \
    assets/bettermodel/items/bm_models.json \
    assets/bettermodel/models/modern_item/blood_moon_resonant_husk_body_1.json \
    assets/bettermodel/models/modern_item/blood_moon_resonant_husk_h_head_1.json \
    assets/bettermodel/models/modern_item/blood_moon_resonant_husk_left_arm_1.json \
    assets/bettermodel/models/modern_item/blood_moon_resonant_husk_left_leg_1.json \
    assets/bettermodel/models/modern_item/blood_moon_resonant_husk_right_arm_1.json \
    assets/bettermodel/models/modern_item/blood_moon_resonant_husk_right_leg_1.json \
    assets/bettermodel/textures/item/blood_moon_resonant_husk_blood_moon_resonant_husk.png \
    assets/bettermodel/models/modern_item/blood_moon_grave_knight_body_1.json \
    assets/bettermodel/textures/item/blood_moon_grave_knight_blood_moon_grave_knight.png \
    assets/bettermodel/models/modern_item/blood_moon_rift_stalker_body_1.json \
    assets/bettermodel/textures/item/blood_moon_rift_stalker_blood_moon_rift_stalker.png; do
    [[ -f "$SCRIPT_DIR/$generated_model_asset" ]] || {
        echo "missing BetterModel-generated resource: $generated_model_asset" >&2
        exit 1
    }
done
if rg -n 'setHelmet\(|elite_[a-z_]+_mask|boss_mask' "$PROJECT_DIR/plugins" \
        -g '**/src/main/**/*.java' -g '!**/LegacyMobMaskMigration.java' >/dev/null; then
    echo "head-only monster model code is forbidden; use the full-body appearance bridge" >&2
    exit 1
fi
rg -q 'equipment\.setHelmet\(null, true\)' \
    "$PROJECT_DIR/plugins/rpg-mobs/src/main/java/cn/originalrpg/mobs/LegacyMobMaskMigration.java" \
    || { echo "legacy persisted head-only mob masks no longer have a bounded removal path" >&2; exit 1; }

generator_args=("$SCRIPT_DIR/assets/originalrpg/textures/item")
if [[ "${REGENERATE_FALLBACK_TEXTURES:-0}" == "1" ]]; then generator_args+=(--force); fi
"$JAVA_BIN" "$SCRIPT_DIR/tools/GenerateTextures.java" "${generator_args[@]}"
"$JAVA_BIN" "$SCRIPT_DIR/tools/GenerateCompassFrames.java" \
    "$SCRIPT_DIR/assets/originalrpg/textures/item/traveler_compass.png" \
    "$SCRIPT_DIR/assets/originalrpg/textures/item/traveler_compass" \
    "$SCRIPT_DIR/assets/originalrpg/models/item"
"$JAVA_BIN" "$SCRIPT_DIR/tools/GenerateHudAtlas.java" \
    "$SCRIPT_DIR/assets/originalrpg/textures/font/hud_symbols.png"
"$JAVA_BIN" "$SCRIPT_DIR/tools/GenerateSkillHudAtlas.java" \
    "$SCRIPT_DIR/assets/originalrpg/textures/font/class_skill_symbols.png"
# BetterHud frames and semantic icon crops are generated from the reviewed
# pixel atlases by this deterministic source tool. BetterHud then owns their
# shader-safe glyph positioning; raw originalrpg font glyphs must not be
# injected into its text renderer.
if python3 -c 'import PIL' >/dev/null 2>&1; then
    python3 "$SCRIPT_DIR/tools/generate_betterhud_panels.py"
else
    # Release bundles already carry the deterministic outputs. Pillow is a
    # development convenience, not a production-server dependency.
    [[ $(find "$PROJECT_DIR/server/config-templates/BetterHud/assets" -maxdepth 1 \
            -type f -name 'sivita_icon_*.png' | wc -l) -eq 49 ]] || {
        echo "Pillow is unavailable and the 49 generated BetterHud icons are incomplete" >&2
        exit 1
    }
fi
cp "$HUD_PANEL_SOURCE" "$HUD_PANEL_PACK"
"$JAVA_BIN" "$SCRIPT_DIR/tools/ValidateHudAtlas.java" \
    "$SCRIPT_DIR/assets/originalrpg/textures/font/hud_symbols.png" \
    "$HUD_GLYPH_COUNT" "$HUD_GLYPH_SIZE"
"$JAVA_BIN" "$SCRIPT_DIR/tools/ValidateHudAtlas.java" \
    "$SCRIPT_DIR/assets/originalrpg/textures/font/class_skill_symbols.png" \
    "$CLASS_SKILL_GLYPH_COUNT" "$HUD_GLYPH_SIZE"
"$JAVA_BIN" "$SCRIPT_DIR/tools/ValidateTextures.java" \
    "$SCRIPT_DIR/assets/originalrpg/textures/item" 32

jq -e --argjson count "$HUD_GLYPH_COUNT" --argjson skill_count "$CLASS_SKILL_GLYPH_COUNT" --argjson size "$HUD_GLYPH_SIZE" '
        .providers | length == 2
        and .[0].type == "bitmap"
        and .[0].file == "originalrpg:font/hud_symbols.png"
        and .[0].ascent == 8
        and .[0].height == $size
        and (.[0].chars | length) == 1
        and (.[0].chars[0] | explode) == [range(57344; 57344 + $count)]
        and .[1].type == "bitmap"
        and .[1].file == "originalrpg:font/class_skill_symbols.png"
        and .[1].ascent == 8
        and .[1].height == $size
        and (.[1].chars | length) == 1
        and (.[1].chars[0] | explode) == [range(57376; 57376 + $skill_count)]' \
    "$SCRIPT_DIR/assets/originalrpg/font/hud.json" >/dev/null || {
    echo "originalrpg:hud must map the base and exact U+E020 class/skill atlas sequences" >&2
    exit 1
}
jq -e --argjson count "$JOURNEY_GLYPH_COUNT" '
        (.providers | length) == $count
        and all(.providers[];
            .type == "bitmap"
            and (.file | startswith("originalrpg:item/journey_"))
            and .ascent == 8
            and .height == 9
            and (.chars | length) == 1)
        and ([.providers[].chars[0] | explode | .[0]] | sort)
            == [range(57600; 57600 + $count)]' \
    "$SCRIPT_DIR/assets/originalrpg/font/journey.json" >/dev/null || {
    echo "originalrpg:journey must map the eight journey textures to U+E100..U+E107" >&2
    exit 1
}
COMPASS_DEFINITION="$SCRIPT_DIR/assets/originalrpg/items/traveler_compass.json"
jq -e '.model
        | .type == "minecraft:range_dispatch"
        and .property == "minecraft:compass"
        and .target == "spawn"
        and .scale == 32
        and (.entries | length) == 33
        and .entries[0].model.model == "originalrpg:item/traveler_compass_16"
        and .entries[16].model.model == "originalrpg:item/traveler_compass_00"
        and .entries[32].model.model == "originalrpg:item/traveler_compass_16"' \
    "$COMPASS_DEFINITION" >/dev/null || {
    echo "traveler compass must preserve the 26.2 spawn-compass angle dispatch" >&2
    exit 1
}

# Stateful equipment is the easiest place for a syntactically valid resource
# pack to degrade into the client missing-model cube: item-definition property
# names are codec values, not arbitrary strings. These contracts mirror the
# installed 26.2 vanilla bow/crossbow/trident/shield definitions while keeping
# Sivita's own models and textures.
BOW_DEFINITION="$SCRIPT_DIR/assets/originalrpg/items/forged_ranger_bow.json"
jq -e '.model
        | .type == "minecraft:condition"
        and .property == "minecraft:using_item"
        and .on_false.model == "originalrpg:item/forged_ranger_bow"
        and .on_true.type == "minecraft:range_dispatch"
        and .on_true.property == "minecraft:use_duration"
        and .on_true.scale == 0.05
        and .on_true.fallback.model == "originalrpg:item/forged_ranger_bow_pulling_0"
        and (.on_true.entries | length) == 2
        and .on_true.entries[0].threshold == 0.65
        and .on_true.entries[0].model.model == "originalrpg:item/forged_ranger_bow_pulling_1"
        and .on_true.entries[1].threshold == 0.9
        and .on_true.entries[1].model.model == "originalrpg:item/forged_ranger_bow_pulling_2"' \
    "$BOW_DEFINITION" >/dev/null || {
    echo "forged ranger bow must preserve the 26.2 using_item/use_duration dispatch" >&2
    exit 1
}

CROSSBOW_DEFINITION="$SCRIPT_DIR/assets/originalrpg/items/forged_ranger_crossbow.json"
jq -e '.model
        | .type == "minecraft:select"
        and .property == "minecraft:charge_type"
        and ([.cases[].when] | sort) == ["arrow", "rocket"]
        and .fallback.type == "minecraft:condition"
        and .fallback.property == "minecraft:using_item"
        and .fallback.on_true.type == "minecraft:range_dispatch"
        and .fallback.on_true.property == "minecraft:crossbow/pull"
        and (.fallback.on_true.entries | length) == 2
        and .fallback.on_true.entries[0].threshold == 0.58
        and .fallback.on_true.entries[1].threshold == 1.0' \
    "$CROSSBOW_DEFINITION" >/dev/null || {
    echo "forged ranger crossbow must preserve the 26.2 charge_type/crossbow-pull dispatch" >&2
    exit 1
}

TRIDENT_DEFINITION="$SCRIPT_DIR/assets/originalrpg/items/forged_ranger_trident.json"
jq -e '.model
        | .type == "minecraft:select"
        and .property == "minecraft:display_context"
        and (.cases | length) == 1
        and (.cases[0].when | sort) == ["fixed", "ground", "gui", "on_shelf"]
        and .cases[0].model.model == "originalrpg:item/forged_ranger_trident"
        and .fallback.type == "minecraft:condition"
        and .fallback.property == "minecraft:using_item"
        and .fallback.on_false.model == "originalrpg:item/forged_ranger_trident_in_hand"
        and .fallback.on_true.model == "originalrpg:item/forged_ranger_trident_throwing"' \
    "$TRIDENT_DEFINITION" >/dev/null || {
    echo "forged ranger trident must preserve the 26.2 display_context/using_item dispatch" >&2
    exit 1
}

SHIELD_DEFINITION="$SCRIPT_DIR/assets/originalrpg/items/forged_shield.json"
jq -e '.model
        | .type == "minecraft:condition"
        and .property == "minecraft:using_item"
        and .on_false.model == "originalrpg:item/forged_shield"
        and .on_true.model == "originalrpg:item/forged_shield_blocking"' \
    "$SHIELD_DEFINITION" >/dev/null || {
    echo "forged shield must preserve the 26.2 using_item blocking dispatch" >&2
    exit 1
}

# HUD 尺寸契约门禁（唯一来源 docs/reference/hud-layout.json）：
#   ① Python 侧核 PNG 头 + BetterHud 字体定义的覆盖；
#   ② Node 侧核上面的 shell 常量与原始图集常量。
# 这两条必须在「素材已生成」之后、打包之前跑，否则断言的是上一轮的产物。
python3 "$SCRIPT_DIR/tools/verify_hud_layout.py"
node "$SCRIPT_DIR/tools/hud-layout.mjs" "$SCRIPT_DIR/build.sh"

jq -e '.pack.pack_format == 88
        and .pack.supported_formats == [88, 88]
        and .pack.min_format == 88
        and .pack.max_format == 88
        and (has("overlays") | not)' "$SCRIPT_DIR/pack.mcmeta" >/dev/null || {
    echo "pack.mcmeta must declare an exact, self-contained 26.2 resource pack" >&2
    exit 1
}
# PNG IHDR 里的宽高：从唯一来源读出十六进制，避免把 26x91 写死在两个地方。
HUD_PANEL_SIZE_HEX=$(python3 -c '
import json, struct, pathlib, sys
layout = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))["rail"]
sys.stdout.write(struct.pack(">II", layout["width"], layout["height"]).hex())
' "$HUD_LAYOUT_JSON")
[[ -s "$HUD_PANEL_SOURCE" && -s "$HUD_PANEL_PACK" \
    && "$(od -An -tx1 -j16 -N8 "$HUD_PANEL_SOURCE" | tr -d ' \n')" == "$HUD_PANEL_SIZE_HEX" \
    && "$(od -An -tx1 -j16 -N8 "$HUD_PANEL_PACK" | tr -d ' \n')" == "$HUD_PANEL_SIZE_HEX" ]] || {
    echo "Sivita BetterHud rail must be an exact ${HUD_PANEL_WIDTH}x${HUD_PANEL_HEIGHT} RGBA PNG in source and generated pack" >&2
    exit 1
}
for hud_asset in \
    assets/betterhud/font/hud_sivita_hud_image.json \
    assets/betterhud/font/hud_sivita_hud_text_1_2_1.json \
    assets/betterhud/font/hud_sivita_hud_text_2_3_1.json \
    assets/betterhud/font/hud_sivita_hud_text_7_8_1.json \
    assets/betterhud/textures/image_sivita_skill_panel.png \
    assets/betterhud/textures/image_sivita_skill_selected.png \
    assets/betterhud/textures/image_sivita_skill_cooldown.png \
    assets/betterhud/textures/image_sivita_class_badge.png \
    assets/betterhud/textures/glyph_skill_frost-nova.png \
    assets/betterhud/textures/glyph_class_mage.png \
    assets/betterhud/textures/glyph_control_cast_zh.png \
    assets/betterhud/textures/glyph_control_cycle_zh.png \
    assets/betterhud/textures/glyph_control_cast_en.png \
    assets/betterhud/textures/glyph_control_cycle_en.png \
    assets/minecraft/textures/gui/sprites/boss_bar/yellow_progress.png \
    betterhud_26_1/assets/minecraft/shaders/core/rendertype_text.vsh \
    betterhud_26_1/assets/minecraft/shaders/core/rendertype_text.fsh; do
    [[ -s "$SCRIPT_DIR/$hud_asset" ]] || {
        echo "missing reviewed BetterHud overlay asset: $hud_asset" >&2
        exit 1
    }
done
for hud_control_asset in cast_zh cycle_zh cast_en cycle_en; do
    source_control_fingerprint=$("$JAVA_BIN" "$SCRIPT_DIR/tools/PngFingerprint.java" \
        "$PROJECT_DIR/server/config-templates/BetterHud/assets/sivita_control_${hud_control_asset}.png")
    generated_control_fingerprint=$("$JAVA_BIN" "$SCRIPT_DIR/tools/PngFingerprint.java" \
        "$SCRIPT_DIR/assets/betterhud/textures/glyph_control_${hud_control_asset}.png")
    [[ "$source_control_fingerprint" == "$generated_control_fingerprint" ]] \
        || {
        echo "BetterHud control glyph is stale relative to its reviewed source: $hud_control_asset" >&2
        exit 1
    }
done
# 语义图标同样从 reviewed 源生成。W7-A4 之前这一段缺失：pack 里的
# glyph_skill_*/glyph_class_* 只被「文件存在」检查过，与生成器的输出
# 可以静默漂移（实测确实有 7 张是生成器输出的 1px 裁剪版）。
# 这里复用已验证过的指纹机制，把「图标是否是 reviewed 源」变成打包期判据。
for hud_icon_kind in skill class; do
    compgen -G "$PROJECT_DIR/server/config-templates/BetterHud/assets/sivita_icon_${hud_icon_kind}_*.png" >/dev/null || {
        echo "reviewed BetterHud ${hud_icon_kind} icons are missing from the config template" >&2
        exit 1
    }
    for source_icon in "$PROJECT_DIR"/server/config-templates/BetterHud/assets/sivita_icon_${hud_icon_kind}_*.png; do
        icon_id=$(basename "$source_icon" .png)
        icon_id=${icon_id#sivita_icon_}
        source_icon_fingerprint=$("$JAVA_BIN" "$SCRIPT_DIR/tools/PngFingerprint.java" "$source_icon")
        generated_icon_fingerprint=$("$JAVA_BIN" "$SCRIPT_DIR/tools/PngFingerprint.java" \
            "$SCRIPT_DIR/assets/betterhud/textures/glyph_${icon_id}.png")
        [[ "$source_icon_fingerprint" == "$generated_icon_fingerprint" ]] || {
            echo "BetterHud semantic icon is stale relative to its reviewed source: $icon_id" >&2
            exit 1
        }
    done
done
if rg -l 'test_hud|entity_health_popup|default_compass' "$SCRIPT_DIR/assets/betterhud" >/dev/null; then
    echo "BetterHud default demo assets must not enter the production resource pack" >&2
    exit 1
fi
if [[ -f "$PROJECT_DIR/server/runtime/cache/mojang_26.2.jar" ]]; then
    SERVER_RESOURCE_FORMAT=$(unzip -p "$PROJECT_DIR/server/runtime/cache/mojang_26.2.jar" version.json \
        | jq -er '.pack_version | select(.resource_minor == 0) | .resource_major')
    [[ "$SERVER_RESOURCE_FORMAT" == "88" ]] || {
        echo "pack.mcmeta format 88 does not match the installed 26.2 server format $SERVER_RESOURCE_FORMAT" >&2
        exit 1
    }
fi

find "$SCRIPT_DIR/assets/originalrpg" -type f -name '*.json' -print0 \
    | while IFS= read -r -d '' json; do jq empty "$json"; done

MANIFEST_KEYS=$(mktemp)
ITEM_KEYS=$(mktemp)
DIRECT_KEYS=$(mktemp)
cleanup() { rm -f "$MANIFEST_KEYS" "$ITEM_KEYS" "$DIRECT_KEYS"; }
trap cleanup EXIT
sed -e 's/[[:space:]]*#.*$//' -e '/^[[:space:]]*$/d' "$MODEL_MANIFEST" | LC_ALL=C sort > "$MANIFEST_KEYS"
if [[ $(wc -l < "$MANIFEST_KEYS") -ne $(sort -u "$MANIFEST_KEYS" | wc -l) ]] \
        || rg -v '^[a-z0-9_./-]+$' "$MANIFEST_KEYS" >/dev/null; then
    echo "runtime-item-models.txt contains duplicate or invalid keys" >&2
    exit 1
fi
find "$SCRIPT_DIR/assets/originalrpg/items" -maxdepth 1 -type f -name '*.json' -printf '%f\n' \
    | sed 's/\.json$//' | LC_ALL=C sort > "$ITEM_KEYS"
diff -u "$MANIFEST_KEYS" "$ITEM_KEYS" || {
    echo "runtime item_model manifest and item definitions differ" >&2
    exit 1
}

# Catch literal keys introduced by plugin code. Dynamic switch/ternary outputs are
# represented by the manifest and its required one-to-one item-definition match.
{
    rg -o --no-filename 'originalrpg:[a-z0-9_./-]+' "$PROJECT_DIR/plugins" \
        -g '**/src/main/**/*.java' 2>/dev/null | sed 's/^originalrpg://' || true
    rg -o --no-filename 'NamespacedKey\("originalrpg",[[:space:]]*"[a-z0-9_./-]+"' \
        "$PROJECT_DIR/plugins" -g '**/src/main/**/*.java' 2>/dev/null \
        | sed -E 's/.*"([a-z0-9_.\/-]+)"$/\1/' || true
    sed -n -E 's/^[[:space:]]+model:[[:space:]]*"?([a-z0-9_]+)"?[[:space:]]*$/\1/p' \
        "$PROJECT_DIR/plugins/rpg-economy/src/main/resources/config.yml"
} | LC_ALL=C sort -u > "$DIRECT_KEYS"
while IFS= read -r key; do
    grep -Fxq "$key" "$MANIFEST_KEYS" || {
        echo "plugin emits undeclared originalrpg item_model: $key" >&2
        exit 1
    }
done < "$DIRECT_KEYS"

declare -A validating_models=()
declare -A validated_models=()
declare -A validated_textures=()
validate_model() {
    local model=$1
    [[ -n "${validated_models[$model]:-}" ]] && return
    [[ -z "${validating_models[$model]:-}" ]] || {
        echo "cyclic model parent chain: $model" >&2
        exit 1
    }
    validating_models[$model]=1
    local model_file="$SCRIPT_DIR/assets/originalrpg/models/item/${model#originalrpg:item/}.json"
    [[ -f "$model_file" ]] || { echo "missing model: $model_file" >&2; exit 1; }
    jq empty "$model_file"
    local texture parent
    texture=$(jq -r '.textures.layer0 // empty' "$model_file")
    parent=$(jq -r '.parent // empty' "$model_file")
    if [[ "$texture" == originalrpg:item/* ]]; then
        local texture_file="$SCRIPT_DIR/assets/originalrpg/textures/item/${texture#originalrpg:item/}.png"
        [[ -f "$texture_file" ]] || { echo "missing texture: $texture_file" >&2; exit 1; }
        validated_textures["${texture#originalrpg:item/}"]=1
    elif [[ "$parent" == originalrpg:item/* ]]; then
        validate_model "$parent"
    elif [[ -z "$texture" && -z "$parent" ]]; then
        echo "model has neither a texture nor a parent: $model_file" >&2
        exit 1
    fi
    unset "validating_models[$model]"
    validated_models[$model]=1
}
while IFS= read -r -d '' definition; do
    jq empty "$definition"
    mapfile -t models < <(jq -r '.. | objects | select(.type? == "minecraft:model")
            | .model? | select(type == "string" and startswith("originalrpg:item/"))' "$definition" | sort -u)
    [[ ${#models[@]} -gt 0 ]] || { echo "item definition has no project model: $definition" >&2; exit 1; }
    for model in "${models[@]}"; do validate_model "$model"; done
done < <(find "$SCRIPT_DIR/assets/originalrpg/items" -type f -name '*.json' -print0)

while IFS= read -r -d '' model_file; do
    model="originalrpg:item/${model_file##*/}"
    model="${model%.json}"
    [[ -n "${validated_models[$model]:-}" ]] || {
        echo "orphan model is not reachable from an item definition: $model_file" >&2
        exit 1
    }
done < <(find "$SCRIPT_DIR/assets/originalrpg/models/item" -type f -name '*.json' -print0)

while IFS= read -r -d '' texture_file; do
    texture="${texture_file#"$SCRIPT_DIR/assets/originalrpg/textures/item/"}"
    texture="${texture%.png}"
    [[ -n "${validated_textures[$texture]:-}" ]] || {
        echo "orphan texture is not reachable from a model: $texture_file" >&2
        exit 1
    }
done < <(find "$SCRIPT_DIR/assets/originalrpg/textures/item" -type f -name '*.png' -print0)

ZH_KEYS=$(jq -r 'keys[]' "$SCRIPT_DIR/assets/originalrpg/lang/zh_cn.json" | LC_ALL=C sort)
EN_KEYS=$(jq -r 'keys[]' "$SCRIPT_DIR/assets/originalrpg/lang/en_us.json" | LC_ALL=C sort)
[[ "$ZH_KEYS" == "$EN_KEYS" ]] || { echo "zh_cn/en_us resource-pack language keys differ" >&2; exit 1; }

if [[ "${SKIP_PROPERTY_UPDATE:-0}" != "1" ]]; then
    for properties in "$PROJECT_DIR/server/config/server.properties" "$PROJECT_DIR/server/runtime/server.properties"; do
        [[ -f "$properties" ]] || continue
        grep -Eq '^resource-pack=.*sivita-hub-26\.2\.zip$' "$properties" || {
            echo "resource-pack URL does not target $PACK_FILENAME in $properties" >&2
            exit 1
        }
    done
fi

mkdir -p "$OUTPUT_DIR"
rm -f "$TEMP_OUTPUT"
PACK_STAGE=$(mktemp -d)
cleanup_stage() {
    cleanup
    rm -rf -- "$PACK_STAGE"
}
trap cleanup_stage EXIT
cp "$SCRIPT_DIR/pack.mcmeta" "$PACK_STAGE/pack.mcmeta"
cp -a "$SCRIPT_DIR/assets" "$PACK_STAGE/assets"
mkdir -p "$PACK_STAGE/assets/minecraft/shaders/core"
cp -a "$SCRIPT_DIR/betterhud_26_1/assets/minecraft/shaders/core/." \
    "$PACK_STAGE/assets/minecraft/shaders/core/"
# ZIP stores DOS modification times even with -X.  Generated PNG/model files
# otherwise give byte-identical packs a different SHA-1 on every build, which
# forces unnecessary client downloads and makes release verification unstable.
PACK_EPOCH="${SOURCE_DATE_EPOCH:-946684800}"
[[ "$PACK_EPOCH" =~ ^[0-9]{1,12}$ ]] || {
    echo "SOURCE_DATE_EPOCH must be a positive Unix timestamp" >&2
    exit 1
}
find "$PACK_STAGE" -exec touch -h -d "@$PACK_EPOCH" {} +
(
    cd "$PACK_STAGE"
    find pack.mcmeta assets -type f ! -name '.gitkeep' -print \
        | LC_ALL=C sort | TZ=UTC zip -X -q "$TEMP_OUTPUT" -@
)
mv "$TEMP_OUTPUT" "$OUTPUT"
PACK_SHA1=$(sha1sum "$OUTPUT" | cut -d ' ' -f 1)
printf '%s  %s\n' "$PACK_SHA1" "$PACK_FILENAME" | tee "$OUTPUT.sha1"
if [[ "${SKIP_PROPERTY_UPDATE:-0}" != "1" ]]; then
    for properties in "$PROJECT_DIR/server/config/server.properties" "$PROJECT_DIR/server/runtime/server.properties"; do
        [[ -f "$properties" ]] || continue
        sed -i "s/^resource-pack-sha1=.*/resource-pack-sha1=$PACK_SHA1/" "$properties"
        sed -i "s/^resource-pack-id=.*/resource-pack-id=$PACK_ID/" "$properties"
    done
fi
