#!/usr/bin/env python3
# scripts/gen-asset-manifest.py —— 生成逐像素基线清单（W12-A1）
#
# 扫描 resource-pack/assets/**/*.png，为每张图登记
#   {"<相对路径>": {"fingerprint": "<宽>x<高>:<sha256hex>", "bytes": N}}
# 相对路径以 resource-pack/assets 为根、用 "/" 分隔（与平台无关）。
#
# 稳定输出的三条纪律（门禁靠「清单没变」判红，抖动一次就是全量假红）：
#   * keys 排序（LC_ALL=C 的字节序，等价 python 默认 str 序，两者都是码位序）
#   * 缩进 2、ensure_ascii=False、末尾换行 —— 同一份素材重复跑字节一致
#   * 不写时间戳 / 不写生成者主机名等易变字段
#
# 用法：
#   python3 scripts/gen-asset-manifest.py            # 写 resource-pack/assets-manifest.json
#   python3 scripts/gen-asset-manifest.py --check    # 只比对，不写（0=一致 1=有差异）

import argparse
import json
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)

from asset_fingerprint import fingerprint_png  # noqa: E402

DEFAULT_ROOT = os.path.dirname(SCRIPT_DIR)
ASSETS_REL = os.path.join("resource-pack", "assets")
MANIFEST_REL = os.path.join("resource-pack", "assets-manifest.json")


def iter_png(assets_dir):
    """按相对路径排序产出每个 PNG（排序是稳定输出的前提）。"""
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


def build_manifest(assets_dir):
    manifest = {}
    for rel, full in iter_png(assets_dir):
        manifest[rel] = {
            "fingerprint": fingerprint_png(full),
            "bytes": os.path.getsize(full),
        }
    return manifest


def dump(manifest):
    return json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def main(argv):
    parser = argparse.ArgumentParser(description="生成素材逐像素基线清单")
    parser.add_argument("--root", default=DEFAULT_ROOT, help="仓库根（默认由脚本位置推出）")
    parser.add_argument("--check", action="store_true", help="只比对不写盘")
    args = parser.parse_args(argv[1:])

    assets_dir = os.path.join(args.root, ASSETS_REL)
    manifest_path = os.path.join(args.root, MANIFEST_REL)
    if not os.path.isdir(assets_dir):
        print("找不到素材目录: %s" % assets_dir, file=sys.stderr)
        return 2

    manifest = build_manifest(assets_dir)
    if not manifest:
        print("没有扫到任何 PNG: %s" % assets_dir, file=sys.stderr)
        return 1
    text = dump(manifest)

    if args.check:
        if not os.path.exists(manifest_path):
            print("清单文件不存在: %s" % manifest_path, file=sys.stderr)
            return 2
        with open(manifest_path, "r", encoding="utf-8") as handle:
            current = handle.read()
        if current != text:
            print("清单与磁盘素材不一致（重跑 gen 后提交新清单）: %s" % manifest_path,
                  file=sys.stderr)
            return 1
        print("清单与磁盘素材一致: %s（%d 张）" % (manifest_path, len(manifest)))
        return 0

    with open(manifest_path, "w", encoding="utf-8") as handle:
        handle.write(text)
    print("已写入 %s（%d 张 PNG）" % (manifest_path, len(manifest)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
