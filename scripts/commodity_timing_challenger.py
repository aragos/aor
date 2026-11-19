"""Evaluate public score timing as a best-response candidate against the new table."""

import argparse
from pathlib import Path

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_heterogeneous_campaign import lineup
from initiative_opening_strategy import load_games, with_mix


REMOTE = "/home/aragos/aor-worker/commodity-timing-challenger-20261119"
ARMS = {
    "hold-30": {"kind": "commodity-lead-timing-1", "maximumPayout": 30},
    "hold-80": {"kind": "commodity-lead-timing-1", "maximumPayout": 80},
}


def paired(campaign, label, plan, count, pressure):
    stage = f"score-timing-{label}"
    ordinary = with_mix(plan["baseline"], [18])
    roster = lineup({int(offset): policy for offset, policy in plan["rivals"].items()})
    rivals = {"kind": "leading-rival-table-1", "offsets": [1, 2, 3], "policy": plan["retargeting"]}
    campaign.stage(
        stage, dict.fromkeys(ARMS, ordinary), count, ("QUALIFIED",), shard=128,
        baseline=ordinary, lineup=roster, commodity_lead_variants=ARMS,
        leading_rival_tables=dict.fromkeys(("control", *ARMS), rivals) if pressure else None,
    )
    reference = load_games(campaign.root / stage, "control")
    effects = {}
    for name in ARMS:
        rows = load_games(campaign.root / stage, name)
        deltas, changes = [], 0
        for i in range(count):
            original, new = reference[i], rows[i]
            baseline = original["diagnostics"]["CANDIDATE"]
            candidate = new["diagnostics"]["CANDIDATE"]
            if (original["seed"] != new["seed"] or candidate["openingBid"] != baseline["openingBid"]
                    or candidate["openingBid"]["bid"] != 18):
                raise ValueError("Each independent challenger must use the same sealed opening in both arms.")
            expected = set(range(4)) - {new["seat"]} if pressure else set()
            if any(set(diagnostic.get("retargetingRivalSeats", [])) != expected
                   for diagnostic in (candidate, baseline)):
                raise ValueError("The two timing arms must face exactly the same relative policy seats.")
            changes += candidate["chosenPolicyDecisionsSha256"] != baseline["chosenPolicyDecisionsSha256"]
            deltas.append(new["roles"]["CANDIDATE"]["wins"][new["seat"]] -
                          original["roles"]["CANDIDATE"]["wins"][original["seat"]])
        effects[name] = {"changedGames": changes, "winDelta": interval(deltas)}
    return effects


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    baseline = previous / "automata-credible-leader-pressure-20261119"
    ready = read(baseline / "result.json")
    launch = read(previous / "local-preview-18780/launch.json")
    if not ready["passed"] or not launch["healthy"]:
        raise ValueError("This particular challenger must face the qualified human-play candidate.")
    anchor = read(baseline / "plan.json")
    plan = {
        "baseline": anchor["baseline"], "rivals": anchor["rivals"], "retargeting": ready["configuration"],
        "qualificationSha256": sha(baseline / "result.json"), "assetSha256": launch["assetSha256"],
        "settings": ARMS, "worldsPerTable": 768,
    }
    path = root / "plan.json"
    if not path.exists():
        write(path, plan)
    elif read(path) != plan:
        raise ValueError("The score-timing study must retain the exact test policies.")
    count = plan["worldsPerTable"]
    ordinary = paired(campaign, "ordinary", plan, count, pressure=False)
    new = paired(campaign, "targeting", plan, count, pressure=True)
    write(root / "result.json", {
        "worldsPerTable": count, "ordinary": ordinary, "targeting": new,
        "challengeSuccessful": [
            name for name, point in new.items() if point["winDelta"]["lower95"] > 0
        ],
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
