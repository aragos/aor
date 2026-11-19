"""Learn when to buy a card instead of spending the same tokens on a market."""

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import statistics

import numpy as np

from initiative_campaign import Campaign, interval, sha, write
from short_lookahead_campaign import fit, predict


FEATURES = (
    "cardPrice", "marketCommitment", "pool", "extraTokensNeededForBoth", "handSize",
    "attack", "victoryChance", "finalTurn", "epochTwo", "epochThree",
)


@dataclass(frozen=True)
class Origin:
    seed: int
    epoch: int
    split: str
    features: tuple[float, ...]
    buy_gain: float


def read_origins(stage):
    for receipt_path in sorted(stage.glob("root-*.receipt.json")):
        name = receipt_path.name.removesuffix(".receipt.json")
        source = stage / f"{name}.json"
        Campaign.validate_result(stage / name, source)
        with (stage / name / "worlds.jsonl").open() as stream:
            for line in stream:
                row = json.loads(line)
                evidence = row["diagnostics"]["CANDIDATE"]["cardTerritoryRollouts"]
                if evidence["status"] != "paired":
                    continue
                epoch = evidence["epoch"]
                block = (row["index"] // 4) % 8
                split = "train" if block < 4 else "validation" if block < 6 else "test"
                cost = evidence["price"]
                market = evidence["territoryTokens"]
                pool = evidence["pool"]
                pairs = zip(evidence["tails"]["buy"], evidence["tails"]["territory"], strict=True)
                win_delta = statistics.mean(buy["terminal"]["winShare"] - other["terminal"]["winShare"]
                                            for buy, other in pairs)
                yield Origin(row["seed"], epoch, split, (
                    cost, market, pool, cost + market - pool, evidence["handSize"], evidence["attack"],
                    evidence["recordedChance"], evidence["finalTurn"], epoch == 2, epoch == 3,
                ), win_delta)


def arrays(rows):
    return (np.asarray([row.features for row in rows],dtype=np.float64),
            np.asarray([row.buy_gain for row in rows],dtype=np.float64))


def measure(model, validation, test):
    features, outcomes = arrays(validation)
    estimate = predict(model, features)
    options = []
    for cut in (.0,.02,.04,.08):
        chosen = estimate > cut
        if chosen.sum() >= 8:
            evidence = interval((outcomes * chosen).tolist())
            if evidence["mean"] > .01:
                options.append((evidence["lower95"], evidence["mean"], cut))
    features, outcomes = arrays(test)
    result = {"validationWorlds":len(validation),"testWorlds":len(test),
              "unconditionalBuyGain":interval(outcomes.tolist()),"cutoff":None,
              "buyOverrides":0,"fittedWinShareGain":interval([0.] * len(test))}
    if options:
        _, _, cut = max(options)
        chosen = predict(model,features) > cut
        result.update(cutoff=cut,buyOverrides=int(chosen.sum()),
                      fittedWinShareGain=interval((outcomes*chosen).tolist()))
    return result


def main(previous, output):
    early = previous / "automata-card-territory-rollouts-20261119"
    late = previous / "automata-late-card-lookahead-20261119"
    rows = [*read_origins(early / "buy-or-expand"),*read_origins(late / "late-card-tradeoff")]
    if len({row.seed for row in rows}) != len(rows):
        raise ValueError("The early and late fits must use independently sampled worlds.")
    training = [row for row in rows if row.split == "train"]
    if len(training) < 64:
        raise ValueError("The card-spending pilot requires at least 64 independent training worlds.")
    model = fit(*arrays(training))
    groups = {}
    for epoch in (1,2,3):
        validation = [row for row in rows if row.epoch == epoch and row.split == "validation"]
        test = [row for row in rows if row.epoch == epoch and row.split == "test"]
        if len(validation) >= 16 and len(test) >= 16:
            groups[str(epoch)] = measure(model,validation,test)
    write(output, {
        "features":list(FEATURES), "trainingWorlds":len(training), "groups":groups,
        "earlyEvidence":sha(early/"result.json"), "lateEvidence":sha(late/"result.json"),
        "candidate":any(row["buyOverrides"]>=8 and row["fittedWinShareGain"]["lower95"]>0
                        for row in groups.values()),
        "model":{name:value.tolist() for name,value in
                 zip(("mean","scale","coefficients"),model,strict=True)},
    })


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--previous",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    main(args.previous.resolve(),args.output.resolve())
