"""Measure whether wider lead targeting holds up once several opponents adopt it."""

import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_opening_strategy import load_games
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/evolved-rival-table-20261119"
WORLDS = 768
CASES = ("mixed", "known-16")
ROSTERS = {
    "control": (), "upgrade": (), "one-rival": (1,),
    "three-rivals": (1, 2, 3), "one-mirror": (1,), "full-mirror": (1, 2, 3),
}
WIDER_HERO = ("upgrade", "one-mirror", "full-mirror")
COMPARISONS = {
    "upgradeAgainstOld": ("upgrade", "control"),
    "upgradeAgainstOneAdopter": ("one-mirror", "one-rival"),
    "upgradeAgainstThreeAdopters": ("full-mirror", "three-rivals"),
    "oneAdopterAgainstWiderHero": ("one-mirror", "upgrade"),
    "allAdoptVersusNone": ("full-mirror", "control"),
}


def summarize(folder, worlds):
    data = {arm: load_games(folder, arm) for arm in ROSTERS}
    wins, attacks = {}, {}
    for arm, offsets in ROSTERS.items():
        own_wins, attacks_on_lead = [], []
        for index in range(worlds):
            row, baseline = data[arm][index], data["control"][index]
            diagnostic = row["diagnostics"]["CANDIDATE"]
            old_opening = baseline["diagnostics"]["CANDIDATE"]["openingBid"]
            if (row["seed"], row["seat"], diagnostic["openingBid"]) != (
                    baseline["seed"], baseline["seat"], old_opening):
                raise ValueError("The matched trial must retain its original capital choice and bid.")
            expected = sorted((row["seat"] + offset) % 4 for offset in offsets)
            if sorted(diagnostic.get("retargetingRivalSeats", [])) != expected:
                raise ValueError("Only the declared seats may adopt the wider attack rule.")
            own_wins.append(row["roles"]["CANDIDATE"]["wins"][row["seat"]])
            attacks_on_lead.append(diagnostic["controlledAttacksOnCurrentPublicLeader"])
        wins[arm] = own_wins
        attacks[arm] = statistics.mean(attacks_on_lead)
    return {
        "winRates": {arm: statistics.mean(values) for arm, values in wins.items()},
        "controlledAttacksOnCurrentLeader": attacks,
        "pairedEffects": {name: interval([win - old for win, old in
                                          zip(wins[new], wins[before], strict=True)])
                          for name, (new, before) in COMPARISONS.items()},
    }


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    original = previous / "automata-weighted-opening-with-pressure-20261119/plan.json"
    qualified = previous / "automata-targeting-evolution-20261119/result.json"
    earlier = read(original)
    finalist = read(qualified)
    if not finalist["passed"]:
        raise ValueError("The adopting rivals require the independently verified wider attack rule.")
    newer = finalist["configuration"]
    plan = {"candidate": newer, "reference": earlier["retargeting"], "rosters": ROSTERS,
            "worldsPerCase": WORLDS, "cases": CASES, "originalSha256": sha(original),
            "qualificationSha256": sha(qualified)}
    path = root / "plan.json"
    if not path.exists():
        write(path, plan)
    elif read(path) != {**plan, "rosters": {arm: list(seats) for arm, seats in ROSTERS.items()},
                        "cases": list(CASES)}:
        raise ValueError("A resume must preserve the original table, rules and benchmark worlds.")
    outcomes = {}
    for case in CASES:
        style, setup, _ = selection_context(earlier, case, WORLDS, True)
        stage = f"evolved-table-challenge-{case}"
        campaign.stage(
            stage, dict.fromkeys(list(ROSTERS)[1:], earlier["baseline"]), WORLDS, (style,),
            shard=128, baseline=earlier["baseline"], lineup=setup["lineup"],
            counter_variants=dict.fromkeys(ROSTERS, setup["counter"]) if "counter" in setup else None,
            capital_geography_variants=tuple(ROSTERS),
            leading_rival_variants={arm: newer if arm in WIDER_HERO else earlier["retargeting"]
                                    for arm in ROSTERS},
            rival_targeting_lineups={arm: {"kind": "leading-rival-table-1", "offsets": list(seats),
                                           "policy": newer}
                                     for arm, seats in ROSTERS.items() if seats},
        )
        outcomes[case] = summarize(root / stage, WORLDS)
    write(root / "result.json", {"worldsPerCase": WORLDS, "outcomes": outcomes})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
