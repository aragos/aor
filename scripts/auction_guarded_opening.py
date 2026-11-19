"""Qualify a varied opening bid while preserving the incumbent in expensive capital auctions."""

import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_opening_strategy import load_games
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/auction-guarded-opening-20261119"
CASES = ("mixed", "known-14", "contested-steep")
ARMS = ("control", "protected")
WORLDS = 1024
MINIMUM_PAID_BID = 9


def measure(directory, require_unchanged):
    reference, candidate = (load_games(directory, arm) for arm in ARMS)
    if set(reference) != set(candidate) or set(reference) != set(range(WORLDS)):
        raise ValueError("The opening strategies require the full common world bank.")
    deltas = []
    changed = 0
    for index in range(WORLDS):
        left, right = reference[index], candidate[index]
        before, after = (row["diagnostics"]["CANDIDATE"] for row in (left, right))
        old, new = (details["openingBid"] for details in (before, after))
        if ((left["seed"], left["seat"]) != (right["seed"], right["seat"])
                or any(old[key] != new[key] for key in ("capital", "capitalPosition", "cash"))):
            raise ValueError("A new initial token bid must preserve each paired capital and cash.")
        seat = left["seat"]
        changed += old["bid"] != new["bid"]
        deltas.append(right["roles"]["CANDIDATE"]["wins"][seat]
                      - left["roles"]["CANDIDATE"]["wins"][seat])
        if require_unchanged and (left["roles"] != right["roles"] or old != new
                                  or before["chosenPolicyDecisionsSha256"]
                                  != after["chosenPolicyDecisionsSha256"]):
            raise ValueError("In an expensive capital auction the incumbent should replay exactly.")
    return {"winEffect": interval(deltas), "changedOpeningWorlds": changed,
            "incumbentReplayRequired": require_unchanged}


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    paths = {
        "opening": previous / "automata-opening-repair-20261119/result.json",
        "attack": previous / "automata-retargeting-coevolution-20261119/result.json",
        "finish": previous / "automata-guarded-finishes-20261119/result.json",
        "reference": previous / "automata-weighted-opening-with-pressure-20261119/plan.json",
    }
    opening, attack, finish, anchor = (read(path) for path in paths.values())
    if not attack["passed"] or not finish["passed"]:
        raise ValueError("Both arms require the qualified attack and finish policies.")
    fallback = {"minimumPaidBid": MINIMUM_PAID_BID, "policy": anchor["baseline"]}
    plan = {"sourceSha256": {key: sha(path) for key, path in paths.items()},
            "schedules": dict.fromkeys(CASES, WORLDS), "opening": opening["configuration"],
            "fallback": fallback, "attack": attack["configuration"], "finish": finish["policy"],
            "minimumLower95": -.025, "minimumMean": .015}
    frozen = root / "plan.json"
    if not frozen.exists():
        write(frozen, plan)
    elif read(frozen) != plan:
        raise ValueError("Resume requires the original opponent pool and opening fallback.")
    outcomes = {}
    for case in CASES:
        style, setup, capital = selection_context(anchor, case, WORLDS, True)
        stage = f"auction-guarded-fresh-{case}"
        campaign.stage(
            stage, {"protected": plan["opening"]}, WORLDS, (style,), shard=128,
            baseline=anchor["baseline"], lineup=setup["lineup"],
            counter_variants=dict.fromkeys(ARMS, setup["counter"]) if "counter" in setup else None,
            capital_variants=dict.fromkeys(ARMS, capital) if capital else None,
            leading_rival_variants=dict.fromkeys(ARMS, plan["attack"]),
            final_advance_variants=dict.fromkeys(ARMS, plan["finish"]),
            capital_geography_variants=ARMS, auction_fallback_variants={"protected": fallback},
        )
        outcomes[case] = measure(root / stage, require_unchanged=capital is not None)
        write(root / "outcomes.json", outcomes, replace=True)
    effects = [outcomes[case]["winEffect"] for case in CASES if case != "contested-steep"]
    passed = (min(effect["lower95"] for effect in effects) > plan["minimumLower95"]
              and statistics.mean(effect["mean"] for effect in effects) >= plan["minimumMean"]
              and any(effect["lower95"] > 0.0 for effect in effects))
    write(root / "result.json", {"passed": passed, "plan": plan, "outcomes": outcomes})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
