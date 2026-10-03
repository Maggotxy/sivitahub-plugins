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
#   ── 形态 ④：遍历式源码扫描（W19-A1 补）─────────────────────────────────────────
#   还有一类间接形态过去被当成「对解析后的集合断言」而豁免，实际是同一性质：
#     `Files.walk(<plugins/*&#47;src/main>) / Files.list(...)` 逐个 readString 出生产源码正文，
#     用 `.contains(identifier)` **筛出「哪些文件提到了某标识符」**，再对筛出来的文件列表
#     做 anyMatch / assertEquals 断言。
#   它的断言对象看着是「文件列表」，但列表的**每一项都来自「这段源码文本含不含 identifier」**
#   —— 生产代码里删掉这个词（哪怕只改注释）就会变红，因此它就是「读生产源码 + 源码字符串
#   断言」，只是把 contains 的结果聚合了一层。
#   ⚠️ W18-A1 第二轮以「解析后的集合」为由把这种形态整体豁免（注释里点名了两个类），
#   使**原形态的源码扫描 helper 在真实仓库里判 GREEN** —— 判据的负向对照（把类改回原形态
#   必须变红）因此失效。W19-A1 撤掉该豁免：只要遍历根指向 plugins|tools 下的 src/main
#   且 readString 的正文被 `.contains(` 判断，就计缺口（例：rpg-items&#47;EquipmentExitPathReachabilityTest
#   与 rpg-core&#47;SeasonAttunementSettlementWiringTest 的 productionReferences(identifier)）。
#   反例（仍不算，避免误报）：LanguageBundleContractTest 遍历 plugins/ 后用
#   Files.newInputStream + SnakeYAML 真解析（不是 readString、不对正文做 contains）⇒ 不算。
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
    # 实参既可能是裸常量名（`read(PRODUCTION)`），也可能是 `Path.of(PRODUCTION)`
    # 这种包了一层的写法（`Files.readString(java.nio.file.Path.of(P))`）。
    # 只认「实参的**读取目标表达式**就是该常量」的形态：`<常量>` 或 `Path.of(<常量>)`
    # （允许 `PRODUCTION + "/x"` 这类以常量名开头的简单拼接）。
    # lambda / 比较里**提及**的常量不算，否则会误报「对解析后的列表做断言」的测试。
    target = argument.strip()
    inner = re.fullmatch(r'(?:[\w.]+\.)?Path\.of\s*\(\s*(.*?)\s*\)', target, re.DOTALL)
    if inner:
        target = inner.group(1).strip()
    for constant in set(re.findall(r'\b([A-Z][A-Z0-9_]{2,})\b', target)):
        if not re.match(r'\s*' + re.escape(constant) + r'\b', target):
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


def reads_production_source_via_traversal(src):
    """形态 ④：经 Files.walk/Files.list 遍历 plugins|tools 下 src/main，再 readString 正文。

    返回 (偏移, 命中路径片段) 或 None。命中条件（三条同时满足）：
      1) 遍历调用的根表达式指向生产源码树：根文本里带 `plugins`/`tools` 且带 `src/main`
         （既覆盖 `Files.walk(root.resolve("plugins/rpg-core/src/main"))` 这种内联写法，
          也覆盖 `Files.list(ROOT.resolve("plugins"))` + `module.resolve("src/main")`
          这种拼接写法 —— 后者在类里同时出现 `"plugins"` 与 `"src/main"` 字面量）；
      2) 类里对该遍历出来的文件调用了 readString/readAllBytes（读的是正文）；
      3) 读过正文后对它做 `.contains(` 判断（无论判定结果是被当场 assert，还是
         聚合成「哪些文件提到了某标识符」的列表后再 assert）。

    这一条把「遍历 + 正文 contains」还原成它本来的性质：断言依赖「生产源码文本含不含
    某标识符」，不是运行期行为。反例（不算）见脚本头部注释的 LanguageBundleContractTest。
    """
    walk = re.search(r'Files\s*\.\s*(?:walk|list)\s*\(', src)
    if walk is None or not READ_CALL.search(src):
        return None
    inline = re.compile(r'"[^"]*(?:plugins|tools)[^"]*src/main')
    root_start = walk.start()
    # 1a) 遍历根是内联字面量（walk 调用括号内的实参里直接带 plugins|tools .../src/main）。
    if inline.search(_balanced_arguments(src, walk.end() - 1)):
        return root_start, sorted(set(re.findall(r'"([^"]*src/main[^"]*)"', src)))[:3]
    # 1b) 拼接写法：类里同时出现 `"plugins"|"tools"` 与 `"src/main"`（根分两段 resolve）。
    if (re.search(r'"(?:plugins|tools)"', src)
            and re.search(r'"(?:src/main)"', src)):
        return root_start, ['(traversal root: plugins|tools -> src/main)']
    return None


def _balanced_arguments(text, open_paren_index):
    """取出从 `(` 开始到配对 `)` 的实参文本（用于判断遍历根是不是内联生产路径）。"""
    depth = 0
    for index in range(open_paren_index, len(text)):
        char = text[index]
        if char == '(':
            depth += 1
        elif char == ')':
            depth -= 1
            if depth == 0:
                return text[open_paren_index + 1:index]
    return text[open_paren_index + 1:]


def inside_assert_arguments(src, offset):
    """offset 处的表达式是否落在某次 assert 调用的实参里。

    向前匹配括号：遇到 `assertXxx(` 且该括号**尚未闭合**，说明读取调用就是这次断言的
    实参（或实参的一部分）。这样 `assertTrue(Files.readString(...).contains(x))`、
    `assertEquals(List.of(LIFECYCLE_ONLY), source("..."))`、
    `assertEquals(0, source("...").indexOf(a) - source("...").indexOf(b))` 都能命中，
    而 `if (Files.readString(f).contains(x)) { ... }` 这种**过滤谓词**（前面的开括号
    是 if，不是 assert）不会命中。

    只向前看同一个语句块（以 `;` `{` `}` 为界），避免把上一个语句的 assert 借给
    当前读取。
    """
    depth = 0
    index = offset - 1
    while index >= 0:
        char = src[index]
        if char == ')':
            depth += 1
        elif char == '(':
            if depth == 0:
                # 找到包住读取调用的那个开括号，看它前面是不是 assertXxx。
                head = src[max(0, index - 64):index]
                if re.search(r'(?:assert[A-Za-z]*|fail)\s*$', head):
                    return True
                # 继续向前：读取调用可能是某个实参的一部分（如 List.of(...)）。
                # 但一旦跨过语句边界就停，避免误借。
                if re.search(r'[;{}]', head):
                    return False
            else:
                depth -= 1
        elif char in ';{}' and depth == 0:
            return False
        index -= 1
    return False


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
    #
    # ⚠️ W19-A1 修正 W18 收紧时引入的**漏判**：W18 用「assert 文本紧邻读取调用」来判定，
    # 但断言块常常是 `assertEquals(0, source("...").indexOf(a) - source("...").indexOf(b))`
    # 或 `assertTrue(Files.readString(...)\n    .contains(x))` —— assert 与读取之间隔着
    # 实参、换行、甚至另一次读取调用。旧实现要求 assert( 与读取之间没有 `;{}`，于是
    # 上面两种**真实断言**全部漏判（实测：把 `assertTrue(Files.readString(...).contains(x))`
    # 的夹具喂进去是 GREEN）。现在改为「读取调用是否落在某次 assert 调用的实参里」：
    # 从读取位置向前扫，找到最近的未闭合 assertXxx(... 开括号即算。
    for match in READ_CALL.finditer(src):
        statement = src[match.start():match.start() + 4000].split(';')[0] + ';'
        chain = re.search(r'\)\s*\.\s*' + ASSERT_CALLS + r'\s*\(', statement)
        if not chain:
            continue
        if not inside_assert_arguments(src, match.start()):
            continue
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


def traversal_reads_assert_source(src):
    """形态 ④ 的断言侧：readString 读到的正文被 `.contains(` 判断（就地或聚合成列表）。

    与 asserts_source_string 的区别：这里**允许** contains 的结果先被收集成「哪些文件提到了
    某标识符」的列表、再对列表断言 —— 列表每一项的真伪仍取决于源码正文含不含 identifier。
    """
    for match in READ_CALL.finditer(src):
        tail = src[match.end():match.end() + 800]
        if re.search(r'\bcontains\s*\(', tail):
            return True
    return False


hits = []
for path in sorted(glob.glob('plugins/**/src/test/**/*.java', recursive=True)
                   + glob.glob('tools/**/src/test/**/*.java', recursive=True)):
    source = open(path, encoding='utf-8').read()
    if not READ_CALL.search(source):
        continue
    reading = reads_production_source(source)
    if reading is not None and asserts_source_string(source, reading[0]):
        offset, targets = reading
    else:
        # 形态 ④：遍历 plugins|tools/src/main + readString + contains（聚合成文件列表也算）。
        traversal = reads_production_source_via_traversal(source)
        if traversal is None or not traversal_reads_assert_source(source):
            continue
        offset, targets = traversal
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
