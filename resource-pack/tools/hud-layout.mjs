// HUD 尺寸契约的 Node 侧消费者。
//
// build.sh 目前只有 shell 常量（HUD_GLYPH_* / CLASS_SKILL_GLYPH_*）和 jq 断言，
// 无法表达「右侧轨 26x91、图标 49x18x18、徽章 29x26、法力 1x87」这类结构化契约。
// 这里读取 docs/reference/hud-layout.json（唯一来源），对三件事做机器判定：
//   1) 原始字体图集（originalrpg:hud / originalrpg:journey）的常量与唯一来源自洽；
//   2) BetterHud 图集清单与唯一来源声明的张数一致；
//   3) shell 传进来的打包期常量与唯一来源一致（防止 build.sh 与文档双漂移）。
//
// 用法：node resource-pack/tools/hud-layout.mjs <build.sh 路径>
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..', '..');
const layout = JSON.parse(readFileSync(join(root, 'docs/reference/hud-layout.json'), 'utf8'));

const failures = [];
let checks = 0;
const expect = (condition, message) => {
  checks += 1;
  if (!condition) failures.push(message);
};

// ---- 1) 原始字体图集常量（与 build.sh 里 jq 断言的是同一批数字） ----
const hudJson = JSON.parse(
  readFileSync(join(root, 'resource-pack/assets/originalrpg/font/hud.json'), 'utf8'));
const baseProvider = hudJson.providers[0];
const skillProvider = hudJson.providers[1];
const hudAtlasSize = pngSize(
  join(root, 'resource-pack/assets/originalrpg/textures/font/hud_symbols.png'));
const classAtlasSize = pngSize(
  join(root, 'resource-pack/assets/originalrpg/textures/font/class_skill_symbols.png'));
const hudGlyphCount = baseProvider.chars[0].length;
const classGlyphCount = skillProvider.chars[0].length;

expect(baseProvider.height === hudAtlasSize[1],
  `originalrpg:hud 的 bitmap height ${baseProvider.height} != hud_symbols.png 高 ${hudAtlasSize[1]}`);
expect(hudAtlasSize[0] === hudAtlasSize[1] * hudGlyphCount,
  `hud_symbols.png 宽 ${hudAtlasSize[0]} != ${hudGlyphCount} x ${hudAtlasSize[1]}`);
expect(classAtlasSize[0] === classAtlasSize[1] * classGlyphCount,
  `class_skill_symbols.png 宽 ${classAtlasSize[0]} != ${classGlyphCount} x ${classAtlasSize[1]}`);
expect(classGlyphCount === layout.icon.skillCount + layout.icon.classCount - 1,
  `类技能图集 ${classGlyphCount} 枚与唯一来源的 ${layout.icon.skillCount}+${layout.icon.classCount}-1 不一致`);

// ---- 2) BetterHud 图集清单与唯一来源一致 ----
const textureDir = join(root, 'resource-pack/assets/betterhud/textures');
const skillIcons = readdirSync(textureDir).filter((name) => name.startsWith('glyph_skill_'));
const classIcons = readdirSync(textureDir).filter((name) => name.startsWith('glyph_class_'));
expect(skillIcons.length === layout.icon.skillCount,
  `glyph_skill_* 有 ${skillIcons.length} 张 != 唯一来源 ${layout.icon.skillCount} 张`);
expect(classIcons.length === layout.icon.classCount,
  `glyph_class_* 有 ${classIcons.length} 张 != 唯一来源 ${layout.icon.classCount} 张`);
const manaFrames = readdirSync(textureDir)
  .filter((name) => name.startsWith('image_image_sivita_mana_fill_up_20_'));
expect(manaFrames.length === layout.mana.frames,
  `法力帧有 ${manaFrames.length} 张 != 唯一来源 ${layout.mana.frames} 张`);

// ---- 3) 打包期 shell 常量与唯一来源一致 ----
const buildScript = process.argv[2] ?? join(root, 'resource-pack/build.sh');
const shell = readFileSync(buildScript, 'utf8');
const shellConst = (name) => {
  const match = new RegExp(`^${name}=(\\d+)\\s*$`, 'm').exec(shell);
  if (!match) {
    failures.push(`build.sh 缺少常量 ${name}`);
    checks += 1;
    return null;
  }
  checks += 1;
  return Number(match[1]);
};
expect(shellConst('HUD_GLYPH_SIZE') === hudAtlasSize[1],
  `build.sh HUD_GLYPH_SIZE 与 hud_symbols.png 高 ${hudAtlasSize[1]} 不一致`);
expect(shellConst('HUD_GLYPH_COUNT') === hudGlyphCount,
  `build.sh HUD_GLYPH_COUNT 与 hud.json 的 ${hudGlyphCount} 不一致`);
expect(shellConst('CLASS_SKILL_GLYPH_COUNT') === classGlyphCount,
  `build.sh CLASS_SKILL_GLYPH_COUNT 与 hud.json 的 ${classGlyphCount} 不一致`);
expect(shellConst('HUD_PANEL_WIDTH') === layout.rail.width,
  `build.sh HUD_PANEL_WIDTH 与唯一来源轨道宽 ${layout.rail.width} 不一致`);
expect(shellConst('HUD_PANEL_HEIGHT') === layout.rail.height,
  `build.sh HUD_PANEL_HEIGHT 与唯一来源轨道高 ${layout.rail.height} 不一致`);
expect(shellConst('HUD_ICON_SIZE') === layout.icon.width,
  `build.sh HUD_ICON_SIZE 与唯一来源图标宽 ${layout.icon.width} 不一致`);
expect(shellConst('HUD_BADGE_WIDTH') === layout.badge.width
  && shellConst('HUD_BADGE_HEIGHT') === layout.badge.height,
  `build.sh HUD_BADGE_* 与唯一来源徽章 ${layout.badge.width}x${layout.badge.height} 不一致`);
expect(shellConst('HUD_MANA_WIDTH') === layout.mana.width
  && shellConst('HUD_MANA_HEIGHT') === layout.mana.height,
  `build.sh HUD_MANA_* 与唯一来源法力 ${layout.mana.width}x${layout.mana.height} 不一致`);
expect(shellConst('HUD_SLOT_COUNT') === layout.slot.count,
  `build.sh HUD_SLOT_COUNT 与唯一来源槽位数 ${layout.slot.count} 不一致`);

console.log(`HUD_LAYOUT_NODE_CHECKS=${checks}`);
if (failures.length > 0) {
  for (const failure of failures) console.error('HUD_LAYOUT_NODE_GATE=RED ' + failure);
  process.exit(1);
}
console.log('HUD_LAYOUT_NODE_GATE=GREEN');

function pngSize(path) {
  const buffer = readFileSync(path);
  const signature = buffer.subarray(0, 8).toString('hex');
  if (signature !== '89504e470d0a1a0a') throw new Error(`not a PNG: ${path}`);
  return [buffer.readUInt32BE(16), buffer.readUInt32BE(20)];
}
