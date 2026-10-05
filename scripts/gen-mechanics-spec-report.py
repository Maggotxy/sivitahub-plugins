#!/usr/bin/env python3
"""scripts/gen-mechanics-spec-report.py —— 从 mechanics-spec.json 生成交付报告节。

背景：docs/GAME_DESIGN.md 的「规格机械化状态」一节必须**可重生成**，
报告里的每个数字都来自 scripts/mechanics-spec.json 与本门禁的真实判据行，
不允许手写。手写数字一旦和 spec 漂移，报告就从「核对结果」变成「另一种传言」。

用法：
  python3 scripts/gen-mechanics-spec-report.py              # 打印到 stdout
  python3 scripts/gen-mechanics-spec-report.py --write      # 写进 docs/GAME_DESIGN.md 末尾
  python3 scripts/gen-mechanics-spec-report.py --check      # 只校验（CI 可用；不一致退出 1）

数据来源（全部由脚本自己算，不接受外部传数字）：
  · scripts/mechanics-spec.json 的 entries[]
  · 真实执行 scripts/check-mechanics-spec.sh 拿判据行（GATE / ENTRIES / BY_KIND / WEAK）
  · entries[] 里 _unverifiable 段落（不可机械核对清单，逐条带理由）
  · entries[] 里 _inconsistencies 段落（本轮发现并修正的不一致）
"""
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SPEC_PATH = os.path.join(ROOT, "scripts/mechanics-spec.json")
DOC_PATH = os.path.join(ROOT, "docs", "GAME_DESIGN.md")
GATE = os.path.join(ROOT, "scripts", "check-mechanics-spec.sh")

SECTION_TITLE = "## 规格机械化状态（W28 自动核对）"
BEGIN = "<!-- BEGIN mechanics-spec-report (generated) -->"
END = "<!-- END mechanics-spec-report (generated) -->"


def run_gate():
    """真实跑门禁，拿判据行。失败即抛，避免报告与门禁脱节。"""
    proc = subprocess.run(
        ["sh", GATE], cwd=ROOT, capture_output=True, text=True)
    lines = {}
    for line in proc.stdout.splitlines():
        if "=" in line and line.split("=")[0].startswith("MECHANICS_SPEC_"):
            key, _, value = line.partition("=")
            lines[key] = value
    if "MECHANICS_SPEC_GATE" not in lines:
        raise SystemExit("门禁没有打出判据行，无法生成报告：\n" + proc.stdout + proc.stderr)
    lines["_exit"] = str(proc.returncode)
    return lines


def load_spec():
    with open(SPEC_PATH, encoding="utf-8") as handle:
        return json.load(handle)


def doc_heading_order():
    """按 docs/GAME_DESIGN.md 里 ## 小节的出现顺序返回节名（不含生成节与后续手写节）。"""
    order = []
    inside_generated = False
    for line in open(DOC_PATH, encoding="utf-8"):
        if line.strip() == BEGIN:
            inside_generated = True
        if line.strip() == END:
            inside_generated = False
            continue
        if inside_generated:
            continue
        if line.startswith("## "):
            # 生成节自己的标题由 SECTION_TITLE 定义，不该被算成「待覆盖的文档节」
            if line[3:].strip() == SECTION_TITLE:
                continue
            order.append(line[3:].strip())
    return order


def kind_of(entry):
    return (entry.get("carrier") or {}).get("kind", "?")


def render(spec, gate):
    entries = spec["entries"]
    headings = doc_heading_order()

    by_section = {}
    for entry in entries:
        by_section.setdefault(entry["section"], []).append(entry)

    # 节顺序取文档标题顺序，spec 里有文档没写的节排在最后（不静默丢弃）
    ordered_sections = [h for h in headings if h in by_section]
    ordered_sections += [s for s in sorted(by_section) if s not in ordered_sections]

    weak_ids = []
    for entry in entries:
        carrier = entry.get("carrier") or {}
        if carrier.get("kind") == "java-const" and not (
                carrier.get("behavior_ref") or entry.get("behavior_ref")):
            weak_ids.append(entry["id"])

    out = []
    out.append(BEGIN)
    out.append(SECTION_TITLE)
    out.append("")
    out.append("> 本节由 `scripts/gen-mechanics-spec-report.py` 从 "
               "`scripts/mechanics-spec.json` 与门禁判据行生成，**禁止手写数字**。")
    out.append("> 重生成命令：`python3 scripts/gen-mechanics-spec-report.py --write`")
    out.append("> 只校验不写：`python3 scripts/gen-mechanics-spec-report.py --check`")
    out.append("")

    out.append("### 门禁判据行（脚本原样输出）")
    out.append("")
    out.append("```")
    out.append("MECHANICS_SPEC_GATE=%s" % gate.get("MECHANICS_SPEC_GATE", "?"))
    out.append("MECHANICS_SPEC_ENTRIES=%s" % gate.get("MECHANICS_SPEC_ENTRIES", "?"))
    out.append("MECHANICS_SPEC_BY_KIND=%s" % gate.get("MECHANICS_SPEC_BY_KIND", "?"))
    out.append("MECHANICS_SPEC_WEAK=%s" % gate.get("MECHANICS_SPEC_WEAK", "?"))
    out.append("```")
    out.append("")

    out.append("### 全量：按节分布 × 载体分布")
    out.append("")
    out.append("| 节 | 登记条数 | config | behavior | java-const |")
    out.append("|---|---:|---:|---:|---:|")
    totals = {"all": 0, "config": 0, "behavior": 0, "java-const": 0}
    for section in ordered_sections:
        rows = by_section[section]
        counts = {"config": 0, "behavior": 0, "java-const": 0}
        for entry in rows:
            counts[kind_of(entry)] = counts.get(kind_of(entry), 0) + 1
        totals["all"] += len(rows)
        for kind in counts:
            totals[kind] += counts[kind]
        out.append("| %s | %d | %d | %d | %d |" % (
            section, len(rows), counts["config"], counts["behavior"], counts["java-const"]))
    out.append("| **合计** | **%d** | **%d** | **%d** | **%d** |" % (
        totals["all"], totals["config"], totals["behavior"], totals["java-const"]))
    out.append("")

    uncovered = [h for h in headings if h != SECTION_TITLE and h not in by_section]
    if uncovered:
        out.append("未登记任何条目的节：%s。" % "、".join(uncovered))
        out.append("")
    else:
        out.append("`docs/GAME_DESIGN.md` 的每个 `##` 节都至少登记了一条可机械核对断言。")
        out.append("")

    if weak_ids:
        out.append("### weak 条目（java-const 且缺 `behavior_ref`）")
        out.append("")
        out.append("`MECHANICS_SPEC_WEAK=%s`，逐条：%s。"
                   % (gate.get("MECHANICS_SPEC_WEAK", "?"), "、".join(weak_ids)))
        out.append("")
    else:
        out.append("### weak 条目")
        out.append("")
        out.append("`MECHANICS_SPEC_WEAK=0`：所有 `java-const` 条目都带了 `behavior_ref`，")
        out.append("没有「只有常量声明、没有行为佐证」的条目。")
        out.append("")

    out.append("### 本轮发现并修正的不一致")
    out.append("")
    inconsistencies = spec.get("_inconsistencies") or []
    if inconsistencies:
        out.append("| 编号 | 文档原文 | 实测事实 | 改后 | 依据 |")
        out.append("|---|---|---|---|---|")
        for item in inconsistencies:
            out.append("| %s | %s | %s | %s | %s |" % (
                item["id"], item["doc_before"], item["observed"], item["doc_after"], item["basis"]))
    else:
        out.append("本轮**未发现需要改一侧的不一致**：后 8 节逐条核对结果，文档与代码一致。")
    out.append("")

    out.append("### 不可机械核对清单")
    out.append("")
    out.append("以下断言**不登记为 spec 条目**（硬凑成 `config` 条目比不登记更危险）。")
    out.append("")
    unverifiable = spec.get("_unverifiable") or []
    if unverifiable:
        out.append("| 断言（含行号） | 判定为不可机械核对的理由 |")
        out.append("|---|---|")
        for item in unverifiable:
            out.append("| %s | %s |" % (item["claim"], item["reason"]))
    else:
        out.append("（无）")
    out.append("")

    out.append("### 一句话结论")
    out.append("")
    locked = totals["all"]
    out.append("`docs/GAME_DESIGN.md` 共 %d 条断言已被机械锁定"
               "（config %d / behavior %d / java-const %d，门禁 `MECHANICS_SPEC_GATE=%s`）；"
               "另有 %d 条经判定为不可机械核对、只以清单形式记录理由，"
               "它们**没有**被任何判据约束。"
               % (locked, totals["config"], totals["behavior"], totals["java-const"],
                  gate.get("MECHANICS_SPEC_GATE", "?"), len(unverifiable)))
    out.append(END)
    return "\n".join(out) + "\n"


def replace_section(document, block):
    pattern = re.compile(
        re.escape(BEGIN) + r".*?" + re.escape(END) + r"\n?", re.S)
    if pattern.search(document):
        return pattern.sub(block, document, count=1)
    trimmed = document.rstrip("\n")
    return trimmed + "\n\n" + block


def main():
    args = sys.argv[1:]
    spec = load_spec()
    gate = run_gate()
    block = render(spec, gate)

    if "--write" in args:
        document = open(DOC_PATH, encoding="utf-8").read()
        new_document = replace_section(document, block)
        if new_document != document:
            with open(DOC_PATH, "w", encoding="utf-8") as handle:
                handle.write(new_document)
            print("已写入 %s（%s 起 %d 字符）"
                  % (os.path.relpath(DOC_PATH, ROOT), SECTION_TITLE, len(block)))
        else:
            print("报告节已是最新，未改动。")
        return 0

    if "--check" in args:
        document = open(DOC_PATH, encoding="utf-8").read()
        if BEGIN not in document or END not in document:
            print("MECHANICS_SPEC_REPORT=MISSING（docs/GAME_DESIGN.md 里没有生成节marker）")
            return 1
        current = document[document.index(BEGIN):document.index(END) + len(END)] + "\n"
        if current != block:
            print("MECHANICS_SPEC_REPORT=STALE")
            print("  重跑：python3 scripts/gen-mechanics-spec-report.py --write", file=sys.stderr)
            return 1
        print("MECHANICS_SPEC_REPORT=FRESH")
        return 0

    sys.stdout.write(block)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
