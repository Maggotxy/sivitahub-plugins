# Economy stress model

[中文说明](README.zh-CN.md)

This runner now executes the fifteen checked-in scenarios. S01–S14 run a deterministic, day-by-day cohort model; S15 must reject a real-money support-credit connection before a simulation is created. The runner reads game prices, rewards, fees, limits and loot probabilities from the actual repository configuration. It never connects to a live database or changes a server configuration.

**The output is an uncalibrated sensitivity model, not a balance verdict or a Paper/PostgreSQL test.** It includes explicit omissions. A successful model gate means the model completed and its accounting invariants held; it does not mean the economy is ready for production.

## Run

From the repository root, with Python 3.10+ and `PyYAML==6.0.3`:

```bash
python3 -m unittest discover -s tools/economy-sim -p 'test_*.py'
python3 tools/economy-sim/run.py --output build/economy-sim/simulation.json
```

The full suite retains the original populations and horizons: 20 players/180 days, 100 and 500 players/365 days, and twelve shorter stress scenarios. To inspect a subset:

```bash
python3 tools/economy-sim/run.py --only S05 S10 S13 S15 --seed 42   --output build/economy-sim/stress.json
```

Reproduction can require the same input hashes:

```bash
python3 tools/economy-sim/run.py   --expected-inputs build/economy-sim/simulation.json   --output build/economy-sim/reproduced.json
```

Changing a configuration, scenario, behavior file or the inspected forge rule makes that comparison fail before output replacement. Each result also records the model source hash. Select a new output filename when retaining multiple experiments; a successful run atomically replaces its explicitly selected report, while a failed run preserves it.

## Actual inputs and synthetic assumptions

| Input | Values used |
|---|---|
| `rpg-core/config.yml` | Starting balance, conversion costs/rewards and daily/weekly quotas, reference valuation, global administrator grant limits |
| `rpg-economy/config.yml` | Hunting, fishing and dungeon rewards, activity caps, commission board/eligibility/evidence rules |
| `rpg-commerce/config.yml` | Ordinary supply-shop prices and quotas |
| `rpg-market/config.yml` | Equipment transaction fee, listing price bounds, maximum open listings and expiration |
| `rpg-items/config.yml` | Forge core/Gold/catalyst costs and success bounds |
| `rpg-enhancement/config.yml` | Refinement Gold cost |
| `rpg-mobs/config.yml` | Elite probability, normal/elite material drop chances and pity counters |
| `ForgeRules.java` | Catalyst success coefficient for the modeled wood-base, minimum-catalyst recipe; unexpected method changes stop the run |
| `behaviors.v1.yaml` | **Unmeasured assumptions**: playtime, participation, activity choices, spending attempts, willingness to pay and content-gap sensitivity |
| `scenarios.v1.yaml` | Population, horizon, shock, random seed and bilingual scenario labels |

All ten documented player types have explicit behavioral profiles. Default population allocation preserves the exact requested total, including small populations. Prices are never copied into the behavior file. Every loaded source is hashed with full SHA-256, and the exact configuration values consumed by the model appear in `parameters_read`.

Gold is represented as integer cents; Merit and Starlight use integer units. Transfers move existing Gold and burn the configured market fee. Returning-player wealth is opening stock, not a new faucet. The report verifies every currency against opening stock plus inflows minus outflows, and verifies that each modeled equipment identity is either owned, listed, or exited exactly once.

## What each stress scenario does

| Scenario | Executed behavior |
|---|---|
| S01–S03 | Daily activity, loot/pity, account evidence, forging, refinement, supply purchases, conversion, listing, sale and expiration across the three cohort sizes |
| S04 | The selected fraction deliberately exhausts the higher Gold-cap faucet; this is a cap stress, not a combat/energy throughput forecast |
| S05 | Multiply requested hunting Gold by ten before enforcing the configured daily cap |
| S06 | Add the specified returning wealth to opening balances and switch the returning fraction to its explicit spending profile |
| S07 | Prioritize a designated merchant's genuine listings and delay competing sales to impose the requested completed-sales concentration; volume may fall |
| S08 | Apply the separately declared retention sensitivity over the content-free interval |
| S09 | Reduce the probability of matching an otherwise eligible listing to 20% |
| S10 | Resubmit the same equipment identity at the configured rate; the model's common delivery gate rejects duplicates |
| S11 | Request the administrator grant total while enforcing the actual per-grant, shared daily and shared weekly limits |
| S12 | Redistribute expected weekly play hours to the requested weekend share; stochastic realized minutes are recorded |
| S13 | Let alts possess shared commission stock while requiring their own qualifying playtime and non-transferable evidence |
| S14 | Interrupt an identity/cursor settlement after a committed prefix and replay it; each account settles once and no currency is invented |
| S15 | Reject `support_credit` to gameplay wealth before initialization; removing the required expectation makes the suite fail |

The S10/S14 guards are **offline model contracts**. Runtime duplicate delivery and crash recovery must be validated against the real Java services and database separately. No model claim replaces those tests.

S12 targets expected weekly hours, so a partial final week and sampled attendance can change the realized share. A zero weekend target is supported. Requests exceeding 24 hours per player day are capped and emit `WEEKEND_HOURS_CLAMPED`; the report also flags a realized share more than three percentage points away from its target. The model never silently invents extra hours to make a target appear satisfied.

## Report

`simulation.json` contains bilingual scenario names, full input hashes, model hash, seed, source parameters, behavior assumptions, scope limits, alerts, counters and:

- Weekly Gold stock growth, relative growth when the opening denominator is nonzero, and net growth per active player-day.
- Weekly sink/faucet ratio, with conversion inflows shown separately.
- Equipment creation/exit counts and ratio.
- Market sales, expirations, completed-sale ages, open-listing P50/P90/oldest age, recent sales/expiry ratio and seller concentration.
- Wealth Gini and top 1% share under the declared reference vector.
- A strictly positive valuation certificate for **all** conversion routes, including multi-currency costs. Every route must be non-increasing under that vector; value-increasing/free routes or unsupported currencies fail before simulation.
- Cap rejections, duplicate attempts, alt-evidence rejections, season replay counts and administrator quota deferrals.

Undefined ratios use JSON `null`, never infinity or a fabricated zero. Monetary flow tables explicitly state integer units.

## Calibration and practical limits

A run without observed data remains `CALIBRATION=NEEDS_TUNING`. Do not tune live prices, quotas or drops from a synthetic stock-growth number alone.

An optional `--ledger-summary` accepts a JSON object with `provenance` and a `scenarios` mapping. Each scenario observation must specify the same `days`, `players` and a `metrics` mapping. Supported comparisons are `wealth_gini`, `top_one_percent_share`, `market_sales`, `equipment_created` and `equipment_exited`. A mismatched horizon/population fails; unmatched or unsupported metrics are not silently used. The tolerance is the explicit behavioral-analysis assumption in `behaviors.v1.yaml`. This compares observations without fitting the model. Even `OBSERVED_METRICS_WITHIN_TOLERANCE` is not a whole-system validation.

The current model intentionally does not implement progression, encounter difficulty, dungeon energy/eligibility, raid/advancement income, commodity production, every sink, or a full enhancement/rarity/binding model. Commission stock and ordinary forge bases/catalysts are assumed available, so account evidence and money/core constraints can be stressed independently. Equipment exits return a conservative one-essence minimum. These omissions can materially affect the economic results; they are repeated in every generated report.

Use the model to identify which assumptions and live counters need measurement, then compare with a reviewed seven-day staging/production summary and controlled cohort experiments. It neither automatically adjusts server values nor claims to recreate player behavior.
