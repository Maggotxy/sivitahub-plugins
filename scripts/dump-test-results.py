#!/usr/bin/env python3
"""按模块汇总 Gradle JUnit XML 结果；无 XML 的模块也显式列出。

判据（退出码）：
  0 = 每个被断言「确实跑过」的模块都有 XML，且失败数为 0
  1 = 有失败/错误，或有模块没有产出 XML（模块漏跑不能被当成通过）
  2 = 一个 XML 都没找到（多半是 gradlew 压根没跑起来，或 test 阶段被绕过）

用法：
  python3 scripts/dump-test-results.py                 # 只要求「跑过的模块全绿」
  python3 scripts/dump-test-results.py --min-modules 26 # 还要求至少 26 个模块真的跑过
"""
import glob, os, sys
import xml.etree.ElementTree as ET

# 本仓 26 个参与编译的子工程（settings.gradle.kts 的真相源由 Gradle 自己保证，
# 这里只做「一个都不能少」的机械断言；新增模块要同步这个数字，否则门禁会红着提醒你）。
EXPECTED_MODULES = 26

args = sys.argv[1:]
min_modules = EXPECTED_MODULES
if args and args[0] == '--min-modules':
    min_modules = int(args[1])

modules = sorted(
    p[:-len('/build/test-results/test')]
    for p in glob.glob('plugins/*/build/test-results/test') + glob.glob('tools/*/build/test-results/test')
)
grand = [0, 0, 0, 0]
listed = set()
no_xml = []
for module in modules:
    d = os.path.join(module, 'build/test-results/test')
    files = glob.glob(os.path.join(d, 'TEST-*.xml'))
    if not files:
        # NO_XML 与「0 个测试」是两回事：前者是模块根本没跑（例如 compileTestJava 挂了
        # 导致 test 任务从未执行），必须显式计入失败，不能因为「没有失败用例」而放过。
        print(f'  {module:28s} NO_XML   <-- 模块没跑，判为失败')
        no_xml.append(module)
        continue
    tests = failures = errors = skipped = 0
    for f in files:
        root = ET.parse(f).getroot()
        tests += int(root.get('tests', 0))
        failures += int(root.get('failures', 0))
        errors += int(root.get('errors', 0))
        skipped += int(root.get('skipped', 0))
    listed.add(module)
    grand[0] += tests; grand[1] += failures; grand[2] += errors; grand[3] += skipped
    flag = 'FAIL' if (failures or errors) else 'ok'
    print(f'  {module:28s} tests={tests:4d} failed={failures} errors={errors} skipped={skipped} {flag}')
print(f'  {"SUM":28s} tests={grand[0]:4d} failed={grand[1]} errors={grand[2]} skipped={grand[3]} modules={len(listed)}')
print(f'  {"NO_XML":28s} modules={len(no_xml)}  期望模块数={min_modules}')

if grand[0] == 0:
    print('SUMMARY_GATE=RED 一个测试都没跑（gradlew 没跑起来？）')
    sys.exit(2)
if grand[1] or grand[2]:
    print(f'SUMMARY_GATE=RED 失败={grand[1]} 错误={grand[2]}')
    sys.exit(1)
if no_xml:
    print('SUMMARY_GATE=RED 以下模块没有产出测试结果：' + ' '.join(no_xml))
    sys.exit(1)
if len(listed) < min_modules:
    print(f'SUMMARY_GATE=RED 只有 {len(listed)} 个模块跑过测试，少于期望的 {min_modules}')
    sys.exit(1)
print('SUMMARY_GATE=GREEN')
sys.exit(0)
