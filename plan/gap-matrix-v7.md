# 全量机制矩阵 v7（gap-matrix-v7）

> basis: 3947000
> 生成者：CNB CodeBuddy NPC，任务 `W15-A1-verify-on-current-main`（**当前 main 最终复验轮**）。
> 本轮在**自己分支出来的那个 commit** 上重跑全量差集矩阵，并重点复查 v6 未覆盖的四类
> （素材层新引入物零消费者 / manifest↔磁盘 / provenance↔manifest / config.yml 与 items 引用方）。
> 未改 `plugins/**/src/main` 任何源码，未改任何 PNG 像素。
> 状态取值：`已接线` / `声明未接线` / `存疑` / `已废弃` / `真实缺口`。

## 0. 本轮结论（先给判定，再给证据）

| 项 | v6 判定 | v7 判定 | 依据 |
|---|---|---|---|
| 素材层 6 个新脚本有无消费者（(a)） | 未覆盖 | **全部已接线（6/6）** | §1：6 个各有真实调用点（`.cnb.yml` stage 或 `selftest-gates.sh` 负向对照），逐条给命令原文 |
| `assets-manifest.json` 252 条 ↔ 磁盘（(b)） | 未覆盖 | **成立** | §2：`ASSET_BASELINE_GATE=GREEN`，`UNREGISTERED=0 MISSING=0 MODIFIED=0`；manifest 条数 252 == 磁盘 PNG 数 252 |
| `asset-provenance.json` 252 条 ↔ manifest 252 条（(c)） | 未覆盖 | **成立** | §3：同集合（归一 `assets/` 前缀后）、`$needsHumanDecision=[]`、六类 `$counts` 合计 252 且与逐条重算一致 |
| items(81) ↔ runtime-item-models(81) ↔ 插件 `item_model` 三边闭合（(d)） | 未覆盖 | **成立** | §4：A==B 完全相同；插件侧 12 个字面量键全部 ⊆ manifest（0 条未声明） |
| 24 个 `config.yml` 顶层键读取方（(d)） | 未覆盖 | **成立** | §5：143 个顶层键，4 个在本插件 `src/main` 无字面量，逐条复核后**全部有真实读方** |
| **G-302 源码字符串伪测试残留** | 未覆盖（v6 只提「最后 2 个类已接线」） | **真实缺口（15 个类）** | §6：`plan/source-string-tests-remaining.json` 的 `_remaining_after_batch2` 登记 15 个类，实测其中 **10 个仍含纯源码字符串断言**；W5-A2 的 `unproven` 自认「未处理」 |
| 其余机制（v6 的 39 条矩阵） | 已接线 | **已接线（复跑一致）** | §7：关键调用点逐条复跑，全部仍有真实消费者 |
| 十门在当前 main | 八门 @ 6d12e03 | **十门 @ 2184f15（十门形态，success）** | §8：sn=`cnb-og3-1k401sol0`，13 stage 全 success，10 条判据行齐全 |

⇒ 差集矩阵「声明未接线」= 0 条；「可派工的实现缺口」= **1 类（G-302 残留 15 个类）**。
⇒ `plan/verdict-v7.json`：`done=false`、`remaining=1`。

## 1. (a) 素材层 6 个新引入物 —— 逐个查「谁调用它」

口径：**「脚本存在」不等于「脚本被调用」**。每条给出调用点原文；查不到调用点即判「声明未接线」。

### A-1 `resource-pack/tools/generate-provenance.py` 的 `--write-doc` 路径 —— 已接线

`--write-doc` 分支本体（`resource-pack/tools/generate-provenance.py:648-655`）：

```python
parser.add_argument("--write-doc", action="store_true",
                    help="渲染并覆盖 resource-pack/ASSET-PROVENANCE.md（生成物）")
...
if args.write_doc:
    (RP / "ASSET-PROVENANCE.md").write_text(render_doc(), encoding="utf-8")
```

调用点（全部证据）：

```
$ grep -rn "generate-provenance" . | grep -v '^\./\.git/' | grep -E 'README|ASSET-PROVENANCE.md|tasks-w1[345]'
resource-pack/README.md:34:python3 resource-pack/tools/generate-provenance.py --write --write-doc
resource-pack/README.md:196:python3 resource-pack/tools/generate-provenance.py --write --write-doc
resource-pack/ASSET-PROVENANCE.md:12:> `python3 resource-pack/tools/generate-provenance.py --write --write-doc`。
resource-pack/ASSET-PROVENANCE.md:234:- 重新生成：`python3 resource-pack/tools/generate-provenance.py --write --write-doc`
resource-pack/tools/generate-provenance.py:633: "> 本节由 `python3 resource-pack/tools/generate-provenance.py --write-doc` 渲染，**不要手改**："
doc/cnb/tasks-w13.json / doc/cnb/tasks-w14.json：作为任务验收步骤
.cnb/agent-out/W14-A1-merge-two-branches.json:113  {\"cmd\": \"python3 resource-pack/tools/generate-provenance.py --write --write-doc\"}
```

判定：**已接线**。它是 `asset-provenance.json` 与 `ASSET-PROVENANCE.md` 两个**入库生成物的唯一生成路径**，
W14-A1 实测跑过（产物已随 PR#61 入库）。它本身**不进门禁**——进门禁的是它的产物（§2/§3 的两道门禁直接对账产物）。
如实说明：**没有 CI stage 调用 `--write-doc`**（它是人工「重新生成」路径，不是判据），所以它的「消费者」是
文档 + 生成物 + 可评审 diff 流程，不是自动门禁。

### A-2 `scripts/check-asset-reproducible.sh` —— 已接线（双调用点）

```
$ grep -rn "check-asset-reproducible.sh" --include='.cnb.yml' --include='*.sh' . | grep -v '^\./\.git/'
./scripts/selftest-gates.sh:923:    sh scripts/check-asset-reproducible.sh > "$1" 2>&1)
./scripts/selftest-gates.sh:1013:  (cd "$ROOT" && ASSET_REPRO_JDK="$repro_jdk" sh scripts/check-asset-reproducible.sh \
./scripts/selftest-gates.sh:1023:if [ -f "$ROOT/.cnb.yml" ] && grep -qF 'sh scripts/check-asset-reproducible.sh' "$.cnb.yml"; then
./scripts/selftest-gates.sh:1024:  echo "  [OK  ] .cnb.yml 全量质量门执行 check-asset-reproducible.sh"
./.cnb.yml:171:          sh scripts/check-asset-reproducible.sh
```

1. **CI 主路径**：`.cnb.yml:171`（stage「素材可复现性门禁（W13-A1）」）。
2. **负向对照**：`selftest-gates.sh:923` / `:1013`（§10）。

判定：**已接线**。

### A-3 `scripts/check-asset-reproducible.py` —— 已接线

```
$ grep -rn "check-asset-reproducible.py" --include='*.sh' --include='*.py' --include='.cnb.yml' .
./scripts/check-asset-reproducible.sh:128:exec python3 scripts/check-asset-reproducible.py --root "$ROOT" --generated "$GEN"
```

判定：**已接线**（由 A-2 的 `.sh` 在 `:128` 用 `exec` 调用；A-2 已被 `.cnb.yml` 调用 ⇒ 传递接线）。

### A-4 `resource-pack/asset-reproducible.json` —— 已接线

```
$ grep -rn "asset-reproducible.json" . | grep -v '^\./\.git/'
./scripts/check-asset-reproducible.py:...（--coverage 读该文件；见下）
./.cnb.yml:153:          # scripts/check-asset-reproducible.py 与 resource-pack/asset-reproducible.json）。
./resource-pack/README.md:136: ... 口径见下一节
```

具体读点（`scripts/check-asset-reproducible.py`）：

```
$ grep -n 'asset-reproducible' scripts/check-asset-reproducible.py
34:SPEC_REL = os.path.join("resource-pack", "asset-reproducible.json")
```

判定：**已接线**（被 A-3 读取，A-3 被 A-2 调用，A-2 被 `.cnb.yml` 调用）。实测 CI 判据行为
`ASSET_REPRO: 比对 70 张（4 个生成器）`（§8）—— 即该文件真的被解析并驱动比对。

### A-5 `scripts/asset_fingerprint.py` —— 已接线（3 个 import + 2 个 selftest 复制点）

```
$ grep -rn "asset_fingerprint" --include='*.py' --include='*.sh' . | grep -v '^\./\.git/'
./scripts/check-asset-baseline.py:33:from asset_fingerprint import fingerprint_png  # noqa: E402
./scripts/gen-asset-manifest.py:25:from asset_fingerprint import fingerprint_png  # noqa: E402
./scripts/check-asset-reproducible.py:32:from asset_fingerprint import fingerprint_png  # noqa: E402
./scripts/selftest-gates.sh:736:cp "$ROOT/scripts/check-asset-baseline.py" "$ROOT/scripts/asset_fingerprint.py" \
./scripts/selftest-gates.sh:898:  "$ROOT/scripts/asset_fingerprint.py" "$REPRO_FIXTURE/scripts/"
```

判定：**已接线**。3 个门禁/生成器都 import 它的 `fingerprint_png`；`selftest-gates.sh` 又把它复制进
两套 fixture（§9、§10）证明负向对照能真的变红。

### A-6 `scripts/gen-asset-manifest.py` —— 已接线

```
$ grep -rn "gen-asset-manifest.py" . | grep -v '^\./\.git/'
./scripts/selftest-gates.sh:856:(cd "$ROOT" && python3 scripts/gen-asset-manifest.py --check >/dev/null 2>&1)
./scripts/selftest-gates.sh:859:check "重跑 gen-asset-manifest.py 后清单字节一致（稳定输出）" 0 "$gen_rc"
./scripts/check-asset-baseline.py:63:        print("  修复：python3 scripts/gen-asset-manifest.py 并提交清单", file=sys.stderr)
./resource-pack/README.md:97:python3 scripts/gen-asset-manifest.py
```

判定：**已接线**（`selftest-gates.sh:856` 用 `--check` 真实执行并断言稳定输出；`:859` 断言 exit=0）。
如实说明：与 A-1 同理，**没有 CI stage 直接跑它来「生成」**——它是「有意更新基线的唯一路径」，
由 `selftest-gates.sh` 以 `--check` 形态纳管（防止清单与磁盘漂移）。

### A-7 `scripts/check-asset-baseline.py` —— 已接线（双调用点）

```
$ grep -rn "check-asset-baseline.py" . | grep -v '^\./\.git/'
./.cnb.yml:143:          python3 scripts/check-asset-baseline.py
./scripts/selftest-gates.sh:742:  (cd "$BASE_FIXTURE" && python3 scripts/check-asset-baseline.py >/dev/null 2>&1)
./scripts/selftest-gates.sh:784/806/826：三组负向对照（改 1 像素 / 删图 / 加未登记图）
./scripts/selftest-gates.sh:849:(cd "$ROOT" && python3 scripts/check-asset-baseline.py >/dev/null 2>&1)
./scripts/selftest-gates.sh:864:if [ -f "$ROOT/.cnb.yml" ] && grep -qF 'python3 scripts/check-asset-baseline.py' "$.cnb.yml"; then
```

判定：**已接线**。

### (a) 小结

| # | 物 | 调用点 | 判定 |
|---|---|---|---|
| A-1 | `generate-provenance.py --write-doc` | README:34/196、ASSET-PROVENANCE.md:12/234、W14-A1 实测 | 已接线 |
| A-2 | `check-asset-reproducible.sh` | `.cnb.yml:171` + `selftest-gates.sh:923/1013` | 已接线 |
| A-3 | `check-asset-reproducible.py` | `check-asset-reproducible.sh:128`（`exec`） | 已接线 |
| A-4 | `asset-reproducible.json` | 被 A-3 读取（`ASSET_REPRO: 比对 70 张`） | 已接线 |
| A-5 | `asset_fingerprint.py` | 3 处 `from asset_fingerprint import` + `selftest-gates.sh:736/898` | 已接线 |
| A-6 | `gen-asset-manifest.py` | `selftest-gates.sh:856/859`（`--check`） | 已接线 |
| A-7 | `check-asset-baseline.py` | `.cnb.yml:143` + `selftest-gates.sh:742/784/806/826/849` | 已接线 |

⇒ **6 个脚本 + 1 个数据文件，零消费者 = 0。**

## 2. (b) `assets-manifest.json` 252 条 ↔ 磁盘

```
$ python3 scripts/check-asset-baseline.py; echo "EXIT=$?"
ASSET_BASELINE: 校验 252 张 PNG（清单 252 条）
  UNREGISTERED=0 MISSING=0 MODIFIED=0
ASSET_BASELINE_GATE=GREEN
EXIT=0

$ find resource-pack/assets -name '*.png' | wc -l
252
$ python3 -c "import json;print(len(json.load(open('resource-pack/assets-manifest.json'))))"
252
```

集合比对（键基 = 相对 `resource-pack/assets/`）：

```
$ python3 -c "
import json,os
m=set(json.load(open('resource-pack/assets-manifest.json')))
disk=set()
base='resource-pack/assets'
for r,d,fs in os.walk(base):
    for f in fs:
        if f.endswith('.png'): disk.add(os.path.relpath(os.path.join(r,f),base))
print('manifest',len(m),'disk',len(disk),'equal',m==disk)
print('disk-only',sorted(disk-m)[:3],'manifest-only',sorted(m-disk)[:3])"
manifest 252 disk 252 equal True
disk-only [] manifest-only []
```

判定：**成立**。252 条与磁盘 252 张一一对应，零未登记 / 零丢失 / 零被改动。

## 3. (c) `asset-provenance.json` 252 条 ↔ manifest 252 条

```
$ python3 -c "
import json
m=set(json.load(open('resource-pack/assets-manifest.json')))
p=json.load(open('resource-pack/asset-provenance.json'))
ps=set(k[len('assets/'):] if k.startswith('assets/') else k for k in p['assets'])
print('manifest',len(m),'prov',len(ps),'same set after normalizing assets/ prefix:',m==ps)
print('manifest-only',sorted(m-ps)[:3],'prov-only',sorted(ps-m)[:3])
print('needsHumanDecision:',repr(p['\$needsHumanDecision']))
print('counts:',p['\$counts'],'sum:',sum(p['\$counts'].values()))
from collections import Counter
c=Counter(v['category'] for v in p['assets'].values())
print('recomputed:',dict(c),'match',dict(c)==p['\$counts'])"
manifest 252 prov 252 same set after normalizing assets/ prefix: True
manifest-only [] prov-only []
needsHumanDecision: []
counts: {'ai-prompt': 10, 'derived-from': 69, 'generator': 129, 'human-override': 1, 'upstream-betterhud': 38, 'upstream-vanilla': 5} sum: 252
recomputed: {'generator': 129, 'upstream-vanilla': 5, 'upstream-betterhud': 38, 'human-override': 1, 'ai-prompt': 10, 'derived-from': 69} match True
```

判定：**成立**。同一集合（provenance 键带 `assets/` 前缀，归一后完全相同）；`$needsHumanDecision` 为空数组；
六类合计 = 252；`$counts` 与逐条重算一致（不是写死的数）。

## 4. (d) items(81) ↔ runtime-item-models(81) ↔ 插件 `item_model` 三边闭合

### A 边：items 定义
```
$ ls resource-pack/assets/originalrpg/items/*.json | sed 's#.*/##; s#\.json$##' | sort | wc -l
81
```
### B 边：`runtime-item-models.txt`
```
$ awk 'NF && $0 !~ /^#/' resource-pack/runtime-item-models.txt | sort | wc -l
81
```
### A == B
```
$ diff /tmp/defs.txt /tmp/manifest.txt && echo IDENTICAL
IDENTICAL
```
### C 边：插件 `item_model` 引用（按 `resource-pack/build.sh:472-484` 的口径复跑）
```
$ grep -rn 'item_model' plugins/*/src/main --include='*.java'      # 字面量 'item_model'
（空）
$ { grep -rhoE 'originalrpg:[a-z0-9_./-]+' plugins/*/src/main --include='*.java' | sed 's/^originalrpg://'
    grep -rhoE 'NamespacedKey\("originalrpg",[[:space:]]*"[a-z0-9_./-]+"' plugins/*/src/main --include='*.java' | sed -E 's/.*"([a-z0-9_./-]+)"$/\1/'
    sed -nE 's/^[[:space:]]+model:[[:space:]]*"?([a-z0-9_]+)"?[[:space:]]*$/\1/p' plugins/rpg-economy/src/main/resources/config.yml
  } | LC_ALL=C sort -u
12 个键：common_insight_skill_book dungeon_core hunt_crystal legacy_blueprint lucky_charm
        mob_boomerang mob_corrosive_orb mob_seeker_orb mutation_shard protection_stone
        sulfur_resonance_imprint traveler_compass
$ comm -23 /tmp/direct.txt /tmp/manifest.txt
（空 = 12 个全部已声明）
```

判定：**三边闭合**。81 == 81 完全相同；插件侧 12 个字面量键全部 ⊆ manifest，**0 条未声明**。
另有 1 个**行为级**消费者：`HelpTopicTest.everyCustomHelpModelIsDeclaredByTheRuntimeResourcePack`
（`plugins/rpg-guide/src/test/java/cn/originalrpg/guide/HelpTopicTest.java:77-92`）真实读取该 manifest
并断言 help topic 引用的 model 全部已声明（该测试带 `@Tag(env)`，在测试门里被显式排除并在门里报告）。

## 5. (d) 24 个 `config.yml` 顶层键的读取方（抽查 + 全量机器复核）

```
$ find plugins -name 'config.yml' -path '*/src/main/*' | wc -l
24
$ python3 <脚本：逐个抽出顶层键，在插件自身 src/main 里找字面量>
有顶层键在插件自身 src/main 无字面量的：4
   rpg-commerce → ['store-title']
   rpg-community → ['config-version']
   rpg-essentials → ['config-version']
   rpg-market → ['config-version']
总插件数: 24 总顶层键: 143
```

抽查（用户要求「抽查即可，但要给出 grep 命令」）—— rpg-admin 的 5 个键全部有读方：

```
$ python3 -c "print([l.split(':')[0] for l in open('plugins/rpg-admin/src/main/resources/config.yml') if l.strip() and not l[0].isspace()])"
['economy-snapshot', 'chapter-snapshot', 'season-settlement', 'snapshot-io', 'test-mode']

$ grep -n 'getConfig()' plugins/rpg-admin/src/main/java/cn/originalrpg/admin/RpgAdminPlugin.java
90:  getBoolean("season-settlement.enabled", false)          ← season-settlement
110: getBoolean("economy-snapshot.enabled", true)            ← economy-snapshot
111: getLong("economy-snapshot.interval-seconds", 300L)
114: getBoolean("chapter-snapshot.enabled", true)            ← chapter-snapshot
115: getLong("chapter-snapshot.interval-seconds", 30L)
129: getLong("snapshot-io.shutdown-timeout-millis", 1_500L)   ← snapshot-io
562: getInt("test-mode.attribute-value", 64)                 ← test-mode
563: getInt("test-mode.skill-points", 10_000)
```

4 个「无字面量」键的逐条复核 —— **全部是误报，都有真实读方**：

1. `rpg-commerce: store-title` —— 是 **lang 键的镜像**，真实读方在语言服务：
   ```
   $ grep -rn 'store-title' plugins/*/src/main --include='*.java' --include='*.yml'
   plugins/rpg-commerce/src/main/java/.../RpgCommercePlugin.java:423: messages.text(player, "commerce.store-title")
   plugins/rpg-core/src/main/resources/lang/en_us.yml:219:  store-title: "Sivita Supply"
   plugins/rpg-core/src/main/resources/lang/zh_cn.yml:219:  store-title: "希维塔大陆补给所"
   ```
2. `rpg-community: config-version` —— 无 Java 读方，带同形态的还有 rpg-essentials / rpg-market（各 1 个），
   共 3 个 `config-version` 无读方，而 rpg-commerce / rpg-cosmetics / rpg-pets / rpg-story 4 个**有**读方：
   ```
   $ grep -rn 'getInt("config-version"' plugins/*/src/main --include='*.java'
   plugins/rpg-commerce/.../RpgCommercePlugin.java:73
   plugins/rpg-cosmetics/.../RpgCosmeticsPlugin.java:836
   plugins/rpg-pets/.../RpgPetsPlugin.java:157
   plugins/rpg-story/.../RpgStoryPlugin.java:46
   ```
   该键是 Paper `config.yml` 的通用迁移版本号约定（读法因插件而异）。**如实记为观察项**：
   这 3 个键当前无读方；但它们不构成「声明未接线」——它们不声明任何机制行为，且本项目
   没有任何门禁或文档声称它们被读取。属 §9 观察项，不计入 remaining。

判定：**成立**。143 个顶层键中，**没有任何一个声明机制行为却无读方**（4 个无字面量项逐条查清，
3 个是通用 `config-version` 键、1 个是 lang 键镜像）。

## 6. ★ G-302 源码字符串伪测试残留 —— 真实缺口（15 个类）

### 6.1 登记在案

`plan/source-string-tests-remaining.json` 的 `_remaining_after_batch2` 明确登记 **15 个类**：

```
$ python3 -c "import json;d=json.load(open('plan/source-string-tests-remaining.json'));print(len(d['_remaining_after_batch2']['classes']))"
15
```

该键的 `_comment` 原文：「第二批未处理的类：它们的断言混有『跨多文件作用域』『|| 备选』『构建文件』等语义，
自动迁移会失真，留待下一波逐条人工迁移到 source-contract 规格或行为化。」

W5-A2 证据文件的 `unproven` 原文（第 4 条）：
「**第二批剩余 15 个读生产源码的类未处理**（见 plan/source-string-tests-remaining.json 的
`_remaining_after_batch2`）：它们的断言含跨多文件作用域 / 「|| 备选」/ 段内 substring / 重复锚点等语义，
自动迁移会失真，需逐条人工迁移。」

### 6.2 实测：其中 10 个仍含纯源码字符串断言

口径：一个 `@Test` 方法体内同时有 `Files.readString` 读生产源码 + `assertTrue/assertFalse(x.contains(...))`
或 `x.indexOf(...)` 的比较 ⇒ 纯源码字符串断言（不是行为级测试）。

```
$ python3 <脚本：拆 @Test 方法，统计「读源码 + contains/indexOf 断言」的方法>
源码字符串断言（纯）残留类：10
  plugins/rpg-api/.../OptionalServiceLifecycleContractTest.java          纯源码断言方法数=1
  plugins/rpg-core/.../i18n/LanguageBundleContractTest.java              纯源码断言方法数=1
  plugins/rpg-dungeons/.../DungeonEntryCommitAmbiguityContractTest.java  纯源码断言方法数=3
  plugins/rpg-enhancement/.../EquipmentOperationIntegrationContractTest.java 纯源码断言方法数=2
  plugins/rpg-hub/.../WorldTravelConfigurationContractTest.java          纯源码断言方法数=1
  plugins/rpg-items/.../forge/ForgeOperationIntegrationContractTest.java 纯源码断言方法数=6
  plugins/rpg-market/.../MailboxDeliveryIntegrationContractTest.java     纯源码断言方法数=1
  plugins/rpg-skills/.../SkillAsyncLifecycleContractTest.java            纯源码断言方法数=1
  plugins/rpg-story/.../StoryJourneyOrderContractTest.java               纯源码断言方法数=1
  plugins/rpg-world/.../SurvivalPerformanceConfigurationContractTest.java 纯源码断言方法数=2
```

典型样本（**不是行为级测试**）：

```
$ sed -n '12,27p' plugins/rpg-dungeons/src/test/java/cn/originalrpg/dungeons/DungeonEntryCommitAmbiguityContractTest.java
    @Test
    void ambiguousCommitQueriesDurableStateBeforeRefundingOrConsumingTickets() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonCommand.java"),
                StandardCharsets.UTF_8);
        int commit = source.indexOf("entries.commitAll(reservations).whenComplete");
        int resolve = source.indexOf("resolveAmbiguousCommit(", commit);
        ...
        assertTrue(commit >= 0 && resolve > commit && status > resolve);
```

### 6.3 与 v6 的差：v6 只关了其中 2 个类

v4 的 V-21 收窄口径是「53 → **仅 2 个是纯源码字符串断言**」；v6 §3 只复查了 W9 新增的那 2 个类
（`MobRewardAsyncLifecycleContractTest` / `PetRevivalLifecycleContractTest`），**未回看 G-302 的 15 个残留**，
却把矩阵里的 G-302 行判成「已接线」。这是本轮相对 v6 的**实质增量**。

### 6.4 判定

**真实缺口，可派工**。判据来源是 GOAL.md §2 第 2 条「至少一条**事件级 / 行为级**测试，
不是 `Files.readString + indexOf` 的源码字符串断言」，以及 `[T]` 证据等级不能按行为测试计。

不计入本缺口的 2 项（如实说明）：
- `WorldTravelConfigurationContractTest`（rpg-hub）/ `SurvivalPerformanceConfigurationContractTest`（rpg-world）
  读的是 `server/` 部署产物，本仓不含 —— 但**它们仍在 10 条里**，因为它们断言的是本仓源码字面量
  （`run_chunky world 7 0 1808`、`getInt("survival-rules.monster-spawn-limit", 56)`）。
- `PerformanceStatusStoreTest` / `EquipmentSigningMaterialTest` 是**真实 IO / 行为测试**（W5-A2 已判），
  不在上述 10 条里。

⇒ 派工范围 = 上述 **10 个类**（另 5 个登记项已判为非缺口或已行为化，见 §6.1 与 §9）。
⇒ gap id：**G-302-RESIDUAL**（`remaining=1`）。

## 7. v6 的 39 条机制矩阵 —— 在本 commit 上复跑

关键调用点逐条复跑，全部仍有真实消费者（节选，命令原文）：

```
$ grep -rn 'requireEnabled' plugins/*/src/main --include='*.java' | wc -l
14          # MechanismRegistry.requireEnabled 的 7 个 faucet 接入点仍在
$ grep -rn 'ProgressionRules\.' plugins/*/src/main --include='*.java' | head
plugins/rpg-api/.../PlayerProfile.java:32: ...ProgressionRules.effectiveLevel(ascension, level)
plugins/rpg-core/.../RpgCommand.java:137: ProgressionRules.totalExperienceForAscension(...)
plugins/rpg-core/.../RpgCommand.java:148: ProgressionRules.totalExperienceToEffective(...)
plugins/rpg-api/.../HiddenPathReachability.java:116: ProgressionRules.effectiveLevelCap()
$ grep -rn 'ProgressionAnomalyService' plugins | grep '/src/main/' | wc -l
14          # 含 AnomalyReviewCommand（W9 读侧出口）与 JdbcProfileService（写侧）
$ grep -rn 'rpg_session_leases\|rpg_outbox' plugins/*/src/main --include='*.java' | wc -l
（JdbcSessionLease.java:71 / JdbcOutboxRegistry.java:36 / OutboxDispatcher.java:112,143,165 —— 读写都在）
$ grep -rn 'canApprove\|rpg_community_approvals' plugins/*/src/main --include='*.java' | wc -l
（CommunityRolePower.java:45-83 / JdbcCommunityService.java:817,984,1039 —— 判定与落库都在）
```

| 机制组 | 代表调用点 | 判定 | gap id |
|---|---|---|---|
| Kill Switch 执行器 | `JdbcActivityEconomyService.java:71-73` 等 14 处 `requireEnabled` | 已接线 | G-002 |
| 成长语义 `ProgressionRules` | `PlayerProfile.java:32` / `RpgCommand.java:137,148` / `HiddenPathReachability.java:116` | 已接线 | G-111 |
| `/rpgadmin anomaly` 读侧 | `AnomalyReviewCommand.java:104,111,160,173` + `RpgAdminPlugin.java:97,762` | 已接线 | G-ANOMALY-ACK |
| 赛季结算九态 | `SeasonSettlementCommand.java` + `RpgAdminPlugin.java:88,92` | 已接线 | G-101 |
| Session Lease / Outbox | `JdbcSessionLease.java:71` / `JdbcOutboxRegistry.java:36` / `OutboxDispatcher.java:112,143,165` | 已接线 | G-102 |
| 社群七角色 / 双批 | `CommunityRolePower.java:45-83` / `JdbcCommunityService.java:817,984,1039` | 已接线 | G-103 |
| 邮箱交付冻结（含 creative） | `MarketCommand.java:44,885`（`InventoryCreativeEvent`） | 已接线 | G-104 |
| 攻方增益单点结算 | `SkillsCommand.java:1000,1042`（`settleAttackerBonus`，`onBattleTranceHit` 不再写 damage） | 已接线 | G-105 |
| 血契可达（三转） | `HiddenPathRules.java:26`（`BLOODPACT -> 3`） | 已接线 | G-106 |
| 副本 world 死键 | `grep 'world: dungeon'` 在 `rpg-dungeons/config.yml` 已无命中 | 已废弃（键已删） | G-108 |
| `#minecraft:quick_actions` 入 VCS | `datapacks/sivita-navigation/data/minecraft/tags/function/quick_actions.json` | 已接线 | G-206 |
| 装备退出路径 | `EquipmentLifecycle` 由 `JdbcEquipmentOperationService` / `EquipmentStateLedger` 消费 | 已接线 | G-110 |
| DDL 消费者 / 登记反向 | `check-ddl-consumers.sh`：81 张 / 豁免 0 / 反向豁免 0 | 已接线 | G-301 / G-109 |
| **源码字符串伪测试** | 见 §6 | **真实缺口** | **G-302-RESIDUAL** |

## 8. 十门在当前 main 上

### 8.1 sn 与关系

```
$ cnb build get-build-logs --repo xzqkjmnbvc2026/sivitahub --targetRef main --page-size 20
- sn: cnb-og3-1k401sol0  createTime: 2026-10-03T04:55:54Z  status: success  event: api_trigger_gate  sha: 2184f15995e05c370a7a1922bb451d35aafd1990
- sn: cnb-kcs-1k401sf5q  createTime: 2026-10-03T04:55:44Z  status: success  event: push               sha: 2184f15995e05c370a7a1922bb451d35aafd1990

$ cnb build get-build-status --repo xzqkjmnbvc2026/sivitahub --sn cnb-og3-1k401sol0
status: success
stages[13]:（见 8.2）
```

**跨 commit 等价性（本轮 basis = 3947000，门跑在 2184f15）**：

```
$ git log --oneline 2184f15..HEAD
3947000 fix(cnb): W15 的 basis 改为 Planner 自实测 git rev-parse HEAD
44903a4 feat(cnb): 第十五波 —— 在当前 main 上复验（gap-matrix-v7 + verdict-v7）
927ce1d docs(cnb): 素材层收口完成 —— 十门全绿（门禁自测 PASS=99）

$ git diff --name-only 2184f15 HEAD
.workbuddy/settings.json
.workbuddy/settings.json.bak-before-v7-20261003131248
doc/cnb/tasks-w15.json
state/world-state.json
```

⇒ `2184f15 → HEAD`（basis 3947000）的差异**没有一个文件进入构建输入**：
没有 `plugins/**`、没有 `scripts/**`、没有 `resource-pack/**`、没有 `.cnb.yml`、没有 `gradle*`。
`.workbuddy/**` 是本地 hook 配置，`doc/`、`state/` 不参与编译/测试/打包/门禁。
**如实说明**：这是「构建输入等价」的论证，不是「在同一 sha 上跑过」。basis 自身（3947000）的十门构建
本轮查询时 status=pending（`cnb-f7n-1k402tgr8`，采样时停在 stage-1）；`927ce1d` 的门同样 pending
（`cnb-8tm-1k402ppbb`，采样时停在 stage-1）。两者都是**正在跑**的十门形态（13 stage），不是失败。

### 8.2 13 个 stage（sn=cnb-og3-1k401sol0，全 success，耗时全非零）

```
success,3927,Prepare,prepare
success,23546,准备下载器（必须是第一个 stage）,stage-0
success,576080,门禁自测（负向对照）,stage-1
success,110736,DDL 门禁（G-301 消费者 + G-302 注册，双向）,stage-2
success,513,素材门禁（W7-A1）,stage-3
success,337,素材逐像素基线门禁（W12-A1）,stage-4
success,1438,素材可复现性门禁（W13-A1）,stage-5
success,134143,compile,stage-6
success,41032,test,stage-7
success,8486,assemble,stage-8
success,396,BeforeEnd,beforeEnd
skipped,0,DebugDetection,debugDetection
success,654,End,end
```

### 8.3 十门判据行（runner 日志运行时输出）

来源：`cnb build build-runner-download-log --pipelineId cnb-og3-1k401sol0-001`
（去 ANSI 后的 `[时间戳]` 行 = 运行时输出，非脚本原文）。

```
[00:10:03] ===== 门禁自测汇总：PASS=99 FAIL=0 =====      → 门禁自测 GREEN
[00:10:03] GATE_SELFTEST=GREEN
[00:11:55]   RUN=8 SKIPPED=0                              → 素材门禁 GREEN
[00:11:55] RESOURCE_PACK_GATE=GREEN
[00:11:55] ASSET_BASELINE: 校验 252 张 PNG（清单 252 条）  → 逐像素基线门禁 GREEN
[00:11:55]   UNREGISTERED=0 MISSING=0 MODIFIED=0
[00:11:55] ASSET_BASELINE_GATE=GREEN
[00:11:56] ASSET_REPRO: 比对 70 张（4 个生成器）           → 可复现性门禁 GREEN
[00:11:56]   逐像素一致: 70
[00:11:56]   REPRODUCIBLE=70 MISMATCH=0 MISSING=0 UNREGISTERED=0
[00:11:56] ASSET_REPRO_GATE=GREEN
[00:11:54] DDL 表数: 81                                    → DDL 消费者门禁 GREEN
[00:11:54] 豁免清单: 0 条已登记
[00:11:54] DDL_CONSUMER_GATE=GREEN
[00:11:54] 代码引用的 rpg_* 表名: 81 个 / 反向豁免清单: 0 条  → DDL 登记反向门禁 GREEN
[00:11:54] DDL_REGISTRATION_GATE=GREEN
[00:11:54] 已注册指令数: 43 / 指令豁免清单: 9 条            → 命令引用门禁 GREEN
[00:11:54] CMD_REFERENCE_GATE=GREEN
[00:14:10] TASKS_DONE=28 / ATTEMPTS=1                      → compile GREEN
[00:14:10] BUILD_EXIT=0 SECONDS=124
[00:14:10] COMPILE_GATE=GREEN
[00:14:51]   SUM    tests=1091 failed=0 errors=0 skipped=2 modules=26   → test GREEN
[00:14:51]   NO_XML modules=0  期望模块数=26
[00:14:51] SUMMARY_GATE=GREEN
[00:14:51] TEST_EXIT=0 SUMMARY_EXIT=0 SECONDS=41
[00:14:51] TEST_GATE=GREEN
[00:15:00] JAR_COUNT=56                                    → assemble GREEN
[00:15:00]   JAR_CHECKED=25 JAR_MISSING_PLUGIN_YML=0
[00:15:00] ASSEMBLE_EXIT=0 JARCHECK_EXIT=0 SECONDS=7
[00:15:00] PACKAGE_GATE=GREEN
```

判定：**十门在十门形态的构建上全绿**，10 条判据行齐全且所有 stage 耗时非零。
（`cnb-kcs-1k401sf5q` 是同一 sha 的 push 事件，同样 success、同样 13 stage 全 success。）

### 8.4 本地可复跑的门（本容器无 JDK，compile/test/assemble 不能本地复跑）

```
$ for g in check-ddl-consumers.sh check-config-fallbacks.sh check-trusted-menu-holders.sh \
           check-repo-artifacts.sh check-file-deps.sh check-source-contract.sh; do sh scripts/$g; done
CMD_REFERENCE_GATE=GREEN / 反向检查扫描文件数: 549 / 代码引用的 rpg_* 表名: 81 个
DDL_REGISTRATION_GATE=GREEN / DDL_CONSUMER_GATE=GREEN            EXIT=0
CONFIG_FALLBACK_GATE=GREEN                                       EXIT=0
TRUSTED_MENU_GATE=GREEN                                          EXIT=0
REPO_ARTIFACT_GATE=GREEN                                         EXIT=0
check-file-deps: OK                                              EXIT=0
SOURCE_CONTRACT_CHECKS=238 SPECS=27 / SOURCE_CONTRACT_GATE=GREEN EXIT=0

$ sh scripts/check-resource-pack.sh          → RUN=8 SKIPPED=0 / RESOURCE_PACK_GATE=GREEN   EXIT=0
$ python3 scripts/check-asset-baseline.py    → ASSET_BASELINE_GATE=GREEN (252/252)         EXIT=0
$ sh scripts/install-datapacks.sh --check    → DATAPACK_CHECK=GREEN packs=sivita-navigation EXIT=0
$ sh scripts/selftest-gates.sh               → 门禁自测汇总：PASS=99 FAIL=0 / GATE_SELFTEST=GREEN EXIT=0
```

如实说明边界：`sh scripts/check-asset-reproducible.sh` 本地**未复跑**——它要现场编译 Java 生成器，
本容器无 JDK；其判据行取自 §8.3 的 CI 记录。

## 9. 观察项（不构成缺口，供后续波次参考）

1. **`config-version` 有 3 个无读方**：`rpg-community` / `rpg-essentials` / `rpg-market` 的 `config.yml`
   声明 `config-version: 1` 但无 `getInt("config-version")`。同形态在 rpg-commerce / rpg-cosmetics /
   rpg-pets / rpg-story 4 个插件里是**被读的**。这些键不声明任何机制行为，无门禁/文档声称它们被读
   ⇒ 不计为「声明未接线」。
2. **门禁对「不存在的 method」空跑打印 GREEN**（v6 §6 已记）：现状安全（27 个包装器 method 全存在）。
   可选加固：method 非空且命中 0 条 check 时非零退出。
3. **`doc/cnb/gap-registry.md` 的 G-302 行仍写 TODO**，而实际状态是「第二批 15 个保留 + 27 个已迁规格」。
   文档与实际不完全同步（本次未改该文件，不在允许路径内）。

## 10. 逐条判定的 grep 命令原文（V-70..V-99）

### V-70 — basis 确认
```
$ git rev-parse HEAD
3947000d3e33189f2d293d828780155c3e28c3b7
$ git rev-parse --short HEAD
3947000
$ git log --oneline -3
3947000 fix(cnb): W15 的 basis 改为 Planner 自实测 git rev-parse HEAD
44903a4 feat(cnb): 第十五波 —— 在当前 main 上复验（gap-matrix-v7 + verdict-v7）
927ce1d docs(cnb): 素材层收口完成 —— 十门全绿（门禁自测 PASS=99）
```

### V-71..77 — (a) 六脚本调用点
```
$ grep -rn "check-asset-reproducible" --include='.cnb.yml' --include='*.sh' --include='*.py' --include='*.md' . | grep -v '^\./\.git/'
$ grep -rn "asset_fingerprint" --include='*.py' --include='*.sh' . | grep -v '^\./\.git/'
$ grep -rn "gen-asset-manifest" . | grep -v '^\./\.git/'
$ grep -rn "check-asset-baseline" . | grep -v '^\./\.git/'
$ grep -rn "generate-provenance" . | grep -v '^\./\.git/'
```
（完整输出见 §1 各小节。）

### V-78 — (b) 基线与磁盘
```
$ python3 scripts/check-asset-baseline.py; echo "EXIT=$?"
ASSET_BASELINE: 校验 252 张 PNG（清单 252 条）
  UNREGISTERED=0 MISSING=0 MODIFIED=0
ASSET_BASELINE_GATE=GREEN
EXIT=0
$ find resource-pack/assets -name '*.png' | wc -l
252
```

### V-79 — (c) provenance ↔ manifest
（命令与输出见 §3。）

### V-80 — (d) items 三边闭合
```
$ ls resource-pack/assets/originalrpg/items/*.json | sed 's#.*/##; s#\.json$##' | sort > /tmp/defs.txt; wc -l < /tmp/defs.txt
81
$ awk 'NF && $0 !~ /^#/' resource-pack/runtime-item-models.txt | sort > /tmp/manifest.txt; wc -l < /tmp/manifest.txt
81
$ diff /tmp/defs.txt /tmp/manifest.txt && echo IDENTICAL
IDENTICAL
$ comm -23 /tmp/direct.txt /tmp/manifest.txt
（空）
```

### V-81 — (d) config.yml 读取方
```
$ find plugins -name 'config.yml' -path '*/src/main/*' | wc -l
24
$ grep -n 'getConfig()' plugins/rpg-admin/src/main/java/cn/originalrpg/admin/RpgAdminPlugin.java
（见 §5）
$ grep -rn 'store-title' plugins/*/src/main --include='*.java' --include='*.yml'
$ grep -rn 'getInt("config-version"' plugins/*/src/main --include='*.java'
```

### V-82 — ★ G-302 残留登记
```
$ python3 -c "import json;d=json.load(open('plan/source-string-tests-remaining.json'));print(len(d['_remaining_after_batch2']['classes']))"
15
$ python3 -c "import json;d=json.load(open('.cnb/agent-out/W5-A2-source-string-tests-batch2.json'));print(d['unproven'][3])"
第二批剩余 15 个读生产源码的类未处理（见 plan/source-string-tests-remaining.json 的 _remaining_after_batch2）...
```

### V-83 — ★ G-302 残留实测（10 个类仍纯源码断言）
```
$ python3 <脚本：拆 @Test 方法，统计「Files.readString + contains/indexOf 断言」的方法>
源码字符串断言（纯）残留类：10
  ...（见 §6.2）
$ sed -n '12,27p' plugins/rpg-dungeons/src/test/java/cn/originalrpg/dungeons/DungeonEntryCommitAmbiguityContractTest.java
```

### V-84 — 规格门禁覆盖数
```
$ python3 -c "import json;d=json.load(open('scripts/source-contract-specs/all.json'));print('specs:',len(d))"
specs: 27
$ find plugins -name '*ContractTest.java' -path '*/src/test/*' | wc -l
56
$ python3 <脚本：按类名与 all.json 的 27 个规格键对表>
total 56 / spec keys 27 / in 26 / out 30
```
⇒ 56 个 `*ContractTest` 里 26 个已纳管（按类名匹配 27 个规格键；27 个键里有 1 个对应非 `*ContractTest` 文件），
**30 个未纳管**，其中 10 个含纯源码字符串断言（另 20 个是行为测试 / 真实 IO / 已豁免）。

### V-85 — 十门 sn 与 13 stage
```
$ cnb build get-build-status --repo xzqkjmnbvc2026/sivitahub --sn cnb-og3-1k401sol0
status: success
stages[13]：（见 §8.2，全 success，耗时全非零）
```

### V-86 — 跨 commit 等价性
```
$ git diff --name-only 2184f15 HEAD
.workbuddy/settings.json
.workbuddy/settings.json.bak-before-v7-20261003131248
doc/cnb/tasks-w15.json
state/world-state.json
```

### V-87 — 本地门禁复跑（见 §8.4）
```
$ sh scripts/selftest-gates.sh; echo "SELFTEST_EXIT=$?"
===== 门禁自测汇总：PASS=99 FAIL=0 =====
GATE_SELFTEST=GREEN
SELFTEST_EXIT=0
```

### V-88 — basis 自身与 927ce1d 的门（本轮查询时）
```
$ cnb build get-build-status --sn cnb-f7n-1k402tgr8      # sha=3947000（= basis）
status: pending    stage-1「门禁自测」start,0；stage-2..8 pending（13 stage 十门形态，在跑）
$ cnb build get-build-status --sn cnb-8tm-1k402ppbb      # sha=927ce1d
status: pending    stage-1「门禁自测」start,0；stage-2..8 pending（13 stage 十门形态，在跑）
```
⇒ **查得到构建，但采样时两个都还在跑**，故本轮十门判据行取自 §8.3 的 `cnb-og3-1k401sol0`（同构建输入）。
