"""Test a finish policy that replaces a pass only from a substantial public lead."""

import argparse
from pathlib import Path
import statistics

from final_advance_campaign import evaluate
from initiative_campaign import Campaign, read, sha, write


REMOTE = "/home/aragos/aor-worker/guarded-finishes-20261119"
CASES = ("mixed", "known-16")
WORLDS = 1536
POLICY = {"kind": "final-advance-plan-1", "margin": 250, "minimumPassLead": 100}


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    aggressor = previous / "automata-retargeting-coevolution-20261119/result.json"
    latest = read(aggressor)
    if not latest["passed"]:
        raise ValueError("The guarded finish trial requires the qualified newest attack strategy.")
    original = previous / "automata-weighted-opening-with-pressure-20261119/plan.json"
    anchor = read(original)
    plan = {"policy": POLICY, "attacks": latest["configuration"], "attackProofSha256": sha(aggressor),
            "schedules": {case: WORLDS for case in CASES}, "winNoninferiorityMargin": .01}
    path = root / "plan.json"
    if not path.exists():
        write(path, plan)
    elif read(path) != plan:
        raise ValueError("A resume must preserve the finishing admission and win noninferiority margin.")
    results = {case: evaluate(campaign, f"guarded-fresh-{case}", case, {"guarded": POLICY}, WORLDS,
                              anchor, latest["configuration"])["guarded"] for case in CASES}
    wins = [row["winEffect"] for row in results.values()]
    passed = (min(row["lower95"] for row in wins) > -.01
              and statistics.mean(row["mean"] for row in wins) >= 0.0
              and all(case["fullSetEffect"]["lower95"] > 0.0 for case in results.values()))
    write(root / "result.json", {"passed": passed, "policy": POLICY, "outcomes": results})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
