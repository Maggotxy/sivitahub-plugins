#!/usr/bin/env python3
# scripts/check-asset-baseline.py —— 素材逐像素基线门禁（W12-A1）
#
# 失败模式（本门禁要掐死的形态）：素材门禁只校验闭包 / 尺寸 / 透明 / 码位 /
# 语言键 —— 它管的是「素材自洽」，管不了「这张图还是不是原来那张」。
# 于是 83 张物品纹理 + 116 张 betterhud 纹理 + 16 张 bettermodel 纹理可以被
# 悄悄换掉（换一张同样 32x32、同样二值 alpha 的图），CI 全绿。
# 本门禁拿 resource-pack/assets-manifest.json 做逐像素对账，三条判据：
#   a) 磁盘上每个 PNG 都在清单里    —— 未登记（新增素材没登记）
#   b) 清单里每个条目都在磁盘上      —— 丢失（删了素材但没改清单）
#   c) 每个 PNG 的指纹与清单一致     —— 被改动（打印 期望/实际）
#
# 退出码：0 全一致；1 有差异；2 清单文件缺失。
#
# 这里**没有** --skip / --force / --update 之类的开关：
# 一旦留下「跳过基线」的口子，它就会变成出事时的默认动作。
# 有意更新基线的唯一路径是 `python3 scripts/gen-asset-manifest.py` + 提交新清单，
# 那会作为一次可评审的 diff 出现在 PR 里（改了什么图，谁改的，为什么）。
#
# 用法：
#   python3 scripts/check-asset-baseline.py                     # 仓库根由脚本位置解析
#   python3 scripts/check-asset-baseline.py --root <dir>        # 负向对照夹具用
#   python3 scripts/check-asset-baseline.py --max-report <n>    # 每类最多打印几条（默认 20）

import argparse
import json
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)

from asset_fingerprint import fingerprint_png  # noqa: E402

ASSETS_REL = os.path.join("resource-pack", "assets")
MANIFEST_REL = os.path.join("resource-pack", "assets-manifest.json")


def iter_png(assets_dir):
    found = []
    for dirpath, dirnames, filenames in os.walk(assets_dir):
        dirnames.sort()
        for name in sorted(filenames):
            if name.endswith(".png"):
                full = os.path.join(dirpath, name)
                rel = os.path.relpath(full, assets_dir).replace(os.sep, "/")
                found.append((rel, full))
    found.sort(key=lambda item: item[0])
    return found


def main(argv):
    parser = argparse.ArgumentParser(description="素材逐像素基线门禁")
    parser.add_argument("--root", default=os.path.dirname(SCRIPT_DIR), help="仓库根")
    parser.add_argument("--max-report", type=int, default=20, help="每类最多打印条数")
    args = parser.parse_args(argv[1:])

    assets_dir = os.path.join(args.root, ASSETS_REL)
    manifest_path = os.path.join(args.root, MANIFEST_REL)

    if not os.path.isfile(manifest_path):
        print("ASSET_BASELINE_GATE=RED 找不到基线清单: %s" % manifest_path, file=sys.stderr)
        print("  修复：python3 scripts/gen-asset-manifest.py 并提交清单", file=sys.stderr)
        return 2
    if not os.path.isdir(assets_dir):
        print("ASSET_BASELINE_GATE=RED 找不到素材目录: %s" % assets_dir, file=sys.stderr)
        return 2

    with open(manifest_path, "r", encoding="utf-8") as handle:
        try:
            manifest = json.load(handle)
        except Exception as exc:  # noqa: BLE001 - 门禁要报告而不是抛栈
            print("ASSET_BASELINE_GATE=RED 基线清单不是合法 JSON: %s (%s)"
                  % (manifest_path, exc), file=sys.stderr)
            return 1

    disk = iter_png(assets_dir)
    disk_index = dict(disk)

    unregistered = []
    modified = []
    for rel, full in disk:
        entry = manifest.get(rel)
        if entry is None:
            unregistered.append(rel)
            continue
        expected = entry.get("fingerprint")
        actual = fingerprint_png(full)
        if expected != actual:
            modified.append((rel, expected, actual))

    missing = sorted(set(manifest) - set(disk_index))

    print("ASSET_BASELINE: 校验 %d 张 PNG（清单 %d 条）" % (len(disk), len(manifest)))
    if unregistered:
        print("  未登记（磁盘有、清单无 —— 新增素材必须登记）: %d 条" % len(unregistered),
              file=sys.stderr)
        for rel in unregistered[:args.max_report]:
            print("    ? %s" % rel, file=sys.stderr)
        if len(unregistered) > args.max_report:
            print("    ... 另有 %d 条" % (len(unregistered) - args.max_report), file=sys.stderr)
    if missing:
        print("  丢失（清单有、磁盘无）: %d 条" % len(missing), file=sys.stderr)
        for rel in missing[:args.max_report]:
            print("    - %s" % rel, file=sys.stderr)
        if len(missing) > args.max_report:
            print("    ... 另有 %d 条" % (len(missing) - args.max_report), file=sys.stderr)
    if modified:
        print("  被改动（指纹与基线不一致）: %d 条" % len(modified), file=sys.stderr)
        for rel, expected, actual in modified[:args.max_report]:
            print("    ! %s" % rel, file=sys.stderr)
            print("        期望 %s" % expected, file=sys.stderr)
            print("        实际 %s" % actual, file=sys.stderr)
        if len(modified) > args.max_report:
            print("    ... 另有 %d 条" % (len(modified) - args.max_report), file=sys.stderr)

    if unregistered or missing or modified:
        print("  UNREGISTERED=%d MISSING=%d MODIFIED=%d"
              % (len(unregistered), len(missing), len(modified)))
        print("ASSET_BASELINE_GATE=RED")
        return 1

    print("  UNREGISTERED=0 MISSING=0 MODIFIED=0")
    print("ASSET_BASELINE_GATE=GREEN")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
