"""Test whether leading players can deny attack access before aggressive rivals expand."""

import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_opening_strategy import load_games
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/frontier-defense-20261119"
CANDIDATES = {
    name: {"kind": "lead-frontier-defense-1", "minimumProtectedMarkets": markets}
    for name, markets in (("protect-one", 1), ("protect-two", 2))
}


def evaluate(campaign, label, condition, offsets, variants, count, anchor, policy):
    stage = f"frontier-defense-{label}"
    style, setup, _ = selection_context(anchor, condition, count, label != "develop")
    arms = ("control", *variants)
    campaign.stage(
        stage, dict.fromkeys(variants, anchor["baseline"]), count, (style,), shard=128,
        baseline=anchor["baseline"], lineup=setup["lineup"],
        counter_variants=dict.fromkeys(arms, setup["counter"]) if "counter" in setup else None,
        capital_geography_variants=arms,
        leading_rival_variants=dict.fromkeys(arms, policy),
        rival_targeting_lineups=dict.fromkeys(
            arms, {"kind": "leading-rival-table-1", "policy": policy, "offsets": offsets},
        ),
        frontier_defense_variants=variants,
    )
    data = campaign.root / stage
    before = load_games(data, "control")
    result = {}
    for name in variants:
        changed = load_games(data, name)
        effects, changed_effects = [], []
        for index in range(count):
            a, b = before[index], changed[index]
            first, second = [row["diagnostics"]["CANDIDATE"] for row in (a, b)]
            if (a["seed"], a["seat"], first["openingBid"]) != (
                    b["seed"], b["seat"], second["openingBid"]):
                raise ValueError("Defensive expansion must not affect the initial public setup.")
            seat = a["seat"]
            rivals = sorted((seat + offset) % 4 for offset in offsets)
            if any(sorted(row["retargetingRivalSeats"]) != rivals for row in (first, second)):
                raise ValueError("The same strong opponent policies must challenge the defensive rule.")
            effect = b["roles"]["CANDIDATE"]["wins"][seat] - a["roles"]["CANDIDATE"]["wins"][seat]
            effects.append(effect)
            if first["chosenPolicyDecisionsSha256"] != second["chosenPolicyDecisionsSha256"]:
                changed_effects.append(effect)
            elif effect != 0:
                raise ValueError("An unchanged complete decision trajectory must retain its result.")
        result[name] = {
            "winGain": interval(effects),
            "changedTrajectories": len(changed_effects),
            "changedOnlyWinGain": interval(changed_effects) if changed_effects else None,
        }
    return result


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    source = previous / "automata-capital-evolved-targeting-20261119/result.json"
    newest = read(source)
    if not newest["passed"]:
        raise ValueError("The defensive study requires the independently qualified attack parameters.")
    anchor = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    policy = newest["configuration"]
    plan = {"defenses": CANDIDATES, "deployedAttack": policy, "referenceSha256": sha(source),
            "developWorlds": 512, "freshWorldsPerCase": 768}
    path = root / "plan.json"
    if not path.exists():
        write(path, plan)
    elif read(path) != plan:
        raise ValueError("A resumed defense study must retain its rules and opponent policy.")
    develop = evaluate(campaign, "develop", "mixed", [1, 2, 3], CANDIDATES, 512, anchor, policy)
    choices = [name for name, evidence in develop.items()
               if evidence["changedTrajectories"] >= 12 and evidence["winGain"]["mean"] >= .005]
    chosen = max(choices, key=lambda name: develop[name]["winGain"]["lower95"]) if choices else None
    write(root / "screen.json", {"selected": chosen, "outcomes": develop})
    if not chosen or campaign.remaining() < 2200:
        write(root / "result.json", {"passed": False, "stage": "screen", "selected": chosen,
                                   "outcomes": develop})
        return
    setups = {"three-adopters": ("mixed", [1, 2, 3]), "one-adopter": ("known-16", [1])}
    outcomes = {}
    for name, (condition, offsets) in setups.items():
        outcomes[name] = evaluate(campaign, f"fresh-{name}", condition, offsets,
                                  {chosen: CANDIDATES[chosen]}, 768, anchor, policy)[chosen]
    wins = [evidence["winGain"] for evidence in outcomes.values()]
    passed = (min(row["lower95"] for row in wins) > -.025
              and statistics.mean(row["mean"] for row in wins) >= .012
              and any(row["lower95"] > 0 for row in wins))
    write(root / "result.json", {"passed": passed, "selected": chosen, "outcomes": outcomes})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
