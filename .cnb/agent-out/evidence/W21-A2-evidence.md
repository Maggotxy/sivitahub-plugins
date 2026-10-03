# W21-A2-ledger-and-guards 证据（本轮复跑，基线 = 当前 main）

本轮返工起点：上一轮实现已被 PR #70 合入 `main`（merge commit `334fb54`），
但工作分支停在合并前的 `8fde71a`，且上一轮结果 JSON 把 PR #70 的 `state` 写成
`open`（实际已是 `merged`）。本轮把分支快进到 `main` 顶端、复跑全部门禁与夹具、
并把「PR 必须是打开的、标题以 `[agent/W21-A2-ledger-and-guards]` 开头」这条补齐。

## 0. 基线

```
$ git rev-parse HEAD origin/main
334fb54b6f83c41df5c56b03f325a97c9a97cb17
334fb54b6f83c41df5c56b03f325a97c9a97cb17
$ git diff --name-status origin/main...HEAD    # 空 ⇒ 内容已全在 main
```

## 1. 单一真相源（交付物状态）

```
$ test ! -e plan/world-state.json && echo OK
OK
$ ls state/world-state.json
state/world-state.json
```

## 2. 接线证明（.cnb.yml 原文行号，未改 .cnb.yml）

```
$ grep -n "selftest-gates.sh\|门禁自测" .cnb.yml
62:      - name: 门禁自测（负向对照）
71:          sh scripts/selftest-gates.sh
$ git status --porcelain    # 未列出 .cnb.yml
```

## 3. 判据 1 正负夹具（§11 原文）

```
$ sh scripts/selftest-gates.sh 2>&1 | awk '/^== 11\./,0'
== 11. W21-A2 交付物级门禁：账本唯一真相源 + verdict↔矩阵一致 ==
  [OK  ] 真实仓库：账本唯一真相源（state 存在 / plan 不存在）→ 0 (exit=0)
    SINGLE_SOURCE_OF_TRUTH_GATE=GREEN
    WORLD_STATE_FILES=1
  [OK  ] 夹具（负向）：plan/world-state.json 存在且与 state 不一致 → 1 (exit=1)
      两份账本内容不一致：state=48679B plan=45B
    SINGLE_SOURCE_OF_TRUTH_GATE=RED
    WORLD_STATE_FILES=2
  [OK  ] 负向夹具打出了 SINGLE_SOURCE_OF_TRUTH_GATE=RED
  [OK  ] 夹具（还原）：删除假 plan/world-state.json → 0（判据不是恒红） (exit=0)
  [OK  ] 还原后 WORLD_STATE_FILES=1
  [OK  ] 夹具（负向2）：state/world-state.json 缺失 → 1 (exit=1)
```

## 4. 判据 2 三态夹具（§11 原文）

```
  [OK  ] 真实仓库：verdict↔矩阵一致（最高版 v8）→ 0 (exit=0)
    VERDICT_MATRIX_CONSISTENCY_GATE=GREEN
    N=8 done=false remaining=1 basis=77112113
  [OK  ] 夹具2(i)：矩阵缺结论行 → 1 (exit=1)
      矩阵缺少机器可读结论行（前 20 行内应有 '> verdict: done=<bool> remaining=<int>'）
    VERDICT_MATRIX_CONSISTENCY_GATE=RED
  [OK  ] 报了「矩阵缺少机器可读结论行」
  [OK  ] 夹具2(ii)：remaining 与 JSON 不一致（矩阵 0 / JSON 1）→ 1 (exit=1)
      remaining 不一致：矩阵=0 JSON=1（/tmp/tmp.44c7EuNzzM/vmc-fixture/plan/verdict-v99.json）
    VERDICT_MATRIX_CONSISTENCY_GATE=RED
  [OK  ] 打出了两边取值
  [OK  ] 夹具2(ii-b)：basis 未出现在矩阵前 20 行 → 1 (exit=1)
  [OK  ] 夹具2(iii)：结论行与 JSON 一致 + basis 在头部 → 0（判据不是恒红） (exit=0)
    VERDICT_MATRIX_CONSISTENCY_GATE=GREEN
    N=99 done=false remaining=1 basis=deadbeef
```

夹具纪律：脚本顶部 `TMP=$(mktemp -d)` + `trap 'rm -rf $TMP' EXIT`，全程只动 `$TMP`。
跑完显式删除 + 自证「夹具路径在真实仓库不存在」。

## 5. 汇总（PASS=n FAIL=0 原文）

```
$ sh scripts/selftest-gates.sh; echo SELFTEST_RC=$?
===== 门禁自测汇总：PASS=132 FAIL=0 =====
GATE_SELFTEST=GREEN
SELFTEST_RC=0
```

## 6. 工作区干净（硬件要求）

```
$ git status --porcelain
(空)
$ ls plan/world-state.json plan/verdict-v99.json plan/gap-matrix-v99.md
ls: cannot access 'plan/world-state.json': No such file or directory
ls: cannot access 'plan/verdict-v99.json': No such file or directory
ls: cannot access 'plan/gap-matrix-v99.md': No such file or directory
```
