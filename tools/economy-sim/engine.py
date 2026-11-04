"""Deterministic offline flow model. This does not execute Paper or JDBC code.

All money uses integer cents/units. Synthetic decisions and deliberately limited
inventory assumptions are recorded separately from actual server parameters.
"""
from __future__ import annotations

from collections import Counter, defaultdict
from dataclasses import dataclass, field
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import json
import math
from pathlib import Path
import random
import re

import yaml

CURRENCIES = ("gold", "merit", "starlight")
MODULES = ("rpg-core", "rpg-economy", "rpg-commerce", "rpg-market", "rpg-items", "rpg-enhancement", "rpg-mobs")


class ModelError(ValueError):
    pass


class PolicyViolation(ModelError):
    pass


class UniqueLoader(yaml.SafeLoader):
    def construct_mapping(self, node, deep=False):
        self.flatten_mapping(node)
        result = {}
        for key_node, value_node in node.value:
            key = self.construct_object(key_node, deep=deep)
            if key in result:
                raise ModelError(f"Duplicate YAML key: {key}")
            result[key] = self.construct_object(value_node, deep=deep)
        return result


def read_yaml(path: Path):
    try:
        value = yaml.load(path.read_text(encoding="utf-8"), Loader=UniqueLoader)
    except (OSError, yaml.YAMLError) as error:
        raise ModelError(f"Cannot load {path}: {error}") from error
    if not isinstance(value, dict):
        raise ModelError(f"Expected a mapping: {path}")
    return value


def number(value, label="number") -> Decimal:
    if isinstance(value, bool):
        raise ModelError(f"{label}: a boolean is not a number")
    try:
        result = Decimal(str(value))
    except Exception as error:
        raise ModelError(f"{label}: invalid number {value!r}") from error
    if not result.is_finite() or result < 0:
        raise ModelError(f"{label}: expected a finite nonnegative value")
    return result


def integer(value, label, minimum=0):
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ModelError(f"{label}: expected an integer >= {minimum}")
    return value


def population_weights(weights, cohorts):
    if not isinstance(weights, dict) or not weights:
        raise ModelError("default_mix must be a nonempty cohort mapping")
    for name, weight in weights.items():
        if name not in cohorts:
            raise ModelError(f"default_mix: unknown cohort {name}")
        integer(weight, f"default_mix.{name}")
    if sum(weights.values()) == 0:
        raise ModelError("default_mix must have positive total weight")
    return weights


def units(currency, value) -> int:
    if currency not in CURRENCIES:
        raise PolicyViolation(f"C-012: unsupported currency {currency}")
    amount = number(value, currency) * (100 if currency == "gold" else 1)
    if currency != "gold" and amount != amount.to_integral():
        raise ModelError(f"{currency}: fractional unit")
    return int(amount.to_integral_value(rounding=ROUND_HALF_UP))


def amounts(values):
    if not isinstance(values, dict):
        raise ModelError("Expected a currency mapping")
    if set(values) - set(CURRENCIES):
        raise PolicyViolation(f"C-012: unsupported currencies {sorted(set(values) - set(CURRENCIES))}")
    return {c: units(c, values.get(c, 0)) for c in CURRENCIES}


def human(values):
    return {c: (round(values.get(c, 0) / 100, 2) if c == "gold" else values.get(c, 0)) for c in CURRENCIES}


class Inputs:
    def __init__(self, root: Path, behaviors_path: Path):
        self.root = root
        self.config = {}
        self.hashes = {}
        for module in MODULES:
            path = root / "plugins" / module / "src/main/resources/config.yml"
            self.config[module] = read_yaml(path)
            self.hashes[str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.behaviors = read_yaml(behaviors_path)
        self.hashes["behaviors"] = hashlib.sha256(behaviors_path.read_bytes()).hexdigest()
        self.read_parameters = {}
        self.routes = self.get("rpg-core", "wealth-exchange.routes")
        reference = self.get("rpg-core", "wealth-exchange.integrity-reference-values")
        self.reference = {c: number(reference[c]) for c in CURRENCIES}
        if any(v <= 0 for v in self.reference.values()):
            raise ModelError("Arbitrage certificate requires strictly positive reference values")
        self.certificate = self.check_routes()
        self._validate_behaviors()
        self._validate_game_parameters()
        # Two coefficients are Java rules, not configuration. Read them from the
        # actual method and fail closed if its shape changes; never silently drift.
        rules_path = root / "plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/ForgeRules.java"
        rules = rules_path.read_text()
        method = rules.split("static double successChance(", 1)[-1].split("static double rarityLuck", 1)[0]
        matches = re.findall(r"catalystAmount \* ([0-9.]+)", method)
        if len(matches) != 1 or "Math.min(maximumChance, baseChance + qualityBonus" not in method:
            raise ModelError("ForgeRules.successChance changed; review the offline model before running")
        self.catalyst_bonus = number(matches[0])
        self.hashes[str(rules_path.relative_to(root))] = hashlib.sha256(rules_path.read_bytes()).hexdigest()

    def get(self, module, path):
        value = self.config[module]
        try:
            for part in path.split("."):
                value = value[part]
        except (KeyError, TypeError) as error:
            raise ModelError(f"Missing real config parameter: {module}:{path}") from error
        if hasattr(self, "read_parameters"):
            self.read_parameters[f"{module}:{path}"] = value
        return value

    def check_routes(self):
        certificate = []
        for route_id, route in sorted(self.routes.items()):
            cost, reward = amounts(route["cost"]), amounts(route["reward"])
            def value(bundle):
                return sum(Decimal(bundle[c]) / (100 if c == "gold" else 1) * self.reference[c]
                           for c in CURRENCIES)
            debit, credit = value(cost), value(reward)
            if debit <= 0 or credit <= 0 or credit > debit:
                raise PolicyViolation(f"C-005: value-increasing or free conversion: {route_id}")
            for period in ("daily", "weekly"):
                limit = route["limits"][period]
                if isinstance(limit, bool) or not isinstance(limit, int) or limit < 1:
                    raise ModelError(f"{route_id}: invalid {period} limit")
            certificate.append({"route": route_id, "cost_value": str(debit), "reward_value": str(credit)})
        return certificate

    def _validate_behaviors(self):
        cohorts = self.behaviors.get("cohorts", {})
        required = {"newcomer", "weekend", "core", "hardcore", "merchant", "forger", "organizer", "dormant", "alt", "automation"}
        if set(cohorts) != required:
            raise ModelError("Behavior file must define the ten documented cohorts")
        population_weights(self.behaviors.get("default_mix"), cohorts)
        for name, params in cohorts.items():
            for field_name in ("hours", "weekday", "weekend", "kills", "fishing", "mining", "dungeons", "forge", "refine", "supply", "exit", "list", "convert"):
                value = number(params.get(field_name), f"{name}.{field_name}")
                if field_name in {"weekday", "weekend", "exit", "list", "convert"} and value > 1:
                    raise ModelError(f"{name}.{field_name}: probability exceeds 1")
            if float(params["hours"]) > 24:
                raise ModelError(f"{name}: hours exceed a day")
        level = self.behaviors.get("effective_mob_level")
        if not isinstance(level, int) or isinstance(level, bool) or not 1 <= level <= 120:
            raise ModelError("effective_mob_level must be within 1..120")
        for key in ("market_price_multiple_of_forge_cost", "market_daily_sale_probability", "calibration_relative_tolerance", "content_gap_maximum_activity_loss"):
            number(self.behaviors.get(key), key)
        if not 0 <= self.behaviors["market_daily_sale_probability"] <= 1:
            raise ModelError("market_daily_sale_probability must be within 0..1")
        if not 0 <= self.behaviors["content_gap_maximum_activity_loss"] <= 1:
            raise ModelError("content_gap_maximum_activity_loss must be within 0..1")

    def _validate_game_parameters(self):
        for module, paths in {
            "rpg-mobs": ("elite.base-chance", "elite.material-drop-chance", "normal.material-drop-chance"),
            "rpg-market": ("fees.custom-equipment", "fees.normal"),
            "rpg-items": ("forge.base-success-rate", "forge.maximum-success-rate"),
        }.items():
            for path in paths:
                if number(self.get(module, path), f"{module}:{path}") > 1:
                    raise ModelError(f"{module}:{path}: probability/fee exceeds one")
        for module, paths in {
            "rpg-mobs": ("elite.material-drop-pity", "normal.material-drop-pity"),
            "rpg-market": ("listing.expiry-hours", "listing.maximum-open-per-player"),
            "rpg-items": ("forge.core-cost", "forge.catalyst-cost"),
            "rpg-economy": ("commissions-policy.visible-per-day", "commissions-policy.total-daily-limit", "commissions-policy.default-evidence-cost"),
            "rpg-core": ("commerce.admin-starlight.maximum-per-grant", "commerce.admin-starlight.daily-limit", "commerce.admin-starlight.weekly-limit"),
        }.items():
            for path in paths:
                value = self.get(module, path)
                if isinstance(value, bool) or not isinstance(value, int) or value < 1:
                    raise ModelError(f"{module}:{path}: expected a positive integer")


@dataclass
class Player:
    cohort: str
    balance: dict = field(default_factory=lambda: dict.fromkeys(CURRENCIES, 0))
    cores: int = 0
    equipment: list = field(default_factory=list)
    minutes: float = 0
    normal_pity: int = 0
    elite_pity: int = 0


class Ledger:
    """Atomic, idempotent *model* movements; not a replacement for the server ledger."""
    def __init__(self, players):
        self.players = players
        self.keys = set()
        self.inflow = Counter()
        self.outflow = Counter()
        self.by_source = defaultdict(Counter)
        self.daily = defaultdict(Counter)

    def apply(self, pid, key, day, source, cost=None, reward=None, conversion=False):
        if key in self.keys:
            return False
        cost = cost or {}
        reward = reward or {}
        if (set(cost) | set(reward)) - set(CURRENCIES):
            raise PolicyViolation("C-012: model movement uses an unsupported currency")
        if any(not isinstance(v, int) or isinstance(v, bool) or v < 0 for v in list(cost.values()) + list(reward.values())):
            raise ModelError("Ledger movements require nonnegative integer units")
        balance = self.players[pid].balance
        if any(balance[c] < amount for c, amount in cost.items()):
            return False
        self.keys.add(key)
        for c, amount in cost.items():
            balance[c] -= amount
            self.outflow[c] += amount
            kind = "conversion_out" if conversion else "sink"
            self.by_source[source][f"{kind}.{c}"] += amount
            self.daily[day][f"{kind}.{c}"] += amount
        for c, amount in reward.items():
            balance[c] += amount
            self.inflow[c] += amount
            kind = "conversion_in" if conversion else "faucet"
            self.by_source[source][f"{kind}.{c}"] += amount
            self.daily[day][f"{kind}.{c}"] += amount
        return True

    def trade(self, buyer, seller, key, day, price, fee):
        integer(price, "Market price")
        integer(fee, "Market fee")
        if fee > price:
            raise ModelError("Invalid market fee")
        if buyer == seller or key in self.keys or self.players[buyer].balance["gold"] < price:
            return False
        self.keys.add(key)
        self.players[buyer].balance["gold"] -= price
        self.players[seller].balance["gold"] += price - fee
        self.outflow["gold"] += fee
        self.by_source["market_fee"]["sink.gold"] += fee
        self.daily[day]["sink.gold"] += fee
        return True


def population(scenario, inputs):
    spec = scenario["population"]
    total = spec["total"]
    if isinstance(total, bool) or not isinstance(total, int) or not 1 <= total <= 10000:
        raise ModelError("Population total must be in 1..10000")
    mix = spec.get("mix")
    if mix is None:
        weights = population_weights(inputs.behaviors["default_mix"], inputs.behaviors["cohorts"])
        weight_total = sum(weights.values())
        mix = {k: int(total * v // weight_total) for k, v in weights.items()}
        remainder = total - sum(mix.values())
        order = sorted(weights, key=lambda k: (-(total * weights[k] % weight_total), k))
        for name in order[:remainder]:
            mix[name] += 1
    if not isinstance(mix, dict) or any(k not in inputs.behaviors["cohorts"] or isinstance(v, bool) or not isinstance(v, int) or v < 0 for k, v in mix.items()):
        raise ModelError("Invalid population mix")
    if sum(mix.values()) != total:
        raise ModelError("Population mix does not sum to total")
    return [Player(name) for name, count in sorted(mix.items()) for _ in range(count)]


def gini(values):
    ordered = sorted(values)
    total = sum(ordered)
    if not ordered or total == 0:
        return 0.0
    return round(float(sum((2 * i - len(ordered) - 1) * value for i, value in enumerate(ordered, 1))
                 / (len(ordered) * total)), 6)


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


class Simulation:
    def __init__(self, inputs, scenario, seed):
        self.inputs, self.scenario = inputs, scenario
        self.seed = seed
        self.rng = random.Random(seed)
        self.shock = scenario.get("shock", scenario["population"].get("shock", {}))
        allowed_shocks = {"focus_highest_yield", "faucet_multiplier", "dormant_return", "carry_gold", "carry_starlight", "monopolize_sales", "no_new_content_weeks", "liquidity", "duplication_rate", "admin_starlight_grant", "weekend_concentration", "alt_shared_inventory", "season_settlement", "support_credit_to_starlight"}
        if set(self.shock) - allowed_shocks:
            raise ModelError(f"Unknown shock: {set(self.shock) - allowed_shocks}")
        if self.shock.get("support_credit_to_starlight"):
            raise PolicyViolation("C-012: support_credit cannot reach gameplay wealth")
        for key in ("focus_highest_yield", "dormant_return", "monopolize_sales", "liquidity", "duplication_rate", "weekend_concentration"):
            if key in self.shock and not 0 <= number(self.shock[key], key) <= 1:
                raise ModelError(f"{key}: probability outside 0..1")
        if "faucet_multiplier" in self.shock:
            multiplier = self.shock["faucet_multiplier"]
            if multiplier.get("key") != "hunt.gold":
                raise ModelError("Only the explicitly modeled hunt.gold multiplier is supported")
            number(multiplier["factor"], "faucet multiplier")
        if "admin_starlight_grant" in self.shock:
            grant = self.shock["admin_starlight_grant"]
            if not isinstance(grant, dict) or set(grant) != {"total", "per_grant"}:
                raise ModelError("admin_starlight_grant requires total and per_grant")
            integer(grant["total"], "Admin grant total")
            integer(grant["per_grant"], "Admin per_grant", 1)
        if "no_new_content_weeks" in self.shock:
            integer(self.shock["no_new_content_weeks"], "no_new_content_weeks", 1)
        self.players = population(scenario, inputs)
        self.dominant_seller = next((i for i, p in enumerate(self.players) if p.cohort == "merchant"), 0)
        self.days = scenario["days"]
        if isinstance(self.days, bool) or not isinstance(self.days, int) or not 1 <= self.days <= 3660:
            raise ModelError("Days must be in 1..3660")
        self.ledger = Ledger(self.players)
        self.metrics = Counter()
        self.openings = Counter()
        self.stock_history = []
        self.active_gold_history = []
        self.listings = []
        self.sale_ages = []
        self.sale_days = []
        self.expiry_days = []
        self.sellers = Counter()
        self.equipment_ids = set()
        self.exit_ids = set()
        self.route_week_counts = Counter()
        self.admin_daily = Counter()
        self.admin_weekly = Counter()
        self.admin_remaining = self.shock.get("admin_starlight_grant", {}).get("total", 0)
        self.initialise_balances()

    def cfg(self, module, path):
        return self.inputs.get(module, path)

    def initialise_balances(self):
        starting = units("gold", self.cfg("rpg-core", "progression.starting-balance"))
        returning = int(len(self.players) * self.shock.get("dormant_return", 0))
        for index, player in enumerate(self.players):
            player.balance["gold"] = starting
            if index < returning:
                player.cohort = "dormant"
                player.balance["gold"] += units("gold", self.shock.get("carry_gold", 0))
                player.balance["starlight"] += units("starlight", self.shock.get("carry_starlight", 0))
            self.openings.update(player.balance)

    def count(self, mean):
        count = int(mean)
        return count + int(self.rng.random() < mean - count)

    def capped_reward(self, pid, day, kind, reward):
        caps = amounts(self.cfg("rpg-economy", f"caps.{kind}"))
        actual = {c: min(amount, caps[c]) for c, amount in reward.items()}
        for c in CURRENCIES:
            self.metrics[f"cap_blocked.{kind}.{c}"] += reward.get(c, 0) - actual.get(c, 0)
        self.ledger.apply(pid, f"{day}:{pid}:activity:{kind}", day, kind, reward=actual)

    def hunting(self, player, pid, day, kills):
        rewards = self.cfg("rpg-economy", "rewards.hunting")
        level = self.inputs.behaviors["effective_mob_level"]
        base = number(rewards["base-gold"]) + number(rewards["gold-per-level"]) * level
        elite_chance = float(self.cfg("rpg-mobs", "elite.base-chance"))
        drops = {kind: (float(self.cfg("rpg-mobs", f"{kind}.material-drop-chance")),
                        int(self.cfg("rpg-mobs", f"{kind}.material-drop-pity"))) for kind in ("elite", "normal")}
        gold, merit = 0, 0
        for _ in range(kills):
            elite = self.rng.random() < elite_chance
            gold += units("gold", base * (number(rewards["elite-multiplier"]) if elite else 1))
            merit += int(rewards["elite-merit"]) if elite else 0
            kind = "elite" if elite else "normal"
            pity_attr = f"{kind}_pity"
            pity = getattr(player, pity_attr) + 1
            chance, threshold = drops[kind]
            if self.rng.random() < chance or pity >= threshold:
                player.cores += 1
                pity = 0
            setattr(player, pity_attr, pity)
        factor = number(self.shock.get("faucet_multiplier", {}).get("factor", 1))
        gold = int(Decimal(gold) * factor)
        self.capped_reward(pid, day, "HUNTING", {"gold": gold, "merit": merit})
        self.metrics["kills"] += kills

    def activities(self, player, pid, day, behavior, hours):
        kills = self.count(float(behavior["kills"]) * hours)
        dungeon_intent = self.count(float(behavior["dungeons"]))
        if pid < int(len(self.players) * self.shock.get("focus_highest_yield", 0)):
            # Compare actual reward ceilings; the stress cohort exhausts its
            # selected faucet. Eligibility/energy is outside this stress model.
            sources = ("HUNTING", "DUNGEON")
            highest = max(sources, key=lambda s: units("gold", self.cfg("rpg-economy", f"caps.{s}.gold")))
            if highest == "HUNTING":
                kills *= 10
            else:
                dungeon_intent = max(dungeon_intent, 20)
        self.hunting(player, pid, day, kills)
        fishing = self.count(float(behavior["fishing"]) * hours)
        fish_reward = amounts(self.cfg("rpg-economy", "rewards.vanilla.fishing"))
        self.capped_reward(pid, day, "GATHERING", {c: v * fishing for c, v in fish_reward.items()})
        dungeon_rewards = list(self.cfg("rpg-economy", "rewards.dungeons").values())
        dungeon_total = Counter()
        for _ in range(dungeon_intent):
            dungeon_total.update(amounts(self.rng.choice(dungeon_rewards)))
        self.capped_reward(pid, day, "DUNGEON", dungeon_total)
        mining = self.count(float(behavior["mining"]) * hours)
        self.commissions(player, pid, day, mining, fishing)

    def commissions(self, player, pid, day, mining, fishing):
        policy = self.cfg("rpg-economy", "commissions-policy")
        evidence = policy["active-evidence"]
        available = min(evidence["daily-cap"],
                        min(mining * evidence["sources"]["natural-mining"]["grant"], evidence["sources"]["natural-mining"]["daily-cap"])
                        + min(fishing * evidence["sources"]["fishing"]["grant"], evidence["sources"]["fishing"]["daily-cap"]))
        offers = list(self.cfg("rpg-economy", "commissions").items())
        # The same daily board is visible to every account. Real material stock
        # is assumed sufficient, deliberately stress-testing the evidence gate.
        board_rng = random.Random(self.seed + day)
        board_rng.shuffle(offers)
        board = offers[:policy["visible-per-day"]]
        attempted = 0
        for offer_id, offer in board:
            if attempted >= policy["total-daily-limit"]:
                break
            attempted += 1
            cost = int(offer.get("evidence-cost", policy["default-evidence-cost"]))
            if player.minutes < policy["minimum-play-minutes"] or available < cost:
                self.metrics["commission_rejected_without_play_or_evidence"] += 1
                if player.cohort == "alt" and self.shock.get("alt_shared_inventory"):
                    self.metrics["shared_inventory_alt_rejections"] += 1
                continue
            reward = amounts({"gold": offer["gold"], "merit": offer["merit"]})
            if reward["gold"] > units("gold", policy["maximum-gold-per-evidence"]) * cost or reward["merit"] > policy["maximum-merit-per-evidence"] * cost:
                raise PolicyViolation(f"Commission exceeds evidence value budget: {offer_id}")
            available -= cost
            self.ledger.apply(pid, f"{day}:{pid}:commission:{offer_id}", day, "commissions", reward=reward)

    def equipment(self, player, pid, day, behavior):
        forge = self.cfg("rpg-items", "forge")
        cost = units("gold", forge["gold-cost"])
        # Wood base, one configured minimum catalyst, no resonance/blueprint.
        chance = min(number(forge["maximum-success-rate"]),
                     number(forge["base-success-rate"]) + self.inputs.catalyst_bonus * forge["catalyst-cost"])
        for attempt in range(self.count(float(behavior["forge"]))):
            if player.cores < forge["core-cost"]:
                break
            if not self.ledger.apply(pid, f"{day}:{pid}:forge:{attempt}", day, "forge", cost={"gold": cost}):
                break
            player.cores -= forge["core-cost"]
            if self.rng.random() >= chance:
                self.metrics["forge_failed"] += 1
                continue
            identity = f"{day}:{pid}:{attempt}"
            self.deliver_equipment(player, identity)
            if self.rng.random() < self.shock.get("duplication_rate", 0):
                # Same identity is rejected, never counted as another real item.
                self.metrics["duplicate_attempts"] += 1
                if not self.deliver_equipment(player, identity):
                    self.metrics["duplicate_rejections"] += 1
        if player.equipment:
            for attempt in range(self.count(float(behavior["refine"]))):
                self.ledger.apply(pid, f"{day}:{pid}:refine:{attempt}", day, "refinement",
                                  cost={"gold": units("gold", self.cfg("rpg-enhancement", "refinement.price"))})
        if len(player.equipment) > 1 and self.rng.random() < behavior["exit"]:
            identity = player.equipment.pop()
            self.exit_ids.add(identity)
            self.metrics["equipment_exited"] += 1
            # Conservative lower bound: one bound essence, as for a common
            # zero-slot, zero-limit item. Rarity/slot rolls are not modeled.
            player.cores += 1
        maximum = self.cfg("rpg-market", "listing.maximum-open-per-player")
        open_count = sum(1 for listing in self.listings if listing["seller"] == pid)
        if len(player.equipment) > 1 and open_count < maximum and self.rng.random() < behavior["list"]:
            price = int(cost * self.inputs.behaviors["market_price_multiple_of_forge_cost"])
            price = max(units("gold", self.cfg("rpg-market", "listing.minimum-price")), price)
            price = min(units("gold", self.cfg("rpg-market", "listing.maximum-price")), price)
            self.listings.append({"id": player.equipment.pop(), "seller": pid, "day": day, "price": price})

    def deliver_equipment(self, player, identity):
        if identity in self.equipment_ids:
            return False
        self.equipment_ids.add(identity)
        player.equipment.append(identity)
        self.metrics["equipment_created"] += 1
        return True

    def spending(self, player, pid, day, behavior):
        products = self.cfg("rpg-commerce", "supply-shop.products")
        if self.rng.random() < min(1, behavior["supply"]):
            name = self.rng.choice(sorted(products))
            product = products[name]
            if product["daily-limit"] > 0:
                self.ledger.apply(pid, f"{day}:{pid}:supply:{name}", day, f"supply:{name}",
                                  cost={"gold": units("gold", product["public-price"])})
        if self.rng.random() < behavior["convert"]:
            route_id = self.rng.choice(sorted(self.inputs.routes))
            route = self.inputs.routes[route_id]
            week_key = (day // 7, pid, route_id)
            if self.route_week_counts[week_key] < route["limits"]["weekly"]:
                if self.ledger.apply(pid, f"{day}:{pid}:convert:{route_id}", day, f"exchange:{route_id}",
                                     amounts(route["cost"]), amounts(route["reward"]), conversion=True):
                    self.route_week_counts[week_key] += 1

    def market(self, day, active):
        remaining = []
        expiry = self.cfg("rpg-market", "listing.expiry-hours") / 24
        chance = self.inputs.behaviors["market_daily_sale_probability"] * self.shock.get("liquidity", 1)
        monopoly = self.shock.get("monopolize_sales", 0)
        ordered = sorted(self.listings, key=lambda item: item["seller"] != self.dominant_seller) if monopoly else self.listings
        for listing in ordered:
            age = day - listing["day"]
            seller = listing["seller"]
            if age >= expiry:
                self.players[seller].equipment.append(listing["id"])
                self.expiry_days.append(day)
                continue
            if self.rng.random() >= chance:
                remaining.append(listing)
                continue
            buyers = [i for i in active if i != seller and self.players[i].balance["gold"] >= listing["price"]]
            # Impose the specified completed-sales concentration. Competing
            # sales wait until genuine dominant-seller sales allow their quota;
            # volume can fall, and neither stock nor buyer money is invented.
            if monopoly and seller != self.dominant_seller and self.sellers[self.dominant_seller] / (len(self.sale_days) + 1) < monopoly:
                self.metrics["concentration_delayed_sales"] += 1
                remaining.append(listing)
                continue
            if not buyers:
                remaining.append(listing)
                continue
            buyer = self.rng.choice(buyers)
            fee = int((Decimal(listing["price"]) * number(self.cfg("rpg-market", "fees.custom-equipment"))).to_integral_value(rounding=ROUND_HALF_UP))
            key = f"market:{listing['id']}:{listing['day']}:{seller}"
            if self.ledger.trade(buyer, seller, key, day, listing["price"], fee):
                self.players[buyer].equipment.append(listing["id"])
                self.sale_ages.append(age)
                self.sale_days.append(day)
                self.sellers[seller] += 1
            else:
                remaining.append(listing)
        self.listings = remaining

    def admin_grants(self, day):
        if not self.admin_remaining:
            return
        limits = self.cfg("rpg-core", "commerce.admin-starlight")
        per_grant = self.shock["admin_starlight_grant"]["per_grant"]
        if per_grant > limits["maximum-per-grant"]:
            self.metrics["admin_oversize_rejections"] += 1
            self.admin_remaining = 0
            return
        while self.admin_remaining:
            amount = min(per_grant, self.admin_remaining)
            if self.admin_daily[day] + amount > limits["daily-limit"] or self.admin_weekly[day // 7] + amount > limits["weekly-limit"]:
                self.metrics["admin_quota_deferrals"] += 1
                break
            pid = self.rng.randrange(len(self.players))
            key = f"admin:{day}:{self.admin_daily[day]}"
            self.ledger.apply(pid, key, day, "admin_starlight", reward={"starlight": amount})
            self.admin_daily[day] += amount
            self.admin_weekly[day // 7] += amount
            self.admin_remaining -= amount

    def season_replay(self):
        if not self.shock.get("season_settlement"):
            return
        shock = self.shock["season_settlement"]
        if shock != {"fail_at": "SETTLING", "replay": True}:
            raise ModelError("Only the explicit SETTLING/replay model is implemented")
        committed = set()
        for pass_id in range(2):
            for index in range(len(self.players)):
                if pass_id == 0 and index == len(self.players) // 2:
                    break  # interruption after a committed prefix
                self.metrics["season_attempts"] += 1
                key = f"launch-1:{index}"
                if key in committed:
                    self.metrics["season_replay_deduplicated"] += 1
                else:
                    committed.add(key)
        self.metrics["season_unique_settlements"] = len(committed)
        # Honor/cursors only. This model never invents a season currency reward.

    def run(self):
        for day in range(self.days):
            active = []
            for pid, player in enumerate(self.players):
                behavior = self.inputs.behaviors["cohorts"][player.cohort]
                weekend = day % 7 >= 5
                probability = behavior["weekend" if weekend else "weekday"]
                concentration = self.shock.get("weekend_concentration", 0)
                if "no_new_content_weeks" in self.shock and day >= 7:
                    # Explicit synthetic retention sensitivity: linear 0..50%
                    # drop over the specified content-free interval, not a forecast.
                    interval = max(1, self.shock["no_new_content_weeks"] * 7)
                    probability *= 1 - self.inputs.behaviors["content_gap_maximum_activity_loss"] * min(1, day / interval)
                if self.rng.random() >= min(1, probability):
                    continue
                hours = float(behavior["hours"])
                if "weekend_concentration" in self.shock and probability > 0:
                    expected_weekly = hours * (5 * behavior["weekday"] + 2 * behavior["weekend"])
                    requested_hours = expected_weekly * (concentration / 2 if weekend else (1 - concentration) / 5) / probability
                    hours = min(24, requested_hours)
                    if requested_hours > 24:
                        self.metrics["play_hours_clamped_player_days"] += 1
                        self.metrics["play_hours_clamped_minutes"] += round((requested_hours - 24) * 60)
                    scale = hours / behavior["hours"] if behavior["hours"] else 0
                    behavior = dict(behavior)
                    for key in ("dungeons", "forge", "refine", "supply"):
                        behavior[key] *= scale
                    for key in ("exit", "list", "convert"):
                        behavior[key] = 1 - (1 - behavior[key]) ** scale
                if hours <= 0:
                    continue
                active.append(pid)
                self.metrics["weekend_play_minutes" if weekend else "weekday_play_minutes"] += round(hours * 60)
                player.minutes += hours * 60
                self.activities(player, pid, day, behavior, hours)
                self.equipment(player, pid, day, behavior)
                self.spending(player, pid, day, behavior)
            self.admin_grants(day)
            self.market(day, active)
            self.stock_history.append(sum(p.balance["gold"] for p in self.players))
            self.active_gold_history.append((len(active), sum(self.players[i].balance["gold"] for i in active)))
        self.season_replay()
        return self.report()

    def report(self):
        closing = Counter()
        for player in self.players:
            if set(player.balance) != set(CURRENCIES):
                raise ModelError("Wallet currencies differ from the authoritative model set")
            for currency, balance in player.balance.items():
                integer(balance, f"Closing wallet {currency}")
            closing.update(player.balance)
        expected = {c: self.openings[c] + self.ledger.inflow[c] - self.ledger.outflow[c] for c in CURRENCIES}
        if any(closing[c] != expected[c] for c in CURRENCIES):
            raise ModelError("Currency conservation failed")
        owned = [item for p in self.players for item in p.equipment] + [l["id"] for l in self.listings]
        if len(owned) != len(set(owned)) or set(owned) & self.exit_ids or set(owned) | self.exit_ids != self.equipment_ids:
            raise ModelError("Equipment identity conservation failed")
        weekly = []
        for start in range(0, self.days, 7):
            end = min(self.days, start + 7)
            faucet = sum(self.ledger.daily[d]["faucet.gold"] for d in range(start, end))
            sink = sum(self.ledger.daily[d]["sink.gold"] for d in range(start, end))
            conversion_in = sum(self.ledger.daily[d]["conversion_in.gold"] for d in range(start, end))
            before = self.openings["gold"] if start == 0 else self.stock_history[start - 1]
            after = self.stock_history[end - 1]
            active_days = sum(self.active_gold_history[d][0] for d in range(start, end))
            weekly.append({"start_day": start + 1, "days": end - start, "gold_growth": (after - before) / 100,
                           "gold_growth_fraction": (after - before) / before if before else None,
                           "gold_net_growth_per_active_player_day": (after - before) / 100 / active_days if active_days else None,
                           "faucet_gold": faucet / 100, "sink_gold": sink / 100,
                           "sink_faucet_ratio": sink / faucet if faucet else None,
                           "conversion_share_of_gold_inflow": conversion_in / (conversion_in + faucet) if conversion_in + faucet else None})
        # Wealth comparison uses the same reference vector as the guard; it is
        # a reporting valuation, not a promise that wallets can redeem that value.
        wealth = [sum(Decimal(p.balance[c]) / (100 if c == "gold" else 1) * self.inputs.reference[c] for c in CURRENCIES) for p in self.players]
        top_count = max(1, math.ceil(len(wealth) * 0.01))
        wealth_total = sum(wealth)
        inventory_ages = [self.days - 1 - l["day"] for l in self.listings]
        last_sales = sum(d >= self.days - 7 for d in self.sale_days)
        last_expired = sum(d >= self.days - 7 for d in self.expiry_days)
        exits = self.metrics["equipment_exited"]
        alerts = ["UNCALIBRATED: synthetic player decisions; no observed seven-day ledger supplied"]
        if weekly and weekly[-1]["sink_faucet_ratio"] is not None and weekly[-1]["sink_faucet_ratio"] < 1:
            alerts.append("MODEL_GOLD_STOCK_GROWTH: modeled sinks are below faucets; inspect omitted sinks and calibrate before tuning")
        if self.metrics["duplicate_attempts"]:
            alerts.append("DUPLICATE_IDENTITY_ATTEMPTS_REJECTED_IN_MODEL")
        if self.shock.get("monopolize_sales") and not self.sellers[self.dominant_seller]:
            alerts.append("SCENARIO_TARGET_UNMET: dominant seller never completed a sale")
        play_minutes = self.metrics["weekend_play_minutes"] + self.metrics["weekday_play_minutes"]
        weekend_share = self.metrics["weekend_play_minutes"] / play_minutes if play_minutes else None
        if self.metrics["play_hours_clamped_player_days"]:
            alerts.append("WEEKEND_HOURS_CLAMPED: the requested redistribution exceeds 24 hours per player day; total hours are reduced")
        if "weekend_concentration" in self.shock and (weekend_share is None or abs(weekend_share - self.shock["weekend_concentration"]) > .03):
            alerts.append("SCENARIO_TARGET_UNMET: observed weekend share differs from target by over 3 percentage points; inspect calendar boundaries, sampled activity and hour clamps")
        return {
            "id": self.scenario["id"], "name": self.scenario.get("name_en", self.scenario["name"]),
            "name_zh": self.scenario["name"], "status": "SIMULATED_UNCALIBRATED",
            "seed": self.seed, "days": self.days, "players": len(self.players),
            "population_mix": dict(Counter(p.cohort for p in self.players)), "shock": self.shock,
            "balances": {"opening": human(self.openings), "closing": human(closing), "conservation": True},
            "flows_integer_units": {k: dict(v) for k, v in sorted(self.ledger.by_source.items())},
            "metrics": {"weekly": weekly, "wealth_gini": gini(wealth),
                        "weekend_play_share": weekend_share,
                        "top_one_percent_share": float(sum(sorted(wealth, reverse=True)[:top_count]) / wealth_total) if wealth_total else 0,
                        "equipment_created": self.metrics["equipment_created"], "equipment_exited": exits,
                        "equipment_create_exit_ratio": self.metrics["equipment_created"] / exits if exits else None,
                        "equipment_identity_conservation": True,
                        "market_sales": len(self.sale_days), "market_expiries": len(self.expiry_days),
                        "market_sale_age_p50_days": percentile(self.sale_ages, .5), "market_sale_age_p90_days": percentile(self.sale_ages, .9),
                        "market_open_age_p50_days": percentile(inventory_ages, .5), "market_open_age_p90_days": percentile(inventory_ages, .9),
                        "market_oldest_open_days": max(inventory_ages, default=None),
                        "market_last_seven_day_sales_expiry_ratio": last_sales / last_expired if last_expired else None,
                        "largest_seller_share": max(self.sellers.values(), default=0) / len(self.sale_days) if self.sale_days else 0},
            "guard_counters": dict(sorted(self.metrics.items())), "alerts": alerts,
            "admin_daily_grants": dict(self.admin_daily), "admin_unissued": self.admin_remaining,
        }


def run_suite(inputs, scenarios, seed, selected=None):
    if not isinstance(scenarios, list) or not scenarios:
        raise ModelError("Scenario suite must be a nonempty list")
    results = []
    ids = set()
    for scenario in scenarios:
        sid = scenario["id"]
        if sid in ids:
            raise ModelError(f"Duplicate scenario ID: {sid}")
        ids.add(sid)
        if selected and sid not in selected:
            continue
        scenario_seed = seed + int(hashlib.sha256(sid.encode()).hexdigest()[:8], 16)
        try:
            simulation = Simulation(inputs, scenario, scenario_seed)
            if scenario.get("expect") == "FAIL_FAST":
                raise ModelError(f"{sid}: expected a policy rejection but initialization succeeded")
            results.append(simulation.run())
        except PolicyViolation as error:
            if scenario.get("expect") != "FAIL_FAST" or not scenario.get("population", {}).get("shock", scenario.get("shock", {})).get("support_credit_to_starlight"):
                raise
            results.append({"id": sid, "name": scenario.get("name_en", scenario["name"]), "name_zh": scenario["name"],
                            "status": "EXPECTED_FAIL_FAST", "reason": str(error), "seed": scenario_seed})
    if selected and set(selected) - ids:
        raise ModelError(f"Unknown selected scenarios: {sorted(set(selected) - ids)}")
    if not results:
        raise ModelError("No scenarios were executed")
    return {"schema_version": 2, "evidence": "M: offline synthetic model only", "calibration_status": "NEEDS_TUNING",
            "model_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "input_sha256": inputs.hashes, "parameters_read": inputs.read_parameters,
            "behavior_assumptions": inputs.behaviors, "seed": seed,
            "conversion_certificate": {"positive_reference_vector": {k: str(v) for k, v in inputs.reference.items()},
                                       "routes": inputs.certificate, "no_positive_value_cycle_under_this_vector": True},
            "scope_limits": [
                "No Paper, JDBC, concurrency, network or actual player behavior is executed here.",
                "Fixed effective mob level; no progression, combat difficulty, energy gates or raid/advancement income model.",
                "Commission stock and forge catalysts/base items are assumed available; account evidence and gold/core costs are enforced.",
                "Forged-item rolls, enhancement outcomes, binding and unbinding are simplified; exits return the minimum one essence.",
                "Market prices are synthetic willingness to pay. Monopoly shock imposes completed-sales concentration by delaying competing sales; volume may fall.",
                "Season replay is an isolated identity/cursor model, not validation of database recovery or a new reward.",
                "Omitted systems can materially change sink/faucet ratios. Do not change live prices or drop rates from this report alone.",
            ], "scenarios": results}
