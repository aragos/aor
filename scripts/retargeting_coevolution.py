"""Evolve a lead-targeting response while current competitors also target leaders."""

import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, read, sha, write
from initiative_opening_strategy import load_games
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/retargeting-coevolution-20261119"
MUTATIONS = {
    "early-window": {"earliestTurn": 2},
    "broader-lead": {"maximumTrailingPoints": 720},
    "same-stock": {"maximumAdditionalTokens": 0},
    "higher-odds": {"maximumOddsDrop": .12, "maximumAdditionalTokens": 2},
    "commodity-denial": {"requireGreaterCommodityDenial": True, "maximumTrailingPoints": 720},
    "wide-aggression": {"earliestTurn": 2, "maximumTrailingPoints": 720,
                        "maximumAdditionalTokens": 2},
    "protect-expansion-pool": {"maximumTrailingPoints": 240, "maximumAdditionalTokens": 0,
                               "maximumOddsDrop": .15},
    "low-risk-early": {"earliestTurn": 2, "maximumAdditionalTokens": 0,
                       "maximumOddsDrop": .10, "maximumTrailingPoints": 500},
}


def score(campaign, label, case, offsets, worlds, anchor, live, alternatives):
    style, setup, _ = selection_context(anchor, case, worlds, True)
    arms = {"control": live, **alternatives}
    rival = {"kind": "leading-rival-table-1", "policy": live, "offsets": offsets}
    results = campaign.stage(
        f"retargeting-coevolution-{label}", dict.fromkeys(alternatives, anchor["baseline"]),
        worlds, (style,), baseline=anchor["baseline"], shard=128, lineup=setup["lineup"],
        counter_variants=dict.fromkeys(arms, setup["counter"]) if "counter" in setup else None,
        leading_rival_variants=arms, capital_geography_variants=tuple(arms),
        rival_targeting_lineups=dict.fromkeys(arms, rival) if offsets else None,
    )
    stage = campaign.root / f"retargeting-coevolution-{label}"
    master = load_games(stage, "control")
    baseline = {i: row["diagnostics"]["CANDIDATE"] for i, row in master.items()}
    scores = {}
    for name in alternatives:
        tested = load_games(stage, name)
        changed = 0
        for index, row in tested.items():
            seat = master[index]["seat"]
            diagnostic = row["diagnostics"]["CANDIDATE"]
            if (row["seed"], row["seat"], diagnostic["openingBid"]) != (
                    master[index]["seed"], seat, baseline[index]["openingBid"]):
                raise ValueError("A lead-targeting mutation must preserve every sealed opening.")
            expected = sorted((seat + offset) % 4 for offset in offsets)
            if sorted(diagnostic.get("retargetingRivalSeats", [])) != expected:
                raise ValueError("Scored arms must use the same declared adopting rivals.")
            changed += diagnostic["chosenPolicyDecisionsSha256"] != baseline[index]["chosenPolicyDecisionsSha256"]
        scores[name] = {"winGain": results[name]["outcomes"][style], "changedWorlds": changed}
    return scores


def risk(row):
    interval = row["winGain"]
    return .80 * interval["mean"] + .20 * interval["lower95"]


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    live_source = previous / "automata-capital-evolved-targeting-20261119/result.json"
    live = read(live_source)
    if not live["passed"]:
        raise ValueError("Coevolution starts from a qualified live strategy.")
    current = live["configuration"]
    anchor = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    variants = {name: {**current, **changes} for name, changes in MUTATIONS.items()}
    plan = {"referenceSha256": sha(live_source), "reference": current, "variants": variants,
            "firstWorlds": 256, "secondWorlds": 384, "freshWorldsPerCase": 1024}
    path = root / "plan.json"
    if not path.exists():
        write(path, plan)
    elif read(path) != plan:
        raise ValueError("Retain the original live policy and all competing parameter settings.")
    first = score(campaign, "develop-all-adopt", "mixed", [1, 2, 3], 256, anchor, current, variants)
    shortlist = sorted((name for name, row in first.items()
                        if row["changedWorlds"] and row["winGain"]["mean"] >= -.015),
                       key=lambda name: risk(first[name]), reverse=True)[:3]
    write(root / "first.json", {"shortlist": shortlist, "outcomes": first})
    if not shortlist or campaign.remaining() < 3100:
        write(root / "result.json", {"passed": False, "stage": "first"})
        return
    second = score(campaign, "develop-rival-adopts", "known-16", [1], 384, anchor, current,
                   {name: variants[name] for name in shortlist})
    eligible = [name for name in shortlist if second[name]["winGain"]["mean"] >= -.015 and
                statistics.mean(case[name]["winGain"]["mean"] for case in (first, second)) >= .01]
    chosen = max(eligible, key=lambda name: min(risk(case[name]) for case in (first, second))) if eligible else None
    write(root / "screen.json", {"selected": chosen, "outcomes": second})
    if not chosen or campaign.remaining() < 2600:
        write(root / "result.json", {"passed": False, "stage": "screen", "selected": chosen})
        return
    fresh = {name: score(campaign, f"fresh-{name}", case, offsets, 1024, anchor, current,
                         {chosen: variants[chosen]})[chosen]
             for name, case, offsets in (("all-adopt", "mixed", [1, 2, 3]),
                                          ("one-adopts", "known-16", [1]),
                                          ("none-adopt", "mixed", []))}
    intervals = [case["winGain"] for case in fresh.values()]
    passed = (min(row["lower95"] for row in intervals) > -.025
              and statistics.mean(row["mean"] for row in intervals) >= .015
              and sum(row["lower95"] > 0 for row in intervals) >= 2)
    write(root / "result.json", {"passed": passed, "selected": chosen,
                               "configuration": variants[chosen], "outcomes": fresh})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
