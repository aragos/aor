"""Train bid weights against a range of ordinary and informed opening policies."""

import argparse
from collections import Counter
import math
from pathlib import Path
import statistics

from capital_auction_league import setup
from initiative_campaign import Campaign, interval, read, sha, write
from initiative_heterogeneous_campaign import lineup
from initiative_informed_bid_matrix import config as fixed_counter
from initiative_opening_strategy import load_games, with_mix
from initiative_payoff_challenger import trained_responses
from initiative_series_rivals import GAMES_PER_GROUP, profiles


REMOTE = "/home/aragos/aor-worker/weighted-opening-20261119"
PRESSURE_REMOTE = "/home/aragos/aor-worker/weighted-opening-with-pressure-20261119"
BIDS = tuple(range(15, 23))
DEVELOP = ("mixed", "known-16", "known-18", "known-22", "experienced")
FRESH = ("mixed", "known-14", "known-20", "experienced", "contested")
RETAIN = .35
PRESSURE_BID_LIMIT = .32


def selection_context(plan, condition, worlds, fresh):
    roster = {int(offset): policy for offset, policy in plan["rivals"].items()}
    learning = condition == "experienced"
    stress = condition.startswith("known-")
    if not learning and not stress:
        auction = {"contested": [1, 4, 7], "contested-steep": [3, 5, 9]}.get(condition)
        capital = setup([], auction) if auction else None
        return "QUALIFIED", {"lineup": lineup(roster)}, capital
    surviving = lineup({offset: policy for offset, policy in roster.items() if offset != 1})
    if stress:
        amount = int(condition.removeprefix("known-"))
        return "REVEALED_UNDERCUT", {"lineup": surviving, "counter": fixed_counter(amount)}, None
    history = plan["history"]
    midpoint = len(history) // 2
    fold = history[midpoint:] if fresh else history[:midpoint]
    if not fold or len(fold) % GAMES_PER_GROUP or worlds % GAMES_PER_GROUP:
        raise ValueError("Each experienced rival needs four completed public games per group.")
    prior = profiles(fold)["capital-modal"]
    replies = trained_responses(fold, plan["payoff"])["expected-win"]
    repeats = (worlds + len(fold) - 1) // len(fold)
    return "REVEALED_UNDERCUT", {
        "lineup": surviving, "priors": (prior * repeats)[:worlds],
        "responses": (replies * repeats)[:worlds],
        "counter": {"minimumObservations": 1, "lookbackTurns": 1},
    }, None


def tournament(campaign, plan, names, condition, worlds, fresh=False):
    style, context, capital = selection_context(plan, condition, worlds, fresh)
    phase = "fresh" if fresh else "develop"
    prefix = "pressure-weighted" if "retargeting" in plan else "weighted"
    label = f"{prefix}-{phase}-{condition}"
    arms = ("control", *names)
    summary = campaign.stage(
        label, names, worlds, (style,), shard=128, baseline=plan["baseline"],
        lineup=context["lineup"],
        counter_variants=dict.fromkeys(arms, context["counter"]) if "counter" in context else None,
        capital_variants=dict.fromkeys(arms, capital) if capital else None,
        series_priors=dict.fromkeys(arms, context["priors"]) if "priors" in context else None,
        series_responses=dict.fromkeys(arms, context["responses"]) if "responses" in context else None,
        leading_rival_variants=dict.fromkeys(arms, plan["retargeting"]) if "retargeting" in plan else None,
    )
    games = {name: load_games(campaign.root / label, name) for name in arms}
    common = []
    for index in range(worlds):
        outcomes, bids, rival_bids = {}, {}, {}
        reference = games["control"][index]
        opening = reference["diagnostics"]["CANDIDATE"]["openingBid"]
        identity = reference["seed"], reference["seat"], opening["capital"], opening["capitalPosition"]
        for name, rows in games.items():
            row = rows[index]
            diagnostic = row["diagnostics"]["CANDIDATE"]
            entry = diagnostic["openingBid"]
            if identity != (row["seed"], row["seat"], entry["capital"], entry["capitalPosition"]):
                raise ValueError("Bid treatments must retain the paired seed, seat and capital selection.")
            if name.startswith("bid-") and entry["bid"] != int(name.removeprefix("bid-")):
                raise ValueError("Every fixed amount must be offered and played in the training bank.")
            if "counter" in context:
                proof = next(item for item in diagnostic["publicTokenCounter"]["decisionProofs"]
                             if item["turn"] == 1 and item["phase"] == "order-revealed")
                group = "lateBid" if identity[-1] >= 2 else "earlyBid"
                expected = (context["responses"][index][group] if "responses" in context
                            else context["counter"]["openingBidPrior"] - 1)
                if proof["actualRivalBid"] != expected:
                    raise ValueError("A sealed rival must use only its prescribed eligible bid.")
                rival_bids[name] = proof["actualRivalBid"]
            bids[name] = entry["bid"]
            values = row["roles"]["CANDIDATE"]["wins"]
            outcomes[name] = values[row["seat"]], values[(row["seat"] + 1) % 4]
        common.append({
            "later": identity[-1] >= 2, "outcomes": outcomes, "bids": bids,
            "rivalBids": rival_bids,
        })
    return common, {name: row["outcomes"][style] for name, row in summary.items()}


def coefficients(cases, rival_penalty):
    results = {}
    for condition, rows in cases.items():
        vectors = []
        for later in (False, True):
            subset = [row["outcomes"] for row in rows if row["later"] == later]
            if len(subset) < 24:
                raise ValueError("Both capital groups need at least 24 paired observations.")
            vectors.append([
                sum(own - baseline - rival_penalty * (rival - base_rival)
                    for result in subset
                    for own, rival in (result[f"bid-{bid}"],)
                    for baseline, base_rival in (result["control"],)) / len(rows)
                for bid in BIDS
            ])
        results[condition] = vectors
    return results


def normalize(log_weights):
    peak = max(log_weights)
    mass = [math.exp(value - peak) for value in log_weights]
    total = sum(mass)
    return [value / total for value in mass]


def projected(vectors, probabilities):
    return sum(coefficient * amount for vector, distribution in zip(
        vectors, probabilities, strict=True,
    ) for coefficient, amount in zip(vector, distribution, strict=True))


def bounded_distribution(log_weights, cap):
    proportions = normalize(log_weights)
    if cap is None:
        return proportions
    result = [0.0] * len(proportions)
    remaining = 1.0
    pending = sorted(range(len(proportions)), key=lambda i: proportions[i], reverse=True)
    for i, choice in enumerate(pending):
        available = sum(proportions[key] for key in pending[i:])
        result[choice] = min(cap, remaining * proportions[choice] / available)
        remaining -= result[choice]
    if abs(remaining) > 1e-9:
        raise ValueError("The maximum per-bid mass must permit a normalized legal distribution.")
    return result


def fit(cases, regularization, cap=None):
    """Smooth robust optimization with a stated uniform prior over legal candidate amounts."""
    effects = tuple(coefficients(cases, .15).values())
    weights = [[0.0] * len(BIDS) for _ in range(2)]
    soft_minimum = .025
    for _ in range(240):
        distributions = [bounded_distribution(group, cap) for group in weights]
        risk = normalize([-projected(effect, distributions) / soft_minimum for effect in effects])
        case_masses = [.75 * chance + .25 / len(effects) for chance in risk]
        for group, logits in enumerate(weights):
            for i in range(len(logits)):
                gradient = sum(mass * effect[group][i] for mass, effect in
                               zip(case_masses, effects, strict=True))
                logits[i] += 1.6 * (gradient - regularization * logits[i])
    integer = [[max(1, round(mass * 10_000)) for mass in bounded_distribution(group, cap)]
               for group in weights]
    return integer


def probabilities(weights):
    return [[mass / sum(group) for mass in group] for group in weights]


def verify_fit(validation, fits):
    effects = coefficients(validation, 0)
    nominees = []
    estimates = {}
    for name, weights in fits.items():
        distribution = probabilities(weights)
        rows = {condition: (1 - RETAIN) * projected(values, distribution)
                for condition, values in effects.items()}
        estimates[name] = rows
        broad_gain = statistics.mean(rows.values())
        if broad_gain > 0 and min(rows.values()) >= -.04:
            nominees.append((.4 * min(rows.values()) + .6 * broad_gain, name))
    return (max(nominees)[-1] if nominees else None), estimates


def policy(anchor, weights):
    return dict(anchor, openingMix={
        "bids": list(BIDS), "weights": weights[0], "laterCapitalBids": list(BIDS),
        "laterCapitalWeights": weights[1], "participation": 1 - RETAIN,
    })


def report(rows, name):
    answer = {}
    for arm in ("control", name):
        answer[arm] = {
            str(later): dict(sorted(Counter(
                row["bids"][arm] for row in rows if row["later"] == later
            ).items()))
            for later in (False, True)
        }
    own = [row["outcomes"][name][0] - row["outcomes"]["control"][0] for row in rows]
    rival = [row["outcomes"][name][1] - row["outcomes"]["control"][1] for row in rows]
    return {"openingBidsByLaterCapital": answer, "ownWinDelta": interval(own),
            "seatToLeftWinDelta": interval(rival)}


def main(root, previous, count, resume, *, pressure=False):
    campaign = Campaign(root, remote=PRESSURE_REMOTE if pressure else REMOTE,
                        batch_size=2, resume=resume)
    campaign.validate_runtime()
    preview_path = previous / "automata-heterogeneous-20261118/table-fresh.json"
    rivals_path = preview_path.with_name("roster.json")
    learner_path = previous / "automata-payoff-trained-challenger-20261119/plan.json"
    learner = read(learner_path)
    previous_result = read(learner_path.with_name("result.json"))["outcomes"]["preview"]
    anchor = read(preview_path)
    if not anchor["passed"] or previous_result["expected-win"]["rivalVersusModal"]["lower95"] <= 0:
        raise ValueError("Training requires the exact qualified reference and verified stronger rival.")
    if learner["targets"]["preview"] != anchor["config"]:
        raise ValueError("Prior public games must belong to the pinned reference policy.")
    development = (*DEVELOP, "contested") if pressure else DEVELOP
    fresh_cases = (*FRESH[:-1], "contested-steep") if pressure else FRESH
    plan = {
        "baseline": anchor["config"], "rivals": read(rivals_path)["rivals"],
        "history": learner["histories"]["preview"], "payoff": learner["payoff"],
        "sources": {name: sha(source) for name, source in (("preview", preview_path),
                    ("rivals", rivals_path), ("learner", learner_path))},
        "bids": BIDS, "developmentWorlds": count, "freshWorlds": 768 if pressure else 640,
        "development": development, "fresh": fresh_cases, "retainedReferenceProbability": RETAIN,
    }
    if pressure:
        current = previous / "automata-credible-leader-pressure-20261119/result.json"
        adversarial = previous / "automata-retargeting-adversarial-test-20261119/result.json"
        ordinary, challenged = read(current), read(adversarial)
        if (not ordinary["passed"] or not challenged["passed"]
                or ordinary["configuration"] != challenged["configuration"]):
            raise ValueError("The new opening search requires the same independently qualified attack rule.")
        plan.update(retargeting=ordinary["configuration"],
                    maximumWeight=PRESSURE_BID_LIMIT,
                    postAttackQualificationSha256=sha(current),
                    afterAdversarialQualificationSha256=sha(adversarial))
    declaration = root / "plan.json"
    normalized = dict(plan, bids=list(BIDS), development=list(development), fresh=list(fresh_cases))
    if not declaration.exists():
        write(declaration, normalized)
    elif read(declaration) != normalized:
        raise ValueError("The weighted opening study needs its original cases and sample sizes.")
    fixed = {f"bid-{bid}": with_mix(plan["baseline"], [bid]) for bid in BIDS}
    training, validation = {}, {}
    for condition in development:
        rows, _ = tournament(campaign, plan, fixed, condition, count)
        training[condition] = [row for i, row in enumerate(rows) if (i // 4) % 3 != 2]
        validation[condition] = [row for i, row in enumerate(rows) if (i // 4) % 3 == 2]
    fits = {name: fit(training, penalty, plan.get("maximumWeight")) for name, penalty in
            (("risk-focused", .012), ("high-entropy", .028))}
    choice, estimates = verify_fit(validation, fits)
    write(root / "screen.json", {
        "selected": choice, "weights": fits, "heldoutWinDeltas": estimates,
        "trainingWorldsPerCase": {case: len(rows) for case, rows in training.items()},
        "heldoutWorldsPerCase": {case: len(rows) for case, rows in validation.items()},
    })
    if not choice or campaign.remaining() < 3600:
        write(root / "result.json", {"passed": False, "stage": "screen"})
        return
    candidate = policy(plan["baseline"], fits[choice])
    fresh = {}
    for condition in fresh_cases:
        raw, _ = tournament(campaign, plan, {choice: candidate}, condition,
                            plan["freshWorlds"], fresh=True)
        fresh[condition] = report(raw, choice)
    estimates = [value["ownWinDelta"] for value in fresh.values()]
    write(root / "result.json", {
        "passed": (min(point["lower95"] for point in estimates) > -.025
                   and statistics.mean(point["mean"] for point in estimates) >= .015
                   and sum(point["lower95"] > 0 for point in estimates) >= 2),
        "configuration": candidate, "selected": choice, "outcomes": fresh,
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--previous", required=True, type=Path)
    parser.add_argument("--count", type=int, default=384, choices=(192, 384, 512, 768))
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--pressure", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.count, args.resume,
         pressure=args.pressure)
