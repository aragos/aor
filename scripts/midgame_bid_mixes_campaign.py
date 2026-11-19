"""Train and qualify bounded randomization for the highly modal later 18-token bid."""

import argparse
from collections import Counter
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, seed_bank, sha, write
from initiative_heterogeneous_campaign import lineup
from initiative_opening_strategy import load_games
from initiative_series_rivals import GAMES_PER_GROUP
from midgame_rematch_campaign import earlier_games, public_reveals


REMOTE = "/home/aragos/aor-worker/midgame-bid-mixes-20261119"
STYLE = "REVEALED_UNDERCUT"
TURNS = range(3, 8)
DEVELOP = 512
FRESH = 1024
ENVS = ("unprepared", "repeated-games")


def mix(bids, weights, use_order=False):
    return {"kind": "public-midgame-bid-mix-1", "bids": bids, "weights": weights,
            "respectPublicOrder": use_order}


CANDIDATES = {
    "adjacent": mix([17, 18, 19], [1, 2, 1]),
    "two-sided": mix([16, 18, 20], [1, 2, 1]),
    "earlier": mix([15, 16, 18, 20], [1, 2, 4, 3]),
    "larger-pool": mix([16, 18, 20, 21], [2, 4, 3, 1]),
    "public-order": mix([16, 17, 18, 19, 20], [1, 1, 3, 1, 1], use_order=True),
}


def score_pair(before, after, count, priors):
    deltas = {"own": [], "rival": []}
    bids = {"control": Counter(), "candidate": Counter()}
    for index in range(count):
        left, right = before[index], after[index]
        a, b = (row["diagnostics"]["CANDIDATE"]["openingBid"] for row in (left, right))
        if (left["seed"], left["seat"], a) != (right["seed"], right["seat"], b):
            raise ValueError("Midgame exploration must preserve the entire matched opening.")
        seat = left["seat"]
        for name, position in (("own", seat), ("rival", (seat + 1) % 4)):
            wins = lambda row: row["roles"]["CANDIDATE"]["wins"][position]
            deltas[name].append(wins(right) - wins(left))
        for name, row in (("control", left), ("candidate", right)):
            for turn, reveal in public_reveals(row).items():
                if turn not in TURNS:
                    continue
                if reveal["prediction"] != (priors[index] if priors else None):
                    raise ValueError("The opponent must follow the declared previous-game knowledge.")
                bids[name][reveal["actualTargetBid"]] += 1
    groups = lambda values: [statistics.mean(values[i:i + GAMES_PER_GROUP])
                             for i in range(0, len(values), GAMES_PER_GROUP)]
    modes = {name: max(counter.values()) / counter.total() for name, counter in bids.items()}
    return {**{name: interval(groups(values) if priors else values)
               for name, values in deltas.items()},
            "modeShare": modes, "modalShareReduction": modes["control"] - modes["candidate"],
            "midgameBidCounts": dict(bids)}


def match(campaign, label, candidates, count, anchor, prior_games, forecasts):
    roster = lineup({int(key): value for key, value in anchor["rivals"].items() if key != "1"})
    arms = ("control", *candidates)
    outcomes = {name: {} for name in candidates}
    for ecology in ENVS:
        stage = f"midgame-mixtures-{label}-{ecology}"
        experienced = ecology == "repeated-games"
        history = forecasts if experienced else None
        if set(seed_bank(f"{stage}/{STYLE}", count)) & {row["seed"] for row in prior_games}:
            raise ValueError("Older public histories and scored simulation worlds must be disjoint.")
        campaign.stage(
            stage, dict.fromkeys(candidates, anchor["baseline"]), count, (STYLE,), shard=128,
            baseline=anchor["baseline"], lineup=roster, capital_geography_variants=arms,
            leading_rival_variants=dict.fromkeys(arms, anchor["retargeting"]),
            counter_variants=dict.fromkeys(arms, {"minimumObservations": 16, "lookbackTurns": 16}),
            series_midgame_priors=dict.fromkeys(arms, history) if experienced else None,
            midgame_bid_variants=candidates,
        )
        folder = campaign.root / stage
        before = load_games(folder, "control")
        for name in candidates:
            outcomes[name][ecology] = score_pair(before, load_games(folder, name), count, history)
    return outcomes


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    capital = previous / "automata-second-capital-geography-20261119"
    if not read(capital / "result.json")["passed"]:
        raise ValueError("This midgame intervention requires the currently qualified policy.")
    evidence = capital / "capital-geography-fresh-experienced"
    anchor = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    previous_dev, prior_dev = earlier_games(evidence, count=DEVELOP)
    previous_test, prior_test = earlier_games(evidence, start=DEVELOP, count=FRESH)
    plan = {
        "immutableSourceSha256": sha(evidence / "method.json"), "candidates": CANDIDATES,
        "developmentPreviousGames": previous_dev, "freshPreviousGames": previous_test,
        "turns": list(TURNS), "worlds": {"development": DEVELOP, "fresh": FRESH},
    }
    manifest = root / "plan.json"
    if not manifest.exists():
        write(manifest, plan)
    elif read(manifest) != plan:
        raise ValueError("A restart must reuse its candidates and original public history games.")
    screening = match(campaign, "develop", CANDIDATES, DEVELOP, anchor, previous_dev, prior_dev)
    ranked = []
    for name, tables in screening.items():
        effects = [row["own"]["mean"] for row in tables.values()]
        if min(effects) >= -.02 and statistics.mean(effects) >= .005 and all(
            row["modalShareReduction"] >= .10 for row in tables.values()
        ):
            ranked.append((.6 * min(effects) + .4 * statistics.mean(effects), name))
    chosen = max(ranked)[1] if ranked else None
    write(root / "screen.json", {"selected": chosen, "outcomes": screening})
    if chosen is None or campaign.remaining() < 2700:
        write(root / "result.json", {"passed": False, "stage": "screen"})
        return
    fresh = match(campaign, "fresh", {chosen: CANDIDATES[chosen]}, FRESH, anchor,
                  previous_test, prior_test)[chosen]
    stats = [table["own"] for table in fresh.values()]
    write(root / "result.json", {
        "passed": (min(row["lower95"] for row in stats) > -.025
                   and statistics.mean(row["mean"] for row in stats) >= .01
                   and fresh["repeated-games"]["own"]["lower95"] > 0
                   and all(table["modalShareReduction"] >= .10 for table in fresh.values())),
        "selected": chosen, "configuration": CANDIDATES[chosen], "outcomes": fresh,
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
