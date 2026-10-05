#!/bin/sh
# scripts/check-mechanics-spec.sh —— 玩法规格 ↔ 代码 机械绑定门禁
#
# 背景：docs/GAME_DESIGN.md（标题即「完整玩法规格」）是唯一真相源的一半，但规格与代码之间
# 长期没有**机械判据**，漂移只能靠人工审计发现。本项目实测已发生 3 次：
#   · G-106 文档把血契写成二转，前置技能 vanished-horizon 却是三转 ⇒ 二转玩家永远拿不到
#   · G-202 文档把「胚材槽上限」写成固定区间，实际是随胚材等级上浮的公式
#   · G-209 文档承诺预警 0.4–0.8 秒，config.yml 是 20 tick（1.0 秒）且被代码下限强制
# 本门禁把这些漂移变成红灯：规格里的每条可机械核对断言都声明「由哪个代码载体承载」，
# 任一条不符（值不同 / 载体不存在）即 RED。
#
# 载体三类（scripts/mechanics-spec.json 的 carrier.kind）：
#   config      : 读 plugins/<mod>/src/main/resources/config.yml 的点分键（支持 * 通配下标）
#   behavior    : 解析一条已存在的行为级 JUnit 测试 `class#method`，断言该方法确实存在于源码。
#                 （该测试是否 PASS 由 test 门禁保证，本门禁只管「载体存在」这件结构事实。）
#   java-const  : 结构化解析 `static final <类型> <名> = <值>;` 声明 —— 不是自由正则匹配任意文本。
#                 没有 behavior_ref 的 java-const 条目计入 MECHANICS_SPEC_WEAK。
#
# 用法：sh scripts/check-mechanics-spec.sh [--spec <path>]
# 判据行：MECHANICS_SPEC_GATE=GREEN|RED
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

SPEC="scripts/mechanics-spec.json"
while [ $# -gt 0 ]; do
  case "$1" in
    --spec) shift; SPEC=$1 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

python3 - "$ROOT" "$SPEC" <<'PY'
import json
import os
import re
import sys

root, spec_path = sys.argv[1], sys.argv[2]
spec_path = spec_path if os.path.isabs(spec_path) else os.path.join(root, spec_path)

if not os.path.isfile(spec_path):
    print("MECHANICS_SPEC_GATE=RED")
    print("  spec 文件不存在：%s" % spec_path)
    sys.exit(1)

try:
    import yaml
except ImportError:
    print("MECHANICS_SPEC_GATE=RED")
    print("  python3 缺少 PyYAML —— 无法解析 config.yml")
    sys.exit(1)

try:
    spec = json.load(open(spec_path, encoding="utf-8"))
except Exception as exc:  # noqa: BLE001
    print("MECHANICS_SPEC_GATE=RED")
    print("  spec JSON 解析失败：%s" % exc)
    sys.exit(1)

entries = spec.get("entries")
if not isinstance(entries, list) or not entries:
    print("MECHANICS_SPEC_GATE=RED")
    print("  spec 缺少非空 entries[]")
    sys.exit(1)

# ---- 载体解析 -------------------------------------------------------------------
_yaml_cache = {}


def load_yaml(rel_path):
    if rel_path not in _yaml_cache:
        full = os.path.join(root, rel_path)
        if not os.path.isfile(full):
            _yaml_cache[rel_path] = None
        else:
            _yaml_cache[rel_path] = yaml.safe_load(open(full, encoding="utf-8"))
    return _yaml_cache[rel_path]


class WildcardDfs:
    """解析点分键；`*` 表示「任意数量的下标」，收集所有匹配的叶子值。"""

    def __init__(self):
        self.values = []
        self.misses = []

    def walk(self, node, parts, origin):
        if not parts:
            self.values.append(node)
            return
        head, rest = parts[0], parts[1:]
        if head == "*":
            # `*` 通配一层：列表元素或字典的值
            children = node if isinstance(node, list) else (
                list(node.values()) if isinstance(node, dict) else [])
            if not children:
                self.misses.append(origin)
                return
            for child in children:
                self.walk(child, rest, origin)
            return
        if not isinstance(node, dict) or head not in node:
            self.misses.append("%s.%s" % (origin, head))
            return
        self.walk(node[head], rest, origin)


def resolve_config(rel_path, key):
    doc = load_yaml(rel_path)
    if doc is None:
        return None, ["config 文件不存在或为空：%s" % rel_path]
    dfs = WildcardDfs()
    dfs.walk(doc, key.split("."), key)
    return dfs.values, dfs.misses


_IDENT = r"[A-Za-z_$][A-Za-z0-9_$]*"


def parse_java_const(rel_path, const_name):
    """结构化解析 `static final <类型> <名> = <值>;`，返回归一化后的字面量或 None。"""
    full = os.path.join(root, rel_path)
    if not os.path.isfile(full):
        return None, "java 文件不存在：%s" % rel_path
    text = open(full, encoding="utf-8").read()
    pattern = re.compile(
        r"\bstatic\s+final\s+(?:%s(?:\s*<[^;=]*>)?|%s(?:\s*<[^;=]*>)?(?:\s*\[\s*\])?)\s+%s\s*=\s*([^;]+);"
        % (r"[A-Za-z_$][\w.$]*", r"[A-Za-z_$][\w.$]*", re.escape(const_name)))
    match = pattern.search(text)
    if not match:
        # 方法形态（HiddenPathRules.capacity 是方法而非字段）——只认「结构化返回单个字面量」
        method = re.search(
            r"\b\w[\w.$]*\s+%s\s*\(\s*[^)]*\)\s*\{([^{}]*)\}" % re.escape(const_name), text)
        if method:
            body = method.group(1)
            returns = re.findall(r"\breturn\s+([^;]+);", body)
            if len(returns) == 1:
                token = returns[0].strip()
                return token, None
        return None, "未找到 `static final <类型> %s = <值>;` 声明" % const_name
    value = match.group(1).strip()
    return value, None


def const_tokens(raw):
    """把常量初始化表达式归一化成候选 token 集合。

    覆盖：纯字面量、`new BigDecimal("15.00")`、以及 switch/方法体里的多个 `-> 字面量`。
    这是**结构化**取值，不是对任意文本做自由正则。
    """
    tokens = set()
    text = raw.strip()
    decimal = re.fullmatch(r'new\s+BigDecimal\(\s*"([^"]+)"\s*\)', text)
    if decimal:
        return {normalize(decimal.group(1))}
    literal = re.fullmatch(r'"?[\w.\-]+"?', text)
    if literal and "->" not in text:
        return {normalize(text)}
    for raw_num in re.findall(r'->\s*([0-9]+)\b', text):
        tokens.add(raw_num)
    return tokens


def normalize(value):
    """把字面量归一化成可比较的 token。"""
    if isinstance(value, bool):
        return str(value).lower()
    if isinstance(value, (int, float)):
        if isinstance(value, float) and value.is_integer():
            return str(int(value))
        return str(value)
    if isinstance(value, str):
        token = value.strip().strip('"')
        if token.startswith("0x") or token.startswith("0X"):
            try:
                return str(int(token, 16))
            except ValueError:
                return token
        token = token.replace("_", "")
        try:
            number = float(token)
            return str(int(number)) if number.is_integer() else str(number)
        except ValueError:
            return token
    return str(value)


def flatten(values):
    out = []
    for value in values:
        if isinstance(value, list):
            out.extend(value)
        else:
            out.append(value)
    return out


def compare(expected, actual_values):
    actual_values = flatten(actual_values)
    if isinstance(expected, list):
        actual = [normalize(v) for v in actual_values]
        want = [normalize(v) for v in expected]
        return sorted(actual) == sorted(want) or actual == want, actual
    actual = [normalize(v) for v in actual_values]
    want = normalize(expected)
    return bool(actual) and all(a == want for a in actual), actual


failures = []
kind_count = {"config": 0, "behavior": 0, "java-const": 0}
weak = 0

for entry in entries:
    eid = entry.get("id", "<no-id>")
    claim = entry.get("claim", "")
    carrier = entry.get("carrier") or {}
    kind = carrier.get("kind")

    if kind not in kind_count:
        failures.append((eid, claim, "未知 carrier.kind=%s" % kind, "config|behavior|java-const"))
        continue
    kind_count[kind] += 1

    if kind == "config":
        rel, key = carrier.get("path"), carrier.get("key")
        if not rel or not key:
            failures.append((eid, claim, "carrier 缺少 path/key", "path+key"))
            continue
        values, misses = resolve_config(rel, key)
        if values is None:
            # 载体文件不存在/为空：必须 RED，不能因为 len(None) 抛异常而被 set -eu 之外的形式
            # 静默放过（实测：缺文件时 python 抛 TypeError，退出码被吞成 0 ⇒ 假绿）。
            failures.append((eid, claim, misses[0] if misses else "config 文件缺失", rel))
            continue
        if "expect_count" in carrier:
            want = carrier["expect_count"]
            if len(values) != want:
                failures.append((eid, claim, "命中 %d 条" % len(values), want))
            continue
        if not values:
            failures.append((eid, claim, "配置键不存在：%s" % (misses[0] if misses else key), key))
            continue
        ok, actual = compare(carrier.get("expect"), values)
        if not ok:
            failures.append((eid, claim, actual, carrier.get("expect")))

    elif kind == "behavior":
        ref = carrier.get("test", "")
        if "#" not in ref:
            failures.append((eid, claim, "behavior.test 必须是 class#method", ref))
            continue
        test_class, method = ref.split("#", 1)
        full = os.path.join(root, test_class)
        if not os.path.isfile(full):
            failures.append((eid, claim, "测试类不存在：%s" % test_class, ref))
            continue
        text = open(full, encoding="utf-8").read()
        if not re.search(r"\b%s\s*\(" % re.escape(method), text):
            failures.append((eid, claim, "测试方法不存在：%s" % method, ref))
            continue

    else:  # java-const
        rel = carrier.get("path")
        const = carrier.get("const")
        if not rel or not const:
            failures.append((eid, claim, "carrier 缺少 path/const", "path+const"))
            continue
        value, error = parse_java_const(rel, const)
        if value is None and error:
            failures.append((eid, claim, error, const))
            continue
        want = normalize(carrier.get("expect"))
        tokens = const_tokens(value)
        if not tokens:
            failures.append((eid, claim, "常量表达式无法结构化解出取值：%s" % value.strip(), const))
            continue
        if want not in tokens:
            failures.append((eid, claim, sorted(tokens), carrier.get("expect")))
        if not (carrier.get("behavior_ref") or entry.get("behavior_ref")):
            weak += 1

total = len(entries)
by_kind = "config:%d,behavior:%d,java-const:%d" % (
    kind_count["config"], kind_count["behavior"], kind_count["java-const"])

if failures:
    print("MECHANICS_SPEC_GATE=RED")
    print("MECHANICS_SPEC_ENTRIES=%d" % total)
    print("MECHANICS_SPEC_BY_KIND=%s" % by_kind)
    print("MECHANICS_SPEC_WEAK=%d" % weak)
    for eid, claim, actual, expected in failures:
        print("  [FAIL] %s" % eid)
        print("         claim  : %s" % claim)
        print("         期望   : %s" % expected)
        print("         实际   : %s" % actual)
    sys.exit(1)

print("MECHANICS_SPEC_GATE=GREEN")
print("MECHANICS_SPEC_ENTRIES=%d" % total)
print("MECHANICS_SPEC_BY_KIND=%s" % by_kind)
print("MECHANICS_SPEC_WEAK=%d" % weak)
PY
