"""Measure turn-position tradeoffs at equal sealed bids and earned expansion pools."""

import argparse
from collections import Counter
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_opening_strategy import load_games
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/phase-aware-turn-order-v2-20261119"
POLICIES = {name: {"kind": "phase-aware-tie-1", "policy": value}
            for name, value in (("control", "ordinary"), ("early", "early"),
                                ("late", "late"), ("phase-aware", "phase-aware"))}


def summarize(stage, arms, worlds):
    rows = {arm: load_games(stage, arm) for arm in arms}
    reference = rows["control"]
    results = {}
    for arm, games in rows.items():
        totals, positions, preferences = Counter(), Counter(), Counter()
        win, affected = [], []
        for index in range(worlds):
            a, b = reference[index], games[index]
            x, y = (row["diagnostics"]["CANDIDATE"] for row in (a, b))
            seat = a["seat"]
            identity = (a["seed"], seat, x["openingBid"], x["tieChoices"]["firstTie"])
            if identity != (b["seed"], b["seat"], y["openingBid"], y["tieChoices"]["firstTie"]):
                raise ValueError("The first affected decision must have identical bids, tokens and tie choices.")
            delta = b["roles"]["CANDIDATE"]["wins"][seat] - a["roles"]["CANDIDATE"]["wins"][seat]
            win.append(delta)
            if x["tieChoices"]["firstTie"]:
                affected.append(delta)
            totals.update(y["orderPhaseEvidence"])
            totals["expansionCards"] += y["controlledExpansionCardsBought"]
            positions.update(y["controlledOrderPositions"])
            preferences.update(y["tieChoices"]["preferences"])
        results[arm] = {
            "winDelta": interval(win), "tiedWorlds": len(affected),
            "tiedWorldWinDelta": interval(affected) if affected else None,
            "tokenCostPerExpansionCard": totals["expansionCardTokens"] / max(1, totals["expansionCards"]),
            "expansionCardsPerGame": totals["expansionCards"] / worlds,
            "markerRetention": totals["retainedMarkers"] / max(1, totals["markerObservations"]),
            "commodityCashPerGame": totals["commodityCardReceipts"] / worlds,
            "orderPositions": dict(positions), "preferences": dict(preferences),
        }
    return results


def evaluate(campaign, label, case, names, worlds, anchor, attack):
    style, setup, _ = selection_context(anchor, case, worlds, True)
    arms = ("control", *names)
    stage = f"phase-order-{label}"
    campaign.stage(
        stage, dict.fromkeys(names, anchor["baseline"]), worlds, (style,), shard=128,
        baseline=anchor["baseline"], lineup=setup["lineup"],
        leading_rival_variants=dict.fromkeys(arms, attack), capital_geography_variants=arms,
        counter_variants=dict.fromkeys(arms, setup["counter"]) if "counter" in setup else None,
        tie_order_variants={name: POLICIES[name] for name in arms},
    )
    return summarize(campaign.root / stage, arms, worlds)


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    certificate = previous / "automata-capital-evolved-targeting-20261119/result.json"
    proof = read(certificate)
    if not proof["passed"]:
        raise ValueError("The turn-position trial starts from the qualified policy.")
    anchor = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    plan = {"policies": POLICIES, "anchorSha256": sha(certificate), "developmentWorlds": 768,
            "freshWorldsPerCase": 1024}
    declaration = root / "plan.json"
    if not declaration.exists():
        write(declaration, plan)
    elif read(declaration) != plan:
        raise ValueError("The resumed analysis must retain the same first tie, policies and seeds.")
    attack = proof["configuration"]
    names = tuple(name for name in POLICIES if name != "control")
    screen = evaluate(campaign, "develop-mixed", "mixed", names, 768, anchor, attack)
    eligible = [name for name in names if screen[name]["winDelta"]["mean"] >= .0075]
    choice = max(eligible, key=lambda name: screen[name]["winDelta"]["lower95"]) if eligible else None
    write(root / "screen.json", {"selected": choice, "outcomes": screen})
    if not choice or campaign.remaining() < 2200:
        write(root / "result.json", {"passed": False, "stage": "screen", "outcomes": screen})
        return
    fresh = {case: evaluate(campaign, f"fresh-{case}", case, (choice,), 1024, anchor, attack)
             for case in ("mixed", "known-16")}
    scores = [comparison[choice]["winDelta"] for comparison in fresh.values()]
    passed = (min(row["lower95"] for row in scores) > -.02
              and statistics.mean(row["mean"] for row in scores) >= .01
              and any(row["lower95"] > 0 for row in scores))
    write(root / "result.json", {"passed": passed, "selected": choice, "outcomes": fresh})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
