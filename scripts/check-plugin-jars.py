#!/usr/bin/env python3
"""打包门产物校验：每个 rpg-* 插件 jar 必须含 plugin.yml（否则 Paper 不会加载它）。

为什么用 python 读 zip 而不是 unzip / jar：
  eclipse-temurin 基础镜像里没有 unzip（实测 `unzip: not found` ⇒ 校验会把
  25 个完好的 jar 全部误报成「缺 plugin.yml」）。python3 由「准备下载器」阶段保证存在。

用法：python3 scripts/check-plugin-jars.py <jar 列表文件>
判据：退出码 —— 0 = 所有插件 jar 都含 plugin.yml 且至少检查到 1 个。
"""
import sys
import zipfile


def main(argv):
    if len(argv) != 2:
        print("用法: check-plugin-jars.py <jar-list-file>")
        return 2

    with open(argv[1], encoding="utf-8") as handle:
        candidates = [line.strip() for line in handle if line.strip()]

    missing = []
    checked = 0
    for jar in candidates:
        # 只看 plugins/<mod>/build/libs/<mod>-<version>.jar；sources jar 与聚合 jar 不算产物
        if not jar.startswith("plugins/") or "-sources.jar" in jar:
            continue
        if jar.count("/") != 4:
            continue
        checked += 1
        try:
            with zipfile.ZipFile(jar) as archive:
                if "plugin.yml" not in archive.namelist():
                    missing.append("%s (无 plugin.yml)" % jar)
        except Exception as exc:  # 打不开的 jar 等同于产物坏了
            missing.append("%s (%s)" % (jar, exc))

    for jar in missing:
        print("  [坏] %s" % jar)
    print("  JAR_CHECKED=%d JAR_MISSING_PLUGIN_YML=%d" % (checked, len(missing)))
    if missing or checked == 0:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
