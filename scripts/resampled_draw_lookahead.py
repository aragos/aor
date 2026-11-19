"""Compare short offline teaching with the original and resampled hidden draw orders."""

import argparse
import json
from pathlib import Path

import numpy as np

from initiative_campaign import Campaign, interval, read, sha, write
from lookahead_distillation import CHECKPOINT_FEATURES, SHORT_FUTURES, collect, report


REMOTE = "/home/aragos/aor-worker/resampled-draw-lookahead-20261119"
KEY = "cardTerritoryRollouts"


def inputs(campaign, original):
    stage = campaign.root/"resampled"
    stage.mkdir(exist_ok=campaign.resume)
    requests = sorted(worlds.parent.with_suffix(".json")
                      for worlds in original.glob("root-*/worlds.jsonl"))
    if not requests:
        raise ValueError("The original teacher campaign must retain its verified case collection.")
    plan = {
        "kind": "hidden-draw-order-1",
        "originalTeacherSha256": sha(original/"method.json"),
        "sourceRequests": {source.name:sha(source) for source in requests},
        "unchangedHorizon": 3,
        "shortFuturesPerAction": SHORT_FUTURES,
    }
    method = stage/"method.json"
    if not method.is_file():
        write(method, plan)
    elif read(method) != plan:
        raise ValueError("The resampled teacher must use its original worlds and horizon.")
    pending = []
    for source in requests:
        spec = dict(read(source), protocolSha256=sha(method), resampledDrawStudy=True)
        saved = stage/source.name
        if not saved.exists():
            write(saved, spec)
        elif read(saved) != spec:
            raise ValueError("A resampled position cannot alter its original teacher specification.")
        receipt = stage/f"{source.stem}.receipt.json"
        if not receipt.exists():
            pending.append((source.stem, saved))
        else:
            if Campaign.validate_result(stage/source.stem, saved) != read(receipt)["games"]:
                raise ValueError("The resampled teacher receipt does not match its output.")
    if pending:
        campaign.execute_jobs(stage, pending)
    return stage, [source.stem for source in requests]


def paired_outputs(original, second, shards):
    by_index, changed, count = {}, 0, 0
    for shard in shards:
        def load(root):
            Campaign.validate_result(root/shard, root/f"{shard}.json")
            rows = [json.loads(line) for line in (root/shard/"worlds.jsonl").read_text().splitlines()]
            return sorted(rows, key=lambda row: row["index"])
        for old, new in zip(load(original), load(second), strict=True):
            count += 1
            def rollout(game):
                game.pop("scienceSha256")
                return game["diagnostics"]["CANDIDATE"].pop(KEY)
            previous, observed = rollout(old), rollout(new)
            if old != new or previous["status"] != observed["status"]:
                raise ValueError("The resampled draws must preserve every original backbone result.")
            if previous["status"] != "paired":
                continue
            previous.pop("tails")
            short = previous.pop("shortTrainingTails")
            resampled = observed.pop("resampledTrainingTails")
            if previous != observed:
                raise ValueError("The hidden draw change must preserve the exact decision root.")
            if any([row["seed"] for row in short[arm]] != [row["seed"] for row in resampled[arm]]
                   for arm in short):
                raise ValueError("A resampled teacher must use the original paired chance seeds.")
            for buy, market in zip(resampled["buy"],resampled["territory"],strict=True):
                if buy["drawOrderChanged"] != market["drawOrderChanged"]:
                    raise ValueError("Both actions must share one hypothetical draw order.")
                changed += buy["drawOrderChanged"]
            index = old["index"]
            if index in by_index:
                raise ValueError("An origin may contribute only one resampled future bank.")
            by_index[index] = resampled
    if not changed:
        raise ValueError("The study must include a genuinely varied hidden draw order.")
    return by_index, changed, count


def heldout_teacher(original, resampled):
    exact = [row for row in original if row.split=="test"]
    alternate = [row for row in resampled if row.split=="test"]
    def applied(positions):
        return [row.terminal_delta * (row.short_delta>0) for row in positions]
    a, b = applied(exact), applied(alternate)
    return {
        "originalDrawOrder": interval(a),
        "resampledDrawOrders": interval(b),
        "matchedDifference": interval([after-before for before,after in zip(a,b,strict=True)]),
    }


def main(root, previous, resume):
    campaign = Campaign(root,remote=REMOTE,resume=resume)
    campaign.validate_runtime()
    teacher_input = previous/"automata-short-lookahead-20261119/result.json"
    summary = read(teacher_input)
    if tuple(summary["features"]) != CHECKPOINT_FEATURES:
        raise ValueError("Resampled draws require the independently fitted three-turn teacher.")
    saved = summary["horizons"]["3"]["model"]
    teacher = tuple(np.asarray(saved[key]) for key in ("mean","scale","coefficients"))
    original = previous/"automata-lookahead-distillation-20261119/short-teacher"
    stage, requests = inputs(campaign, original)
    future, changed, all_worlds = paired_outputs(original, stage, requests)
    original_origins = collect(original,teacher)
    resampled = collect(original,teacher,alternative_futures=future)
    write(root/"result.json",{
        "teacherSha256":sha(teacher_input), "worlds":all_worlds, "eligible":len(future),
        "differentFutureOrders":changed, "heldOutTeacher":heldout_teacher(original_origins,resampled),
        "originalStudent":report(original_origins), "resampledStudent":report(resampled),
    })


if __name__=="__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--previous",type=Path,required=True)
    parser.add_argument("--resume",action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(),args.previous.resolve(),args.resume)
