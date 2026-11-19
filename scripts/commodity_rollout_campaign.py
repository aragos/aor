"""Estimate play-versus-hold values at natural commodity timing positions."""
import argparse
import json
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, seed_bank, sha, write


REMOTE = "/home/aragos/aor-worker/commodity-rollouts-20261118"
EXPANSION_REMOTE = "/home/aragos/aor-worker/expansion-card-rollouts-20261118"
TERRITORY_REMOTE = "/home/aragos/aor-worker/card-territory-rollouts-20261119"


def simulated_positions(campaign, root, baseline, count, *, expansion=False, territory=False,
                        lookahead=False, epochs=(), chance_trial=False, short_training_futures=0,
                        relief_unlock=False, commodity_expansion=False):
    if sum((expansion, territory, relief_unlock, commodity_expansion)) > 1:
        raise ValueError("A paired study requires one root intervention.")
    topic, bank = "play-or-hold", "commodity-play-or-hold-v1"
    if expansion:
        topic, bank = "buy-or-pass", "expansion-buy-or-pass-v1"
    elif territory:
        topic, bank = "buy-or-expand", "card-versus-territory-v1"
    elif relief_unlock:
        topic, bank = "unlock-relief", "first-tier-relief-v1"
    elif commodity_expansion:
        topic, bank = "commodity-sequence", "hold-expand-play-v1"
    if lookahead:
        if not territory:
            raise ValueError("Short lookahead requires the card versus territory comparison.")
        topic, bank = "lookahead-checkpoints", "card-territory-lookahead-checkpoints-v1"
    if epochs:
        if not territory:
            raise ValueError("Requested root epochs require the card versus territory comparison.")
        topic, bank = "late-card-tradeoff", "card-territory-late-epochs-v1"
    if chance_trial:
        if not territory:
            raise ValueError("The chance trial requires the card versus territory comparison.")
        topic, bank = "chance-alignment", "card-territory-chance-alignment-v1"
    if short_training_futures:
        if not territory:
            raise ValueError("Abbreviated training futures require the card versus territory comparison.")
        topic, bank = "short-teacher", "card-territory-short-teacher-v1"
    stage = root / topic
    stage.mkdir(exist_ok=campaign.resume)
    seeds = seed_bank(bank, count)
    timing = {"kind": "affordable-commodity-retention-1", "futureWeight": .10,
              "minimumGrowth": 8, "currentPayoutLimit": 10}
    method = {"kind": "natural-commodity-root-screen-1", "seeds": seeds, "initiative": baseline,
              "commodityTiming": timing, "tailReplicates": 2, "runtime": campaign.runtime}
    if expansion:
        method["kind"] = "natural-card-purchase-root-screen-1"
        method.pop("commodityTiming")
    elif territory:
        method["kind"] = "natural-card-versus-territory-root-screen-1"
        method.pop("commodityTiming")
    elif relief_unlock:
        method["kind"] = "first-tier-relief-1"
        method.pop("commodityTiming")
    elif commodity_expansion:
        method["kind"] = "hold-expand-play-1"
    if lookahead:
        method["checkpointTurns"] = [1, 3]
    if epochs:
        method["requestedEpochs"] = list(epochs)
    if chance_trial:
        method["chanceModes"] = ["sequential", "turn-and-outcome-channel-1"]
    if short_training_futures:
        method["shortTrainingFutures"] = short_training_futures
    declared = stage / "method.json"
    if not declared.exists():
        write(declared, method)
    elif read(declared) != method:
        raise ValueError("The rollout plan differs from the existing frozen protocol.")
    pending = []
    for start in range(0, count, 32):
        name = f"root-{start}"
        source = stage / f"{name}.json"
        template = {key: dict(value, path="../" + value["path"]) if isinstance(value, dict)
                    and set(value) == {"path", "sha256"} else value
                    for key, value in campaign.template.items()}
        request = dict(template, protocolSha256=sha(declared), seeds=seeds, globalStart=start,
                       count=min(32,count-start), opponentStyle="QUALIFIED", candidateOnly=True,
                       replayIndices=[0,count-1], initiative=baseline)
        if expansion:
            request["expansionCardRollouts"] = True
        elif territory:
            request["cardTerritoryRollouts"] = True
            if lookahead:
                request["lookaheadCheckpoints"] = True
            if epochs:
                request["cardTerritoryEpochs"] = list(epochs)
            if chance_trial:
                request["chanceAlignmentTrial"] = True
            if short_training_futures:
                request["shortTrainingFutures"] = short_training_futures
        elif relief_unlock:
            request["reliefUnlockRollouts"] = True
        else:
            request.update(commodityTiming=timing, commodityRollouts=True)
            if commodity_expansion:
                request["commodityExpansionRollouts"] = True
        if not source.exists():
            write(source, request)
        elif read(source) != request:
            raise ValueError(f"The original rollout request changed for {source}.")
        receipt = stage / f"{name}.receipt.json"
        if receipt.exists():
            if campaign.validate_result(stage/name, source) != read(receipt)["games"]:
                raise ValueError(f"The completed rollout receipt changed for {name}.")
        else:
            pending.append((name, source))
    campaign.execute_jobs(stage, pending)
    return stage


def report(stage):
    points = []
    origins = 0
    for raw in sorted(stage.glob("root-*/worlds.jsonl")):
        with raw.open() as stream:
            for line in stream:
                world = json.loads(line)
                event = world["diagnostics"]["CANDIDATE"]["commodityRollouts"]
                origins += 1
                if event["status"] != "paired":
                    continue
                difference = statistics.mean(
                    hold["terminal"]["winShare"] - play["terminal"]["winShare"]
                    for hold, play in zip(event["tails"]["hold"], event["tails"]["play"], strict=True)
                )
                points.append((event["ordinary"] == "pass", event["projectedGrowth"], difference))
    if not points:
        return {"origins": origins, "eligible": 0}
    return {"origins": origins, "eligible": len(points), "terminalRollouts": len(points)*4,
            "holdWinShareDelta": interval([point[2] for point in points]),
            "byOrdinaryChoice": {name: interval([win for held,_,win in points if held == expected])
                                 for name, expected in (("hold", True), ("play", False))
                                 if any(held == expected for held,_,_ in points)},
            "byForecastGrowth": {f"{lower}-{upper}": interval([win for _,growth,win in points
                                                               if lower <= growth < upper])
                                for lower, upper in ((8,16),(16,32),(32,10000))
                                if any(lower <= growth < upper for _,growth,_ in points)}}


def expansion_report(stage):
    observations = []
    origins = 0
    for file in sorted(stage.glob("root-*/worlds.jsonl")):
        with file.open() as stream:
            for line in stream:
                record = json.loads(line)["diagnostics"]["CANDIDATE"]["expansionCardRollouts"]
                origins += 1
                if record["status"] != "paired":
                    continue
                gain = statistics.mean(buy["terminal"]["winShare"] - passed["terminal"]["winShare"]
                    for buy, passed in zip(record["tails"]["buy"],record["tails"]["pass"],strict=True))
                observations.append((record, gain))
    groups = {}
    for label,filtration in (
        ("all",lambda row: True),
        ("declined-by-policy",lambda row: row["ordinary"] == "pass"),
        ("no-peaceful-market",lambda row: row["completePeacefulMarkets"] == 0),
        ("three-token-card",lambda row: row["price"] == 3),
        ("later-card",lambda row: row["price"] > 3),
    ):
        shifts = [gain for row,gain in observations if filtration(row)]
        if shifts:
            groups[label] = interval(shifts)
    return {"origins":origins,"eligible":len(observations),"terminalRollouts":4*len(observations),
            "buyWinShareDelta":groups}


def territory_report(stage):
    observations, origins = [], 0
    for path in sorted(stage.glob("root-*/worlds.jsonl")):
        for line in path.open():
            record = json.loads(line)["diagnostics"]["CANDIDATE"]["cardTerritoryRollouts"]
            origins += 1
            if record["status"] != "paired":
                continue
            outcomes = zip(record["tails"]["buy"], record["tails"]["territory"], strict=True)
            gain = statistics.mean(a["terminal"]["winShare"] - b["terminal"]["winShare"]
                                   for a, b in outcomes)
            observations.append((record, gain))
    filters = {
        "all": lambda row: True,
        "peaceful-market": lambda row: not row["attack"],
        "attack": lambda row: row["attack"],
        "first-epoch": lambda row: row["epoch"] == 1,
        "later-epoch": lambda row: row["epoch"] > 1,
        "second-epoch": lambda row: row["epoch"] == 2,
        "third-epoch": lambda row: row["epoch"] == 3,
        "final-turn": lambda row: row["finalTurn"],
        "cheap-card": lambda row: row["price"] == 3,
        "costlier-card": lambda row: row["price"] > 3,
        "empty-hand": lambda row: row["handSize"] == 0,
    }
    groups = {}
    for name, predicate in filters.items():
        gains = [gain for row, gain in observations if predicate(row)]
        if gains:
            groups[name] = interval(gains)
    return {"origins": origins, "eligible": len(observations), "terminalRollouts": 4 * len(observations),
            "buyMinusTerritory": groups}


def main(root, previous, count, resume, expansion, territory=False):
    remote = TERRITORY_REMOTE if territory else EXPANSION_REMOTE if expansion else REMOTE
    campaign = Campaign(root, remote=remote, resume=resume)
    campaign.validate_runtime()
    if territory:
        qualified = read(previous / "automata-heterogeneous-20261118/table-fresh.json")
        if not qualified["passed"]:
            raise ValueError("The card tradeoff requires the qualified mixed-table policy.")
        baseline = qualified["config"]
    else:
        baseline = read(previous / "automata-initiative-20261118/result.json")["comparison"]["config"]
    stage = simulated_positions(campaign, root, baseline, count, expansion=expansion, territory=territory)
    summarize = territory_report if territory else expansion_report if expansion else report
    write(root / "result.json", summarize(stage))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--count", type=int, default=384)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--expansion-card", action="store_true")
    mode.add_argument("--card-versus-territory", action="store_true")
    parser.add_argument("--resume", action="store_true")
    arguments = parser.parse_args()
    if arguments.count not in range(32,1537):
        parser.error("--count must be between 32 and 1536")
    main(arguments.root.resolve(), arguments.previous.resolve(), arguments.count, arguments.resume,
         arguments.expansion_card, arguments.card_versus_territory)
