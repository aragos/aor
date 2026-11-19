"""Check the interaction of qualified opening and midgame bidding with three automated seats."""

import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_heterogeneous_campaign import lineup
from initiative_opening_strategy import load_games
from repeated_undercut_campaign import MODE
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/combined-bid-study-20261119"
CASES = ("fixed-14", "ordinary", "rolling")
WORLDS = 768
ARMS = ("control", "opening")
PEERS = (2, 3)


def compare(folder):
    control, opening = (load_games(folder, role) for role in ARMS)
    if set(control) != set(opening) or set(control) != set(range(WORLDS)):
        raise ValueError("The combined bidding strategy requires every original paired world.")
    individual, table = [], []
    moves = 0
    for index in range(WORLDS):
        left, right = control[index], opening[index]
        seat = left["seat"]
        if (left["seed"], seat) != (right["seed"], right["seat"]):
            raise ValueError("Both strategies must face identical seats and seed streams.")
        reference, candidate = (row["diagnostics"]["CANDIDATE"]["openingBid"] for row in
                                (left, right))
        if any(reference[key] != candidate[key] for key in ("capital", "capitalPosition", "cash")):
            raise ValueError("An opening token policy needs the same capital auction and cash.")
        moves += reference["bid"] != candidate["bid"]
        before, after = (row["roles"]["CANDIDATE"]["wins"] for row in (left, right))
        individual.append(after[seat] - before[seat])
        challenger = (seat + 1) % 4
        table.append(before[challenger] - after[challenger])
    return {"trackedSeatWinEffect": interval(individual), "automationWinEffect": interval(table),
            "changedOpeningWorlds": moves}


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    paths = {kind: previous / f"automata-{folder}-20261119/result.json" for kind, folder in (
        ("opening", "auction-guarded-opening"), ("response", "repeated-undercut-response"),
        ("attack", "retargeting-coevolution"), ("finish", "guarded-finishes"),
    )}
    qualifications = {kind: read(path) for kind, path in paths.items()}
    if not all(result["passed"] for result in qualifications.values()):
        raise ValueError("The combined study requires all independently qualified components.")
    opening = qualifications["opening"]["plan"]
    candidate = opening["opening"]
    incumbent = opening["fallback"]["policy"]
    attack, guard = qualifications["attack"]["configuration"], qualifications["finish"]["policy"]
    basis = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    plan = {"proofs": {key: sha(path) for key, path in paths.items()},
            "cases": list(CASES), "worlds": WORLDS, "peers": list(PEERS),
            "opening": candidate, "openingFallback": opening["fallback"],
            "meanAutomationEffect": .015, "minimumAutomationLower95": -.025}
    declaration = root / "plan.json"
    if not declaration.exists():
        write(declaration, plan)
    elif read(declaration) != plan:
        raise ValueError("Retain the frozen opponent so that the opening is the only policy change.")
    outcomes = {}
    for case in CASES:
        ordinary = case == "ordinary"
        style, setup, _ = selection_context(basis, "mixed" if ordinary else "known-14", WORLDS, True)
        roster = dict.fromkeys(PEERS, incumbent)
        if ordinary:
            roster[1] = basis["rivals"]["1"]
        counter = setup.get("counter")
        if case == "rolling":
            counter = {"minimumObservations": 2, "lookbackTurns": 4, "openingBidPrior": 17}
        stage = f"combined-bid-fresh-{case}"
        campaign.stage(
            stage, {"opening": candidate}, WORLDS, (style,), shard=128, baseline=incumbent,
            lineup=lineup(roster), preview_peer_offsets=PEERS,
            leading_rival_variants=dict.fromkeys(ARMS, attack),
            final_advance_variants=dict.fromkeys(ARMS, guard), capital_geography_variants=ARMS,
            phase_bid_variants=dict.fromkeys(ARMS, MODE),
            auction_fallback_variants={"opening": opening["fallback"]},
            counter_variants=dict.fromkeys(ARMS, counter) if counter else None,
            series_midgame_priors=dict.fromkeys(ARMS, [15] * WORLDS) if case == "fixed-14" else None,
            rival_targeting_lineups=dict.fromkeys(
                ARMS, {"kind": "leading-rival-table-1", "offsets": [1, *PEERS], "policy": attack},
            ),
        )
        outcomes[case] = compare(root / stage)
        write(root / "outcomes.json", outcomes, replace=True)
    effects = [row["automationWinEffect"] for row in outcomes.values()]
    passed = (min(row["lower95"] for row in effects) > plan["minimumAutomationLower95"]
              and statistics.mean(row["mean"] for row in effects) >= plan["meanAutomationEffect"]
              and any(row["lower95"] > 0 for row in effects))
    write(root / "result.json", {"passed": passed, "outcomes": outcomes})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
