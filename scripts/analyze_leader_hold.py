"""Fixed-policy, one-game hidden-world diagnosis; all streams evaluate, none select."""
import argparse
import gzip
import hashlib
import json
import math
from pathlib import Path

POLICIES = ["recorded-play", "hold-original-leader-current-turn"]


def moments(values):
    n = len(values)
    mean = sum(values) / n if n else None
    variance = sum((value - mean) ** 2 for value in values) / (n - 1) if n > 1 else 0
    # Approximate paired Student t(31); partial non-Chaos subsets are descriptive only.
    width = 2.0395134463964077 * math.sqrt(variance / n) if n else 0
    return {"mean": mean, "samples": n, "nonzero": sum(value != 0 for value in values),
            "conditionalApprox95": [mean - width, mean + width] if n == 32 else None}


def economic_values(row):
    economy = row["economics"]
    boundary = economy["nextBoundary"]
    values = {"boundaryCash": boundary["cash"], "boundaryMarkers": boundary["markers"],
              "boundaryIncome": (boundary.get("income") or {}).get("ordinary"),
              "rootTurnOwnLeaderSaving": sum(x["ownNewLeaderEffectiveSaving"] for x in economy["rootTurnPurchases"]),
              "rootTurnPrintedWordRebate": sum(x["projectedPrintedWordRebate"] for x in economy["rootTurnLeaderPlays"]),
              "rootTurnPatronageCash": sum(x["actualOwnerCashChange"] for x in economy["rootTurnOwnLeaderPatronage"])}
    values.update({"market." + key: value["marketCount"]
                   for key, value in boundary["commodityMarkets"].items()})
    return values


def paired_fields(baseline, hold):
    fields = sorted(set().union(*(set(value) for value in baseline + hold)))
    return {field: moments([right.get(field, 0) - left.get(field, 0)
                            for left, right in zip(baseline, hold)
                            if left.get(field, 0) is not None and right.get(field, 0) is not None])
            for field in fields}


def analyze_root(arms, actor):
    if len(arms) != 2 or any(len(rows) != 32 for rows in arms):
        raise ValueError("Two fixed policies require all 32 paired evaluation tails")
    for action, rows in enumerate(arms):
        if [row["replicate"] for row in rows] != list(range(32)):
            raise ValueError("The frozen replicate order changed")
        if any(row["action"] != action or row["split"] != "evaluation" or
               row["policy"] != POLICIES[action] for row in rows):
            raise ValueError("Fixed policy identity or evaluation-only contract changed")
    baseline, hold = arms
    def scores(rows):
        return [next(score for score in row["terminal"]["scores"] if score["player"] == actor)
                for row in rows]
    left, right = scores(baseline), scores(hold)
    metrics = {name: moments([b["terminal"][name] - a["terminal"][name]
                              for a, b in zip(baseline, hold)])
               for name in ("reward", "winShare", "rankReward")}
    score_deltas = {name: moments([b[name] - a[name] for a, b in zip(left, right)
                                  if a[name] is not None and b[name] is not None])
                    for name in ("total", "cash", "advancePoints", "misery")}
    return {"entry": baseline[0]["entry"], "evaluation": metrics,
            "terminalScoreDeltas": score_deltas,
            "chaosCounts": [sum(score["total"] is None for score in rows) for rows in (left, right)],
            "economicDeltas": paired_fields([economic_values(row) for row in baseline],
                                             [economic_values(row) for row in hold]),
            "heldFirstCommands": sorted(set(row["command"] for row in hold)),
            "maskedDecisions": sum(row["hold"]["maskedDecisions"] for row in hold),
            "forcedReleaseTails": sum(bool(row["hold"]["forcedReleases"]) for row in hold)}


def analyze(directory):
    prepared = json.loads((directory / "prepared.json").read_text())
    if prepared["kind"] != "recorded-leader-hold-diagnostic-1" or prepared["selectionPerformed"] is not False:
        raise ValueError("Expected the fixed-policy hold profile")
    complete = json.loads((directory / "complete.json").read_text())
    if complete["tails"] != 448 or complete["replicates"] != 224 or complete["allReplaysVerified"] is not True:
        raise ValueError("The full predeclared bank must finish")
    records = [json.loads(line) for line in (directory / "completed.jsonl").read_text().splitlines()]
    hashes = {record["file"]: record["sha256"] for record in records}
    expected = {f"root-{root['entry']}-replicate-{i}.json.gz" for root in prepared["roots"] for i in range(32)}
    if len(prepared["roots"]) != 7 or len(records) != 224 or set(hashes) != expected:
        raise ValueError("Completed manifest differs from the frozen seven-root bank")
    roots = []
    for root in prepared["roots"]:
        if root["policies"] != POLICIES:
            raise ValueError("Frozen policy order changed")
        arms = [[], []]
        for replicate in range(32):
            path = directory / f"root-{root['entry']}-replicate-{replicate}.json.gz"
            if hashlib.sha256(path.read_bytes()).hexdigest() != hashes[path.name]:
                raise ValueError("Completed tail bytes changed")
            with gzip.open(path, "rt") as stream:
                tails = json.load(stream)
            if len(tails) != 2:
                raise ValueError("A paired replicate omitted a fixed policy")
            for action, tail in enumerate(tails):
                command = root["recordedCommand"] if action == 0 else root["holdFirstCommands"][replicate]
                if tail["entry"] != root["entry"] or tail["command"] != command or not tail["replayVerified"]:
                    raise ValueError("Frozen root command or replay identity changed")
                if tail["policySeed"] != root["policySeeds"][replicate] or tail["terminal"]["continuationSeed"] != root["chanceSeeds"][replicate]:
                    raise ValueError("Paired streams differ from the frozen bank")
                arms[action].append(tail)
        roots.append(analyze_root(arms, root["actor"]))
    return {"kind": "hidden-world-specific-fixed-leader-hold", "roots": roots,
            "selectionPerformed": False, "rootCount": 7, "tails": 448,
            "descriptiveMeanRewardDelta": sum(root["evaluation"]["reward"]["mean"] for root in roots) / 7,
            "limitations": ["All roots are from one game; no aggregate confidence interval or strength claim.",
                            "Existing hidden hands/deck stay fixed; only future chance/policy streams vary.",
                            "The former human seat uses the frozen automata continuation.",
                            "No outcomes select actions, fit models or qualify policies.",
                            "Partial non-Chaos terminal total comparisons are descriptive, with their counts retained.",
                            "Global decision-index RNG can diverge between arms."]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    with args.output.open("x") as stream:
        json.dump(analyze(args.run), stream, indent=2, sort_keys=True)
        stream.write("\n")
