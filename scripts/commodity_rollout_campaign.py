"""Compare action pairs from naturally reached card and stabilization positions."""
import argparse
import json
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, seed_bank, sha, write


REMOTE = "/home/aragos/aor-worker/commodity-rollouts-20261118"
EXPANSION_REMOTE = "/home/aragos/aor-worker/expansion-card-rollouts-20261118"
STABILIZATION_REMOTE = "/home/aragos/aor-worker/stabilization-rollouts-20261119"


def simulated_positions(campaign, root, baseline, count, *, expansion=False, stabilization=False):
    topic, bank = "play-or-hold", "commodity-play-or-hold-v1"
    if expansion:
        topic, bank = "buy-or-pass", "expansion-buy-or-pass-v1"
    elif stabilization:
        topic, bank = "cash-or-misery", "stabilization-cash-or-misery-v1"
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
    elif stabilization:
        method["kind"] = "natural-stabilization-root-screen-1"
        method.pop("commodityTiming")
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
        elif stabilization:
            request["stabilizationRollouts"] = True
        else:
            request.update(commodityTiming=timing, commodityRollouts=True)
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


def stabilization_report(stage):
    origins, observations = 0, []
    for file in sorted(stage.glob("root-*/worlds.jsonl")):
        with file.open() as stream:
            for line in stream:
                world = json.loads(line)
                record = world["diagnostics"]["CANDIDATE"]["stabilizationRollouts"]
                origins += 1
                if record["status"] != "paired":
                    continue
                cash = [tail["terminal"] for tail in record["tails"]["cash"]]
                misery = [tail["terminal"] for tail in record["tails"]["misery"]]
                actor = f"p{world['seat'] + 1}"

                def chaos(result):
                    return next(row["total"] for row in result["scores"] if row["player"] == actor) is None

                outcomes = list(zip(cash, misery, strict=True))
                observations.append((
                    record,
                    statistics.mean(a["winShare"] - b["winShare"] for a, b in outcomes),
                    statistics.mean(int(chaos(a)) - int(chaos(b)) for a, b in outcomes),
                ))
    filters = {
        "all": lambda row: True,
        "policy-paid-cash": lambda row: row["ordinary"] == "stabilize cash",
        "policy-took-misery": lambda row: row["ordinary"] == "stabilize misery",
        "low-misery-after": lambda row: row["miseryAfter"] < 350,
        "high-misery-after": lambda row: row["miseryAfter"] >= 350 and not row["immediateChaos"],
        "immediate-chaos": lambda row: row["immediateChaos"],
        "unplayed-misery-event": lambda row: row["famineOutstanding"] or row["mysticismOutstanding"],
        "cash-shortfall-after-payment": lambda row: row["cash"] - row["price"] < 20,
    }
    groups = {}
    for label, predicate in filters.items():
        eligible = [(win, chaos) for row, win, chaos in observations if predicate(row)]
        if eligible:
            groups[label] = {"winShare": interval([point[0] for point in eligible]),
                             "chaos": interval([point[1] for point in eligible])}
    return {"origins": origins, "eligible": len(observations), "terminalRollouts": len(observations) * 4,
            "cashMinusMisery": groups}


def main(root, previous, count, resume, expansion, stabilization=False):
    remote = STABILIZATION_REMOTE if stabilization else EXPANSION_REMOTE if expansion else REMOTE
    campaign = Campaign(root, remote=remote, resume=resume)
    campaign.validate_runtime()
    first = read(previous / "automata-initiative-20261118/result.json")
    stage = simulated_positions(campaign, root, first["comparison"]["config"], count,
                                expansion=expansion, stabilization=stabilization)
    summarize = stabilization_report if stabilization else expansion_report if expansion else report
    write(root / "result.json", summarize(stage))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--count", type=int, default=384)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--expansion-card", action="store_true")
    mode.add_argument("--stabilization", action="store_true")
    parser.add_argument("--resume", action="store_true")
    arguments = parser.parse_args()
    if arguments.count not in range(32,1537):
        parser.error("--count must be between 32 and 1536")
    main(arguments.root.resolve(), arguments.previous.resolve(), arguments.count, arguments.resume,
         arguments.expansion_card, arguments.stabilization)
