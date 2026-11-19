"""Test whether four previous games expose the live preview's later bidding pattern."""

import argparse
from collections import Counter
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, seed_bank, sha, write
from initiative_opening_strategy import load_games
from initiative_series_rivals import GAMES_PER_GROUP
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/midgame-rematch-20261119"
STAGE = "midgame-rematch-fresh"
WORLDS = 1024
TURNS = range(3, 11)
ARMS = ("control", "learned-midgame")


def public_reveals(row):
    diagnostics = row["diagnostics"]["CANDIDATE"]
    entries = diagnostics["publicTokenCounter"]["decisionProofs"]
    return {entry["turn"]: entry for entry in entries if entry["phase"] == "order-revealed"}


def earlier_games(stage):
    for path in stage.glob("geography-0-*/worlds.jsonl"):
        Campaign.validate_result(path.parent, stage / f"{path.parent.name}.json")
    worlds = load_games(stage, "geography")
    if set(worlds) != set(range(1536)):
        raise ValueError("Repeated-game learning needs the full completed live-policy trial.")
    histories, learned = [], []
    for start in range(0, WORLDS, GAMES_PER_GROUP):
        records = []
        for i in range(start, start + GAMES_PER_GROUP):
            row = worlds[i]
            amounts = {str(turn): entry["actualTargetBid"]
                       for turn, entry in public_reveals(row).items() if turn in TURNS}
            if len(amounts) < 4:
                raise ValueError("Each prior completed game needs at least four public midgame bid reveals.")
            records.append({"seed": row["seed"], "amounts": amounts})
        counts = Counter(bid for game in records for bid in game["amounts"].values())
        forecast = max(counts, key=lambda amount: (counts[amount], amount))
        histories.extend(records)
        learned.extend([forecast] * GAMES_PER_GROUP)
    return histories, learned


def comparison(stage, priors):
    games = {name: load_games(stage, name) for name in ARMS}
    win_differences = {kind: [] for kind in ("target", "rival")}
    performance = {name: [] for name in ARMS}
    for i in range(WORLDS):
        original, countered = (games[name][i] for name in ARMS)
        seat = original["seat"]
        opening = [row["diagnostics"]["CANDIDATE"]["openingBid"]
                   for row in (original, countered)]
        if (original["seed"], original["seat"], opening[0]) != (countered["seed"], countered["seat"], opening[1]):
            raise ValueError("The midgame counter cannot change the sealed opening or capital.")
        a, b = [row["roles"]["CANDIDATE"]["wins"] for row in (original, countered)]
        win_differences["target"].append(b[seat] - a[seat])
        win_differences["rival"].append(b[(seat + 1) % 4] - a[(seat + 1) % 4])
        for name, row in zip(ARMS, (original, countered), strict=True):
            reveals = public_reveals(row)
            for turn, event in reveals.items():
                if turn not in TURNS:
                    continue
                if name == "learned-midgame" and event["prediction"] != priors[i]:
                    raise ValueError("A rematch prediction must equal its four-game precomputed prior.")
                performance[name].append((
                    event["prediction"] == event["actualTargetBid"], event["rivalBeforeTarget"],
                    event["actualTargetBid"], event["actualRivalBid"],
                ))
    return {
        "matchedWinEffects": {kind: interval([
            statistics.mean(values[i:i + GAMES_PER_GROUP])
            for i in range(0, WORLDS, GAMES_PER_GROUP)])
            for kind, values in win_differences.items()},
        "midgameReveals": {
            name: {
                "turns": len(records), "exactPrediction": statistics.mean(row[0] for row in records),
                "rivalEarlier": statistics.mean(row[1] for row in records),
                "targetBidCounts": dict(sorted(Counter(row[2] for row in records).items())),
                "rivalBidCounts": dict(sorted(Counter(row[3] for row in records).items())),
            } for name, records in performance.items()
        },
    }


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    capital = previous / "automata-second-capital-geography-20261119"
    if not read(capital / "result.json")["passed"]:
        raise ValueError("Extract histories only from the exact qualified live policy.")
    historical = capital / "capital-geography-fresh-experienced"
    records, learned = earlier_games(historical)
    anchor = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    style, context, _ = selection_context(anchor, "experienced", WORLDS, True)
    if set(seed_bank(f"{STAGE}/{style}", WORLDS)) & {row["seed"] for row in records}:
        raise ValueError("Four-game prior histories must be disjoint from all fresh rematches.")
    plan = {"historyMethodSha256": sha(historical / "method.json"),
            "earlierGames": records, "frozenMidgamePriors": learned,
            "turns": list(TURNS), "worlds": WORLDS}
    manifest = root / "plan.json"
    if not manifest.exists():
        write(manifest, plan)
    elif read(manifest) != plan:
        raise ValueError("An interrupted repeated-game test must retain its four-game histories.")
    campaign.stage(
        STAGE, {"learned-midgame": anchor["baseline"]}, WORLDS, (style,), shard=128,
        baseline=anchor["baseline"], lineup=context["lineup"],
        counter_variants=dict.fromkeys(ARMS, context["counter"]),
        series_priors=dict.fromkeys(ARMS, context["priors"]),
        series_responses=dict.fromkeys(ARMS, context["responses"]),
        leading_rival_variants=dict.fromkeys(ARMS, anchor["retargeting"]),
        capital_geography_variants=ARMS, series_midgame_priors={"learned-midgame": learned},
    )
    write(root / "result.json", {
        "worlds": WORLDS, "independentHistories": WORLDS // GAMES_PER_GROUP,
        "priorCounts": dict(sorted(Counter(learned).items())),
        **comparison(root / STAGE, learned),
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
