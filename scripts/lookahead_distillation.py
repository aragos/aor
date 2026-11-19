"""Fit actor-visible decisions from short simulated continuations and from full games."""

import argparse
from collections import Counter
from dataclasses import dataclass
import json
from pathlib import Path
import statistics

import numpy as np

from commodity_rollout_campaign import simulated_positions
from initiative_campaign import Campaign, interval, read, sha, write
from short_lookahead_campaign import FEATURES as CHECKPOINT_FEATURES, fit, predict


REMOTE = "/home/aragos/aor-worker/lookahead-distillation-20261119"
SHORT_FUTURES = 6


@dataclass(frozen=True)
class Origin:
    split: str
    features: dict[str, float]
    terminal_delta: float
    short_delta: float
    terminal_decisions: int
    short_decisions: int


def collect(stage, teacher, *, alternative_futures=None):
    origins = []
    for file in sorted(stage.glob("root-*/worlds.jsonl")):
        with file.open() as stream:
            for line in stream:
                game = json.loads(line)
                data = game["diagnostics"]["CANDIDATE"]["cardTerritoryRollouts"]
                if data["status"] != "paired":
                    continue
                full = data["tails"]
                terminal = list(zip(full["buy"],full["territory"],strict=True))
                abbreviated = (
                    data["shortTrainingTails"] if alternative_futures is None
                    else alternative_futures[game["index"]]
                )
                short = list(zip(abbreviated["buy"],abbreviated["territory"],strict=True))
                if len(terminal)!=2 or len(short)!=SHORT_FUTURES:
                    raise ValueError("Each training position must contain the fixed full and short futures.")
                long_seeds = {row["terminal"]["continuationSeed"] for pair in terminal for row in pair}
                if (any(buy["seed"]!=market["seed"] for buy,market in short)
                    or len({buy["seed"] for buy,_ in short})!=SHORT_FUTURES
                    or long_seeds & {buy["seed"] for buy,_ in short}):
                    raise ValueError("Abbreviated futures need matched seeds independent of the terminal ones.")
                signals = np.asarray([[
                    float(buy["checkpoint"][feature])-float(market["checkpoint"][feature])
                    for feature in CHECKPOINT_FEATURES
                ] for buy,market in short])
                short_delta = float(np.clip(predict(teacher,signals.mean(axis=0,keepdims=True))[0],-1,1))
                terminal_delta = statistics.mean(
                    buy["terminal"]["winShare"]-market["terminal"]["winShare"] for buy,market in terminal)
                features = dict(data["rootTrainingFeatures"])
                for name in ("price","territoryTokens","pool","handSize","attack","recordedChance","finalTurn"):
                    features["root."+name] = float(data[name])
                decisions = data["decision"]
                full_work = sum(row["terminal"]["decisions"]-decisions for pair in terminal for row in pair)
                short_work = sum(row["checkpoint"]["decisions"] for pair in short for row in pair)
                block = (game["index"] // 4) % 8
                split = "train" if block < 4 else "validation" if block < 6 else "test"
                origins.append(Origin(split,features,terminal_delta,short_delta,full_work,short_work))
    return origins


def quality(validation, test, model, matrix):
    options = []
    labels = np.asarray([row.terminal_delta for row in validation])
    estimate = predict(model,matrix(validation))
    for threshold in (.0,.02,.04,.08):
        chosen = estimate > threshold
        if chosen.sum() >= 8:
            evidence = interval((labels*chosen).tolist())
            if evidence["mean"] > 0:
                options.append((evidence["lower95"],evidence["mean"],threshold))
    count = len(test)
    if not options:
        return {"threshold":None,"heldOutWorlds":count,"overrides":0,
                "terminalWinGain":interval([0.] * count)}
    _, _, threshold=max(options)
    chosen=predict(model,matrix(test)) > threshold
    return {"threshold":threshold,"heldOutWorlds":count,"overrides":int(chosen.sum()),
            "terminalWinGain":interval([item.terminal_delta * bool(take) for item,take in zip(test,chosen,strict=True)])}


def report(origins):
    groups = {split:[row for row in origins if row.split==split]
              for split in ("train","validation","test")}
    sizes = {split:len(rows) for split,rows in groups.items()}
    if min(sizes.values()) < 48:
        return {"status":"insufficient-positions","groups":sizes}
    train = groups["train"]
    counts = Counter(name for row in train for name,value in row.features.items()
                     if value and not name.startswith("root."))
    shape = sorted(counts,key=lambda name:(-counts[name],name))[:40]
    shape.extend(sorted({name for row in train for name in row.features if name.startswith("root.")}))

    def matrix(rows):
        return np.asarray([[row.features.get(name,0.) for name in shape] for row in rows])

    designs = {
        "full-terminal":fit(matrix(train),np.asarray([row.terminal_delta for row in train])),
        "short-teacher":fit(matrix(train),np.asarray([row.short_delta for row in train])),
    }
    outcomes = {name:dict(
        quality(groups["validation"],groups["test"],model,matrix),
        model={key:value.tolist() for key,value in
               zip(("mean","scale","coefficients"),model,strict=True)},
    ) for name,model in designs.items()}
    return {"status":"complete","groups":sizes,"features":shape,"outcomes":outcomes,
            "shortToFullTrainingDecisions":sum(row.short_decisions for row in train)/
                                           sum(row.terminal_decisions for row in train)}


def main(root, previous, count, resume):
    campaign=Campaign(root,remote=REMOTE,resume=resume)
    campaign.validate_runtime()
    anchor=read(previous/"automata-heterogeneous-20261118/table-fresh.json")
    teacher_path=previous/"automata-short-lookahead-20261119/result.json"
    teacher=read(teacher_path)
    if (anchor["passed"] is not True or teacher["status"]!="complete"
        or tuple(teacher["features"])!=CHECKPOINT_FEATURES
        or teacher["horizons"]["3"]["terminalWinShareGain"]["lower95"]<=0):
        raise ValueError("Distillation needs a verified preview and a successful three-turn teacher.")
    target=teacher["horizons"]["3"]["model"]
    model=tuple(np.asarray(target[name]) for name in ("mean","scale","coefficients"))
    stage=simulated_positions(campaign,root,anchor["config"],count,territory=True,epochs=(1,),
                              short_training_futures=SHORT_FUTURES)
    write(root/"result.json",dict(report(collect(stage,model)),teacherSha256=sha(teacher_path)))


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--previous",type=Path,required=True)
    parser.add_argument("--count",type=int,default=512,choices=(256,512,768))
    parser.add_argument("--resume",action="store_true")
    args=parser.parse_args()
    main(args.root.resolve(),args.previous.resolve(),args.count,args.resume)
