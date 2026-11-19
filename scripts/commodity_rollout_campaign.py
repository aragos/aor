"""Estimate play-versus-hold values at natural commodity timing positions."""
import argparse
import json
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, seed_bank, sha, write


REMOTE = "/home/aragos/aor-worker/commodity-rollouts-20261118"


def simulated_positions(campaign, root, baseline, count):
    stage = root / "play-or-hold"
    stage.mkdir(exist_ok=campaign.resume)
    seeds = seed_bank("commodity-play-or-hold-v1", count)
    timing = {"kind": "affordable-commodity-retention-1", "futureWeight": .10,
              "minimumGrowth": 8, "currentPayoutLimit": 10}
    method = {"kind": "natural-commodity-root-screen-1", "seeds": seeds, "initiative": baseline,
              "commodityTiming": timing, "tailReplicates": 2, "runtime": campaign.runtime}
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
                       replayIndices=[0,count-1], initiative=baseline, commodityTiming=timing,
                       commodityRollouts=True)
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


def main(root, previous, count, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    first = read(previous / "automata-initiative-20261118/result.json")
    simulated_positions(campaign, root, first["comparison"]["config"], count)
    write(root / "result.json", report(root / "play-or-hold"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--count", type=int, default=384)
    parser.add_argument("--resume", action="store_true")
    arguments = parser.parse_args()
    if arguments.count not in range(32,1537):
        parser.error("--count must be between 32 and 1536")
    main(arguments.root.resolve(), arguments.previous.resolve(), arguments.count, arguments.resume)
