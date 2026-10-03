#!/usr/bin/env python3
# scripts/check-asset-reproducible.py —— 生成器可复现性判据（W13-A1）
#
# 与 scripts/check-asset-baseline.py 的分工（两道门禁都逐像素，但判的不是同一件事）：
#   基线门禁：磁盘上的像素 == assets-manifest.json 里登记的像素（「还是原来那张」）
#   本门禁  ：磁盘上的像素 == **跑生成器产出的**像素（「这张图还造得出来吗」）
# 前者挡不住「注册表和生成器一起漂走」：有人手改 PNG 再重跑 gen-asset-manifest.py，
# 基线依然全绿，但那 30 张物品纹理从此再也造不出来。本门禁就是补这个洞。
#
# 判据三条：
#   a) 生成器输出与仓库逐像素一致                → reproducible
#   b) 未登记在生成器包里的 PNG                  → 漏覆盖（生成器吐出仓库没有的图）
#   c) human-override：必须**不等于**生成器输出  → 反向断言
#      （ancient_echo_skill_book 是 v2 人工覆盖，生成器 book() 的旧紫皮已弃用；
#       若有人把生成器的旧设计盖回去，这条必须 RED）
#
# 退出码：0 全一致；1 有差异；2 生成器输出目录缺失。
# 有意**不留** --skip / --force 开关：唯一让门禁转绿的路是改生成器或改素材，
# 两者都会留下可评审的 diff。
#
# 用法：
#   python3 scripts/check-asset-reproducible.py --root <repo> --generated <dir> [--max-report N]

import argparse
import json
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)

from asset_fingerprint import fingerprint_png  # noqa: E402

SPEC_REL = os.path.join("resource-pack", "asset-reproducible.json")


def load_spec(root):
    path = os.path.join(root, SPEC_REL)
    if not os.path.isfile(path):
        print("ASSET_REPRO_GATE=RED 找不到生成器覆盖规格: %s" % path, file=sys.stderr)
        return None, None
    with open(path, "r", encoding="utf-8") as handle:
        try:
            spec = json.load(handle)
        except Exception as exc:  # noqa: BLE001 - 门禁要报告而不是抛栈
            print("ASSET_REPRO_GATE=RED 规格不是合法 JSON: %s (%s)" % (path, exc),
                  file=sys.stderr)
            return None, path
    return spec, path


def check_human_override_registered(root, spec):
    """human-override 项必须在 asset-provenance.json 里带 override_reason + source_prompt。"""
    path = os.path.join(root, "resource-pack", "asset-provenance.json")
    if not os.path.isfile(path):
        return ["找不到 provenance 登记: %s" % path]
    with open(path, "r", encoding="utf-8") as handle:
        provenance = json.load(handle).get("assets", {})
    problems = []
    for group in spec["generators"]:
        for entry in group.get("humanOverrides", []):
            key = "assets/originalrpg/textures/item/" + entry["path"]
            record = provenance.get(key)
            if record is None:
                problems.append("%s 未登记进 asset-provenance.json" % key)
                continue
            if record.get("category") != "human-override":
                problems.append("%s 的 category 是 %s，应为 human-override"
                                % (key, record.get("category")))
            for field in ("override_reason", "source_prompt"):
                if not record.get(field):
                    problems.append("%s 缺 %s" % (key, field))
    return problems


def main(argv):
    parser = argparse.ArgumentParser(description="生成器可复现性判据")
    parser.add_argument("--root", default=os.path.dirname(SCRIPT_DIR), help="仓库根")
    parser.add_argument("--generated", required=True,
                        help="生成器输出目录（按 generator 分组的子目录）")
    parser.add_argument("--max-report", type=int, default=20, help="每类最多打印条数")
    args = parser.parse_args(argv[1:])

    spec, spec_path = load_spec(args.root)
    if spec is None:
        return 2
    if not os.path.isdir(args.generated):
        print("ASSET_REPRO_GATE=RED 找不到生成器输出目录: %s" % args.generated, file=sys.stderr)
        return 2

    reproducible = 0
    mismatch = []
    missing_output = []
    unexpected = []

    for group in spec["generators"]:
        generator = group["generator"]
        out_dir = os.path.join(args.generated, generator)
        assets_root = os.path.join(args.root, group["assetsRoot"])
        overrides = {entry["path"]: entry.get("reason", "") for entry in
                     group.get("humanOverrides", [])}
        declared = set()
        for rel, output in group["assets"]:
            disk = os.path.join(assets_root, rel)
            produced = os.path.join(out_dir, output)
            declared.add(output)
            if not os.path.isfile(disk):
                mismatch.append((generator, rel, "仓库里没有这张图", "-"))
                continue
            if not os.path.isfile(produced):
                missing_output.append((generator, rel, output))
                continue
            produced_fp = fingerprint_png(produced)
            disk_fp = fingerprint_png(disk)
            if rel in overrides:
                # 反向断言：人工覆盖必须**不等于**生成器输出
                if produced_fp == disk_fp:
                    mismatch.append((generator, rel,
                                     "人工覆盖竟与生成器输出一致（生成器旧设计被盖回去了）",
                                     produced_fp))
                else:
                    reproducible += 1
                continue
            if produced_fp == disk_fp:
                reproducible += 1
            else:
                mismatch.append((generator, rel, "生成器输出与仓库不一致", produced_fp))

        if os.path.isdir(out_dir):
            for name in sorted(os.listdir(out_dir)):
                if name.endswith(".png") and name not in declared:
                    unexpected.append((generator, name))

    total = sum(len(group["assets"]) for group in spec["generators"])
    print("ASSET_REPRO: 比对 %d 张（%d 个生成器）" % (total, len(spec["generators"])))
    print("  逐像素一致: %d" % reproducible)
    if mismatch:
        print("  不一致: %d 条" % len(mismatch), file=sys.stderr)
        for generator, rel, why, produced in mismatch[:args.max_report]:
            print("    ! %s <- %s（%s）" % (rel, generator, why), file=sys.stderr)
            if produced != "-":
                print("        生成器输出 %s" % produced, file=sys.stderr)
        if len(mismatch) > args.max_report:
            print("    ... 另有 %d 条" % (len(mismatch) - args.max_report), file=sys.stderr)
    if missing_output:
        print("  生成器没产出: %d 条" % len(missing_output), file=sys.stderr)
        for generator, rel, output in missing_output[:args.max_report]:
            print("    ? %s <- %s/%s" % (rel, generator, output), file=sys.stderr)
    if unexpected:
        print("  仓库未登记的生成器输出: %d 条" % len(unexpected), file=sys.stderr)
        for generator, name in unexpected[:args.max_report]:
            print("    + %s/%s（规格里没有登记这张）" % (generator, name), file=sys.stderr)

    # human-override 必须真的在 provenance 里登记过 —— 否则「反向断言」会退化成
    # 「这张图不用管」，那就等于给了一个随便用的豁免口子。
    provenance_problems = check_human_override_registered(args.root, spec)

    if mismatch or missing_output or unexpected or provenance_problems:
        print("  REPRODUCIBLE=%d MISMATCH=%d MISSING=%d UNREGISTERED=%d"
              % (reproducible, len(mismatch), len(missing_output), len(unexpected)))
        if provenance_problems:
            print("  provenance 登记问题: %d 条" % len(provenance_problems), file=sys.stderr)
            for problem in provenance_problems[:args.max_report]:
                print("    ! %s" % problem, file=sys.stderr)
        print("ASSET_REPRO_GATE=RED")
        return 1

    print("  REPRODUCIBLE=%d MISMATCH=0 MISSING=0 UNREGISTERED=0" % reproducible)
    print("ASSET_REPRO_GATE=GREEN")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
