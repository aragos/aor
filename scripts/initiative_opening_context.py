"""Search paired opening returns for a hand- or route-conditioned mixed bid."""

import argparse
from itertools import combinations
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_opening_mix_fit import TABLES, cautious_mean, robustness
from initiative_opening_strategy import OPENINGS, load_games


REMOTE = "/home/aragos/aor-worker/opening-context-20261119"
THRESHOLDS = {
    "hand": {
        "commodityCards": [0, 1],
        "leaderCards": [0],
        "militaryCards": [0],
        "smallerPoolHeldGain": [0, 5, 10, 20],
        "extraPoolHeldGain": [0, 5, 10],
        "extraPoolOpportunity": [0, 5, 10, 20],
    },
    "routes": {"reachableMarkets": [2, 3, 4, 5, 6], "contestedMarkets": [0, 1, 2]},
}


def checked_games(folder, role):
    for worlds in folder.glob(f"{role}-0-*/worlds.jsonl"):
        Campaign.validate_result(worlds.parent, folder / f"{worlds.parent.name}.json")
    return load_games(folder, role)


def probe(campaign, source, table):
    stage = campaign.root / f"probe-{table}"
    stage.mkdir(exist_ok=campaign.resume)
    original = source / f"opening-screen-{table}"
    plan = {
        "sourceMethodSha256": sha(original / "method.json"),
        "sourceRequests": {},
        "kind": "opening-context-paired-return-1",
        "features": THRESHOLDS,
    }
    files = sorted(
        worlds.parent.with_suffix(".json")
        for worlds in original.glob("control-0-*/worlds.jsonl")
    )
    if not files:
        raise ValueError("The prior campaign did not produce any completed control worlds.")
    for entry in files:
        plan["sourceRequests"][entry.name] = sha(entry)
    method = stage / "method.json"
    if not method.exists():
        write(method, plan)
    elif read(method) != plan:
        raise ValueError("The frozen source chronology or feature search changed.")
    pending = []
    for entry in files:
        request = stage / entry.name
        specification = dict(read(entry), protocolSha256=sha(method), recordOpeningContext=True)
        if not request.exists():
            write(request, specification)
        elif read(request) != specification:
            raise ValueError("Opening-context collection requires the same source worlds.")
        receipt = stage / f"{entry.stem}.receipt.json"
        if receipt.exists():
            Campaign.validate_result(stage / entry.stem, request)
        else:
            pending.append((entry.stem, request))
    if pending:
        campaign.execute_jobs(stage, pending)
    recorded = checked_games(stage, "control")
    reference = checked_games(original, "control")
    arms = {bid: checked_games(original, f"bid-{bid}") for bid in OPENINGS}
    if any(bank.keys() != reference.keys() for bank in (recorded, *arms.values())):
        raise ValueError("The feature study needs all reference worlds paired across every bid.")
    rows = []
    for index in sorted(reference):
        clean = recorded[index]
        opening = clean["diagnostics"]["CANDIDATE"]["openingBid"]
        context = opening.pop("context")
        clean.pop("scienceSha256")
        expected = {key: value for key, value in reference[index].items()
                    if key != "scienceSha256"}
        if clean != expected:
            raise ValueError("Recording an actor-visible context changed a frozen original game.")
        seat = clean["seat"]
        gains = {}
        for bid, bank in arms.items():
            row = bank[index]
            bid_opening = row["diagnostics"]["CANDIDATE"]["openingBid"]
            if (
                row["seed"] != clean["seed"] or row["seat"] != seat
                or (bid_opening["capital"], bid_opening["capitalPosition"], bid_opening["bid"])
                != (opening["capital"], opening["capitalPosition"], bid)
            ):
                raise ValueError("Each replacement amount must belong to the same opening world.")
            gains[bid] = row["roles"]["CANDIDATE"]["wins"][seat]
        rows.append((clean["seed"], opening["capitalPosition"] >= 2, context, gains))
    return rows


def expected(returns, bids):
    return statistics.mean(returns[bid] for bid in bids)


def effects(records, anchor, test):
    feature, threshold, above, replacement = test
    result = {}
    for table, rows in records.items():
        changes = []
        applied = 0
        changed = 0
        for _, later, context, outcomes in rows:
            normal = anchor[int(later)]
            applies = (context[feature] > threshold) == above
            bids = replacement if applies else normal
            applied += applies
            changed += bids != normal
            changes.append(expected(outcomes, bids) - expected(outcomes, normal))
        result[table] = dict(interval(changes), applied=applied, changed=changed,
                             cautiousMean=cautious_mean(changes))
    return result


def fit(records, anchor, signals):
    choices = [bids for count in (2, 3) for bids in combinations(OPENINGS, count)]
    candidates = []
    for feature, levels in signals.items():
        for threshold in levels:
            side_counts = [
                sum(context[feature] > threshold for _, _, context, _ in table)
                for table in records.values()
            ]
            for above in (True, False):
                if any((count if above else len(rows)-count) < 20
                       for count, rows in zip(side_counts, records.values(), strict=True)):
                    continue
                for bids in choices:
                    hypothesis = (feature, threshold, above, bids)
                    outcomes = effects(records, anchor, hypothesis)
                    score = robustness([value["cautiousMean"] for value in outcomes.values()])
                    candidates.append((score, len(bids), hypothesis, outcomes))
    if not candidates:
        raise ValueError("No measurable opening context met the minimum coverage per table.")
    return max(candidates, key=lambda row: row[:2])


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    source = previous / "automata-opening-strategy-20261119"
    model = read(previous / "automata-opening-mix-fit-20261119/fit.json")
    frozen = model["candidates"].get("capital-priority", model["candidates"]["global"])
    opening = frozen["openingMix"]
    anchor = (tuple(opening["bids"]), tuple(opening.get("laterCapitalBids", opening["bids"])))
    worlds = {table: probe(campaign, source, table) for table in TABLES}
    if any(len(rows) != 192 for rows in worlds.values()):
        raise ValueError("The exploratory study requires the original three 192-world banks.")
    streams = [seed for rows in worlds.values() for seed, *_ in rows]
    if len(streams) != len(set(streams)):
        raise ValueError("Independent opponent tables must use different chance streams.")
    train = {table: rows[:128] for table, rows in worlds.items()}
    held_out = {table: rows[128:] for table, rows in worlds.items()}
    studies = {}
    for family, fields in THRESHOLDS.items():
        score, _, hypothesis, development = fit(train, anchor, fields)
        feature, threshold, above, bids = hypothesis
        test = effects(held_out, anchor, hypothesis)
        studies[family] = {
            "rule": {"feature": feature, "threshold": threshold, "above": above, "bids": bids},
            "development": development,
            "score": score,
            "heldOut": test,
            "freshGameNominee": min(row["mean"] for row in test.values()) >= -.02
            and statistics.mean(row["mean"] for row in test.values()) >= .025,
        }
    write(root / "result.json", {"anchor": frozen, "exploratory": True, "studies": studies})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
