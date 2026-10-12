#!/usr/bin/env python3
"""轮询 sn=cnb-fef-1k4ot1qvq 到终态，抽取判据行，落盘取证。

与 run_gate.py 的区别：不重新触发，只对一个已知 sn 取状态 + stage 日志。
--tail-chars 保持足够大（判据行可能在 stage 开头）。
"""
from __future__ import annotations
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.expanduser("~/.workbuddy-ai/skills/cnb-workflow"))
from cnb_client import CnbClient, CnbError  # noqa: E402

REPO = "xzqkjmnbvc2026/sivitahub"
SN = "cnb-fef-1k4ot1qvq"
OUT = "plan/gate-run-v18.json"
TAIL = 2000000
TIMEOUT = 4200  # 70 min

# 复刻 run_gate.py 的判据行清单（同一份规则）
KEY_PATTERNS = [
    r"FINAL_STATUS=\S+", r"GATE_SELFTEST=\S+", r"PASS=\d+", r"FAIL=\d+",
    r"SOURCE_STRING_GATE=\S+", r"SOURCE_STRING_TESTS=\d+",
    r"SOURCE_CONTRACT_CHECKS=\d+", r"SPECS=\d+", r"SPEC_FILES=\d+",
    r"ASSET_BASELINE_GATE=\S+", r"ASSET_REPRO\w*=\S+",
    r"RESOURCE_PACK\w*=\S+", r"DDL_\w+=\S+", r"CMD_REFERENCE\w*=\S+",
    r"COMPILE_GATE=\S+", r"TEST_GATE=\S+", r"SUMMARY_GATE=\S+",
    r"PACKAGE_GATE=\S+", r"PAPER_SMOKE_GATE=\S+", r"PAPER_STARTED=\S+",
    r"PAPER_PLUGINS_ENABLED=\d+", r"PAPER_OWN_ERRORS=\d+",
    r"PAPER_COMMUNITY_ERRORS=\d+", r"SMOKE_LOCK_CHECK=\S+",
    r"SMOKE_LOCK_ENTRIES=\d+", r"SMOKE_LOCK_LOCAL=\d+",
    r"SMOKE_LOCK_PROBLEMS=\d+", r"SMOKE_LOCK_SELFTEST=\S+",
    r"SUM\s+tests=\d+\s+failed=\d+\s+errors=\d+\s+skipped=\d+\s+modules=\d+",
    r"JAR_COUNT=\d+", r"BUILD SUCCESSFUL", r"真实仓库已无此类断言",
    r"SINGLE_SOURCE_OF_TRUTH_GATE=\S+", r"VERDICT_MATRIX_CONSISTENCY_GATE=\S+",
    r"MECHANICS_SPEC_GATE=\S+", r"MECHANICS_SPEC_ENTRIES=\d+",
    r"MECHANICS_SPEC_BY_KIND=\S+", r"MECHANICS_SPEC_WEAK=\d+",
    r"MECHANICS_SPEC_REPORT=\S+", r"\[OK\]", r"\[FAIL\]", r"\[RED\]",
    r"REPRODUCIBLE=\d+", r"MISMATCH=\d+",
]


def extract(text: str) -> list[str]:
    hits: list[str] = []
    for pat in KEY_PATTERNS:
        for m in re.finditer(pat, text):
            ls = text.rfind("\n", 0, m.start()) + 1
            le = text.find("\n", m.end())
            if le == -1:
                le = len(text)
            line = text[ls:le].strip()
            if line and line not in hits:
                hits.append(line)
    return hits


def main() -> int:
    c = CnbClient()
    t0 = time.time()
    status: dict = {}
    while True:
        try:
            status = c.build_status(REPO, SN)
        except CnbError as e:
            print("status err:", e, flush=True)
            time.sleep(20)
            continue
        pipes = status.get("pipelinesStatus") or {}
        states = []
        for pid, pipe in pipes.items():
            for st in pipe.get("stages") or []:
                states.append(f"{st.get('name')}={st.get('status')}")
        print(f"[{int(time.time()-t0)}s] {status.get('status')} | " + " ".join(states), flush=True)
        if status.get("status") in ("success", "failed", "error", "canceled", "timeout"):
            break
        if time.time() - t0 > TIMEOUT:
            print("TIMEOUT waiting", flush=True)
            break
        time.sleep(20)

    stages = c.collect_stage_logs(REPO, SN, tail_chars=TAIL)
    all_hits: list[str] = []
    for st in stages:
        for h in extract(st.get("text") or ""):
            if h not in all_hits:
                all_hits.append(h)

    payload = {
        "sn": SN,
        "status": status.get("status"),
        "basis": "d2cffd27632ba9a623c957397fe168bca753ff7f",
        "event": "api_trigger_gate",
        "hits": all_hits,
        "stages": [
            {"stage": s["stage"], "status": s["status"],
             "chars": len(s.get("text") or "")}
            for s in stages
        ],
    }
    os.makedirs("plan", exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=1)

    dump = OUT + ".stages.txt"
    with open(dump, "w", encoding="utf-8") as fh:
        for s in stages:
            fh.write("\n" + "=" * 80 + f"\n=== stage: {s['stage']}  status={s['status']}\n" + "=" * 80 + "\n")
            fh.write((s.get("text") or "") + "\n")

    print("\n===== 判据行 =====", flush=True)
    for h in all_hits:
        print(" ", h, flush=True)
    print("\n===== stage 状态 =====", flush=True)
    for s in stages:
        print(f"  {s['status']:>8}  {s['stage']}  ({len(s.get('text') or '')} chars)", flush=True)
    print("\nSN=%s STATUS=%s -> %s" % (SN, status.get("status"), OUT), flush=True)
    return 0 if status.get("status") == "success" else 1


if __name__ == "__main__":
    raise SystemExit(main())
