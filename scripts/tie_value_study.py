"""Find actor-visible conditions where moving later at one fixed tie improves wins."""

import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_opening_strategy import load_games
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/tie-value-study-20261119"
POLICIES = {arm: {"kind": "phase-aware-tie-1", "policy": policy}
            for arm, policy in (("control", "root-control"), ("later", "root-late"))}
ARMS = tuple(POLICIES)


def collect(campaign, label, table, count, anchor, live):
    style, setup, _ = selection_context(anchor, table, count, True)
    stage = f"single-tie-value-{label}"
    campaign.stage(
        stage, {"later": anchor["baseline"]}, count, (style,), shard=128, baseline=anchor["baseline"],
        lineup=setup["lineup"], leading_rival_variants=dict.fromkeys(ARMS, live),
        capital_geography_variants=ARMS, tie_order_variants=POLICIES,
        counter_variants=dict.fromkeys(ARMS, setup["counter"]) if "counter" in setup else None,
    )
    before, after = (load_games(campaign.root / stage, arm) for arm in ARMS)
    observations = []
    for index in range(count):
        left, right = before[index], after[index]
        a, b = [row["diagnostics"]["CANDIDATE"] for row in (left, right)]
        seat = left["seat"]
        first, second = a["tieChoices"]["firstTie"], b["tieChoices"]["firstTie"]
        if (left["seed"], seat, a["openingBid"], first) != (
                right["seed"], right["seat"], b["openingBid"], second):
            raise ValueError("Both trajectories must reach the same sampled tie with identical paid bids.")
        if bool(first) != (b["tieChoices"]["preferences"].get("single-late", 0) == 1):
            raise ValueError("One world may contribute at most one changed tied position.")
        own_before = left["roles"]["CANDIDATE"]["wins"][seat]
        own_after = right["roles"]["CANDIDATE"]["wins"][seat]
        delta = own_after - own_before
        if not first and delta != 0:
            raise ValueError("Ineligible worlds must preserve the incumbent outcome.")
        observations.append({"index": index, "features": first.get("features"), "delta": delta})
    return observations


def matches(row, rule):
    features = row["features"]
    if features is None:
        return False
    value = features[rule["feature"]]
    return value >= rule["threshold"] if rule["atLeast"] else value <= rule["threshold"]


def measure(rows, rule):
    support = [row["delta"] for row in rows if matches(row, rule)]
    unconditional = [row["delta"] if matches(row, rule) else 0.0 for row in rows]
    return {"support": len(support), "conditional": interval(support) if support else None,
            "population": interval(unconditional)}


def fit(rows):
    # Split rotating sets of all four seats together.
    train = [row for row in rows if row["features"] and (row["index"] // 4) % 3 != 2]
    validate = [row for row in rows if (row["index"] // 4) % 3 == 2]
    if len(train) < 120:
        return {"selected": None, "eligibleTraining": len(train), "shortlist": []}
    nominees = []
    for feature in train[0]["features"]:
        values = sorted(row["features"][feature] for row in train)
        thresholds = {values[round((len(values) - 1) * q)] for q in (.2, .35, .5, .65, .8)}
        for threshold in sorted(thresholds):
            for at_least in (False, True):
                rule = {"feature": feature, "threshold": threshold, "atLeast": at_least}
                evidence = measure(train, rule)
                if evidence["support"] >= 80 and evidence["conditional"]["mean"] > 0:
                    nominees.append({"rule": rule, "training": evidence})
    nominees.sort(key=lambda row: row["training"]["population"]["lower95"], reverse=True)
    finalists = [{**row, "validation": measure(validate, row["rule"])} for row in nominees[:5]]
    supported = [row for row in finalists
                 if row["validation"]["support"] >= 32 and
                 row["validation"]["population"]["mean"] >= .004]
    best = max(supported, key=lambda row: row["validation"]["population"]["lower95"]) if supported else None
    return {"selected": best["rule"] if best else None,
            "eligibleTraining": len(train), "shortlist": finalists}


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    source = previous / "automata-retargeting-coevolution-20261119/result.json"
    latest = read(source)
    if not latest["passed"]:
        raise ValueError("The tied position study must use the newly qualified attack strategy.")
    live = latest["configuration"]
    anchor = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    plan = {"targetProofSha256": sha(source), "developmentWorlds": 2048,
            "freshWorldsPerCase": 1024, "policies": POLICIES}
    path = root / "plan.json"
    if not path.exists():
        write(path, plan)
    elif read(path) != plan:
        raise ValueError("The tied-position analysis must keep its original policy and data split.")
    points = collect(campaign, "develop", "mixed", 2048, anchor, live)
    screened = fit(points)
    screened["unconditionalLate"] = interval([row["delta"] for row in points])
    write(root / "screen.json", screened)
    choice = screened["selected"]
    if not choice or campaign.remaining() < 2200:
        write(root / "result.json", {"passed": False, "stage": "screen", **screened})
        return
    fresh = {name: measure(collect(campaign, f"fresh-{name}", name, 1024, anchor, live), choice)
             for name in ("mixed", "known-16")}
    effects = [case["population"] for case in fresh.values()]
    passed = (min(row["lower95"] for row in effects) > -.015
              and statistics.mean(row["mean"] for row in effects) >= .008
              and any(row["lower95"] > 0 for row in effects))
    write(root / "result.json", {"passed": passed, "rule": choice, "outcomes": fresh})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
