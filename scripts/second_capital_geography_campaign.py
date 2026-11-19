"""Qualify the second-chooser geographic capital rule with the current strong attack policy."""

import argparse
from collections import defaultdict
from pathlib import Path
import statistics

from capital_auction_league import setup
from initiative_campaign import Campaign, interval, read, sha, write
from initiative_opening_strategy import load_games
from initiative_series_rivals import GAMES_PER_GROUP
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/second-capital-geography-20261119"
ENVIRONMENTS = ("mixed", "experienced", "known-16", "contested-second")
WORLDS = 1536


def measure(stage, count, history_groups=None):
    reference = load_games(stage, "control")
    candidate = load_games(stage, "geography")
    all_wins, eligible, changed = [], [], []
    clusters = defaultdict(list)
    for index in range(count):
        ordinary, test = reference[index], candidate[index]
        if (ordinary["seed"], ordinary["seat"]) != (test["seed"], test["seat"]):
            raise ValueError("Both policies must receive exactly the same initial world and seat.")
        actor = test["seat"]
        registered = ordinary["diagnostics"]["CANDIDATE"]
        proposed = test["diagnostics"]["CANDIDATE"]
        old, new = registered["openingBid"], proposed["openingBid"]
        if (old["capitalPosition"], old["cash"]) != (new["capitalPosition"], new["cash"]):
            raise ValueError("The treatment may change only the capital, not the auction or its payment.")
        delta = (test["roles"]["CANDIDATE"]["wins"][actor] -
                 ordinary["roles"]["CANDIDATE"]["wins"][actor])
        selected_new = old["capital"] != new["capital"]
        second = old["capitalPosition"] == 1
        if selected_new and not second:
            raise ValueError("The new rule may change only the second chooser's offered capital.")
        if not selected_new:
            # The geography wrapper logs its explicit capital command. The ordinary fallback
            # bypasses the controller and therefore adds no command to that diagnostic digest.
            later = "chosenPolicyDecisionsSha256"
            if (ordinary["roles"] != test["roles"] or ordinary["canonicalSha256"] != test["canonicalSha256"]
                    or {k: v for k, v in registered.items() if k != later}
                    != {k: v for k, v in proposed.items() if k != later}):
                raise ValueError("An unchanged capital must reproduce the game and every scheduled replay.")
        all_wins.append(delta)
        if second:
            eligible.append(delta)
        if selected_new:
            changed.append(delta)
        if history_groups:
            clusters[(index // GAMES_PER_GROUP) % history_groups].append(delta)
    if len(eligible) < 100 or len(changed) < 60:
        raise ValueError("Full qualification must see enough eligible and actually different openings.")
    win = interval([statistics.mean(values) for values in clusters.values()] if clusters else all_wins)
    return {"worlds": count, "eligible": len(eligible), "changed": len(changed),
            "winDelta": win, "secondChooser": interval(eligible), "changedChoices": interval(changed)}


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, batch_size=2, resume=resume)
    campaign.validate_runtime()
    confirmation_path = previous / "automata-second-capital-confirmation-20261119/result.json"
    confirmation = read(confirmation_path)
    if "all-affordable-nearby" not in confirmation["nominees"]:
        raise ValueError("Verify the frozen simple rule independently before whole-game qualification.")
    qualified_path = previous / "automata-credible-leader-pressure-20261119/result.json"
    qualified = read(qualified_path)
    if not qualified["passed"]:
        raise ValueError("Full qualification must preserve the already qualified attack policy.")
    weighted_path = previous / "automata-weighted-opening-with-pressure-20261119/plan.json"
    live = read(weighted_path)
    if (live["retargeting"] != qualified["configuration"] or
            live["postAttackQualificationSha256"] != sha(qualified_path)):
        raise ValueError("The rival and candidate configurations must refer to the actual current preview.")
    plan = {
        "confirmedChoiceSha256": sha(confirmation_path), "qualifiedAttackSha256": sha(qualified_path),
        "rivalSourceSha256": sha(weighted_path), "worldsPerTable": WORLDS,
        "arms": ["control", "geography"], "environments": list(ENVIRONMENTS),
        "contestedAuction": setup([5, 7, 9], [3, 5, 9]),
    }
    manifest = root / "plan.json"
    if not manifest.exists():
        write(manifest, plan)
    elif read(manifest) != plan:
        raise ValueError("Full-policy qualification must retain its original fixed study identity.")
    outcomes = {}
    for name in ENVIRONMENTS:
        style, context, capital = selection_context(live, name, WORLDS, True)
        if name == "contested-second":
            capital = plan["contestedAuction"]
        arms = plan["arms"]
        label = f"capital-geography-fresh-{name}"
        campaign.stage(
            label, {"geography": live["baseline"]}, WORLDS, (style,), shard=128,
            baseline=live["baseline"], lineup=context["lineup"],
            leading_rival_variants=dict.fromkeys(arms, live["retargeting"]),
            counter_variants=dict.fromkeys(arms, context["counter"]) if "counter" in context else None,
            series_priors=dict.fromkeys(arms, context["priors"]) if "priors" in context else None,
            series_responses=dict.fromkeys(arms, context["responses"]) if "responses" in context else None,
            capital_variants=dict.fromkeys(arms, capital) if capital else None,
            capital_geography_variants=("geography",),
        )
        groups = len(live["history"]) // (2 * GAMES_PER_GROUP) if name == "experienced" else None
        outcomes[name] = measure(root / label, WORLDS, groups)
    points = [outcome["winDelta"] for outcome in outcomes.values()]
    write(root / "result.json", {
        "passed": (min(p["lower95"] for p in points) > -.02 and
                   statistics.mean(p["mean"] for p in points) >= .015 and
                   sum(p["lower95"] > 0 for p in points) >= 2),
        "outcomes": outcomes, "worldsPerTable": WORLDS,
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
