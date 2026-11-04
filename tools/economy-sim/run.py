#!/usr/bin/env python3
"""Run the 15 offline economy stress scenarios against actual repository configs."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
from engine import Inputs, ModelError, read_yaml, run_suite

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def calibrate(report, path, tolerance):
    """Compare observations; synthetic decisions are not automatically fitted."""
    observed = json.loads(path.read_text(encoding="utf-8"))
    if not observed.get("provenance") or not isinstance(observed.get("scenarios"), dict):
        raise ModelError("Ledger summary needs provenance and a scenarios mapping")
    comparisons = []
    for result in report["scenarios"]:
        actual = observed["scenarios"].get(result["id"])
        if actual is None or "metrics" not in result:
            continue
        if actual.get("days") != result["days"] or actual.get("players") != result["players"]:
            raise ModelError(f"{result['id']}: observation horizon/population differs from the scenario")
        for metric, expected in actual.get("metrics", {}).items():
            if metric not in {"wealth_gini", "top_one_percent_share", "market_sales", "equipment_created", "equipment_exited"}:
                raise ModelError(f"Unsupported calibration metric: {metric}")
            if not isinstance(expected, (int, float)) or isinstance(expected, bool) or not 0 <= expected < float("inf"):
                raise ModelError(f"Invalid observed metric: {metric}")
            modeled = result["metrics"][metric]
            relative = abs(modeled - expected) / abs(expected) if expected else (0 if modeled == 0 else None)
            within = relative is not None and relative <= tolerance
            comparisons.append({"scenario": result["id"], "metric": metric, "observed": expected,
                                "modeled": modeled, "relative_error": relative, "within_tolerance": within})
    if not comparisons:
        raise ModelError("No observations match selected scenarios")
    report["calibration"] = {"provenance": observed["provenance"], "summary_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                             "tolerance": tolerance, "comparisons": comparisons}
    report["calibration_status"] = "OBSERVED_METRICS_WITHIN_TOLERANCE" if all(c["within_tolerance"] for c in comparisons) else "NEEDS_TUNING"
    report["calibration"]["limitation"] = "Comparison only; no fit performed and no runtime or production-readiness claim."


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="Checkout or reviewed configuration snapshot root")
    parser.add_argument("--scenarios", type=Path, default=HERE / "scenarios.v1.yaml")
    parser.add_argument("--behaviors", type=Path, default=HERE / "behaviors.v1.yaml")
    parser.add_argument("--only", nargs="+", help="Selected scenario IDs; default runs all 15")
    parser.add_argument("--seed", type=int)
    parser.add_argument("--output", type=Path, default=ROOT / "build/economy-sim/simulation.json")
    parser.add_argument("--expected-inputs", type=Path, help="Prior report with input_sha256; fail on any input change")
    parser.add_argument("--ledger-summary", type=Path, help="Observed metrics with provenance; never connects to a database")
    args = parser.parse_args(argv)
    try:
        scenarios = read_yaml(args.scenarios)
        inputs = Inputs(args.root.resolve(), args.behaviors)
        inputs.hashes["scenarios"] = hashlib.sha256(args.scenarios.read_bytes()).hexdigest()
        if args.expected_inputs:
            expected = json.loads(args.expected_inputs.read_text())["input_sha256"]
            if expected != inputs.hashes:
                raise ModelError("Input SHA256 mismatch; review changes before comparing runs")
        seed = args.seed if args.seed is not None else scenarios["seed"]
        if isinstance(seed, bool) or not isinstance(seed, int):
            raise ModelError("Seed must be an integer")
        report = run_suite(inputs, scenarios["scenarios"], seed, args.only)
        if args.ledger_summary:
            calibrate(report, args.ledger_summary, float(inputs.behaviors["calibration_relative_tolerance"]))
        encoded = json.dumps(report, indent=2, ensure_ascii=False, sort_keys=True, allow_nan=False) + "\n"
        args.output.parent.mkdir(parents=True, exist_ok=True)
        fd, temporary = tempfile.mkstemp(prefix=".simulation-", dir=args.output.parent)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                stream.write(encoded)
            os.replace(temporary, args.output)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
        for scenario in report["scenarios"]:
            print(f"{scenario['id']}: {scenario['status']}")
        print(f"ECONOMY_MODEL_GATE=PASS scenarios={len(report['scenarios'])}")
        print(f"CALIBRATION={report['calibration_status']}")
        print(f"REPORT={args.output}")
        print("Offline sensitivity evidence only; read scope_limits before interpreting results.")
        return 0
    except (ModelError, OSError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"ECONOMY_MODEL_GATE=FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
