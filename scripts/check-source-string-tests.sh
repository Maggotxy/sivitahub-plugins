#!/bin/sh
# G-302-RESIDUAL 判据门禁：还有哪些测试在「读本仓生产源码 + 对源码做字符串断言」？
#
# ── 为什么要有这一版（判据补强动机）────────────────────────────────────────────
# W16-A1 的判据太窄：它要求**同一个 @Test 方法体内直接**出现
#   Files.readString/Files.readAllBytes + "plugins/...|tools/..." 字面量 + contains/indexOf。
# 于是三种**间接形态**全部漏判（W17 实测漏掉 DungeonTimeoutLifecycleContractTest）：
#   ① readString 藏在 helper/私有方法里，@Test 方法体只调 source("plugins/...") 这类包装，
#      生产路径字面量其实在**调用方的实参**上；
#   ② 路径字面量来自类的常量/字段，而不是方法体内联；
#   ③ 断言用 indexOf 做**先后比较**（a < b / assertTrue(... > ...)）而不是 contains。
# 本版把「读生产源码」解析成类级事实：helper 里的 readString + 调用方传入的生产路径
# 也算，并按类级追踪断言，覆盖以上三种形态。
#
# ── 什么算缺口（三条同时满足）───────────────────────────────────────────────────
#   1) 类里存在把文件内容当字符串读出来的调用点：
#        Files.readString(...) / Files.readAllBytes(...)
#   2) 有路径指向本仓**生产源码/生产清单**：字面量以 plugins/ 或 tools/ 开头，且落在
#      src/main/java 或 src/main/resources 下。解析来源包括：
#        · readString 的实参里；
#        · helper 调用方的实参里（①：readString 藏在 helper）；
#        · 类的常量/字段（②）：调用方传的是常量名时，回溯该常量的初始化语句。
#   3) 断言**直接落在读出来的那个字符串上**：
#        · 一行式 Files.readString(...).contains(...)；或
#        · 读结果赋给变量 V 后，出现 V.contains( / V.indexOf( / V.lastIndexOf( /
#          V.startsWith(。V 既可以是 readString 的直接赋值，也可以是接收 helper 返回值
#          的变量（①）；或
#        · assertTrue/assertFalse(... a < b ...) 这类先后比较（③），且类里确有上述读取。
#      "直接落在读出来的字符串上"是排除误报的关键：对**解析后的 Map/集合**做 contains
#      （配置契约测试）不算。
#
# ── 什么不算缺口（避免误报，逐条说明为什么）────────────────────────────────────
#   · 读 src/test/resources 夹具、或读测试**自己**的文件：断言对象是"测试数据"，
#     生产代码改了它也不会漂移 ⇒ 不算（前缀同为 plugins/，但没有 /src/main/）。
#   · 路径不以 plugins/ 或 tools/ 开头（相对路径、变量拼接）：静态判据无法确认是否
#     指向 src/main，宁可漏判也不误报 ⇒ 不算。
#   · 读 **server/** 部署产物：前缀不是 plugins/，本仓按设计不含 server/，相关测试带
#     @Tag(env) 由 .cnb.yml 的 excludeTags 分类 ⇒ 不算。
#     例：WorldTravelConfigurationContractTest 读 server/bin/maintain-world-coverage.sh。
#   · 只读文件、没有任何字符串/位置断言（例如读 YAML 后按结构断言）：断言对象是解析
#     结果而不是源码字符串 ⇒ 不算。这一条同时排除了「读配置 → 对 Map/Set 做 contains」
#     的配置契约测试（例：LanguageBundleContractTest 读语言包得到 key 集合）。
#   · Files.newBufferedReader / newInputStream / lines()：返回流/行，用途是反序列化
#     （YAML/JSON/二进制）。算进来会把一批配置契约测试误判成缺口（W18-A1 实测多报 4 个）
#     ⇒ 不算。
#   · 断言只用 .contains( 等字符串方法但类里根本没有读生产源码的调用点 ⇒ 不算。
#   · 用读取结果做**过滤谓词**（`if (Files.readString(f).contains(x)) { hits.add(f); }`），
#     再对**筛出来的文件列表**做 contains/anyMatch/assertEquals：断言对象是「哪些文件提到
#     某标识符」这一仓库布局事实，不是源码字符串本身。按上一条口径（对解析后的集合断言）
#     ⇒ 不算。例：rpg-items/EquipmentExitPathReachabilityTest 与
#     rpg-core/SeasonAttunementSettlementWiringTest 的 productionReferences(identifier)。
#     ⚠️ W18-A1 第二轮修正：旧实现只看「`).contains(` 紧邻读取调用」就把这种谓词当断言，
#     会误报上面两个类。现在要求 contains 结果真的处在 assert 调用里才计。
#   · `Files.walk(生产目录)` 遍历出文件后只断言「文件名列表」：路径不是 plugins/ 开头的
#     字面量（是 ROOT.resolve("plugins") 拼出来的），按上上条口径宁可漏判不误报 ⇒ 不算。
#     它的**子形态**（对读出来的正文做 contains/顺序断言）仍然会被判为缺口，见上面的判据。
#
# 用法：sh scripts/check-source-string-tests.sh [--json]
# 退出码：0 = 本仓生产源码里已无此类断言；1 = 仍有命中（打印文件:行:类名）
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

exec python3 - "$@" <<'PY'
import json, re, sys, glob

PROD_PREFIXES = ('plugins/', 'tools/')
ASSERT_CALLS = r'(?:contains|indexOf|lastIndexOf|startsWith)'
READ_CALL = re.compile(r'Files\s*\.\s*(?:readString|readAllBytes)\s*\(')
# 一行式：Files.readString(...).contains(...)
ONE_LINER = re.compile(r'\)\s*\.\s*' + ASSERT_CALLS + r'\s*\(')
# assertTrue/assertFalse 里的先后比较（等价原 indexOf 先后比较，形态 ③）。
ORDER_COMPARISON = re.compile(r'assert(?:True|False)\s*\([^;{}]*[<>][^;{}]*\)')
# 读结果被赋给变量（含 helper 返回值：String s = source("plugins/...")）。
ASSIGNED_STRING = re.compile(r'\b(?:String|var|String\[\])\s+(\w+)\s*=\s*')
# 方法参数名 → helper 名（用于把「调用方传的路径」追回到「helper 里的 readString」）。
METHOD_DECL = re.compile(r'\b(?:private|static|public|protected)\s+[\w<>\[\], ]+\s+(\w+)\s*\(([^)]*)\)')


def test_methods(src):
    return re.findall(r'@Test\b[^;{]*?void\s+(\w+)\s*\(', src, re.DOTALL)


def is_production_path(literal):
    """路径字面量是否指向本仓生产源码/生产清单（不是测试夹具、不是部署产物）。"""
    if not literal.startswith(PROD_PREFIXES):
        return False
    if '/src/test/' in literal:
        return False
    return '/src/main/java/' in literal or '/src/main/resources/' in literal


def production_paths(text):
    return sorted({literal for literal in re.findall(r'"([^"]*)"', text)
                   if is_production_path(literal)})


def constant_paths(src, argument):
    """读取实参**就是**某个常量名时的常量路径解析（形态 ②）。

    覆盖 `read(PRODUCTION)` / `readString(PRODUCTION_PATH)` 这类写法：路径字面量不在
    调用点，而在字段声明里。只回溯该常量的**初始化语句**，避免把同类里无关路径误报。

    ⚠️ 收紧点（W18-A1 第二轮）：实参必须是**指向读取目标的那个常量本身**
    （允许 `PRODUCTION` 以及 `PRODUCTION + "/x"` 这类以常量名开头的简单拼接），
    而不是「实参文本里恰好提到了某个大写常量名」。否则会误报：把
    `anyMatch(path -> path.equals(LIFECYCLE_ONLY))` 这种**比较/lambda 里提及**的常量
    当成「读取的是 LIFECYCLE_ONLY 这个生产路径」，从而把「对解析后的路径列表做断言」
    的测试判成缺口（实测误报 rpg-items/EquipmentExitPathReachabilityTest）。
    """
    found = []
    for constant in set(re.findall(r'\b([A-Z][A-Z0-9_]{2,})\b', argument)):
        # 实参必须**以该常量名开头**（`read(PRODUCTION)` / `read(PRODUCTION + suffix)`），
        # 才算「读取目标就是这个常量」。在 lambda / 比较里出现的常量名一律不算。
        if not re.match(r'\s*' + re.escape(constant) + r'\b', argument):
            continue
        declaration = re.search(
            r'\b' + re.escape(constant) + r'\s*=\s*'
            r'(?:"[^"]*"|\n\s*"[^"]*"|Path\.of\s*\(\s*"[^"]*")',
            src)
        if not declaration:
            continue
        found.extend(production_paths(declaration.group(0)))
    return sorted(set(found))


def read_helpers(src):
    """helper 方法名 → 该方法体内是否真的 readString。

    helper 的判定与调用点分开，因此 ①（readString 藏在 source(...) 里）被覆盖。
    """
    helpers = set()
    for match in METHOD_DECL.finditer(src):
        name, params = match.group(1), match.group(2)
        brace = src.find('{', match.end())
        if brace < 0:
            continue
        depth, index = 0, brace
        while index < len(src):
            if src[index] == '{':
                depth += 1
            elif src[index] == '}':
                depth -= 1
                if depth == 0:
                    break
            index += 1
        if READ_CALL.search(src[brace:index]):
            helpers.add((name, params))
    return helpers


def reads_production_source(src):
    """类里是否存在「读生产源码」的调用链，返回 (偏移, 命中路径) 或 None。

    覆盖 ①：helper 读文件，但生产路径字面量出现在**调用方实参**上。
    覆盖 ②：路径字面量直接写在 readString 实参里（内联或常量/字段）。
    """
    helpers = read_helpers(src)
    helper_names = {name for name, _ in helpers}
    # ① readString 的实参里就有生产路径。
    for match in READ_CALL.finditer(src):
        start = match.end() - 1
        depth, index = 0, start
        while index < len(src):
            if src[index] == '(':
                depth += 1
            elif src[index] == ')':
                depth -= 1
                if depth == 0:
                    break
            index += 1
        site = src[start + 1:index]
        found = production_paths(site)
        if found:
            return match.start(), found
    # ② helper 被调用，调用方实参里带生产路径（helper 返回 String）。实参可以是
    #    内联字面量，也可以是常量名（形态 ②：回溯常量初始化语句）。
    for name in helper_names:
        for call in re.finditer(r'\b' + re.escape(name) + r'\s*\(([^;{}]*)\)', src):
            argument = call.group(1)
            if '(' in argument and ')' not in argument:
                continue  # 跨行/嵌套参数：跳过，避免误把方法签名当调用
            found = production_paths(argument) or constant_paths(src, argument)
            if found:
                return call.start(), found
    # ③ 路径来自类的常量/字段：readString 的实参是标识符（如 LOOPBACK_PATH），
    #    此时路径字面量写在类的字段声明里（readString 所在方法体看不到）。只在
    #    「字段初始化语句」里回溯，避免把同一个类里另一处 YAML 路径误报进来。
    for match in READ_CALL.finditer(src):
        argument = src[match.end():src.find(';', match.end())]
        # readString(SOME_CONST) 形态：常量名 → 找它的字段初始化里的生产路径。
        for constant in re.findall(r'\b([A-Z][A-Z0-9_]{2,})\b', argument):
            declaration = re.search(
                r'\b' + re.escape(constant) + r'\s*=\s*("[^"]*"|Path\.of\s*\(\s*"[^"]*")',
                src)
            if declaration:
                found = production_paths(declaration.group(1))
                if found:
                    return match.start(), found
    return None


def asserts_source_string(src, read_offset):
    """断言是否**直接落在读出来的字符串**上。

    三种形态：
      · 一行式 Files.readString(...).contains(...)；
      · 读结果赋给变量 V（含 String s = source("plugins/...") 这种 helper 返回值），
        之后出现 V.contains(/.indexOf( 等 —— 这就是原残留（DungeonTimeout）的形态；
      · assertTrue/assertFalse(... a < b ...) 先后比较（③）。
    「直接落在读出来的字符串上」把「读 config.yml → 对解析后的 Map/Set 做 contains」
    的配置契约测试排除在外。
    """
    # 一行式：读取调用后紧跟字符串断言，**且该断言真的被 assert 出去**。
    #
    # ⚠️ 收紧点（W18-A1 第二轮）：`if (Files.readString(f).contains(x)) { hits.add(f); }`
    # 是**过滤谓词**（用读取内容筛出文件列表），不是断言。旧实现只看「`).contains(` 紧邻」，
    # 会把这种形态判成缺口 —— 但按本脚本注释的口径 #3，断言必须**直接落在读出来的字符串**上；
    # 对「读一遍 src/main 得到的路径列表」做 contains/anyMatch 属于对**解析后的集合**断言，
    # 不算（实测误报 rpg-items/EquipmentExitPathReachabilityTest 与
    # rpg-core/SeasonAttunementSettlementWiringTest 的同类 helper）。
    for match in READ_CALL.finditer(src):
        statement = src[match.start():match.start() + 4000].split(';')[0] + ';'
        chain = re.search(r'\)\s*\.\s*' + ASSERT_CALLS + r'\s*\(', statement)
        if not chain:
            continue
        # contains 结果必须处在 assert 调用里（assertTrue(...) / assertFalse(...)），
        # 而不是 if/while/三元/stream filter 的谓词位置。
        prefix = src[max(0, match.start() - 400):match.start()] + statement
        if re.search(r'assert(?:True|False|Equals|NotEquals)\s*\([^;{}]*$', prefix):
            return True
    # 读结果被赋给变量（readString 直接赋值或 helper 返回值赋值）后对该变量做断言。
    read_helpers_names = {name for name, _ in read_helpers(src)}
    for match in ASSIGNED_STRING.finditer(src):
        name = match.group(1)
        right = src[match.end():match.end() + 220]
        # 只认「右侧来自读取」的赋值：readString / 已知读文件的 helper。
        if not (READ_CALL.search(right) or any(
                re.match(r'\s*' + re.escape(helper) + r'\s*\(', right)
                for helper in read_helpers_names)):
            continue
        if re.search(r'\b' + re.escape(name) + r'\s*\.\s*' + ASSERT_CALLS + r'\s*\(', src):
            return True
    # 先后比较（形态 ③）：indexOf 链等。
    return bool(ORDER_COMPARISON.search(src))


hits = []
for path in sorted(glob.glob('plugins/**/src/test/**/*.java', recursive=True)
                   + glob.glob('tools/**/src/test/**/*.java', recursive=True)):
    source = open(path, encoding='utf-8').read()
    if not READ_CALL.search(source):
        continue
    reading = reads_production_source(source)
    if reading is None:
        continue
    if not asserts_source_string(source, reading[0]):
        continue
    offset, targets = reading
    hits.append({
        'file': path,
        'line': source.count('\n', 0, offset) + 1,
        'tests': test_methods(source),
        'production_paths': targets[:3],
    })

if '--json' in sys.argv:
    print(json.dumps({'hits': hits, 'count': len(hits)}, ensure_ascii=False, indent=1))

print(f'SOURCE_STRING_TESTS={len(hits)}')
for hit in hits:
    print(f"  {hit['file']}:{hit['line']} -> {hit['production_paths'][:2]} tests={hit['tests'][:2]}")
if hits:
    print('SOURCE_STRING_GATE=RED 仍有测试在「读本仓生产源码 + 源码字符串断言」')
    sys.exit(1)
print('SOURCE_STRING_GATE=GREEN 本仓生产源码已无源码字符串断言（豁免口径见脚本头部注释）')
sys.exit(0)
PY
