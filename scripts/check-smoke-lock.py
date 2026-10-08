#!/usr/bin/env python3
"""scripts/check-smoke-lock.py —— 冒烟社区插件锁清单的静态预检

为什么存在
----------
W36 的真实事故：`scripts/runtime-smoke-plugins.lock.json` 里 AuthMe 一条把
**Sivita 自改版的 sha512** 配了**公网 Modrinth 的 url** —— 两个来源对不上，
校验必然失败。发现它的代价是一次约 10 分钟的云上冒烟（sn=cnb-kus-1k4djem3f，
14/15 OK，只有 AuthMeReloaded 红）。

这类错误**纯静态可查**，不该花 10 分钟才知道。所以把它前移到脚本开头：
1 秒内把「local 文件是否真在仓库里」「sha512 是否对得上」「每条是否恰好一个来源」
查干净，再决定要不要去下 60MB 的 Paper。

判据行（stdout，供 CI 机器读取）
-------------------------------
  SMOKE_LOCK_CHECK=PASS|FAIL
  SMOKE_LOCK_ENTRIES=<n>      清单条数
  SMOKE_LOCK_LOCAL=<n>        其中从 libs/vendor/ 取本地文件的条数
  SMOKE_LOCK_PROBLEMS=<n>     问题条数（>0 即 FAIL）

退出码：0 = PASS；1 = FAIL（问题已逐条打印）；2 = 用法错误

环境约束：只用 stdlib。门禁镜像 eclipse-temurin:25-jdk 里没有 pip / PyYAML / jq。

用法：
  python3 scripts/check-smoke-lock.py                 # 查真实清单
  python3 scripts/check-smoke-lock.py <清单> <仓库根>
  python3 scripts/check-smoke-lock.py --selftest      # 跑负向夹具（见下）

--selftest（负向夹具，10 组）
----------------------------
本仓规矩：每加一条判据，都必须配一条「原形态必须变红」的负向夹具，否则判据可能
**反向失效**（漏判，甚至把已判 GREEN 的东西重新判绿）。这里把夹具做成**常驻可执行**的，
而不是一次性跑完就丢。

而且每条夹具不只要求「红」，还要求**红在预期的原因上** —— 只判退出码不看原因，
正是本项目踩过的坑（一次夹具因为路径写错而「全部按预期变红」，其实是文件根本没找到）。
"""
import copy
import hashlib
import json
import os
import sys
import tempfile

_HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_LOCK = os.path.join(_HERE, "runtime-smoke-plugins.lock.json")
DEFAULT_ROOT = os.path.dirname(_HERE)


def sha512_of(path):
    h = hashlib.sha512()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def check(lock_path, root):
    """返回 (entries, local_count, problems)。problems 为空即 PASS。"""
    problems = []
    try:
        with open(lock_path, encoding="utf-8") as fh:
            lock = json.load(fh)
    except Exception as exc:  # 解析不了就直接一条问题，别抛栈
        return 0, 0, ["锁清单无法解析：%s: %s" % (lock_path, exc)]

    plugins = lock.get("plugins")
    if not isinstance(plugins, list) or not plugins:
        return 0, 0, ["锁清单的 plugins 不是非空数组"]

    seen_files, seen_names, local_count = set(), set(), 0
    for index, entry in enumerate(plugins, start=1):
        tag = "#%d" % index
        if not isinstance(entry, dict):
            problems.append("%s 不是对象" % tag)
            continue

        name = entry.get("name")
        filename = entry.get("file")
        digest = entry.get("sha512")
        has_url, has_local = "url" in entry, "local" in entry

        if not name:
            problems.append("%s 缺 name" % tag)
        elif name in seen_names:
            problems.append("%s name 重复：%s" % (tag, name))
        else:
            seen_names.add(name)

        if not filename:
            problems.append("%s 缺 file" % tag)
        elif filename in seen_files:
            problems.append("%s file 重复：%s" % (tag, filename))
        else:
            seen_files.add(filename)

        # sha512 必须是 128 位十六进制串。只校验形态，值本身靠 local 分支或下载后核对。
        if not isinstance(digest, str) or len(digest) != 128:
            problems.append("%s sha512 不是 128 位十六进制串" % tag)
        elif any(c not in "0123456789abcdef" for c in digest):
            problems.append("%s sha512 含非十六进制字符" % tag)

        # 来源必须唯一确定：两个都没有 = 拿不到文件；两个都有 = 到底信哪个？
        if has_url == has_local:
            problems.append(
                "%s 必须恰好有 url 或 local 之一（现在 url=%s local=%s）"
                % (tag, has_url, has_local)
            )

        if has_local:
            local_count += 1
            rel = entry["local"]
            path = os.path.join(root, rel)
            if not os.path.isfile(path):
                problems.append(
                    "%s local 文件不存在：%s（vendor jar 必须入库，"
                    ".gitignore 已用 !libs/vendor/*.jar 放行；见 libs/vendor/README.md）"
                    % (tag, rel)
                )
            elif isinstance(digest, str) and sha512_of(path) != digest:
                problems.append("%s local 文件 sha512 与清单不符：%s" % (tag, rel))

    return len(plugins), local_count, problems


# --------------------------------------------------------------- 负向夹具

MISSING = "libs/vendor/__definitely_missing__.jar"


def _first_local_index(plugins):
    for i, e in enumerate(plugins):
        if isinstance(e, dict) and "local" in e:
            return i
    return 0


def mutations(base, root):
    """返回 [(名字, 期望命中的原因片段, 变异后的 lock 或 None, 原始文本覆盖)]。

    每条都带「期望原因」：夹具必须红在预期的原因上，不能只是红。
    """
    plugins = base.get("plugins") or [{}]
    li = _first_local_index(plugins)
    fi = 0
    si = 1 if len(plugins) > 1 else 0

    # 「sha512 与清单不符」这条夹具必须让 local 指向一个**确实存在**的文件，
    # 否则会先撞上「local 文件不存在」分支，sha512 比较根本跑不到 —— 那就是
    # 「红了但红在别的原因上」，等于没测到。首选清单里真实的 local 路径，
    # 退化时用 README.md 顶上（它一定在仓库里）。
    real_local = None
    entry_li = plugins[li] if isinstance(plugins[li], dict) else {}
    if isinstance(entry_li.get("local"), str) and \
            os.path.isfile(os.path.join(root, entry_li["local"])):
        real_local = entry_li["local"]
    else:
        real_local = "libs/vendor/README.md"

    def mut(fn):
        lock = copy.deepcopy(base)
        fn(lock)
        return lock

    def as_local(entry):
        entry.pop("url", None)
        entry["local"] = MISSING

    def as_local_existing_wrong_digest(entry):
        entry.pop("url", None)
        entry["local"] = real_local
        entry["sha512"] = "0" * 128

    return [
        (
            "local 指向不存在的文件",
            "local 文件不存在",
            mut(lambda l: as_local(l["plugins"][li])),
            None,
        ),
        (
            "local 文件 sha512 与清单不符",
            "sha512 与清单不符",
            mut(lambda l: as_local_existing_wrong_digest(l["plugins"][li])),
            None,
        ),
        (
            "url 与 local 同时存在",
            "必须恰好有 url 或 local 之一",
            mut(lambda l: l["plugins"][fi].__setitem__("local", MISSING)),
            None,
        ),
        (
            "url 与 local 都没有",
            "必须恰好有 url 或 local 之一",
            mut(lambda l: (l["plugins"][fi].pop("url", None),
                           l["plugins"][fi].pop("local", None))),
            None,
        ),
        (
            "sha512 长度不对",
            "不是 128 位十六进制串",
            mut(lambda l: l["plugins"][fi].__setitem__("sha512", "abc")),
            None,
        ),
        (
            "sha512 含非十六进制字符",
            "含非十六进制字符",
            mut(lambda l: l["plugins"][fi].__setitem__("sha512", "z" * 128)),
            None,
        ),
        (
            "file 重复",
            "file 重复",
            mut(lambda l: l["plugins"][si].__setitem__("file", l["plugins"][fi]["file"])),
            None,
        ),
        (
            "name 重复",
            "name 重复",
            mut(lambda l: l["plugins"][si].__setitem__("name", l["plugins"][fi]["name"])),
            None,
        ),
        (
            "plugins 为空数组",
            "不是非空数组",
            mut(lambda l: l.__setitem__("plugins", [])),
            None,
        ),
        (
            "清单不是合法 JSON",
            "无法解析",
            None,
            "{ this is not json",
        ),
    ]


def selftest(lock_path, root):
    """跑负向夹具。返回 0/1。"""
    try:
        base = json.load(open(lock_path, encoding="utf-8"))
    except Exception as exc:
        print("SMOKE_LOCK_SELFTEST=FAIL=0 FAIL=1")
        print("  - 夹具基准清单无法解析：%s" % exc)
        return 1

    passed, failed = 0, 0
    tmp = tempfile.mkdtemp(prefix="smoke-lock-selftest.")
    for name, expect, lock, raw in mutations(base, root):
        path = os.path.join(tmp, "case.json")
        if raw is not None:
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(raw)
        else:
            with open(path, "w", encoding="utf-8") as fh:
                json.dump(lock, fh, ensure_ascii=False)

        _, _, problems = check(path, root)
        joined = "\n".join(problems)
        if not problems:
            print("  - [FAIL] %s：判成 GREEN 了（判据反向失效）" % name)
            failed += 1
        elif expect not in joined:
            print("  - [FAIL] %s：红了但原因不对（期望命中「%s」，实际：%s）"
                  % (name, expect, problems[0]))
            failed += 1
        else:
            print("  - [ok]   %s → %s" % (name, problems[0]))
            passed += 1

    try:
        os.remove(os.path.join(tmp, "case.json"))
        os.rmdir(tmp)
    except OSError:
        pass

    print("SMOKE_LOCK_SELFTEST=PASS=%d FAIL=%d" % (passed, failed))
    return 0 if failed == 0 else 1


def main(argv):
    args = argv[1:]
    if args and args[0] == "--selftest":
        lock_path = args[1] if len(args) > 1 else DEFAULT_LOCK
        root = args[2] if len(args) > 2 else DEFAULT_ROOT
        return selftest(lock_path, root)

    lock_path = args[0] if len(args) > 0 else DEFAULT_LOCK
    root = args[1] if len(args) > 1 else DEFAULT_ROOT

    entries, local_count, problems = check(lock_path, root)

    print("SMOKE_LOCK_ENTRIES=%d" % entries)
    print("SMOKE_LOCK_LOCAL=%d" % local_count)
    print("SMOKE_LOCK_PROBLEMS=%d" % len(problems))
    for item in problems:
        print("  - %s" % item)
    ok = not problems
    print("SMOKE_LOCK_CHECK=%s" % ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
