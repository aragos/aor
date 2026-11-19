"""Test a response to an opponent alternating between an undercut and a higher bid."""

import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, read, sha, write
from initiative_heterogeneous_campaign import lineup
from repeated_undercut_campaign import MODE
from table_bid_adoption import PEERS, WORLDS, compare
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/alternating-bid-rivals-20261119"
CASES = {"ordinary": None, "alternate-16-18": [16, 18], "alternate-17-19": [17, 19]}
MODELS = {"response": MODE, "alternating": dict(MODE, profile="alternating-undercut")}
ARMS = ("control", *MODELS)


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    paths = {
        "response": previous / "automata-repeated-undercut-response-20261119/result.json",
        "attack": previous / "automata-retargeting-coevolution-20261119/result.json",
        "finish": previous / "automata-guarded-finishes-20261119/result.json",
    }
    proofs = {key: read(path) for key, path in paths.items()}
    if not all(record["passed"] for record in proofs.values()):
        raise ValueError("Retain the current qualified attack, finishing and response policies.")
    anchor = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    attack, guard = proofs["attack"]["configuration"], proofs["finish"]["policy"]
    plan = {"qualifications": {key: sha(path) for key, path in paths.items()},
            "worlds": WORLDS, "peers": PEERS, "cases": CASES, "bidPolicies": MODELS,
            "challengerMinimumMean": .02, "ordinaryMinimumLower95": -.02}
    declaration = root / "plan.json"
    if not declaration.exists():
        write(declaration, plan)
    elif read(declaration) != plan:
        raise ValueError("A resumed trial must retain the original opponent and bid policies.")
    results = {}
    for case, pattern in CASES.items():
        style, setup, _ = selection_context(anchor, "known-16" if pattern else "mixed", WORLDS, True)
        if pattern:
            counter = dict(setup["counter"], midgamePriorCycle=[bid + 1 for bid in pattern])
            fixed = lineup(dict.fromkeys(PEERS, anchor["baseline"]))
        else:
            counter = None
            fixed = lineup({1: anchor["rivals"]["1"], **dict.fromkeys(PEERS, anchor["baseline"])})
        label = f"alternating-bid-fresh-{case}"
        campaign.stage(
            label, dict.fromkeys(MODELS, anchor["baseline"]), WORLDS, (style,), shard=128,
            baseline=anchor["baseline"], lineup=fixed, preview_peer_offsets=PEERS,
            leading_rival_variants=dict.fromkeys(ARMS, attack),
            final_advance_variants=dict.fromkeys(ARMS, guard), capital_geography_variants=ARMS,
            phase_bid_variants=MODELS, counter_variants=dict.fromkeys(ARMS, counter) if counter else None,
            rival_targeting_lineups=dict.fromkeys(
                ARMS, {"kind": "leading-rival-table-1", "offsets": [1, *PEERS], "policy": attack},
            ),
        )
        folder = root / label
        results[case] = {
            "currentVersusIncumbent": compare(folder, ("control", "response")),
            "alternatingVersusCurrent": compare(folder, ("response", "alternating")),
        }
        write(root / "outcomes.json", results, replace=True)
    estimates = {case: row["alternatingVersusCurrent"]["automationWinShareEffect"]
                 for case, row in results.items()}
    stress = [estimate for case, estimate in estimates.items() if case != "ordinary"]
    passed = (estimates["ordinary"]["lower95"] > plan["ordinaryMinimumLower95"]
              and min(row["lower95"] for row in stress) > -.03
              and statistics.mean(row["mean"] for row in stress) >= plan["challengerMinimumMean"]
              and any(row["lower95"] > 0.0 for row in stress))
    write(root / "result.json", {"passed": passed, "outcomes": results})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
