"""Evaluate how a rival adapts to each of two opening policies after four observed games."""

import argparse
from collections import Counter
from pathlib import Path
import statistics
import time

from initiative_campaign import Campaign, interval, read, seed_bank, sha, write
from initiative_heterogeneous_campaign import lineup
from initiative_informed_bid_matrix import chronological_rows
from initiative_opening_strategy import load_games
from initiative_series_rivals import GAMES_PER_GROUP, profiles, revealed_openings
from weighted_opening_campaign import BIDS


REMOTE = "/home/aragos/aor-worker/opening-rematch-20261119"
STYLE = "REVEALED_UNDERCUT"
STAGE = "opening-rematch-fresh"
WORLDS = 512
REPLIES = (16, 18, 22)
ARMS = ("control", "reference-modal", "reference-learned",
        "weighted-blind", "weighted-modal", "weighted-learned")


def frozen_payoffs(source, count):
    table, proof, seeds = {}, {}, set()
    for counter in REPLIES:
        stage = source / f"pressure-weighted-develop-known-{counter}"
        method = stage / "method.json"
        seeds.update(read(method)["banks"][STYLE])
        proof[str(counter)] = sha(method)
        table[counter] = {}
        for bid in BIDS:
            arm = f"bid-{bid}"
            for output in stage.glob(f"{arm}-0-*/worlds.jsonl"):
                Campaign.validate_result(output.parent, stage / f"{output.parent.name}.json")
            rows = chronological_rows(stage, arm, counter, bid)
            if len(rows) != count:
                raise ValueError("A learned reply needs every matched payoff observation.")
            grouped = {}
            for later in (False, True):
                matching = [row for row in rows if row["later"] == later]
                if len(matching) < 64:
                    raise ValueError("Each capital group needs its own observed payoff estimates.")
                utility = lambda row: row["rival"] - .15 * row["own"]
                grouped[later] = .7 * statistics.mean(map(utility, matching)) + .3 * statistics.mean(
                    map(utility, rows))
            table[counter][bid] = grouped
    return table, proof, seeds


def responses(history, table):
    selected = []
    for index in range(0, len(history), GAMES_PER_GROUP):
        earlier = history[index:index + GAMES_PER_GROUP]
        if len(earlier) != GAMES_PER_GROUP or any(row["bid"] not in BIDS for row in earlier):
            raise ValueError("The adapting rival must use four complete comparable histories.")
        all_bids = Counter(row["bid"] for row in earlier)
        choices = {}
        for later, label in ((False, "earlyBid"), (True, "lateBid")):
            by_capital = Counter(row["bid"] for row in earlier if row["laterCapitalChoice"] == later)
            weights = {bid: .25 + .5 * all_bids[bid] + by_capital[bid] for bid in BIDS}
            choices[label] = max(
                REPLIES, key=lambda rival: sum(weights[bid] * table[rival][bid][later] for bid in BIDS),
            )
        selected.extend([choices] * GAMES_PER_GROUP)
    return selected


def read_result(stage, name, priors, replies):
    outcomes = {}
    rows = load_games(stage, name)
    if set(rows) != set(range(WORLDS)):
        raise ValueError("Each rematch needs its full independent seed bank.")
    for index, row in rows.items():
        diag = row["diagnostics"]["CANDIDATE"]
        first = diag["openingBid"]
        group = "lateBid" if first["capitalPosition"] >= 2 else "earlyBid"
        proof = next(item for item in diag["publicTokenCounter"]["decisionProofs"]
                     if item["turn"] == 1 and item["phase"] == "order-revealed")
        predicted = priors[index][group] if priors else None
        if (first["bid"] != proof["actualTargetBid"] or proof["prediction"] != predicted
                or replies and replies[index][group] != proof["actualRivalBid"]):
            raise ValueError("The rematch must reproduce the fixed earlier-game forecast and reply.")
        seat = row["seat"]
        wins = row["roles"]["CANDIDATE"]["wins"]
        outcomes[index] = {
            "own": wins[seat], "rival": wins[(seat + 1) % 4],
            "bid": first["bid"], "rivalBid": proof["actualRivalBid"],
            "predictionCorrect": predicted == first["bid"],
            "setup": (row["seed"], seat, first["capital"], first["capitalPosition"], first["cash"]),
        }
    return outcomes


def difference(after, before, field):
    if any(after[i]["setup"] != before[i]["setup"] for i in range(WORLDS)):
        raise ValueError("Only opening choices and their effects may differ in the rematch.")
    pairs = [after[i][field] - before[i][field] for i in range(WORLDS)]
    return interval([statistics.mean(pairs[i:i + GAMES_PER_GROUP])
                     for i in range(0, WORLDS, GAMES_PER_GROUP)])


def main(root, previous, resume, wait):
    campaign = Campaign(root, remote=REMOTE, batch_size=2, background=True, resume=resume)
    campaign.validate_runtime()
    weighted = previous / "automata-weighted-opening-with-pressure-20261119"
    prerequisite = weighted / "result.json"
    while wait and not prerequisite.is_file() and campaign.remaining() > 3000:
        time.sleep(min(30, max(1, campaign.remaining() - 3000)))
    if not prerequisite.is_file():
        write(root / "result.json", {"status": "no-qualified-input-during-window"})
        return
    qualified = read(prerequisite)
    if not qualified["passed"] or campaign.remaining() < 3000:
        write(root / "result.json", {"status": "no-qualified-opening-during-window"})
        return
    anchor = read(weighted / "plan.json")
    history_stage = weighted / "pressure-weighted-fresh-experienced"
    previous_games = {
        name: revealed_openings(history_stage, role, anchor["freshWorlds"])
        for name, role in (("reference", "control"), ("weighted", qualified["selected"]))
    }
    table, sources, payoff_seeds = frozen_payoffs(weighted, anchor["developmentWorlds"])
    fresh = set(seed_bank(f"{STAGE}/{STYLE}", WORLDS))
    if (fresh & payoff_seeds or any(fresh & {row["seed"] for row in records}
                                   for records in previous_games.values())):
        raise ValueError("Rematch games must be independent of the payoff and public history games.")
    prior, reply = {}, {}
    for name, records in previous_games.items():
        forecast = profiles(records)["capital-modal"]
        prior[f"{name}-modal"] = forecast
        prior[f"{name}-learned"] = forecast
        reply[f"{name}-learned"] = responses(records, table)
    plan = {
        "openingQualificationSha256": sha(prerequisite),
        "historyMethodSha256": sha(history_stage / "method.json"),
        "payoffMethodSha256": sources, "priorGames": previous_games,
        "sealedPredictions": prior, "sealedReplies": reply,
        "retargeting": anchor["retargeting"], "worlds": WORLDS,
    }
    manifest = root / "plan.json"
    if not manifest.exists():
        write(manifest, plan)
    elif read(manifest) != plan:
        raise ValueError("A rematch must preserve its earlier games and predeclared responses.")
    rival = lineup({int(offset): policy for offset, policy in anchor["rivals"].items() if offset != "1"})
    policies = {name: qualified["configuration"] if name.startswith("weighted") else anchor["baseline"]
                for name in ARMS[1:]}
    campaign.stage(
        STAGE, policies, WORLDS, (STYLE,), shard=128, baseline=anchor["baseline"],
        lineup=rival, counter_variants=dict.fromkeys(ARMS, {"minimumObservations": 1, "lookbackTurns": 1}),
        series_priors=prior, series_responses=reply,
        leading_rival_variants=dict.fromkeys(ARMS, anchor["retargeting"]),
    )
    games = {name: read_result(root / STAGE, name, prior.get(name), reply.get(name)) for name in ARMS}
    changed = {
        "versus-blind": ("weighted-blind", "control"),
        "versus-modal": ("weighted-modal", "reference-modal"),
        "versus-learned": ("weighted-learned", "reference-learned"),
        "reference-learning-effect": ("reference-learned", "control"),
        "weighted-learning-effect": ("weighted-learned", "weighted-blind"),
    }
    outcomes = {name: {key: difference(games[a], games[b], key) for key in ("own", "rival")}
                for name, (a, b) in changed.items()}
    write(root / "result.json", {
        "status": "complete", "worlds": WORLDS, "independentHistories": WORLDS // GAMES_PER_GROUP,
        "outcomes": outcomes,
        "openingBidCounts": {name: dict(sorted(Counter(row["bid"] for row in rows.values()).items()))
                             for name, rows in games.items()},
        "predictionAccuracy": {name: statistics.mean(row["predictionCorrect"] for row in games[name].values())
                               for name in prior},
        "passed": outcomes["versus-learned"]["own"]["lower95"] > 0,
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--wait", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume, args.wait)
