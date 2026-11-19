"""Fit bids to the upcoming card and expansion phases at varied public tables."""

import argparse
from collections import Counter
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_opening_strategy import load_games
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/phase-aware-bids-20261119"
VARIANTS = {name: {"kind": "phase-aware-bids-1", "profile": name}
            for name in ("balanced", "wide", "cash-conscious")}
TABLES = ("mixed", "fixed-17")


def score(root, name, worlds):
    reference, candidate = (load_games(root, arm) for arm in ("control", name))
    own, rival = [], []
    intents, actual_bids = Counter(), Counter()
    changed_worlds = 0
    for index in range(worlds):
        left, right = reference[index], candidate[index]
        a, b = [row["diagnostics"]["CANDIDATE"] for row in (left, right)]
        seat = left["seat"]
        if (left["seed"], seat, a["openingBid"]) != (right["seed"], right["seat"], b["openingBid"]):
            raise ValueError("Phase-based later bids must preserve the opening and matched worlds.")
        base, changed = [row["roles"]["CANDIDATE"]["wins"] for row in (left, right)]
        own.append(changed[seat] - base[seat])
        rival.append(changed[(seat + 1) % 4] - base[(seat + 1) % 4])
        decisions = b["phaseAwareBidDecisions"]
        switched = [decision for decision in decisions if decision["chosen"] != decision["ordinary"]]
        if switched:
            changed_worlds += 1
            for decision in switched:
                if not 3 <= decision["turn"] <= 10:
                    raise ValueError("Opening bids cannot receive a later-turn phase adjustment.")
                intents[decision["intent"]] += 1
                actual_bids[decision["chosen"]] += 1
        elif own[-1] or rival[-1]:
            raise ValueError("Unchanged bid decisions must retain matched wins.")
    return {"own": interval(own), "leftRival": interval(rival), "changedWorlds": changed_worlds,
            "decisionIntents": dict(intents), "newBids": dict(actual_bids)}


def compare(campaign, label, count, variants, anchor, attack):
    arms = ("control", *variants)
    result = {name: {} for name in variants}
    for table in TABLES:
        prepared = table == "fixed-17"
        style, setup, _ = selection_context(anchor, "known-16" if prepared else table, count, True)
        stage = f"phase-bids-{label}-{table}"
        campaign.stage(
            stage, dict.fromkeys(variants, anchor["baseline"]), count, (style,), shard=128,
            baseline=anchor["baseline"], lineup=setup["lineup"], capital_geography_variants=arms,
            leading_rival_variants=dict.fromkeys(arms, attack), phase_bid_variants=variants,
            counter_variants=dict.fromkeys(arms, setup["counter"]) if prepared else None,
            series_midgame_priors=dict.fromkeys(arms, [18] * count) if prepared else None,
            rival_targeting_lineups=dict.fromkeys(
                arms, {"kind": "leading-rival-table-1", "offsets": [1], "policy": attack},
            ) if prepared else None,
        )
        for name in variants:
            result[name][table] = score(campaign.root / stage, name, count)
    return result


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    anchor = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    source = previous / "automata-capital-evolved-targeting-20261119/result.json"
    live = read(source)
    if not live["passed"]:
        raise ValueError("Use the independently qualified attack strategy throughout this study.")
    attack = live["configuration"]
    declaration = root / "plan.json"
    plan = {"originalSha256": sha(source), "variants": VARIANTS, "develop": 512, "fresh": 768}
    if not declaration.exists():
        write(declaration, plan)
    elif read(declaration) != plan:
        raise ValueError("A resume must keep the original economic and turn-order policies.")
    screen = compare(campaign, "develop", 512, VARIANTS, anchor, attack)
    ranked = []
    for name, tables in screen.items():
        gains = [table["own"]["mean"] for table in tables.values()]
        mean = statistics.mean(gains)
        if min(gains) >= -.02 and mean >= .01:
            ranked.append((.6 * min(gains) + .4 * mean, name))
    choice = max(ranked)[1] if ranked else None
    write(root / "screen.json", {"selected": choice, "outcomes": screen})
    if not choice or campaign.remaining() < 2200:
        write(root / "result.json", {"passed": False, "stage": "screen", "selected": choice})
        return
    fresh = compare(campaign, "fresh", 768, {choice: VARIANTS[choice]}, anchor, attack)[choice]
    effects = [table["own"] for table in fresh.values()]
    passed = (min(row["lower95"] for row in effects) > -.025
              and statistics.mean(row["mean"] for row in effects) >= .01
              and any(row["lower95"] > 0 for row in effects))
    write(root / "result.json", {"passed": passed, "selected": choice, "outcomes": fresh})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
