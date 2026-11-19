"""Confirm whether a single two-action route transfers to complete current-policy games."""

import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_opening_strategy import load_games
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/caravan-whole-games-20261119"
WORLDS = 1024
CASES = {"mixed": ("mixed", []), "known-16": ("known-16", [1]),
         "three-adopters": ("mixed", [1, 2, 3])}
ARMS = ("control", "route")


def outcome(stage, offsets):
    before, after = (load_games(stage, arm) for arm in ARMS)
    effects, active = [], []
    followed = 0
    for index in range(WORLDS):
        a, b = before[index], after[index]
        first, second = [row["diagnostics"]["CANDIDATE"] for row in (a, b)]
        if (a["seed"], a["seat"], first["openingBid"]) != (
                b["seed"], b["seat"], second["openingBid"]):
            raise ValueError("Caravan lookahead must preserve all earlier sealed decisions.")
        seat = a["seat"]
        rivals = sorted((seat + offset) % 4 for offset in offsets)
        if any(sorted(row.get("retargetingRivalSeats", [])) != rivals for row in (first, second)):
            raise ValueError("Use the same explicit rivals throughout each paired comparison.")
        delta = b["roles"]["CANDIDATE"]["wins"][seat] - a["roles"]["CANDIDATE"]["wins"][seat]
        effects.append(delta)
        if second["caravanRouteAttempted"]:
            active.append(delta)
            followed += second["caravanRouteFollowedThrough"]
        elif second["caravanRouteFollowedThrough"] or delta:
            raise ValueError("A game with no attempted route must retain its exact win share.")
    return {"worlds": WORLDS, "ownWinGain": interval(effects), "attempted": len(active),
            "followedThrough": followed, "attemptedWinGain": interval(active) if active else None}


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    published = previous / "automata-capital-evolved-targeting-20261119/result.json"
    reference = read(published)
    plans = [previous / f"automata-caravan-{name}-20261119/result.json"
             for name in ("followthrough-v2", "expected-routes")]
    if not reference["passed"] or any(read(source)["all"]["planEffect"]["mean"] <= 0 for source in plans):
        raise ValueError("Both prior route studies must give an independent positive estimate.")
    anchor = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    policy = reference["configuration"]
    plan = {"attackQualificationSha256": sha(published), "worldsPerCase": WORLDS,
            "priorRoutesSha256": [sha(path) for path in plans], "cases": CASES}
    path = root / "plan.json"
    if not path.exists():
        write(path, plan)
    elif read(path) != {**plan, "cases": {name: list(case) for name, case in CASES.items()}}:
        raise ValueError("Retain the original prospective route qualification on resume.")
    scores = {}
    for name, (case, offsets) in CASES.items():
        style, setup, _ = selection_context(anchor, case, WORLDS, True)
        stage = f"single-caravan-route-fresh-{name}"
        rivals = {"kind": "leading-rival-table-1", "policy": policy, "offsets": offsets}
        campaign.stage(
            stage, {"route": anchor["baseline"]}, WORLDS, (style,), shard=128,
            baseline=anchor["baseline"], lineup=setup["lineup"], capital_geography_variants=ARMS,
            counter_variants=dict.fromkeys(ARMS, setup["counter"]) if "counter" in setup else None,
            leading_rival_variants=dict.fromkeys(ARMS, policy),
            rival_targeting_lineups=dict.fromkeys(ARMS, rivals) if offsets else None,
            caravan_route_variants=("route",),
        )
        scores[name] = outcome(root / stage, offsets)
    effects = [row["ownWinGain"] for row in scores.values()]
    passed = (min(row["lower95"] for row in effects) > -.025
              and statistics.mean(row["mean"] for row in effects) >= .01
              and sum(row["lower95"] > 0 for row in effects) >= 2)
    write(root / "result.json", {"passed": passed, "outcomes": scores})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
