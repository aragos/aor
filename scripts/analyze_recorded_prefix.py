"""Descriptive hidden-world-specific tails from one game; no strength or cross-game inference."""
import argparse
import gzip
import hashlib
import json
import math
from pathlib import Path


def moments(values):
    if not values:
        return {"mean": None, "conditionalApprox95": None, "nonzero": 0, "samples": 0}
    mean = sum(values) / len(values)
    variance = sum((value - mean) ** 2 for value in values) / (len(values) - 1) if len(values) > 1 else 0
    # Student t(15), two-sided 95%; conditional approximation with only 16 bounded samples.
    width = 2.131449545559323 * math.sqrt(variance / len(values))
    return {"mean": mean, "conditionalApprox95": [mean - width, mean + width] if len(values) == 16 else None,
            "nonzero": sum(value != 0 for value in values), "samples": len(values)}


def analyze_root(actions, actor=None):
    if not actions or any(len(rows) != 32 for rows in actions):
        raise ValueError("All predeclared arms require all 32 tails")
    for index, rows in enumerate(actions):
        if [row["replicate"] for row in rows] != list(range(32)):
            raise ValueError("Every arm requires exactly the frozen replicate order")
        if any(row["action"] != index or row["split"] !=
               ("selection" if row["replicate"] < 16 else "evaluation") for row in rows):
            raise ValueError("Frozen action indices and selection/evaluation splits changed")
    baseline = actions[0]
    def deltas(rows, metric, indices):
        return [rows[i]["terminal"][metric] - baseline[i]["terminal"][metric] for i in indices]
    selection = [sum(deltas(rows, "reward", range(16))) / 16 for rows in actions]
    # Recorded action participates with zero delta; deterministic frozen action order breaks ties.
    selected = max(range(len(actions)), key=lambda index: selection[index])
    evaluation = {metric: moments(deltas(actions[selected], metric, range(16, 32)))
                  for metric in ("reward", "winShare", "rankReward")}
    def scores(rows):
        if actor is None:
            return {}
        values = [next(score for score in row["terminal"]["scores"] if score["player"] == actor)
                  for row in rows]
        reference = [next(score for score in row["terminal"]["scores"] if score["player"] == actor)
                     for row in baseline]
        return {field: moments([values[i][field] - reference[i][field] for i in range(16, 32)])
                for field in ("total", "cash", "advancePoints", "misery")
                if all(values[i][field] is not None and reference[i][field] is not None
                       for i in range(16, 32))}
    def economy(rows):
        if "economics" not in rows[0]:
            return {}
        def values(row):
            receipt = row["economics"]
            boundary = receipt["nextBoundary"]
            result = {"nextBoundaryCash": boundary["cash"], "nextBoundaryMarkers": boundary["markers"],
                      "nextBoundaryOrdinaryIncome": (boundary.get("income") or {}).get("ordinary"),
                      "projectedPrintedWordRebate": sum(play["projectedPrintedWordRebate"] for play in receipt["rootTurnLeaderPlays"]),
                      "ownNewLeaderEffectiveSaving": sum(purchase["ownNewLeaderEffectiveSaving"] for purchase in receipt["rootTurnPurchases"]),
                      "ownLeaderPatronageCash": sum(claim["actualOwnerCashChange"] for claim in receipt["rootTurnOwnLeaderPatronage"])}
            for name, market in boundary["commodityMarkets"].items():
                result["commodityMarkets." + name] = market["marketCount"]
            return result
        actual = [values(row) for row in rows]
        reference = [values(row) for row in baseline]
        fields = sorted(set().union(*(set(value) for value in actual + reference)))
        return {field: moments([actual[i].get(field, 0) - reference[i].get(field, 0) for i in range(16, 32)
                                if actual[i].get(field, 0) is not None and reference[i].get(field, 0) is not None])
                for field in fields}
    return {"entry": baseline[0]["entry"], "selectedAction": selected,
            "selectedCommand": actions[selected][0]["command"],
            "selectionRewardDeltas": selection, "evaluation": evaluation,
            "selectedTerminalScoreDeltas": scores(actions[selected]),
            "selectedEconomicDeltas": economy(actions[selected]),
            "allArmEvaluation": [{"action": index, "command": rows[0]["command"],
                                   "reward": moments(deltas(rows, "reward", range(16, 32))),
                                   "terminalScoreDeltas": scores(rows), "economicDeltas": economy(rows)}
                                  for index, rows in enumerate(actions)]}


def analyze(directory):
    prepared = json.loads((directory / "prepared.json").read_text())
    if not (directory / "complete.json").exists():
        raise ValueError("Incomplete attempts cannot be analyzed as completed diagnostics")
    records = [json.loads(line) for line in (directory / "completed.jsonl").read_text().splitlines()]
    hashes = {record["file"]: record["sha256"] for record in records}
    expected = {f"root-{root['entry']}-replicate-{i}.json.gz" for root in prepared["roots"] for i in range(32)}
    if len(records) != len(expected) or set(hashes) != expected:
        raise ValueError("Completed tail manifest differs from its predeclared bank")
    roots = []
    for root in prepared["roots"]:
        actions = [[] for _ in root["actions"]]
        for replicate in range(32):
            name = f"root-{root['entry']}-replicate-{replicate}.json.gz"
            if hashlib.sha256((directory / name).read_bytes()).hexdigest() != hashes[name]:
                raise ValueError("A completed paired-tail file changed")
            with gzip.open(directory / name, "rt") as stream:
                tails = json.load(stream)
            if len(tails) != len(actions):
                raise ValueError("A paired replicate omitted a frozen arm")
            for index, tail in enumerate(tails):
                if tail["entry"] != root["entry"] or tail["command"] != root["actions"][index]["command"] or not tail["replayVerified"]:
                    raise ValueError("Tail identity or canonical replay verification differs")
                if tail["policySeed"] != root["policySeeds"][replicate] or tail["terminal"]["continuationSeed"] != root["chanceSeeds"][replicate]:
                    raise ValueError("Tail streams differ from their frozen paired bank")
                actions[index].append(tail)
        roots.append(analyze_root(actions, root["actor"]))
    return {"kind": "hidden-world-specific-one-game-diagnostic", "roots": roots,
            "rootCount": len(roots), "selectedAlternativeCount": sum(root["selectedAction"] != 0 for root in roots),
            "descriptiveMeanEvaluationRewardDelta": sum(root["evaluation"]["reward"]["mean"] for root in roots) / len(roots),
            "limitations": ["All roots are from one game; no aggregate confidence interval or strength claim.",
                            "Existing hidden hands/deck remain fixed; only future chance/policy streams vary.",
                            "After each prefix the human seat is replaced by the frozen automata.",
                            "Only selection tails choose an arm; all-arm evaluation comparisons are descriptive.",
                            "Global decision-index RNG can diverge between arms; paired variance may remain large."]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    with args.output.open("x") as stream:
        json.dump(analyze(args.run), stream, indent=2, sort_keys=True)
        stream.write("\n")
