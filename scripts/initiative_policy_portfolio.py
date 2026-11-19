"""Fit and re-evaluate a mixture over complete opening and expansion policies."""

import argparse
from itertools import combinations
import json
from pathlib import Path
import random
import statistics

from capital_auction_league import setup
from initiative_campaign import Campaign, interval, read, write
from initiative_heterogeneous_campaign import lineup, trained_counter
from initiative_opening_strategy import load_games


REMOTE = "/home/aragos/aor-worker/initiative-portfolio-20261119"
CASES = (
    ("mixed-table", "QUALIFIED", [], False),
    ("trained-counter", "REVEALED_UNDERCUT", [], True),
    ("familiar-counter", "REVEALED_UNDERCUT", [], False),
    ("contested-capitals", "QUALIFIED", [1, 4, 7], False),
    ("prepared-table", "REVEALED_UNDERCUT", [5, 1, 3], True),
)
WEIGHT_UNITS = 8


def strategy(initiative, bids=()):
    return {"initiative": initiative, "capitalBids": list(bids)}


def frozen_plan(previous):
    live = read(previous / "automata-heterogeneous-20261118/table-fresh.json")
    if live["passed"] is not True:
        raise ValueError("Portfolio fitting requires the fresh mixed-table preview.")
    control = strategy(live["config"])
    league = read(previous / "automata-extended-20261118/league-confirm.json")
    roster = read(previous / "automata-heterogeneous-20261118/roster.json")
    former = read(previous / "automata-initiative-20261118/result.json")["comparison"]["config"]
    capital = read(previous / "automata-capital-auction-20261118/result.json")["bid"]
    proposals = {
        "former": strategy(former),
        "conservative": strategy(league["configs"][league["ranking"][0]]),
        "capital": strategy(live["config"], [capital]),
    }
    response = read(previous / "automata-initiative-crossplay-20261118/response-check/summary.json")
    attack = next(iter(response.values()))
    if attack["outcomes"]["QUALIFIED"]["lower95"] > 0:
        proposals["aggressive"] = strategy(attack["config"])
    opening_path = previous / "automata-opening-mix-fit-20261119/result.json"
    if not opening_path.is_file():
        opening_path = previous / "automata-opening-strategy-20261119/result.json"
    if opening_path.is_file():
        opening = read(opening_path)
        if "configuration" in opening:
            proposals["fitted-opening"] = strategy(opening["configuration"])
            proposals["capital-and-opening"] = strategy(opening["configuration"], [capital])
    capital_path = previous / "automata-capital-auction-league-20261119/result.json"
    if capital_path.is_file():
        fitted = read(capital_path)
        if fitted.get("bids"):
            proposals["contested-capital"] = strategy(live["config"], fitted["bids"])
    seen = {json.dumps(control, sort_keys=True)}
    policies = {}
    for name, proposal in proposals.items():
        identity = json.dumps(proposal, sort_keys=True)
        if identity not in seen:
            policies[name] = proposal
            seen.add(identity)
    return {"control": control, "policies": policies, "rivals": roster["rivals"],
            "counter": trained_counter(previous, roster["counter"])}


def tournament(campaign, label, programs, count, plan):
    effects = {}
    roster = {int(offset): policy for offset, policy in plan["rivals"].items()}
    for case, style, rival_bids, use_roster in CASES:
        stage = f"{label}-{case}"
        is_counter = style == "REVEALED_UNDERCUT"
        counter = plan["counter"] if use_roster else {"openingBidPrior": 19}
        fixed = {offset: policy for offset, policy in roster.items()
                 if not is_counter or use_roster and offset != 1}
        names = {"control": plan["control"], **programs}
        capital = {name: setup(policy["capitalBids"], rival_bids) for name, policy in names.items()}
        campaign.stage(
            stage, {name: policy["initiative"] for name, policy in programs.items()}, count, (style,),
            baseline=plan["control"]["initiative"], shard=128,
            lineup=lineup(fixed) if fixed else None,
            counter_variants=dict.fromkeys(names, counter) if is_counter else None,
            capital_variants=capital,
        )
        folder = campaign.root / stage
        control = load_games(folder, "control")
        identities = [(row["index"], row["seed"], row["seat"]) for _, row in sorted(control.items())]
        observed = {}
        for name in names:
            rows = [row for _, row in sorted(load_games(folder, name).items())]
            if [(row["index"], row["seed"], row["seat"]) for row in rows] != identities:
                raise ValueError("Every program must have exactly the same world and seat bank.")
            observed[name] = [row["roles"]["CANDIDATE"]["wins"][row["seat"]] for row in rows]
        effects[case] = observed
    return effects


def robust_score(table_means):
    return .75 * min(table_means) + .25 * statistics.mean(table_means)


def divisions(total, size):
    if size == 1:
        yield (total,)
        return
    for first in range(1, total - size + 2):
        for tail in divisions(total - first, size - 1):
            yield (first, *tail)


def fit(cases):
    names = sorted(next(iter(cases.values())))
    if len(names) < 2:
        raise ValueError("Portfolio fitting requires at least two distinct strategies.")
    means = {case: {name: statistics.mean(values) for name, values in results.items()}
             for case, results in cases.items()}

    def projected(weights):
        return robust_score([
            sum(value * outcomes[name] / WEIGHT_UNITS for name, value in weights.items())
            for outcomes in means.values()
        ])

    pure = max(names, key=lambda name: projected({name: WEIGHT_UNITS}))
    options = (
        dict(zip(policies, units, strict=True))
        for size in (2, 3) for policies in combinations(names, size)
        for units in divisions(WEIGHT_UNITS, size)
    )
    weight = max(options, key=projected)
    return pure, weight, projected({pure: WEIGHT_UNITS}), projected(weight)


def fresh_comparison(cases, pure, mixture):
    report = {}
    for case, results in cases.items():
        values = [results[key] for key in mixture]
        expected = [sum(outcome * units / WEIGHT_UNITS
                        for outcome, units in zip(arms, mixture.values(), strict=True))
                    for arms in zip(*values, strict=True)]
        rng = random.Random(f"aor-initiative-portfolio-draw-v1:{case}")
        picks = rng.choices(list(mixture), weights=list(mixture.values()), k=len(expected))
        realized = [results[name][index] for index, name in enumerate(picks)]
        control = results["control"]
        reference = results[pure]
        report[case] = {
            "expectedVersusPreview": interval([a - b for a, b in zip(expected, control, strict=True)]),
            "expectedVersusBestSingle": interval([a - b for a, b in zip(expected, reference, strict=True)]),
            "sampledVersusPreview": interval([a - b for a, b in zip(realized, control, strict=True)]),
            "expectedWinShare": statistics.mean(expected),
        }
    return report


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume, batch_size=2)
    campaign.validate_runtime()
    plan_path = root / "plan.json"
    if resume and plan_path.is_file():
        plan = read(plan_path)
    else:
        plan = frozen_plan(previous)
        write(plan_path, plan)
    screen = tournament(campaign, "portfolio-screen", plan["policies"], 192, plan)
    pure, weights, pure_score, portfolio_score = fit(screen)
    fitter = {"bestSingle": pure, "weights": weights, "bestSingleScore": pure_score,
              "portfolioScore": portfolio_score, "unitCount": WEIGHT_UNITS}
    fit_path = root / "fit.json"
    if not fit_path.is_file():
        write(fit_path, fitter)
    elif read(fit_path) != fitter:
        raise ValueError("The portfolio fit must reproduce from the validated screening games.")
    if portfolio_score < pure_score + .0125 or campaign.remaining() < 4800:
        write(root / "result.json", {"status": "screen-complete", **fitter})
        return
    names = set(weights) | {pure}
    nominees = {name: policy for name, policy in plan["policies"].items() if name in names}
    holdout = tournament(campaign, "portfolio-fresh", nominees, 640, plan)
    fresh = fresh_comparison(holdout, pure, weights)
    advance = all(row["expectedVersusPreview"]["lower95"] > 0 for row in fresh.values())
    minimum_single = min(row["expectedVersusBestSingle"]["lower95"] for row in fresh.values())
    write(root / "result.json", {"status": "fresh-complete", **fitter, "fresh": fresh,
                                 "passed": advance and minimum_single > -.025})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
