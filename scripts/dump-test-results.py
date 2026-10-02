#!/usr/bin/env python3
"""按模块汇总 Gradle JUnit XML 结果；无 XML 的模块也显式列出。"""
import glob, os, re, sys
import xml.etree.ElementTree as ET

modules = sorted(
    p[:-len('/build/test-results/test')]
    for p in glob.glob('plugins/*/build/test-results/test') + glob.glob('tools/*/build/test-results/test')
)
grand = [0, 0, 0, 0]
listed = set()
for module in modules:
    d = os.path.join(module, 'build/test-results/test')
    files = glob.glob(os.path.join(d, 'TEST-*.xml'))
    if not files:
        print(f'  {module:28s} NO_XML')
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
sys.exit(1 if (grand[1] or grand[2]) else 0)
