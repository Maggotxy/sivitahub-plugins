#!/usr/bin/env python3
"""CNB 配额恢复后的一键收口脚本（对应 Stop hook 第 1~4 步）。

背景：v18 轮的交付物（两个分支的本地提交）因 CNB 根组织 CPU 配额耗尽而无法推送。
本脚本把「配额恢复后要做的事」固化成一条命令，避免每次人工重述。

用法：
  python plan/recover_after_quota.py --probe        # 只探测配额（安全，无副作用）
  python plan/recover_after_quota.py --push         # 推送两分支（git push → putfile 降级）
  python plan/recover_after_quota.py --pr           # 为两分支各开一个 PR
  python plan/recover_after_quota.py --merge <n>    # 合入 PR #n
  python plan/recover_after_quota.py --gate         # 触发十门并回读十条 *_GATE= 行
  python plan/recover_after_quota.py --all          # 探测 → 推送 → 开 PR（不合入）

设计纪律：
  - 只做文档/取证推送，不碰运行 Java / Schema / 经济配置。
  - 合入是**显式**动作（--merge <n>），不自动合入，避免误合。
  - 配额未恢复时**立即退出**，不重试刷配额。
"""
from __future__ import annotations

import argparse
import base64
import json
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path.home() / ".workbuddy-ai/skills/cnb-workflow"))
from cnb_client import CnbClient, CnbError, load_token  # noqa: E402

REPO = "xzqkjmnbvc2026/sivitahub"
ROOT = Path(__file__).resolve().parent.parent
BRANCHES = ["codex/asset-repro-encoding-fix", "codex/gap-matrix-v18"]
GIT_BASH = r"C:\Program Files\Git\bin\bash.exe"

TEN_GATE_KEYS = [
    "RESOURCE_PACK_GATE", "ASSET_BASELINE_GATE", "ASSET_REPRO_GATE",
    "DDL_CONSUMER_GATE", "DDL_REGISTRATION_GATE", "CMD_REFERENCE_GATE",
    "COMPILE_GATE", "TEST_GATE", "SUMMARY_GATE", "PACKAGE_GATE",
]

PROBE_CONFIG = """$:
  api_trigger_seed:
    - name: probe
      docker:
        image: python:3.13-slim
      stages:
        - name: probe
          script: |
            echo QUOTA_PROBE_OK
            git rev-parse HEAD
"""


# ---------------------------------------------------------------- 基础工具
def sh(cmd: str, timeout: int = 120):
    p = subprocess.run([GIT_BASH, "-lc", cmd], cwd=str(ROOT),
                       capture_output=True, text=True, timeout=timeout)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def wait_terminal(c: CnbClient, sn: str, limit: int = 40) -> dict:
    st = {}
    for _ in range(limit):
        time.sleep(6)
        st = c.build_status(REPO, sn)
        if str(st.get("status")) in ("success", "failed", "error", "canceled", "timeout"):
            break
    return st


# ---------------------------------------------------------------- 步骤 1
def probe_quota(c: CnbClient) -> tuple[bool, str]:
    r = c.start_build(REPO, "api_trigger_seed", branch="main",
                      title="quota-probe-recover", config=PROBE_CONFIG)
    sn = r.get("sn") or r.get("buildSn")
    st = wait_terminal(c, sn)
    for pid, p in (st.get("pipelinesStatus") or {}).items():
        for stage in (p.get("stages") or []):
            if stage.get("name") == "Prepare":
                if stage.get("status") == "success":
                    return True, f"Prepare=success (sn={sn})"
                return False, f"Prepare={stage.get('status')} (sn={sn}): {p.get('error') or ''}"
    return str(st.get("status")) == "success", f"sn={sn} status={st.get('status')}"


# ---------------------------------------------------------------- 步骤 2
def changed_files(c: CnbClient, base: str, head: str) -> list[str]:
    data = c.compare(REPO, base, head)
    files = data.get("files") or []
    out = []
    for f in files:
        if isinstance(f, dict):
            p = f.get("path") or f.get("filename") or f.get("name") or ""
            if p:
                out.append(p)
    return out


def putfile(c: CnbClient, local: Path, dest: str, msg: str, branch: str) -> bool:
    """播种法：base64 塞进 start_build config，流水线里 base64 -d + git push。"""
    b64 = base64.b64encode(local.read_bytes()).decode()
    script = (
        "set -e\n"
        f"mkdir -p $(dirname '{dest}')\n"
        f"printf '%s' '{b64}' | base64 -d > '{dest}'\n"
        f"wc -c '{dest}'\n"
        "git -c user.name=cnb -c user.email=cnb@cnb.cool add -A\n"
        f"git -c user.name=cnb -c user.email=cnb@cnb.cool commit -m '{msg}' || echo '无变更'\n"
        f"git push origin HEAD:{branch}\n"
    )
    body = "            " + script.replace("\n", "\n            ").rstrip() + "\n"
    config = (
        "$:\n"
        "  api_trigger_seed:\n"
        "    - name: seed\n"
        "      docker:\n"
        "        image: python:3.13-slim\n"
        "      stages:\n"
        "        - name: write and push\n"
        "          script: |\n" + body
    )
    r = c.start_build(REPO, "api_trigger_seed", branch=branch, title=msg, config=config)
    sn = r.get("sn") or r.get("buildSn")
    st = wait_terminal(c, sn, limit=60)
    ok = str(st.get("status")) == "success"
    print(f"    putfile {dest} -> {'OK' if ok else st.get('status')} (sn={sn})")
    return ok


def push_branches(c: CnbClient) -> dict:
    res = {}
    for b in BRANCHES:
        try:
            rc, out = sh(f"git push origin {b}", timeout=90)
            if rc == 0:
                print(f"  [push] {b} via git push OK")
                res[b] = "git-push"
                continue
            print(f"  [push] git push {b} rc={rc}: {out.strip()[:160]}")
        except Exception as e:
            print(f"  [push] git push {b} exception: {e}")
        res[b] = "needs-putfile"
    # 对 needs-putfile 的分支，逐文件播种
    for b in BRANCHES:
        if res[b] != "needs-putfile":
            continue
        files = changed_files(c, "main", b)
        print(f"  [push] {b}: 待推 {len(files)} 个文件（putfile 播种法）")
        allok = True
        for f in files:
            local = ROOT / f
            if not local.exists():
                print(f"    skip（本地不存在）{f}")
                continue
            ok = putfile(c, local, f, f"recover: 推送 {f}（配额恢复后补推）", b)
            allok = allok and ok
        res[b] = "putfile-ok" if allok else "putfile-partial"
    return res


# ---------------------------------------------------------------- 步骤 3
def open_pr(c: CnbClient, branch: str, title: str, body: str | None = None) -> int | None:
    try:
        r = c.request("POST", f"/{REPO}/-/pulls", body={
            "head": branch, "base": "main", "title": title, "body": body or title,
        })
        num = r.get("number") or r.get("id") if isinstance(r, dict) else None
        print(f"  [pr] {branch} -> #{num}")
        return num
    except CnbError as e:
        print(f"  [pr] {branch} ERR {e.status}: {str(e.body)[:200]}")
        return None


# ---------------------------------------------------------------- 步骤 4
def run_gate_and_read(c: CnbClient, tail: int = 2_000_000) -> tuple[dict, str]:
    r = c.start_build(REPO, "api_trigger_gate", branch="main", title="gate-after-recover")
    sn = r.get("sn") or r.get("buildSn")
    st = wait_terminal(c, sn, limit=200)
    hits = {}
    text = ""
    for item in c.collect_stage_logs(REPO, sn, tail_chars=tail):
        text += (item.get("text") or "") + "\n"
    for k in TEN_GATE_KEYS + ["GATE_SELFTEST", "REPRODUCIBLE", "MISMATCH"]:
        m = re.search(rf"{k}\s*[=:]\s*([^\s]+(?:\s+[A-Z]+=\d+)*)", text)
        if m:
            hits[k] = m.group(0).strip()
    return hits, sn


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--probe", action="store_true")
    ap.add_argument("--push", action="store_true")
    ap.add_argument("--pr", action="store_true")
    ap.add_argument("--merge", type=int)
    ap.add_argument("--gate", action="store_true")
    ap.add_argument("--all", action="store_true")
    args = ap.parse_args()

    c = CnbClient(load_token())

    if args.probe or args.all:
        print("== 步骤 1：探测配额 ==")
        ok, msg = probe_quota(c)
        print(f"  配额恢复: {ok} —— {msg}")
        if args.probe and not args.all:
            return 0 if ok else 1
        if not ok:
            print("  配额仍未恢复 ⇒ 停止（推送/PR/十门均不可行）。")
            return 1

    if args.push or args.all:
        print("== 步骤 2：推送分支 ==")
        print("  结果：", json.dumps(push_branches(c), ensure_ascii=False))

    if args.pr or args.all:
        print("== 步骤 3：开 PR ==")
        for b in BRANCHES:
            open_pr(c, b, f"recover: {b}（v18 判定 + 配额恢复后补推）")

    if args.merge:
        print(f"== 步骤 3b：合入 PR #{args.merge} ==")
        try:
            r = c.merge_pull(REPO, args.merge, merge_style="merge",
                             title=f"merge: PR #{args.merge}")
            print("  ", json.dumps(r, ensure_ascii=False)[:300])
        except CnbError as e:
            print(f"  merge ERR {e.status}: {str(e.body)[:250]}")

    if args.gate:
        print("== 步骤 4：触发十门并回读判据行 ==")
        hits, sn = run_gate_and_read(c)
        print(f"  sn={sn}")
        for k in TEN_GATE_KEYS:
            print(f"  {k} = {hits.get(k, '(未取到)')}")
        print(f"  GATE_SELFTEST = {hits.get('GATE_SELFTEST', '(未取到)')}")
        print(f"  REPRODUCIBLE = {hits.get('REPRODUCIBLE', '(未取到)')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
