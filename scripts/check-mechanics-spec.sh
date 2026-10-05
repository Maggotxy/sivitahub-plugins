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
# 依赖：**只用 Python 标准库**。门禁镜像（eclipse-temurin + apt 装 curl/python3）里既没有
# PyYAML 也没有 pip，因此脚本自带 §「最小 YAML 子集解析器」，不再 `import yaml`。
#   · 真 JAR 由 SnakeYAML 解析；本门禁只需要读 config.yml 里的标量/序列/行内映射，
#     不需要完整 YAML 语义（锚点/别名/多文档/顶层序列一律显式报错，绝不静默猜）。
#   · selftest-gates.sh 有一条回归夹具：脚本再出现 `import yaml` 就当场变红。
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
_doc_cache = {}


def load_yaml(rel_path):
    """按扩展名读载体：.json 用标准库 json；.yml 用下面的最小 YAML 子集解析器。"""
    if rel_path not in _doc_cache:
        full = os.path.join(root, rel_path)
        if not os.path.isfile(full):
            _doc_cache[rel_path] = None
        else:
            text = open(full, encoding="utf-8").read()
            if rel_path.endswith(".json"):
                _doc_cache[rel_path] = json.loads(text)
            else:
                _doc_cache[rel_path] = yaml_subset_load(text)
    return _doc_cache[rel_path]


# ---- 最小 YAML 子集解析器（纯标准库） -------------------------------------------------
# 门禁镜像只有 python3 标准库（无 PyYAML、无 pip），因此这里自带一个只覆盖本仓
# config.yml 实际形态的解析器：缩进映射 / 行内 {k: v} 映射 / 序列（块与行内）/
# 引号标量（单引号与双引号，含 "350.00" 这类必须保持字符串语义的带引号数字）/
# 块标量 | 与 |- / 行尾注释 / 点分键命中 null 时按 Python None 语义处理。
# 解析不了的形态一律抛 YamlSubsetError，绝不静默猜。
class YamlSubsetError(Exception):
    pass


_ESCAPES = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\", "/": "/",
            "0": "\0", "b": "\b", "f": "\f", "a": "\a", "v": "\v"}


def _is_ws(ch):
    return ch in " \t"


def _decode_double(body):
    out = []
    i = 0
    while i < len(body):
        ch = body[i]
        if ch != "\\":
            out.append(ch)
            i += 1
            continue
        i += 1
        if i >= len(body):
            raise YamlSubsetError("双引号字符串以反斜杠结尾")
        esc = body[i]
        if esc == "u" and i + 4 < len(body):
            out.append(chr(int(body[i + 1:i + 5], 16)))
            i += 5
            continue
        out.append(_ESCAPES.get(esc, esc))
        i += 1
    return "".join(out)


def _next_structural(text, i):
    """返回 >= i 的下一个**结构字符**位置，跳过空白与注释。"""
    n = len(text)
    while i < n:
        ch = text[i]
        if _is_ws(ch):
            i += 1
            continue
        if ch == "#" and (i == 0 or _is_ws(text[i - 1])):
            return n
        return i
    return n


def _comment_or_end(text, i):
    """标量结束处理：跳过空白；# 前必须有空白才是注释；还有别的字符即报错。"""
    n = len(text)
    j = i
    while j < n and _is_ws(text[j]):
        j += 1
    if j < n and text[j] != "#":
        raise YamlSubsetError("标量后有多余内容：%r" % text[i:].strip())
    return


def _split_top_level(body, offset, text):
    """按顶层逗号切分行内集合；引号内的逗号不算分隔符。"""
    items = []
    depth = 0
    buf = []
    i = 0
    quote = None
    while i < len(body):
        ch = body[i]
        if quote:
            buf.append(ch)
            if quote == '"' and ch == "\\":
                if i + 1 < len(body):
                    buf.append(body[i + 1])
                    i += 2
                    continue
            elif ch == quote:
                quote = None
            i += 1
            continue
        if ch in "\"'":
            quote = ch
            buf.append(ch)
            i += 1
            continue
        if ch in "[{":
            depth += 1
        elif ch in "]}":
            depth -= 1
            if depth < 0:
                raise YamlSubsetError("括号不配对：%r" % text.strip())
        if ch == "," and depth == 0:
            items.append("".join(buf))
            buf = []
            i += 1
            continue
        buf.append(ch)
        i += 1
    if quote:
        raise YamlSubsetError("引号未闭合：%r" % text.strip())
    if depth != 0:
        raise YamlSubsetError("括号不配对：%r" % text.strip())
    items.append("".join(buf))
    return items


def _find_colon(item):
    """在行内映射成员里找分隔 `:`（跳过引号与嵌套括号）。无则返回 -1。"""
    depth = 0
    quote = None
    i = 0
    while i < len(item):
        ch = item[i]
        if quote:
            if quote == '"' and ch == "\\":
                i += 2
                continue
            if ch == quote:
                quote = None
            i += 1
            continue
        if ch in "\"'":
            quote = ch
        elif ch in "[{":
            depth += 1
        elif ch in "]}":
            depth -= 1
        elif ch == ":" and depth == 0:
            nxt = item[i + 1] if i + 1 < len(item) else " "
            if _is_ws(nxt) or nxt in ",}]" or i + 1 == len(item):
                return i
        i += 1
    return -1


def _plain_scalar(token, line):
    token = token.strip()
    if token == "":
        raise YamlSubsetError("第 %d 行的标量为空" % line)
    if token[0] in "&*!>%@`" or token.startswith("<<"):
        raise YamlSubsetError("第 %d 行使用了不支持的 YAML 特性：%r" % (line, token))
    if token in ("null", "~", "Null", "NULL"):
        return None
    if token in ("true", "True", "TRUE", "yes", "Yes", "YES"):
        return True
    if token in ("false", "False", "FALSE", "no", "No", "NO"):
        return False
    if token in (".inf", ".Inf", ".INF"):
        return float("inf")
    if token in ("-.inf", "-.Inf", "-.INF"):
        return float("-inf")
    if token in (".nan", ".NaN", ".NAN"):
        return float("nan")
    try:
        if any(c in token for c in ".eE"):
            return float(token)
        return int(token)
    except ValueError:
        return token


def _scalar(text, i, line):
    """解析从 i 起的标量，返回 (值, 结束位置)。"""
    n = len(text)
    if i >= n:
        return None, i
    ch = text[i]
    if ch == '"':
        j = i + 1
        while j < n:
            if text[j] == "\\":
                j += 2
                continue
            if text[j] == '"':
                break
            j += 1
        if j >= n:
            raise YamlSubsetError("第 %d 行双引号未闭合" % line)
        value = _decode_double(text[i + 1:j])
        _comment_or_end(text, j + 1)
        return value, n
    if ch == "'":
        j = i + 1
        out = []
        while j < n:
            if text[j] == "'":
                if j + 1 < n and text[j + 1] == "'":
                    out.append("'")
                    j += 2
                    continue
                break
            out.append(text[j])
            j += 1
        if j >= n:
            raise YamlSubsetError("第 %d 行单引号未闭合" % line)
        _comment_or_end(text, j + 1)
        return "".join(out), n
    if ch == "[" or ch == "{":
        return _flow(text, i, line)
    j = i
    while j < n:
        c = text[j]
        if c == "#" and _is_ws(text[j - 1]):
            break
        j += 1
    value = _plain_scalar(text[i:j], line)
    return value, n


def _flow(text, i, line):
    n = len(text)
    opening = text[i]
    closing = "]" if opening == "[" else "}"
    depth = 1
    quote = None
    j = i + 1
    while j < n:
        c = text[j]
        if quote:
            if quote == '"' and c == "\\":
                j += 2
                continue
            if c == quote:
                quote = None
            j += 1
            continue
        if c in "\"'":
            quote = c
        elif c in "[{":
            depth += 1
        elif c in "]}":
            depth -= 1
            if depth == 0:
                break
        j += 1
    if j >= n:
        raise YamlSubsetError("第 %d 行行内集合未闭合" % line)
    body = text[i + 1:j]
    _comment_or_end(text, j + 1)
    items = _split_top_level(body, i, text)
    if opening == "[":
        return [(_scalar_value(it, line)) for it in items if it.strip() != ""], n
    result = {}
    for item in items:
        if item.strip() == "":
            continue
        colon = _find_colon(item)
        if colon < 0:
            raise YamlSubsetError("第 %d 行行内映射成员缺少 ':'：%r" % (line, item))
        key = item[:colon].strip()
        if not key:
            raise YamlSubsetError("第 %d 行行内映射键为空" % line)
        result[key] = _scalar_value(item[colon + 1:], line)
    return result, n


def _scalar_value(fragment, line):
    """解析一个完整的行内片段（可能又是行内集合）。"""
    text = fragment
    i = _next_structural(text, 0)
    if i >= len(text):
        raise YamlSubsetError("第 %d 行存在空的集合成员" % line)
    value, _ = _scalar(text, i, line)
    return value


def _line_tokens(line):
    """按顶格减号切出 (前导空白, 是否序列项, 项后内容起始列)。"""
    stripped = line.lstrip(" ")
    if stripped == "-" or stripped.startswith("- "):
        return len(line) - len(stripped), True
    return len(line) - len(stripped), False


def _parse_block(lines, start, indent, out_parent, line_map):
    """解析 [start, ...) 中缩进为 indent 的块，返回 (结果, 下一条未消费行号)。

    结果落在 out_parent 里：容器由调用方按上下文决定（映射 / 序列）。
    """
    i = start
    n = len(lines)
    is_seq = None
    while i < n:
        raw = lines[i]
        if raw.strip() == "" or raw.lstrip().startswith("#"):
            i += 1
            continue
        stripped = raw.lstrip(" ")
        cur_indent = len(raw) - len(stripped)
        if cur_indent < indent:
            break
        if cur_indent > indent:
            raise YamlSubsetError("第 %d 行缩进异常（%d > %d）" % (i + 1, cur_indent, indent))
        seq_item = stripped == "-" or stripped.startswith("- ")
        if is_seq is None:
            is_seq = seq_item
        elif is_seq != seq_item:
            raise YamlSubsetError("第 %d 行映射与序列混用" % (i + 1))
        if is_seq:
            i = _parse_seq_item(lines, i, indent, out_parent, line_map)
        else:
            i = _parse_map_entry(lines, i, indent, out_parent, line_map)
    return i


def _parse_seq_item(lines, i, indent, out_parent, line_map):
    raw = lines[i]
    stripped = raw[indent:]
    rest = stripped[1:]
    j = 0
    while j < len(rest) and _is_ws(rest[j]):
        j += 1
    inner_col = indent + 1 + j
    if j >= len(rest) or rest[j] == "#":
        # `-` 后为空：值在更深缩进的块里
        values, nxt = _parse_child(lines, i + 1, indent + 1, line_map)
        out_parent.append(values)
        return nxt
    item_text = raw[inner_col:]
    colon = _mapping_colon(item_text)
    if colon >= 0:
        entry = {}
        out_parent.append(entry)
        return _parse_map_entry(lines, i, indent, entry, line_map,
                                lead=(1 + j, colon))
    value, _ = _scalar(item_text, 0, i + 1)
    out_parent.append(value)
    return i + 1


def _mapping_colon(text):
    """判断该行是否是 `key: ...`；返回 ':' 的位置，不是映射则 -1。"""
    colon = _find_colon(text)
    if colon < 0:
        return -1
    key = text[:colon]
    if not key.strip():
        return -1
    return colon


def _parse_map_entry(lines, i, indent, out_parent, line_map, lead=None):
    raw = lines[i]
    base = indent if lead is None else indent + lead[0]
    text = raw[base:]
    colon = _mapping_colon(text) if lead is None else lead[1]
    if colon < 0:
        raise YamlSubsetError("第 %d 行不是 `键: 值` 形态" % (i + 1))
    key = _unquote_key(text[:colon].strip(), i + 1)
    value_part = text[colon + 1:]
    k = 0
    while k < len(value_part) and _is_ws(value_part[k]):
        k += 1
    if k < len(value_part) and value_part[k] in "|>":
        value, nxt = _parse_block_scalar(lines, i, value_part[k], line_map)
        out_parent[key] = value
        return nxt
    rest = value_part[k:]
    if rest == "" or rest.startswith("#"):
        value, nxt = _parse_child(lines, i + 1, indent + 1, line_map)
        out_parent[key] = value
        return nxt
    value, _ = _scalar(value_part, k, i + 1)
    out_parent[key] = value
    return i + 1


def _parse_child(lines, start, min_indent, line_map):
    """解析子块；空块按 YAML 规则给 None。"""
    n = len(lines)
    i = start
    while i < n:
        raw = lines[i]
        if raw.strip() == "" or raw.lstrip().startswith("#"):
            i += 1
            continue
        break
    if i >= n:
        return None, i
    stripped = lines[i].lstrip(" ")
    cur_indent = len(lines[i]) - len(stripped)
    if cur_indent < min_indent:
        return None, start
    if stripped == "-" or stripped.startswith("- "):
        container = []
    else:
        container = {}
    nxt = _parse_block(lines, i, cur_indent, container, line_map)
    return container, nxt


def _parse_block_scalar(lines, i, style, line_map):
    """块标量 `|` / `|-` / `>` / `>-`（本仓只用到 `|-`）。"""
    raw = lines[i]
    spec = raw[raw.index(style):].strip()
    keep = spec.endswith("-")
    folded = style == ">"
    indent = None
    collected = []
    i += 1
    n = len(lines)
    while i < n:
        cur = lines[i]
        if cur.strip() == "":
            collected.append(("", None))
            i += 1
            continue
        cur_indent = len(cur) - len(cur.lstrip(" "))
        if indent is None:
            if cur_indent == 0:
                break
            indent = cur_indent
        if cur_indent < indent:
            break
        collected.append((cur[indent:], cur_indent))
        i += 1
    while collected and collected[-1][1] is None:
        collected.pop()
    parts = [text for text, _ in collected]
    if folded:
        joined = []
        for idx, text in enumerate(parts):
            if text == "":
                joined.append("\n")
            elif idx + 1 < len(parts) and parts[idx + 1] != "":
                joined.append(text + " ")
            else:
                joined.append(text)
        value = "".join(joined)
    else:
        value = "\n".join(parts)
    if not keep and parts:
        value += "\n"
    return value, i


def _unquote_key(raw_key, line):
    if raw_key.startswith('"') or raw_key.startswith("'"):
        value, _ = _scalar(raw_key, 0, line)
        return str(value)
    if raw_key == "":
        raise YamlSubsetError("第 %d 行键名为空" % line)
    if ":" in raw_key:
        raise YamlSubsetError("第 %d 行键名含 ':'：%r" % (line, raw_key))
    return raw_key


def yaml_subset_load(text):
    """解析最小 YAML 子集，返回 Python 结构。"""
    if "\t" in text:
        raise YamlSubsetError("不支持制表符缩进")
    lines = text.split("\n")
    root = {}
    first = None
    for idx, raw in enumerate(lines):
        if raw.strip() == "" or raw.lstrip().startswith("#"):
            continue
        if raw.startswith("%") or raw.startswith("---") or raw.startswith("..."):
            raise YamlSubsetError("第 %d 行不支持的文档标记" % (idx + 1))
        first = idx
        break
    if first is None:
        return None
    stripped = lines[first].lstrip(" ")
    if stripped == "-" or stripped.startswith("- "):
        raise YamlSubsetError("顶层序列不受支持（本仓 config.yml 顶层是映射）")
    _parse_block(lines, first, 0, root, None)
    return root


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
