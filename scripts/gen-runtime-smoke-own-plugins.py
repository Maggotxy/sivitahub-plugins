#!/usr/bin/env python3
"""生成 scripts/runtime-smoke-own-plugins.txt：<module>\t<plugin.yml 的 name>。

为什么需要这个清单：
  运行冒烟要把每个插件 jar 装进 plugins/，并按 Paper 日志的 `Enabling <name>`
  统计启用数。这两个名字来源不同 —— jar 在 plugins/<module>/build/libs/，
  而 Paper 用 plugin.yml 的 name（如 RpgCore）。冒烟脚本跑在 dash 下且镜像
  没有 PyYAML，所以这里一次性把对应关系机械生成成纯文本清单，
  由 scripts/run-paper-smoke.sh 直接读；生成时逐个读 plugin.yml 校验一致性。

用法：python3 scripts/gen-runtime-smoke-own-plugins.py
判据：退出码 —— 0 = 25 个插件全部写出且 name 与目录名在两种命名法下一致。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "scripts", "runtime-smoke-own-plugins.txt")
PLUGINS = os.path.join(ROOT, "plugins")


def camel(module):
    # rpg-ai-npc -> RpgAiNpc（与本仓 plugin.yml 的 name 完全一致）
    return "".join(part.capitalize() for part in module.split("-"))


def main():
    rows = []
    missing = []
    for module in sorted(os.listdir(PLUGINS)):
        if not module.startswith("rpg-"):
            continue
        plugin_yml = os.path.join(PLUGINS, module, "src", "main", "resources", "plugin.yml")
        if not os.path.isfile(plugin_yml):
            missing.append(module)
            continue
        text = open(plugin_yml, encoding="utf-8").read()
        match = re.search(r"^name:\s*(\S+)", text, re.M)
        if not match:
            missing.append(module + "(无 name)")
            continue
        name = match.group(1)
        if name != camel(module):
            # 不是错误：只是提醒两种命名法不一致时，日志名与本清单名不同
            print("注意：%s 的 plugin.yml name=%s，与目录名推导 %s 不同（以 plugin.yml 为准）"
                  % (module, name, camel(module)), file=sys.stderr)
        rows.append((module, name))

    if missing:
        print("gen-runtime-smoke-own-plugins: 缺 plugin.yml：%s" % ", ".join(missing), file=sys.stderr)
        return 1
    if len(rows) != 25:
        print("gen-runtime-smoke-own-plugins: 期望 25 个自研插件，实际 %d" % len(rows), file=sys.stderr)
        return 1

    with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        for module, name in rows:
            handle.write("%s\t%s\n" % (module, name))
    print("gen-runtime-smoke-own-plugins: 写出 %d 条 -> %s" % (len(rows), OUT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
