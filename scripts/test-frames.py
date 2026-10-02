#!/usr/bin/env python3
"""scripts/test-frames.py —— 最小夹具的构造器（给门禁自测用）

为什么要有这个文件：门禁自测（scripts/selftest-gates.sh）要复跑的是**判据本身**。
如果夹具由自测脚本自己拼出来，就会踩到两个坑（本仓都实测过）：

  1) 夹具不完整 ⇒ 被验证的不是判据。第一次手搭 DDL 反向检查的夹具时只拷了
     `Database.java` + 脚本、没拷业务源码 ⇒ 第 1 节「DDL 里的表必须有消费者」
     把 75 张表全报成未接线、门禁直接红。那次自测的红/绿毫无解释力，等于噪声。
  2) 夹具与真相源「拼错」时判据不会红。自测的价值恰恰在于：真相源被改坏
     （门禁加载器指错目录、表名抽取正则失效）时必须立刻红。

因此：判据要跑的每个目录都在这里给出唯一的构建入口，负向对照调用同一入口。

用法：
  python3 scripts/test-frames.py build <frame> <dest> [--remove-table <name>]
  python3 scripts/test-frames.py list

frame（当前唯一）：ddl-consumer-root
  check-ddl-consumers.sh 的扫描根是 `plugins/*/src/main` 与 `tools/*/src/main`
  （`find plugins tools -path '*/src/main/*'`），所以夹具必须是**这四个目录树的
  逐文件镜像**，少一个文件就会换一个红法 —— 上面第 1 条踩的就是这个。

  落盘内容：
    - plugins/*/src/main、tools/*/src/main 的全部源码（原样拷贝）
    - scripts/ 全部（自测还要用到脚本自己）
    - 不拷 build/、docs/、plan/：门禁不看它们，拷了只会让夹具读数对不同步
  版本来源一律是**工作区**（不是 HEAD）：
    - `check-ddl-consumers.sh`：自测要复跑「正在写的这个脚本」，取 HEAD 会永远验证旧版
    - `Database.java`：要含本次新增的表，取 HEAD 会让「新表已登记」的格子永远红
    - 其余源码同理（夹具要能代表当前代码）

--remove-table <rpg_x>：从夹具的 DDL 真相源里整段删掉该表的
  `CREATE TABLE IF NOT EXISTS ... )`，用来构造「表消失」的负向对照。
  表不存在时退出码 2（静默什么都不删 = 夹具没造出来 = 自测恒绿）。
"""

from __future__ import annotations

import argparse
import re
import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

DDL_SOURCE = "plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java"
MANIFEST_SOURCE = "tools/db-migration/src/main/java/cn/originalrpg/tools/dbmigration/SchemaManifest.java"
GATE_SCRIPT = "scripts/check-ddl-consumers.sh"
SELF = "scripts/test-frames.py"

IGNORED_DIRECTORIES = ("build", ".gradle")

# DDL 里的建表块形如：
#   """
#   CREATE TABLE IF NOT EXISTS rpg_x (
#       ...
#   )
#   """,
# 结尾可能是 `""",`（后面还有别的语句）或 `"""`（数组最后一条）。
# 这里按「三引号 + 可选逗号」匹配，并把三引号连同收尾一起还原，
# 保证删完之后 Java 数组语法仍然合法（不自作聪明去删外面的逗号）。
TABLE_BLOCK = re.compile(
    r'CREATE TABLE IF NOT EXISTS {table} \(.*?\n\s*\)\n(\s*""",?)',
    re.DOTALL,
)


def copy_tree(source: Path, dest: Path) -> None:
    for path in source.rglob("*"):
        if any(part in IGNORED_DIRECTORIES for part in path.parts):
            continue
        if path.is_dir():
            continue
        target = dest / path.relative_to(REPO_ROOT)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)


def remove_table_block(ddl: str, table: str) -> tuple:
    pattern = TABLE_BLOCK.pattern.format(table=re.escape(table))
    new, count = re.subn(pattern, r"\1", ddl, count=1, flags=re.DOTALL)
    return new, count == 1


def collect_sources() -> list:
    sources = []
    for top in ("plugins", "tools"):
        root = REPO_ROOT / top
        if not root.is_dir():
            continue
        for main_dir in sorted(root.glob("*/src/main")):
            if main_dir.is_dir():
                sources.append(main_dir)
    return sources


def build_ddl_consumer_root(dest: Path, remove_table) -> None:
    if dest.exists() and any(dest.iterdir()):
        raise SystemExit("test-frames: 目标目录非空，拒绝覆盖: " + str(dest))
    dest.mkdir(parents=True, exist_ok=True)

    sources = collect_sources()
    if not sources:
        raise SystemExit("test-frames: 找不到 plugins/*/src/main 或 tools/*/src/main")
    for main_dir in sources:
        copy_tree(main_dir, dest)
    # scripts/ 也要：门禁自身与自测会互相调用
    copy_tree(REPO_ROOT / "scripts", dest)

    missing = [m for m in (DDL_SOURCE, MANIFEST_SOURCE, GATE_SCRIPT) if not (dest / m).is_file()]
    if missing:
        raise SystemExit("test-frames: 夹具骨架不完整，缺 " + repr(missing))

    if remove_table:
        path = dest / DDL_SOURCE
        text = path.read_text(encoding="utf-8")
        updated, removed = remove_table_block(text, remove_table)
        if not removed:
            raise SystemExit(
                "test-frames: --remove-table " + remove_table + " 在 DDL 真相源里没找到（夹具没造出来）"
            )
        path.write_text(updated, encoding="utf-8")


BUILDERS = {
    "ddl-consumer-root": build_ddl_consumer_root,
}


def main(argv) -> int:
    parser = argparse.ArgumentParser(description="门禁自测的最小夹具构造器")
    sub = parser.add_subparsers(dest="command", required=True)
    build = sub.add_parser("build", help="构造夹具到 <dest>")
    build.add_argument("frame", choices=sorted(BUILDERS))
    build.add_argument("dest")
    build.add_argument("--remove-table", default=None)
    sub.add_parser("list", help="列出可用 frame")
    args = parser.parse_args(argv)

    if args.command == "list":
        for name in sorted(BUILDERS):
            print(name)
        return 0

    dest = Path(args.dest).resolve()
    BUILDERS[args.frame](dest, args.remove_table)
    print("test-frames: BUILD " + args.frame + " -> " + str(dest))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
