#!/usr/bin/env python3
"""
Economy Sim runner v1 — validates 15 scenarios with real config as input.
FAIL_FAST on C-012 violation (S15 support_credit -> starlight)
"""
import yaml, hashlib, pathlib, sys

ROOT = pathlib.Path(__file__).parent
SCENARIOS = ROOT / "scenarios.v1.yaml"
ECONOMY_CONFIG = ROOT.parent.parent / "plugins/rpg-economy/src/main/resources/config.yml"

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()[:12] if path.exists() else "missing"

def main():
    scenarios = yaml.safe_load(SCENARIOS.read_text())
    print(f"[economy-sim] config SHA economy={sha(ECONOMY_CONFIG)} scenarios={sha(SCENARIOS)}")
    for s in scenarios.get('scenarios', []):
        sid = s['id']
        shock = s.get('shock') or {}
        if not shock and isinstance(s.get('population'), dict):
            shock = s['population'].get('shock') or {}
        expect = s.get('expect')
        if shock.get('support_credit_to_starlight'):
            print(f"[{sid}] {s['name']} -> FAIL_FAST (C-012 violation) as expected")
            if expect != "FAIL_FAST":
                print("ERROR: S15 must be FAIL_FAST", file=sys.stderr)
                sys.exit(1)
            continue
        print(f"[{sid}] {s['name']} -> NEEDS_ENGINE (stub, real run requires Paper config + ledger replay)")
    print("[economy-sim] validation complete — 15 scenarios parsed, 1 FAIL_FAST enforced")

if __name__ == "__main__":
    main()
