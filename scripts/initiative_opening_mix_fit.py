"""Learn diverse first-turn bids from paired game returns against several rival tables."""

import argparse
from itertools import combinations
import math
from pathlib import Path
import statistics

from initiative_campaign import Campaign, read, sha, write
from initiative_league import rank
from initiative_opening_strategy import OPENINGS, load_games, measure, with_mix


REMOTE = "/home/aragos/aor-worker/opening-mix-fit-20261119"
TABLES = ("mixed-table", "adaptive-table", "familiar")


def paired_rows(root):
    rows = {}
    for table in TABLES:
        folder = root / f"opening-screen-{table}"
        control = load_games(folder, "control")
        arms = {bid: load_games(folder, f"bid-{bid}") for bid in OPENINGS}
        if any(games.keys() != control.keys() for games in arms.values()):
            raise ValueError("Every fitted first-turn bid must have a complete paired table.")
        table_rows = []
        for index, trial in sorted(control.items()):
            seat = trial["seat"]
            reference = trial["diagnostics"]["CANDIDATE"]["openingBid"]
            share = trial["roles"]["CANDIDATE"]["wins"][seat]
            capital_position = reference["capitalPosition"]
            gains = {}
            for bid, games in arms.items():
                game = games[index]
                opening = game["diagnostics"]["CANDIDATE"]["openingBid"]
                if (
                    game["seed"] != trial["seed"] or game["seat"] != seat
                    or opening["capitalPosition"] != capital_position
                    or opening["capital"] != reference["capital"] or opening["bid"] != bid
                ):
                    raise ValueError("A fitted bid needs the same original world and setup.")
                gains[bid] = game["roles"]["CANDIDATE"]["wins"][seat] - share
            table_rows.append((capital_position >= 2, gains))
        rows[table] = table_rows
    return rows


def cautious_mean(values):
    return statistics.mean(values) - .5 * statistics.stdev(values) / math.sqrt(len(values))


def robustness(effects):
    return .7 * min(effects) + .3 * statistics.mean(effects)


def fit(rows):
    options = [bids for size in (2, 3, 4) for bids in combinations(OPENINGS, size)]

    def projected(earlier, later=None):
        return {
            table: cautious_mean([
                statistics.mean(gains[bid] for bid in (later if priority and later else earlier))
                for priority, gains in dataset
            ])
            for table, dataset in rows.items()
        }

    global_trials = {bids: projected(bids) for bids in options}
    global_scores = {bids: robustness(gains.values()) for bids, gains in global_trials.items()}
    best = max(options, key=lambda bids: (global_scores[bids], len(bids)))

    def by_priority(later):
        def score(bids):
            groups = [
                cautious_mean([
                    statistics.mean(gains[bid] for bid in bids)
                    for priority, gains in dataset if priority == later
                ])
                for dataset in rows.values()
            ]
            return .7 * robustness(groups) + .3 * global_scores[bids]
        return max(options, key=lambda bids: (score(bids), len(bids)))

    earlier, later = by_priority(False), by_priority(True)
    choices = {"global": (best, (), global_trials[best])}
    conditional = projected(earlier, later)
    if earlier != later and robustness(conditional.values()) > global_scores[best] + .005:
        choices["capital-priority"] = (earlier, later, conditional)
    return choices


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume, background=True, batch_size=2)
    campaign.validate_runtime()
    source = previous / "automata-opening-strategy-20261119"
    source_report = source / "screen.json"
    live_report = previous / "automata-heterogeneous-20261118/table-fresh.json"
    live = read(live_report)
    if live["passed"] is not True:
        raise ValueError("Fitted opening bids require the independently verified live reference.")
    plan_path = root / "fit.json"
    if resume and plan_path.is_file():
        fitted = read(plan_path)
        if fitted["screenSha256"] != sha(source_report) or fitted["liveSha256"] != sha(live_report):
            raise ValueError("The original fit and live policy must remain fixed.")
    else:
        choices = fit(paired_rows(source))
        plan = dict(read(source / "plan.json"), baseline=live["config"])
        fitted = {
            "screenSha256": sha(source_report), "liveSha256": sha(live_report), "plan": plan,
            "candidates": {name: with_mix(plan["baseline"], early, late)
                           for name, (early, late, _) in choices.items()},
            "developmentEffects": {name: outcomes for name, (_, _, outcomes) in choices.items()},
        }
        write(plan_path, fitted)
    plan = fitted["plan"]
    outcomes = measure(campaign, "opening-mixture-evaluation", fitted["candidates"], 640, plan)
    winner = rank(outcomes)[0]
    passed = all(point["lower95"] > 0 for point in outcomes[winner].values())
    stress = {}
    if passed and campaign.remaining() >= 1800:
        for prior in (17, 21):
            scores = campaign.stage(
                f"opening-mixture-stress-{prior}", {winner: fitted["candidates"][winner]}, 512,
                ("REVEALED_UNDERCUT",), shard=128,
                baseline=plan["baseline"], opening_bid_prior=prior,
            )
            stress[str(prior)] = scores[winner]["outcomes"]["REVEALED_UNDERCUT"]
    write(root / "result.json", {
        "winner": winner, "configuration": fitted["candidates"][winner], "outcomes": outcomes,
        "stress": stress, "passed": passed and len(stress) == 2
        and min(point["lower95"] for point in stress.values()) >= -.025,
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
