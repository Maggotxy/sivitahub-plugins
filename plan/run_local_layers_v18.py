#!/usr/bin/env python3
"""在 basis=d2cffd2 上重跑可本地执行的判据层 (a)-(i)，抽取全部判据行。

**为什么要在干净 LF 树里跑**（本项目实测踩坑）：
  - 本机 `core.autocrlf=true` ⇒ 工作树与 `git archive` 默认都给 CRLF，
    POSIX `.sh` 跑到 `\r` 就报 `$'\\r': command not found`。
  - 用 `git -c core.autocrlf=false -c core.eol=lf archive HEAD` 导出的树是 **LF**，
    且**保持仓库目录结构** ⇒ 脚本里 `dirname $0/..` 解析出的 ROOT 就是树根（正确）。
  - 对照组：把脚本单独复制到别处再 `sh`，`dirname $0/..` 会指向错目录（实测）。

**取 python**：`python3` 在 CNB 门禁镜像里有；本机用受管 python 的绝对路径喂 PATH。

输出：plan/local-layers-v18.txt（判据行汇总 + 原始 stdout）。
不修改仓库任何被跟踪文件；临时树 `/.tmp-lf-v18` 跑完即删。
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 临时 LF 树必须放在**仓库内**：沙箱只允许向工作区批量写文件（仓库外的 tar -x 实测 exit=2）。
# 清理不由本脚本做（仓库内批量删除会触发安全守卫）；跑完手动删。
LF = os.path.join(ROOT, ".tmp-lf-v18")
OUT = os.path.join(ROOT, "plan", "local-layers-v18.txt")
PYDIR = "/c/Users/maggo/.workbuddy-ai/binaries/python/versions/3.13.12"

KEY = [
    r"FINAL_STATUS=\S+", r"GATE_SELFTEST=\S+", r"PASS=\d+", r"FAIL=\d+",
    r"SOURCE_STRING_GATE=\S+", r"SOURCE_STRING_TESTS=\d+",
    r"SOURCE_CONTRACT_CHECKS=\d+", r"SPECS=\d+", r"SPEC_FILES=\d+",
    r"ASSET_BASELINE_GATE=\S+", r"ASSET_REPRO\w*=\S+",
    r"RESOURCE_PACK\w*=\S+", r"DDL_\w+=\S+", r"CMD_REFERENCE\w*=\S+",
    r"COMPILE_GATE=\S+", r"TEST_GATE=\S+", r"SUMMARY_GATE=\S+",
    r"PACKAGE_GATE=\S+", r"SMOKE_LOCK_CHECK=\S+", r"SMOKE_LOCK_ENTRIES=\d+",
    r"SMOKE_LOCK_LOCAL=\d+", r"SMOKE_LOCK_PROBLEMS=\d+", r"SMOKE_LOCK_SELFTEST=\S+",
    r"SINGLE_SOURCE_OF_TRUTH_GATE=\S+", r"VERDICT_MATRIX_CONSISTENCY_GATE=\S+",
    r"MECHANICS_SPEC_GATE=\S+", r"MECHANICS_SPEC_ENTRIES=\d+",
    r"MECHANICS_SPEC_BY_KIND=\S+", r"MECHANICS_SPEC_WEAK=\d+",
    r"MECHANICS_SPEC_REPORT=\S+", r"ENV_TAG_\w+=\S+",
    r"UNREGISTERED=\d+", r"MISSING=\d+", r"MODIFIED=\d+",
    r"REPRODUCIBLE=\d+", r"MISMATCH=\d+", r"COUNT=\d+",
    r"ASSET_BASELINE:\s.*", r"manifest=\d+", r"prov=\d+",
]


def extract(text: str) -> list[str]:
    hits: list[str] = []
    for pat in KEY:
        for m in re.finditer(pat, text):
            ls = text.rfind("\n", 0, m.start()) + 1
            le = text.find("\n", m.end())
            if le == -1:
                le = len(text)
            line = text[ls:le].strip()
            if line and line not in hits:
                hits.append(line)
    return hits


def sh(cmd: str, cwd: str | None = None) -> tuple[int, str]:
    """用 **Git Bash** 执行（不是 WSL）。

    实测坑：subprocess 里裸调 `["bash", ...]` 会命中系统 PATH 里的 **WSL bash**，
    而 WSL 不认 `/d/...` 路径（它用 `/mnt/d/...`）⇒ 所有 `cd /d/...` 全部
    "No such file or directory"，且报 `wsl: 检测到 localhost 代理配置…`。
    显式用 Git Bash 的绝对路径即可与工具本身的 shell 语义对齐。
    """
    git_bash = r"C:\Program Files\Git\bin\bash.exe"
    p = subprocess.run([git_bash, "-lc", cmd], cwd=cwd or ROOT, capture_output=True, text=True,
                       encoding="utf-8", errors="replace", timeout=2400)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def to_posix(p: str) -> str:
    """Windows 绝对路径 → git-bash 的 /d/... 形式（反斜杠会被 bash 当转义吃掉）。"""
    p = os.path.abspath(p).replace("\\", "/")
    if len(p) >= 2 and p[1] == ":":
        p = "/" + p[0].lower() + p[2:]
    return p


# 每条在 LF 树里跑；PATH 前置受管 python 目录使 `python3` 可用。
# PRE 用函数生成（LF 目录名在 main() 里才定，每次不同）。
def pre() -> str:
    return f"export PATH='{PYDIR}':\"$PATH\"; cd {to_posix(LF)}; "


LAYER_CMDS = [
    ("a", "scripts 计数", "ls scripts/*.sh scripts/*.py | wc -l"),
    ("b", "素材逐像素基线 (W12-A1)", "python3 scripts/check-asset-baseline.py"),
    ("c", "素材 provenance 同集 (W12-A1)", "python3 scripts/gen-asset-manifest.py --check"),
    ("d", "资源包完整性 (W7-A1)", "sh scripts/check-resource-pack.sh"),
    ("e", "source-string 测试", "sh scripts/check-source-string-tests.sh"),
    ("f", "source-contract 规格", "sh scripts/check-source-contract.sh"),
    ("g", "mechanics-spec 绑定", "sh scripts/check-mechanics-spec.sh"),
    ("h", "env-tag 正当性", "sh scripts/check-env-tag-justified.sh"),
    ("i", "素材可复现性 (W13-A1)", "sh scripts/check-asset-reproducible.sh"),
]


def main() -> int:
    lines: list[str] = []
    lines.append("# 本地判据层复跑（basis=d2cffd2）")
    lines.append("# 生成者：本地 WorkBuddy agent，任务 W36-A5-gate-quota-blocked")
    lines.append("# 运行环境：干净 LF 树（git archive core.autocrlf=false），保持仓库结构")
    lines.append("")

    rc, head = sh("git -c core.autocrlf=false rev-parse HEAD")
    lines.append(f"HEAD={head.strip()}")
    rc, lsr = sh("git rev-parse origin/main")
    lines.append(f"origin/main={lsr.strip()}")

    # 导出 LF 干净树（仓库内；仓库外会被沙箱拒批量写）。
    # **每次换新目录名**：实测复用同名目录时，仓库内 `rm -rf` 会被安全守卫静默跳过，
    # 于是 tar 覆盖到半旧的树上、或脚本读到上一轮的文件 ⇒ 层 (i) 读到未含修复的旧脚本，
    # 报出「已修好却仍然红」的假象。换名可保证「这棵树 100% 来自本轮 HEAD」。
    global LF
    LF = os.path.join(ROOT, f".tmp-lf-v18-{int(time.time())}")
    os.makedirs(LF, exist_ok=True)
    rc, out = sh(f"git -c core.autocrlf=false -c core.eol=lf archive HEAD | tar -x -C {to_posix(LF)}")
    lines.append(f"LF_TREE_DIR={os.path.basename(LF)}")
    lines.append(f"LF_TREE_EXTRACT_EXIT={rc}")
    # 自证：树里脚本必须是 LF，且结构完整 —— 否则后面所有 shell 层都会假红
    rc_chk, chk = sh(f"head -1 {to_posix(LF)}/scripts/check-resource-pack.sh | od -c | head -1")
    lines.append(f"LF_TREE_HEAD_CHECK={chk.strip()[:120]}")
    # 自证：树里必须含本轮的关键修复（编码 UTF-8），否则说明树没刷新
    rc_fix, fixc = sh(f"grep -c 'encoding UTF-8' {to_posix(LF)}/scripts/check-asset-reproducible.sh || true")
    lines.append(f"LF_TREE_HAS_ENCODING_FIX={fixc.strip()}")
    lines.append("")

    all_hits: list[str] = []
    for tag, name, raw in LAYER_CMDS:
        cmd = pre() + raw
        print(f"===== ({tag}) {name} =====", flush=True)
        print(f"$ {cmd}", flush=True)
        try:
            rc, out = sh(cmd)
        except subprocess.TimeoutExpired:
            rc, out = 124, "TIMEOUT"
        tail = "\n".join(out.strip().splitlines()[-40:])
        print(f"EXIT={rc}")
        print(tail, flush=True)
        for h in extract(out):
            if h not in all_hits:
                all_hits.append(h)
        lines.append(f"===== ({tag}) {name} =====")
        lines.append(f"EXIT={rc}")
        lines.append(tail)
        lines.append("")

    lines.append("===== 汇总判据行 =====")
    for h in all_hits:
        lines.append(h)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))

    print("\n===== 汇总判据行 =====", flush=True)
    for h in all_hits:
        print(" ", h, flush=True)
    print("\n-> %s" % OUT, flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
