"""Finite offline search over how far a qualified attack preference should generalize."""

import argparse
from itertools import product
from pathlib import Path
import random
import statistics

from initiative_campaign import Campaign, read, sha, write
from initiative_heterogeneous_campaign import lineup
from initiative_opening_strategy import load_games


REMOTE = "/home/aragos/aor-worker/targeting-evolution-20261119"
AXES = {
    "maximumTrailingPoints": (40, 70, 99, 125, 175, 240, 360),
    "maximumAdditionalTokens": (0, 1, 2, 3, 4),
    "maximumOddsDrop": (.04, .08, .12, .15, .20, .25),
    "earliestTurn": (2, 3, 4, 5, 6, 7),
}
DEFAULTS = (99, 2, .15, 4)


def identity(config):
    return tuple(config.get(key, value) for key, value in zip(AXES, DEFAULTS, strict=True))


def program(reference, genome):
    return dict(reference, **dict(zip(AXES, genome, strict=True)))


def seed_population(reference):
    rng = random.Random(319574)
    all_options = list(product(*AXES.values()))
    seeds = [options for options in all_options if sum(
        a != b for a, b in zip(options, identity(reference), strict=True)
    ) >= 2]
    frozen = [
        (70, 1, .12, 4), (125, 2, .15, 3),
        (175, 3, .20, 4), (99, 2, .15, 6),
    ]
    chosen = frozen + rng.sample([x for x in seeds if x not in frozen], 8)
    return {f"initial-{i:02d}": program(reference, values) for i, values in enumerate(chosen)}


def descendants(reference, best, seen):
    rng = random.Random(819272)
    children = {}
    population = {}
    fingerprints = set(seen) | {identity(reference)}
    for i, parent in enumerate(best):
        baseline = identity(parent)
        population[f"retained-{i}"] = parent
        choices = []
        for axis, values in enumerate(AXES.values()):
            position = values.index(baseline[axis])
            for value in values[max(0, position - 1):position + 2]:
                changed = baseline[:axis] + (value,) + baseline[axis + 1:]
                if changed not in fingerprints:
                    choices.append(changed)
        rng.shuffle(choices)
        for sequence, choice in enumerate(choices[:3]):
            fingerprints.add(choice)
            children[f"child-{i}-{sequence}"] = program(reference, choice)
    return population | children


def cases(campaign, label, plan, candidates, environments, worlds):
    reference = plan["retargeting"]
    base = plan["baseline"]
    roster = {int(k): v for k, v in plan["rivals"].items()}
    arms = {"control": reference, **candidates}
    effects = {name: {} for name in candidates}
    for name in environments:
        counter = {
            "adaptive": plan["counter"], "familiar": {"openingBidPrior": 19},
            "known-16": {"minimumObservations": 1, "lookbackTurns": 1, "openingBidPrior": 17},
        }.get(name)
        style = "REVEALED_UNDERCUT" if counter else "QUALIFIED"
        if name == "familiar":
            fixed = None
        else:
            fixed = lineup({k: v for k, v in roster.items() if not counter or k != 1})
        stage = f"targeting-evolution-{label}-{name}"
        output = campaign.stage(
            stage, dict.fromkeys(candidates, base), worlds, (style,),
            shard=128, baseline=base, lineup=fixed,
            counter_variants=dict.fromkeys(arms, counter) if counter else None,
            leading_rival_variants=arms,
        )
        control = load_games(campaign.root / stage, "control")
        reference_bids = {i: row["diagnostics"]["CANDIDATE"]["openingBid"]
                          for i, row in control.items()}
        for candidate in candidates:
            tested = load_games(campaign.root / stage, candidate)
            if reference_bids != {i: row["diagnostics"]["CANDIDATE"]["openingBid"]
                                  for i, row in tested.items()}:
                raise ValueError("Expansion evolution must preserve every recorded opening choice.")
            effects[candidate][name] = output[candidate]["outcomes"][style]
    return effects


def ranking(evidence, *, second=False):
    sortable = []
    for name, tables in evidence.items():
        means = [row["mean"] for row in tables.values()]
        bounds = [row["lower95"] for row in tables.values()]
        average = statistics.mean(means)
        worst = min(means)
        if worst < (-.025 if second else -.04) or second and average < .0075:
            continue
        robust = .55 * worst + .45 * average + .10 * min(bounds)
        sortable.append((robust, average, name))
    return sorted(sortable, reverse=True)


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    champion = previous / "automata-credible-leader-pressure-20261119"
    strongest = read(champion / "result.json")
    adversarial = previous / "automata-retargeting-adversarial-test-20261119/result.json"
    stress = read(adversarial)
    if (not strongest["passed"] or not stress["passed"]
            or strongest["configuration"] != stress["configuration"]):
        raise ValueError("The anchor must be the exact policy qualified in both independent studies.")
    anchor = read(champion / "plan.json")
    plan = {
        "baseline": anchor["baseline"], "rivals": anchor["rivals"], "counter": anchor["counter"],
        "retargeting": strongest["configuration"],
        "qualifiedSha256": sha(champion / "result.json"), "experiencedSha256": sha(adversarial),
        "generationSizes": [12, 12], "worlds": [256, 320, 1024],
    }

    def seal(name, value):
        path = root / name
        if not path.exists():
            write(path, value)
        elif read(path) != value:
            raise ValueError("A resumed generation must keep its exact population and result.")

    seal("plan.json", plan)
    reference = plan["retargeting"]
    first = seed_population(reference)
    data = cases(campaign, "first", plan, first, ("mixed", "adaptive"), plan["worlds"][0])
    ranked = ranking(data)
    seal("first.json", {"candidates": first, "outcomes": data,
                       "ranking": [{"utility": a, "mean": b, "name": c} for a, b, c in ranked]})
    nominees = [first[item[-1]] for item in ranked[:3]]
    if not nominees or campaign.remaining() < 2700:
        seal("result.json", {"passed": False, "stage": "initial"})
        return
    second = descendants(reference, nominees, [identity(config) for config in first.values()])
    more = cases(campaign, "second", plan, second, ("mixed", "adaptive", "familiar"), plan["worlds"][1])
    possible = ranking(more, second=True)
    seal("second.json", {"candidates": second, "outcomes": more,
                        "ranking": [{"utility": a, "mean": b, "name": c} for a, b, c in possible]})
    if not possible or campaign.remaining() < 3600:
        seal("result.json", {"passed": False, "stage": "second"})
        return
    chosen = possible[0][-1]
    results = cases(campaign, "fresh", plan, {chosen: second[chosen]},
                    ("mixed", "adaptive", "familiar", "known-16"), plan["worlds"][2])[chosen]
    points = list(results.values())
    seal("result.json", {
        "passed": (min(row["lower95"] for row in points) > -.025
                   and statistics.mean(row["mean"] for row in points) >= .015
                   and sum(row["lower95"] > 0 for row in points) >= 2),
        "selected": chosen, "configuration": second[chosen], "outcomes": results,
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--previous", required=True, type=Path)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
