# W21-A1 证据 — 门禁假红（flaky）修复

## A. 复现（首选嫌疑确认：awaitQuiet() 时间启发式）

### A1. 修复前循环（无负载，30 次）
```
run 1 rc=0 ... run 30 rc=0  TOTAL secs=240 fails=0   # 顺序/轻载下不复现（说明是低概率竞态）
```

### A2. 修复前：8x CPU hog + 单类循环，抓到一次与门禁完全一致的失败
```
21:AnomalyReviewCommandTest > acknowledgeWithoutConfirmOrSignatureIsRefusedAndWritesNothing() FAILED
22:    org.opentest4j.AssertionFailedError: [] ==> expected: <true> but was: <false>
30:5 tests completed, 1 failed
32:> Task :plugins:rpg-admin:test FAILED
43:BUILD FAILED in 19s
```
失败用例与门禁 stage 日志 82 行完全一致：AnomalyReviewCommandTest > acknowledgeWithoutConfirmOrSignatureIsRefusedAndWritesNothing()
失败断言 = 该类第 3 条断言（missing 分支，源码第 140 行）：
```
assertTrue(missing.messages.stream().anyMatch(line -> line.contains("admin.anomaly-not-pending")),
        missing.messages.toString());
```
注意：实际值为 **[]**（空消息列表），证明是「异步链还没收尾就断言」，不是「行为不再播报」。

### A3. 探针：把「静默 30ms」的脆弱性变成可观测量（未改任何断言）
用每跳延迟 N ms 的 executor 包装被注入服务，逐字复制 awaitQuiet()：
```
231:    PROBE hopDelay=0ms  awaitQuiet returned after 30ms  messages=[]
232:    PROBE hopDelay=0ms  after 3s messages=[admin.anomaly-not-pending {player=ba6c98e4-a351-4c30-8681-cd3d76af1f39}]  expected(admin.anomaly-not-pending) present=true
239:    PROBE hopDelay=45ms  awaitQuiet returned after 30ms  messages=[]
240:    PROBE hopDelay=45ms  after 3s messages=[admin.anomaly-not-pending {player=8b380250-35f8-46dc-bab9-45e7b1504844}]  expected(admin.anomaly-not-pending) present=true
247:    PROBE hopDelay=200ms  awaitQuiet returned after 30ms  messages=[]
248:    PROBE hopDelay=200ms  after 3s messages=[admin.anomaly-not-pending {player=62045176-40e0-4aa0-be01-e3b3977881d3}]  expected(admin.anomaly-not-pending) present=true
```
结论：即使 hopDelay=0ms，awaitQuiet() 也在 30ms 后带着 messages=[] 返回；期望消息随后才到达。
⇒ 30ms 静默窗口与「两跳异步链已收尾」之间没有因果关系。首选嫌疑成立。

## B. 修复
只改 plugins/rpg-admin/src/test/java/cn/originalrpg/admin/AnomalyReviewCommandTest.java：
- 注入可跟踪 executor（inFlight 计数，**提交线程**递增 / 任务体含 whenComplete 回调之后递减）
- awaitQuiet() 改为「等在飞任务归零（连续 3 次确认）」，硬超时 10s，超时抛 AssertionError 报「异步链未收尾」
- 未改任何断言期望值；异步路径仍是真实虚拟线程 + 真实 JDBC 事务（未改成同步）

### B1. 关键迭代：第一版修复仍复现（在飞计数错放在任务体内）
```
run 13 rc=1
21:AnomalyReviewCommandTest > acknowledgeWithoutConfirmOrSignatureIsRefusedAndWritesNothing() FAILED
22:    org.opentest4j.AssertionFailedError: [] ==> expected: <true> but was: <false>
28:        at app//cn.originalrpg.admin.AnomalyReviewCommandTest.acknowledgeWithoutConfirmOrSignatureIsRefusedAndWritesNothing(AnomalyReviewCommandTest.java:161)
```
根因：计数在任务体内递增 ⇒ 主线程提交后立刻看到 0，等于把竞态换了个位置。
改为提交线程递增后，同一 13 号位置不再复现。

## C. 验证
### C1. 修复后 30/30 全绿（在 6 个 CPU hog 负载下跑，比门禁更严苛）
```
run 1 rc=0
run 2 rc=0
run 3 rc=0
run 4 rc=0
run 5 rc=0
run 6 rc=0
run 7 rc=0
run 8 rc=0
run 9 rc=0
run 10 rc=0
run 11 rc=0
run 12 rc=0
run 13 rc=0
run 14 rc=0
run 15 rc=0
run 16 rc=0
run 17 rc=0
run 18 rc=0
run 19 rc=0
run 20 rc=0
run 21 rc=0
run 22 rc=0
run 23 rc=0
run 24 rc=0
run 25 rc=0
run 26 rc=0
run 27 rc=0
run 28 rc=0
run 29 rc=0
run 30 rc=0
FAILS=0 REDS= TOTAL=30 LOAD=6hogs
```

### C2. 负向对照（破坏行为 → 必红 → 撤销 → 恢复绿）

C2-1 破坏：把 missing 分支期望的消息键改成不存在的键（admin.anomaly-DOES-NOT-EXIST）
```
NEG_RED rc=1
22:    org.opentest4j.AssertionFailedError: [admin.anomaly-not-pending {player=b91885c3-5c52-4a8a-aa03-9e5fac39517e}] ==> expected: <true> but was: <false>
30:34 tests completed, 1 failed
41:BUILD FAILED in 8s
```
注意实际值 = [admin.anomaly-not-pending {...}] —— **非空**，断言因行为不符而红（不是等得不够久）。

C2-2 撤销破坏（git 恢复为修复版）
```
RESTORE rc=0
21:BUILD SUCCESSFUL in 8s
```

### C3. 完整模块测试（CI 同款调用）
```
MODULE rc=0
21:BUILD SUCCESSFUL in 8s
plugins/rpg-admin            tests=  34 failed=0 errors=0 skipped=0 ok
```

### C4. 全量测试（门禁 test stage 同款：全 26 模块，parallel=true, workers=4）
```
FULL_TEST rc=0
  SUM                          tests=1101 failed=0 errors=0 skipped=2 modules=26
  NO_XML                       modules=0  期望模块数=26
  SUMMARY_GATE=GREEN
excluding tests with tag: env
```
与门禁第 2 次（sn=cnb-h3n-1k414popl）的绿完全一致：tests=1101 failed=0。
