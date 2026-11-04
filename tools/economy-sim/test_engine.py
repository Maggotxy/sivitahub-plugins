import copy
from decimal import Decimal
from pathlib import Path
import unittest

from engine import (Inputs, Ledger, ModelError, Player, PolicyViolation, Simulation,
                    gini, population, read_yaml, run_suite, units)

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


class EconomyModelTest(unittest.TestCase):
    def setUp(self):
        self.inputs = Inputs(ROOT, HERE / "behaviors.v1.yaml")
        self.scenarios = read_yaml(HERE / "scenarios.v1.yaml")["scenarios"]

    def scenario(self, sid, days=3):
        value = copy.deepcopy(next(s for s in self.scenarios if s["id"] == sid))
        value["days"] = days
        return value

    def test_population_conservation_including_small_and_awkward_counts(self):
        for total in (1, 3, 20, 101, 500):
            scenario = self.scenario("S04")
            scenario["population"]["total"] = total
            self.assertEqual(total, len(population(scenario, self.inputs)))
        invalid = self.scenario("S01")
        invalid["population"]["total"] += 1
        with self.assertRaises(ModelError):
            population(invalid, self.inputs)

    def test_money_never_uses_binary_float_rounding_or_negative_currency(self):
        self.assertEqual(101, units("gold", "1.005"))
        for amount in ("NaN", "Infinity", "-1", True):
            with self.assertRaises(ModelError):
                units("gold", amount)
        with self.assertRaises(ModelError):
            units("merit", "1.5")
        with self.assertRaises(PolicyViolation):
            units("support_credit", 1)

    def test_retry_and_insufficient_funds_are_atomic(self):
        players = [Player("core")]
        ledger = Ledger(players)
        self.assertFalse(ledger.apply(0, "purchase", 0, "shop", cost={"gold": 100}))
        self.assertTrue(ledger.apply(0, "grant", 0, "hunt", reward={"gold": 1000}))
        self.assertFalse(ledger.apply(0, "grant", 0, "hunt", reward={"gold": 1000}))
        self.assertTrue(ledger.apply(0, "purchase", 0, "shop", cost={"gold": 100}))
        self.assertEqual(900, players[0].balance["gold"])
        self.assertEqual(1000, ledger.inflow["gold"])

    def test_market_is_a_transfer_minus_fee_not_a_faucet(self):
        players = [Player("core"), Player("merchant")]
        players[0].balance["gold"] = 10000
        ledger = Ledger(players)
        self.assertTrue(ledger.trade(0, 1, "sale", 0, 2000, 200))
        self.assertFalse(ledger.trade(0, 1, "sale", 0, 2000, 200))
        self.assertFalse(ledger.trade(0, 0, "self", 0, 2000, 200))
        self.assertEqual([8000, 1800], [p.balance["gold"] for p in players])
        self.assertEqual(0, ledger.inflow["gold"])
        self.assertEqual(200, ledger.outflow["gold"])

    def test_market_rejects_fractional_boolean_and_negative_units_atomically(self):
        for price, fee in ((.5, 0), (100, .5), (True, 0), (100, False), (-1, 0), (100, -1), (100, 101)):
            with self.subTest(price=price, fee=fee):
                players = [Player("core"), Player("merchant")]
                players[0].balance["gold"] = 1000
                ledger = Ledger(players)
                with self.assertRaises(ModelError):
                    ledger.trade(0, 1, "invalid", 0, price, fee)
                self.assertEqual([1000, 0], [p.balance["gold"] for p in players])
                self.assertEqual(set(), ledger.keys)

    def test_invalid_default_mix_fails_with_an_explanatory_model_error(self):
        for mix in ({"core": 0}, {}, {"unknown": 1}, {"core": True}, {"core": .5}, {"core": -1}):
            with self.subTest(mix=mix):
                self.inputs.behaviors["default_mix"] = mix
                with self.assertRaises(ModelError):
                    self.inputs._validate_behaviors()
                with self.assertRaises(ModelError):
                    population(self.scenario("S04"), self.inputs)

    def test_same_seed_produces_identical_results_and_different_seed_changes_behavior(self):
        scenario = self.scenario("S01", 7)
        one = Simulation(self.inputs, scenario, 42).run()
        two = Simulation(self.inputs, scenario, 42).run()
        three = Simulation(self.inputs, scenario, 43).run()
        self.assertEqual(one, two)
        self.assertNotEqual(one["balances"], three["balances"])

    def test_real_config_caps_contain_tenfold_faucet_shock(self):
        result = Simulation(self.inputs, self.scenario("S05"), 42).run()
        cap = units("gold", self.inputs.get("rpg-economy", "caps.HUNTING.gold"))
        self.assertLessEqual(result["flows_integer_units"]["HUNTING"]["faucet.gold"], cap * 100 * 3)
        self.assertGreater(result["guard_counters"]["cap_blocked.HUNTING.gold"], 0)
        self.assertTrue(result["balances"]["conservation"])

    def test_returning_wealth_is_opening_stock_not_generated_income(self):
        result = Simulation(self.inputs, self.scenario("S06", 1), 42).run()
        self.assertEqual(4_000_000, result["balances"]["opening"]["gold"])
        self.assertEqual(16_000, result["balances"]["opening"]["starlight"])
        self.assertTrue(result["balances"]["conservation"])

    def test_admin_shock_obeys_global_daily_quota(self):
        result = Simulation(self.inputs, self.scenario("S11", 4), 42).run()
        limit = self.inputs.get("rpg-core", "commerce.admin-starlight.daily-limit")
        self.assertEqual(5000, sum(result["admin_daily_grants"].values()))
        self.assertTrue(all(amount <= limit for amount in result["admin_daily_grants"].values()))
        self.assertGreater(result["guard_counters"]["admin_quota_deferrals"], 0)

    def test_invalid_admin_quantities_fail_before_any_simulation(self):
        for total, per_grant in ((1.9, 1), (True, 1), (-1, 1), (1, .5), (1, True), (0, 0)):
            with self.subTest(total=total, per_grant=per_grant):
                scenario = self.scenario("S11", 1)
                scenario["population"]["shock"]["admin_starlight_grant"] = {"total": total, "per_grant": per_grant}
                with self.assertRaises(ModelError):
                    Simulation(self.inputs, scenario, 42)

    def test_equipment_identity_cannot_be_duplicated_or_resurrected(self):
        simulation = Simulation(self.inputs, self.scenario("S10", 1), 42)
        player = simulation.players[0]
        self.assertTrue(simulation.deliver_equipment(player, "identity"))
        self.assertFalse(simulation.deliver_equipment(player, "identity"))
        player.equipment.remove("identity")
        simulation.exit_ids.add("identity")
        self.assertFalse(simulation.deliver_equipment(player, "identity"))
        self.assertEqual([], player.equipment)

    def test_shared_inventory_is_not_transferable_activity_evidence(self):
        result = Simulation(self.inputs, self.scenario("S13", 3), 42).run()
        self.assertGreater(result["guard_counters"]["shared_inventory_alt_rejections"], 0)

    def test_season_replay_resumes_committed_prefix_without_currency(self):
        simulation = Simulation(self.inputs, self.scenario("S14", 1), 42)
        before = copy.deepcopy([p.balance for p in simulation.players])
        simulation.season_replay()
        self.assertEqual(100, simulation.metrics["season_unique_settlements"])
        self.assertEqual(50, simulation.metrics["season_replay_deduplicated"])
        self.assertEqual(before, [p.balance for p in simulation.players])

    def test_support_credit_is_rejected_before_simulation(self):
        scenario = self.scenario("S15", 1)
        with self.assertRaises(PolicyViolation):
            Simulation(self.inputs, scenario, 42)
        suite = run_suite(self.inputs, [scenario], 42)
        self.assertEqual("EXPECTED_FAIL_FAST", suite["scenarios"][0]["status"])
        del scenario["expect"]
        with self.assertRaises(PolicyViolation):
            run_suite(self.inputs, [scenario], 42)

    def test_expected_fail_fast_cannot_hide_normal_scenarios(self):
        scenario = self.scenario("S01", 1)
        scenario["expect"] = "FAIL_FAST"
        with self.assertRaises(ModelError):
            run_suite(self.inputs, [scenario], 42)

    def test_empty_or_invalid_suite_cannot_report_a_passing_gate(self):
        for scenarios in ([], {}, None):
            with self.subTest(scenarios=scenarios), self.assertRaises(ModelError):
                run_suite(self.inputs, scenarios, 42)

    def test_value_increasing_conversion_fails_startup_certificate(self):
        self.inputs.routes["gold-to-merit"]["reward"]["merit"] = 2
        with self.assertRaises(PolicyViolation):
            self.inputs.check_routes()

    def test_unknown_shocks_fail_instead_of_being_silently_ignored(self):
        scenario = self.scenario("S04", 1)
        scenario["population"]["shock"] = {"unknown_shock": 1}
        with self.assertRaises(ModelError):
            Simulation(self.inputs, scenario, 42)

    def test_monopoly_shock_reaches_the_requested_share_without_creating_items(self):
        result = Simulation(self.inputs, self.scenario("S07", 30), 42).run()
        self.assertGreater(result["metrics"]["market_sales"], 0)
        self.assertGreaterEqual(result["metrics"]["largest_seller_share"], 0.5)
        self.assertTrue(result["metrics"]["equipment_identity_conservation"])
        self.assertTrue(result["balances"]["conservation"])

    def test_weekend_shock_concentrates_hours_not_just_labels(self):
        result = Simulation(self.inputs, self.scenario("S12", 56), 42).run()
        counters = result["guard_counters"]
        share = counters["weekend_play_minutes"] / (counters["weekend_play_minutes"] + counters["weekday_play_minutes"])
        self.assertAlmostEqual(.70, share, delta=.03)

    def test_zero_weekend_target_removes_weekend_activity(self):
        scenario = self.scenario("S12", 7)
        scenario["population"] = {"total": 1, "mix": {"core": 1}, "shock": {"weekend_concentration": 0}}
        self.inputs.behaviors["cohorts"]["core"].update(weekday=1, weekend=1)
        result = Simulation(self.inputs, scenario, 42).run()
        self.assertEqual(0, result["guard_counters"].get("weekend_play_minutes", 0))
        self.assertGreater(result["guard_counters"]["weekday_play_minutes"], 0)
        self.assertEqual(0, result["metrics"]["weekend_play_share"])

    def test_impossible_weekend_hours_are_clamped_and_reported(self):
        scenario = self.scenario("S12", 7)
        scenario["population"] = {"total": 1, "mix": {"automation": 1}, "shock": {"weekend_concentration": .7}}
        self.inputs.behaviors["cohorts"]["automation"].update(hours=12, weekday=1, weekend=1)
        result = Simulation(self.inputs, scenario, 42).run()
        self.assertEqual(2, result["guard_counters"]["play_hours_clamped_player_days"])
        self.assertEqual(48 * 60, result["guard_counters"]["weekend_play_minutes"])
        self.assertTrue(any(alert.startswith("WEEKEND_HOURS_CLAMPED") for alert in result["alerts"]))
        self.assertTrue(any(alert.startswith("SCENARIO_TARGET_UNMET") for alert in result["alerts"]))

    def test_report_rejects_fractional_or_negative_wallets_even_when_total_conserves(self):
        simulation = Simulation(self.inputs, self.scenario("S01", 1), 42)
        for delta in (.5, -1):
            simulation.players[0].balance["gold"] = delta
            simulation.players[1].balance["gold"] = -delta
            with self.assertRaises(ModelError):
                simulation.report()

    def test_gini_zero_equal_and_extreme_distributions(self):
        self.assertEqual(0, gini([0, 0, 0]))
        self.assertEqual(0, gini([Decimal(7), Decimal(7)]))
        self.assertEqual(0.75, gini([0, 0, 0, 100]))


if __name__ == "__main__":
    unittest.main()
