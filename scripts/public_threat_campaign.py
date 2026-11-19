"""Fit public winner forecasts and test them across new player populations."""

import argparse
from collections import defaultdict
import json
from pathlib import Path
import statistics

import numpy as np

from initiative_campaign import Campaign, interval, read, seed_bank, sha, write
from initiative_heterogeneous_campaign import lineup, trained_counter


REMOTE = "/home/aragos/aor-worker/public-threat-model-20261119"
KEY = "publicWinningPositions"
VERSION = "public-winning-position-1"
TURNS = (3, 5, 7, 9)


def bank(campaign, name, control, rivals, count, style, counter=None):
    phase = campaign.root / name
    phase.mkdir(exist_ok=campaign.resume)
    seeds = seed_bank(name, count)
    declaration = {
        "kind": VERSION, "sampleTurns": list(TURNS), "seeds": seeds, "baseline": control,
        "rivals": rivals, "opponentStyle": style, "counter": counter, "runtime": campaign.runtime,
    }
    plan = phase / "method.json"
    if not plan.exists():
        write(plan, declaration)
    elif read(plan) != declaration:
        raise ValueError("Public snapshots require the original exact game bank.")
    inputs = {key: dict(value, path="../" + value["path"])
              if isinstance(value, dict) and set(value) == {"path", "sha256"} else value
              for key, value in campaign.template.items()}
    jobs = []
    for start in range(0, count, 128):
        job = f"worlds-{start}"
        source = phase / f"{job}.json"
        request = dict(inputs, protocolSha256=sha(plan), seeds=seeds, globalStart=start,
                       count=min(128, count - start), candidateOnly=True, replayIndices=[0, count - 1],
                       opponentStyle=style, initiative=control, collectPublicPositions=True)
        if rivals:
            request["initiativeLineup"] = rivals
        if counter:
            request["adaptiveOpponent"] = dict(
                {"kind": "public-token-history-counter-1", "minimumObservations": 2,
                 "lookbackTurns": 8, "targetedRivalCount": 1}, **counter)
        if not source.exists():
            write(source, request)
        elif read(source) != request:
            raise ValueError("A source declaration changed while resuming collection.")
        receipt = phase / f"{job}.receipt.json"
        if receipt.exists():
            if read(receipt)["games"] != Campaign.validate_result(phase / job, source):
                raise ValueError("A completed collection receipt changed.")
        else:
            jobs.append((job, source))
    campaign.execute_jobs(phase, jobs)
    return observations(phase, count)


def observations(stage, count):
    worlds, columns, seen = [], None, set()
    for part in sorted(stage.glob("worlds-*/worlds.jsonl")):
        Campaign.validate_result(part.parent, part.parent.with_suffix(".json"))
        for line in part.read_text().splitlines():
            row = json.loads(line)
            index = row["index"]
            if index in seen:
                raise ValueError("Each original game must contribute only one public trajectory.")
            seen.add(index)
            terminal = row["roles"]["CANDIDATE"]
            package = row["diagnostics"]["CANDIDATE"][KEY]
            if columns is None:
                columns = package["columns"]
            if package["kind"] != VERSION or columns != package["columns"]:
                raise ValueError("Every game must bind the same ordered public feature contract.")
            positions = package["snapshots"]
            turns = [state["turn"] for state in positions]
            if not set(turns) <= set(TURNS) or turns != sorted(set(turns)):
                raise ValueError("Each public trajectory has at most one position at each selected turn.")
            labels = terminal["wins"]
            seats = [score["player"] for score in terminal["scores"]]
            for state in positions:
                records = state["observedSeats"]
                if [record["player"] for record in records] != seats:
                    raise ValueError("Public seats must bind to the matching terminal winner shares.")
                if any(label > 0 and not record["active"]
                       for record, label in zip(records, labels, strict=True)):
                    raise ValueError("An eliminated position cannot contain the eventual winner.")
                if any(len(record["features"]) != len(columns) for record in records):
                    raise ValueError("Each public seat needs exactly one value per declared field.")
            if abs(sum(labels) - 1) < 1e-9 and positions:
                worlds.append({"index": index, "seed": row["seed"], "positions": positions,
                               "terminal": labels})
    if seen != set(range(count)) or not columns:
        raise ValueError("Fit and calibration require completion of the entire original game bank.")
    return sorted(worlds, key=lambda row: row["index"]), columns


def dataset(worlds, fields):
    data, masks, labels, group_weights, groups, turn_values, score_leaders = [], [], [], [], [], [], []
    for world in worlds:
        positions = world["positions"]
        group_weights.extend([1 / len(positions)] * len(positions))
        for snapshot in positions:
            records = snapshot["observedSeats"]
            active = [seat["active"] for seat in records]
            scores = [seat["features"][0] if seat["active"] else -1e12 for seat in records]
            raw = np.array([[seat["features"][i] for i in fields] for seat in records])
            time = (snapshot["turn"] - 5) / 4
            data.append(np.concatenate((raw, raw * time), axis=1))
            masks.append(active)
            labels.append(world["terminal"])
            groups.append(world["seed"])
            turn_values.append(snapshot["turn"])
            score_leaders.append(int(np.argmax(scores)))
    return {
        "x": np.stack(data), "active": np.array(masks, dtype=bool), "y": np.array(labels),
        "weight": np.array(group_weights), "group": np.array(groups), "turn": np.array(turn_values),
        "scoreLeader": np.array(score_leaders),
    }


def probabilities(data, weights, scale):
    logits = data["x"] / scale @ weights
    logits = np.where(data["active"], logits, -1e10)
    safe = np.exp(logits - np.max(logits, axis=1, keepdims=True))
    return safe / np.sum(safe, axis=1, keepdims=True)


def fit(data, penalty):
    active = data["active"]
    scale = np.maximum(np.std(data["x"][active], axis=0), .05)
    weights = np.zeros(len(scale))
    weighted = data["weight"] / sum(data["weight"])
    x = data["x"] / scale
    for _ in range(500):
        errors = (probabilities(data, weights, scale) - data["y"]) * weighted[:, None]
        gradient = np.einsum("i j, i j k -> k", errors, x)
        weights -= .15 * (gradient + penalty * weights)
    return weights, scale


def loss(y, probabilities):
    return -np.sum(y * np.log(np.maximum(probabilities, 1e-10)), axis=1)


def grouped_interval(groups, values):
    per_game = defaultdict(list)
    for group, value in zip(groups, values, strict=True):
        per_game[int(group)].append(float(value))
    return interval([statistics.mean(rows) for rows in per_game.values()])


def compare(model_data, base_data, model, baseline):
    learned = probabilities(model_data, *model)
    simple = probabilities(base_data, *baseline)
    labels = model_data["y"]
    if not np.array_equal(labels, base_data["y"]):
        raise ValueError("Model comparisons must preserve the exact same positions and labels.")
    picked = np.argmax(learned, axis=1)
    score_pick = model_data["scoreLeader"]
    positions = np.arange(len(learned))
    top_chance = labels[positions, picked]
    base_chance = labels[positions, score_pick]
    improvement = loss(labels, simple) - loss(labels, learned)
    groups = model_data["group"]
    return {
        "positions": len(labels), "worlds": len(set(groups)),
        "topThreatDifferent": int(np.count_nonzero(picked != score_pick)),
        "logLossReduction": grouped_interval(groups, improvement),
        "topThreatWinsDelta": grouped_interval(groups, top_chance - base_chance),
        "byTurn": {str(turn): grouped_interval(groups[mask], improvement[mask])
                   for turn in TURNS for mask in (model_data["turn"] == turn,) if sum(mask) > 1},
    }


def train(development, independent, columns):
    train = [row for row in development if (row["index"] // 4) % 6 < 4]
    valid = [row for row in development if (row["index"] // 4) % 6 == 4]
    test = [row for row in development if (row["index"] // 4) % 6 == 5]
    fields = tuple(range(len(columns)))
    train_full, train_base = dataset(train, fields), dataset(train, (0,))
    valid_full, valid_base = dataset(valid, fields), dataset(valid, (0,))
    base = fit(train_base, .003)
    choices = {}
    for power in (.003, .01, .03):
        model = fit(train_full, power)
        quality = compare(valid_full, valid_base, model, base)["logLossReduction"]
        choices[power] = quality["mean"], model
    penalty = max(choices, key=lambda value: choices[value][0])
    selected = choices[penalty][1]
    fresh = compare(dataset(test, fields), dataset(test, (0,)), selected, base)
    transfer = compare(dataset(independent, fields), dataset(independent, (0,)), selected, base)
    weights, scale = selected
    return {
        "kind": VERSION, "columns": columns, "penalty": penalty,
        "fitWorlds": len(train), "tuneWorlds": len(valid),
        "weights": list(map(float, weights / scale)),
        "heldout": fresh, "unseenOpponentTable": transfer,
        "nominateThreatModel": all(case["logLossReduction"]["lower95"] > 0
                                   and case["topThreatWinsDelta"]["mean"] > 0
                                   for case in (fresh, transfer)),
    }


def main(root, previous, count, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume, background=True, batch_size=2)
    campaign.validate_runtime()
    live = read(previous / "automata-heterogeneous-20261118/table-fresh.json")
    roster = read(previous / "automata-heterogeneous-20261118/roster.json")
    if not live["passed"]:
        raise ValueError("The threat predictor needs the qualified reference policy.")
    rivals = {int(k): v for k, v in roster["rivals"].items()}
    counter = trained_counter(previous, roster["counter"])
    control = live["config"]
    ordinary, cols = bank(campaign, "positions-ordinary", control, lineup(rivals), count, "QUALIFIED")
    adaptive, adaptive_cols = bank(campaign, "positions-adaptive", control,
        lineup({k: v for k, v in rivals.items() if k != 1}), count, "REVEALED_UNDERCUT", counter)
    transfer, transfer_cols = bank(campaign, "positions-unseen", control, None, count, "MIXED")
    if cols != adaptive_cols or cols != transfer_cols:
        raise ValueError("Each roster needs the identical public feature dictionary.")
    all_seeds = [row["seed"] for row in ordinary + adaptive + transfer]
    if len(all_seeds) != len(set(all_seeds)):
        raise ValueError("The three populations require disjoint simulation seed banks.")
    result = train(ordinary + adaptive, transfer, cols)
    write(root / "result.json", {"worldsPerPopulation": count, **result})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--previous", required=True, type=Path)
    parser.add_argument("--count", type=int, choices=(512, 1024, 1536), default=1024)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.count, args.resume)
