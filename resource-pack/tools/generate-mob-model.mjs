#!/usr/bin/env node

import { createHash } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import sharp from "../../website/node_modules/sharp/dist/index.mjs";

const toolDir = path.dirname(fileURLToPath(import.meta.url));
const projectDir = path.resolve(toolDir, "../..");
const modelId = "blood_moon_resonant_husk";
const textureOutput = path.join(projectDir, "resource-pack/ai/output/final/mobs", `${modelId}.png`);
const previewOutput = path.join(projectDir, "resource-pack/ai/output/review/mobs", `${modelId}-front.png`);
const turnaroundOutput = path.join(projectDir, "resource-pack/ai/output/review/mobs", `${modelId}-turnaround.png`);
const modelOutput = path.join(projectDir, "server/config-templates/BetterModel/models", `${modelId}.bbmodel`);

const width = 64;
const height = 64;
const pixels = Buffer.alloc(width * height * 4);
const palette = {
  void: [25, 25, 29, 255],
  charcoal: [42, 43, 48, 255],
  slate: [63, 65, 68, 255],
  stone: [106, 105, 97, 255],
  bone: [145, 140, 126, 255],
  umber: [78, 56, 38, 255],
  cloth: [105, 72, 43, 255],
  blood: [142, 39, 36, 255],
  ember: [223, 55, 48, 255],
  cyan: [54, 184, 198, 255],
  cyanLight: [118, 232, 230, 255]
};

function uuid(label) {
  const hex = createHash("sha256").update(`sivita:${modelId}:${label}`).digest("hex").slice(0, 32).split("");
  hex[12] = "4";
  hex[16] = ((Number.parseInt(hex[16], 16) & 3) | 8).toString(16);
  return `${hex.slice(0, 8).join("")}-${hex.slice(8, 12).join("")}-${hex.slice(12, 16).join("")}-${hex.slice(16, 20).join("")}-${hex.slice(20).join("")}`;
}

function setPixel(x, y, color) {
  if (x < 0 || y < 0 || x >= width || y >= height) return;
  const offset = (y * width + x) * 4;
  pixels.set(color, offset);
}

function fillTile(tileX, tileY, base, shade, seed) {
  const startX = tileX * 16;
  const startY = tileY * 16;
  for (let y = 0; y < 16; y++) {
    for (let x = 0; x < 16; x++) {
      const noise = (x * 17 + y * 31 + seed * 13) % 11;
      setPixel(startX + x, startY + y, noise < 3 || (x + y + seed) % 13 === 0 ? shade : base);
    }
  }
}

function line(x0, y0, x1, y1, color) {
  let x = x0;
  let y = y0;
  const dx = Math.abs(x1 - x0);
  const sx = x0 < x1 ? 1 : -1;
  const dy = -Math.abs(y1 - y0);
  const sy = y0 < y1 ? 1 : -1;
  let error = dx + dy;
  while (true) {
    setPixel(x, y, color);
    if (x === x1 && y === y1) break;
    const twice = 2 * error;
    if (twice >= dy) { error += dy; x += sx; }
    if (twice <= dx) { error += dx; y += sy; }
  }
}

function rect(x0, y0, x1, y1, color) {
  for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) setPixel(x, y, color);
}

// Row 0: torso front/side/back and dark structural faces.
fillTile(0, 0, palette.stone, palette.slate, 1);
fillTile(1, 0, palette.stone, palette.slate, 2);
fillTile(2, 0, palette.slate, palette.charcoal, 3);
fillTile(3, 0, palette.charcoal, palette.void, 4);
rect(0, 0, 15, 4, palette.charcoal);
rect(0, 12, 15, 15, palette.umber);
line(2, 5, 7, 10, palette.blood);
line(13, 4, 8, 10, palette.blood);
line(38, 2, 44, 9, palette.blood);

// Row 1: eroded face, side, back and jaw. The missing corner is geometry, not alpha.
fillTile(0, 1, palette.bone, palette.stone, 5);
fillTile(1, 1, palette.slate, palette.charcoal, 6);
fillTile(2, 1, palette.charcoal, palette.void, 7);
fillTile(3, 1, palette.stone, palette.slate, 8);
rect(2, 21, 6, 23, palette.charcoal);
rect(10, 21, 13, 23, palette.void);
setPixel(4, 22, palette.ember);
setPixel(11, 22, palette.blood);
line(8, 17, 5, 25, palette.blood);
line(38, 18, 45, 25, palette.blood);

// Row 2: long forearms. Sparse fissures keep several mobs readable together.
fillTile(0, 2, palette.stone, palette.bone, 9);
fillTile(1, 2, palette.charcoal, palette.slate, 10);
fillTile(2, 2, palette.slate, palette.charcoal, 11);
fillTile(3, 2, palette.charcoal, palette.void, 12);
rect(0, 32, 15, 36, palette.stone);
rect(0, 42, 15, 47, palette.charcoal);
line(7, 37, 9, 44, palette.blood);

// Row 3: cloth/boots and the cyan chest-core tile.
fillTile(0, 3, palette.cloth, palette.umber, 13);
fillTile(1, 3, palette.charcoal, palette.void, 14);
fillTile(2, 3, palette.stone, palette.slate, 15);
fillTile(3, 3, palette.charcoal, palette.void, 16);
rect(0, 48, 15, 53, palette.cloth);
rect(0, 59, 15, 63, palette.charcoal);
for (let dy = -5; dy <= 5; dy++) {
  const span = 5 - Math.abs(dy);
  for (let dx = -span; dx <= span; dx++) setPixel(56 + dx, 56 + dy, Math.abs(dx) <= 1 ? palette.cyanLight : palette.cyan);
}

const texture = await sharp(pixels, { raw: { width, height, channels: 4 } }).png({ palette: true, colours: 24 }).toBuffer();

const tiles = {
  torso: { north: [0, 0, 16, 16], east: [16, 0, 32, 16], south: [32, 0, 48, 16], west: [16, 0, 32, 16], up: [48, 0, 64, 16], down: [48, 0, 64, 16] },
  head: { north: [0, 16, 16, 32], east: [16, 16, 32, 32], south: [32, 16, 48, 32], west: [16, 16, 32, 32], up: [48, 16, 64, 32], down: [48, 16, 64, 32] },
  arm: { north: [0, 32, 16, 48], east: [16, 32, 32, 48], south: [32, 32, 48, 48], west: [16, 32, 32, 48], up: [48, 32, 64, 48], down: [48, 32, 64, 48] },
  leg: { north: [0, 48, 16, 64], east: [16, 48, 32, 64], south: [32, 48, 48, 64], west: [16, 48, 32, 64], up: [32, 48, 48, 64], down: [16, 48, 32, 64] },
  dark: Object.fromEntries(["north", "east", "south", "west", "up", "down"].map(face => [face, [48, 0, 64, 16]])),
  crystal: Object.fromEntries(["north", "east", "south", "west", "up", "down"].map(face => [face, [48, 48, 64, 64]]))
};

function cube(name, from, to, origin, tile) {
  return {
    name,
    box_uv: false,
    render_order: "default",
    locked: false,
    allow_mirror_modeling: true,
    from,
    to,
    autouv: 0,
    color: 0,
    origin,
    faces: Object.fromEntries(Object.entries(tiles[tile]).map(([face, uv]) => [face, { uv, texture: 0 }])),
    type: "cube",
    uuid: uuid(`cube:${name}`)
  };
}

const elements = [
  cube("torso", [-4, 12, -2.5], [4, 23, 2.5], [0, 12, 0], "torso"),
  cube("broken_shoulder", [-5.5, 19, -3], [-3.5, 24, 3], [0, 12, 0], "dark"),
  cube("cranium", [-2.6, 23, -2.6], [3.2, 28.6, 2.6], [0, 23, 0], "head"),
  cube("upper_cranium", [-1.4, 28.6, -2.5], [3.2, 30.2, 2.5], [0, 23, 0], "head"),
  cube("broken_crown", [-3.7, 24.2, -2.4], [-2.6, 29.4, 2.4], [0, 23, 0], "dark"),
  cube("projecting_jaw", [-2.4, 22.2, -3], [2.2, 24.8, 2.4], [0, 23, 0], "head"),
  cube("resonance_core", [-0.9, 17, -3.05], [0.9, 20, -2.4], [0, 12, 0], "crystal"),
  cube("right_arm", [4, 10.5, -2], [7, 23, 2], [5.5, 22, 0], "arm"),
  cube("left_arm", [-7, 10.5, -2], [-4, 23, 2], [-5.5, 22, 0], "arm"),
  cube("right_leg", [0.3, 0, -2.7], [3.7, 12, 2.2], [2, 12, 0], "leg"),
  cube("left_leg", [-3.7, 0, -2.7], [-0.3, 12, 2.2], [-2, 12, 0], "leg")
];
const byName = Object.fromEntries(elements.map(element => [element.name, element]));

function group(name, origin) {
  return {
    uuid: uuid(`bone:${name}`), export: true, locked: false, origin, rotation: [0, 0, 0], color: 0, name,
    children: [], reset: false, shade: true, mirror_uv: false, selected: false, visibility: true,
    autouv: 0, isOpen: true, primary_selected: false
  };
}

const bones = {
  root: group("root", [0, 0, 0]),
  body: group("body", [0, 12, 0]),
  head: group("h_head", [0, 23, 0]),
  rightArm: group("right_arm", [5.5, 22, 0]),
  leftArm: group("left_arm", [-5.5, 22, 0]),
  rightLeg: group("right_leg", [2, 12, 0]),
  leftLeg: group("left_leg", [-2, 12, 0])
};

const node = (bone, children) => ({ uuid: bone.uuid, isOpen: true, children });
const outliner = [node(bones.root, [
  node(bones.body, [byName.torso.uuid, byName.broken_shoulder.uuid, byName.resonance_core.uuid,
    node(bones.head, [byName.cranium.uuid, byName.upper_cranium.uuid, byName.broken_crown.uuid, byName.projecting_jaw.uuid]),
    node(bones.rightArm, [byName.right_arm.uuid]), node(bones.leftArm, [byName.left_arm.uuid])]),
  node(bones.rightLeg, [byName.right_leg.uuid]), node(bones.leftLeg, [byName.left_leg.uuid])
])];

function keyframe(label, channel, time, values, interpolation = "linear") {
  return { channel, data_points: [{ x: String(values[0]), y: String(values[1]), z: String(values[2]) }], uuid: uuid(`key:${label}:${channel}:${time}`), time, color: -1, interpolation };
}

function animator(bone, frames) {
  return [bone.uuid, { name: bone.name, type: "bone", rotation_global: false, quaternion_interpolation: false, keyframes: frames }];
}

function animation(name, loop, length, animatorEntries) {
  return {
    uuid: uuid(`animation:${name}`), name, loop, override: false, length, snapping: 20, selected: false,
    group_name: "", anim_time_update: "", blend_weight: "", start_delay: "", loop_delay: "",
    animators: Object.fromEntries(animatorEntries)
  };
}

const animations = [
  animation("idle", "loop", 2, [
    animator(bones.body, [keyframe("idle-body-a", "rotation", 0, [2, 0, 0]), keyframe("idle-body-b", "rotation", 1, [-1, 0, 0], "catmullrom"), keyframe("idle-body-c", "rotation", 2, [2, 0, 0], "catmullrom")]),
    animator(bones.head, [keyframe("idle-head-a", "rotation", 0, [-3, -4, 0]), keyframe("idle-head-b", "rotation", 1, [2, 4, 0], "catmullrom"), keyframe("idle-head-c", "rotation", 2, [-3, -4, 0], "catmullrom")]),
    animator(bones.rightArm, [keyframe("idle-ra-a", "rotation", 0, [-6, 0, -2]), keyframe("idle-ra-b", "rotation", 1, [-10, 0, 1], "catmullrom"), keyframe("idle-ra-c", "rotation", 2, [-6, 0, -2], "catmullrom")]),
    animator(bones.leftArm, [keyframe("idle-la-a", "rotation", 0, [-9, 0, 2]), keyframe("idle-la-b", "rotation", 1, [-5, 0, -1], "catmullrom"), keyframe("idle-la-c", "rotation", 2, [-9, 0, 2], "catmullrom")])
  ]),
  animation("walk", "loop", 1, [
    animator(bones.rightArm, [keyframe("walk-ra-a", "rotation", 0, [28, 0, 0]), keyframe("walk-ra-b", "rotation", 0.5, [-30, 0, 0], "catmullrom"), keyframe("walk-ra-c", "rotation", 1, [28, 0, 0], "catmullrom")]),
    animator(bones.leftArm, [keyframe("walk-la-a", "rotation", 0, [-30, 0, 0]), keyframe("walk-la-b", "rotation", 0.5, [28, 0, 0], "catmullrom"), keyframe("walk-la-c", "rotation", 1, [-30, 0, 0], "catmullrom")]),
    animator(bones.rightLeg, [keyframe("walk-rl-a", "rotation", 0, [-26, 0, 0]), keyframe("walk-rl-b", "rotation", 0.5, [26, 0, 0], "catmullrom"), keyframe("walk-rl-c", "rotation", 1, [-26, 0, 0], "catmullrom")]),
    animator(bones.leftLeg, [keyframe("walk-ll-a", "rotation", 0, [26, 0, 0]), keyframe("walk-ll-b", "rotation", 0.5, [-26, 0, 0], "catmullrom"), keyframe("walk-ll-c", "rotation", 1, [26, 0, 0], "catmullrom")])
  ]),
  animation("spawn", "once", 0.7, [
    animator(bones.root, [keyframe("spawn-a", "position", 0, [0, -8, 0]), keyframe("spawn-b", "position", 0.7, [0, 0, 0], "catmullrom")]),
    animator(bones.body, [keyframe("spawn-body-a", "rotation", 0, [25, 0, 0]), keyframe("spawn-body-b", "rotation", 0.7, [0, 0, 0], "catmullrom")])
  ])
];

const model = {
  meta: { format_version: "4.10", model_format: "free", box_uv: false },
  name: modelId,
  model_identifier: modelId,
  visible_box: [2.2, 2.2, 0],
  variable_placeholders: "",
  variable_placeholder_buttons: [],
  timeline_setups: [],
  unhandled_root_fields: {},
  resolution: { width, height },
  elements,
  groups: Object.values(bones),
  outliner,
  textures: [{
    name: `${modelId}.png`, path: "", folder: "", namespace: "", id: "0", group: "", width, height,
    uv_width: width, uv_height: height, particle: false, use_as_default: true, layers_enabled: false,
    sync_to_project: "", render_mode: "default", render_sides: "auto", pbr_channel: "color", frame_time: 1,
    frame_order_type: "loop", frame_order: "", frame_interpolate: false, visible: true, internal: true,
    saved: false, uuid: uuid("texture"), source: `data:image/png;base64,${texture.toString("base64")}`
  }],
  animations
};

await mkdir(path.dirname(textureOutput), { recursive: true });
await mkdir(path.dirname(previewOutput), { recursive: true });
await mkdir(path.dirname(modelOutput), { recursive: true });
await writeFile(textureOutput, texture);
await writeFile(modelOutput, `${JSON.stringify(model)}\n`, "utf8");

const scale = 12;
const canvasSize = 512;
const centerX = canvasSize / 2;
const groundY = 456;
const frontOrder = ["left_leg", "right_leg", "torso", "left_arm", "right_arm", "broken_shoulder", "cranium", "upper_cranium", "broken_crown", "projecting_jaw", "resonance_core"];
const composites = [];
for (const name of frontOrder) {
  const element = byName[name];
  const [u0, v0, u1, v1] = element.faces.north.uv;
  const renderWidth = Math.max(1, Math.round((element.to[0] - element.from[0]) * scale));
  const renderHeight = Math.max(1, Math.round((element.to[1] - element.from[1]) * scale));
  const input = await sharp(texture).extract({ left: u0, top: v0, width: u1 - u0, height: v1 - v0 })
    .resize(renderWidth, renderHeight, { kernel: "nearest" }).png().toBuffer();
  composites.push({ input, left: Math.round(centerX + element.from[0] * scale), top: Math.round(groundY - element.to[1] * scale) });
}
await sharp({ create: { width: canvasSize, height: canvasSize, channels: 4, background: { r: 224, g: 222, b: 216, alpha: 1 } } })
  .composite(composites).png().toFile(previewOutput);

// A deterministic engineering review sheet is deliberately generated from the
// actual cuboids and UVs, not from the concept art. It makes missing body parts,
// accidental head-only revisions, back-face noise and silhouette regressions
// visible before the bbmodel reaches a client.
const reviewWidth = 1152;
const reviewHeight = 512;
const reviewScale = 10;
const reviewGroundY = 430;
const panelWidth = reviewWidth / 3;
const reviewViews = [
  {
    face: "north",
    centerX: panelWidth * 0.5,
    depth: element => -element.from[2],
    left: element => panelWidth * 0.5 + element.from[0] * reviewScale,
    width: element => (element.to[0] - element.from[0]) * reviewScale
  },
  {
    face: "west",
    centerX: panelWidth * 1.5,
    depth: element => -element.from[0],
    left: element => panelWidth * 1.5 + element.from[2] * reviewScale,
    width: element => (element.to[2] - element.from[2]) * reviewScale
  },
  {
    face: "south",
    centerX: panelWidth * 2.5,
    depth: element => element.to[2],
    left: element => panelWidth * 2.5 - element.to[0] * reviewScale,
    width: element => (element.to[0] - element.from[0]) * reviewScale
  }
];
const reviewComposites = [];
for (const view of reviewViews) {
  const ordered = [...elements].sort((a, b) => view.depth(a) - view.depth(b));
  for (const element of ordered) {
    const [u0, v0, u1, v1] = element.faces[view.face].uv;
    const renderWidth = Math.max(1, Math.round(view.width(element)));
    const renderHeight = Math.max(1, Math.round((element.to[1] - element.from[1]) * reviewScale));
    const input = await sharp(texture).extract({ left: u0, top: v0, width: u1 - u0, height: v1 - v0 })
      .resize(renderWidth, renderHeight, { kernel: "nearest" }).png().toBuffer();
    reviewComposites.push({
      input,
      left: Math.round(view.left(element)),
      top: Math.round(reviewGroundY - element.to[1] * reviewScale)
    });
  }
}
const divider = await sharp({
  create: { width: 1, height: 424, channels: 4, background: { r: 186, g: 184, b: 178, alpha: 1 } }
}).png().toBuffer();
reviewComposites.push({ input: divider, left: panelWidth, top: 44 });
reviewComposites.push({ input: divider, left: panelWidth * 2, top: 44 });
await sharp({ create: { width: reviewWidth, height: reviewHeight, channels: 4, background: { r: 224, g: 222, b: 216, alpha: 1 } } })
  .composite(reviewComposites).png().toFile(turnaroundOutput);

console.log(`${modelId}: ${elements.length} cuboids, ${Object.keys(bones).length} bones, ${animations.length} animations`);
console.log(modelOutput);
console.log(textureOutput);
console.log(previewOutput);
console.log(turnaroundOutput);
