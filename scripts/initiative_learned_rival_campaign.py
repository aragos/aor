"""Train a public-history rival for its own wins, then test it on separate simulated worlds."""
import argparse
import json
from pathlib import Path

from initiative_campaign import Campaign, interval, read, write


REMOTE = "/home/aragos/aor-worker/initiative-learned-rival-20261118"
CONDITION = ("REVEALED_UNDERCUT",)


def shifts(stage, names):
    def per_world(name):
        outcomes = {}
        for source in stage.glob(f"{name}-0-*/worlds.jsonl"):
            with source.open() as stream:
                for line in stream:
                    row = json.loads(line)
                    opponents = row["diagnostics"]["CANDIDATE"]["publicCounterTargetedRivals"]
                    if len(opponents) != 1:
                        raise ValueError("A calibrated rival requires exactly one controlled challenger.")
                    challenger = int(opponents[0].removeprefix("p")) - 1
                    wins = row["roles"]["CANDIDATE"]["wins"]
                    outcomes[(row["index"],row["seed"],row["seat"])] = (
                        wins[challenger], wins[row["seat"]],
                    )
        return outcomes

    baseline = per_world("control")
    result = {}
    for name in names:
        experiments = per_world(name)
        if experiments.keys() != baseline.keys() or not experiments:
            raise ValueError(f"Rival {name} needs the same complete world bank as its control.")
        result[name] = {
            "rival": interval([wins[0]-baseline[key][0] for key,wins in experiments.items()]),
            "target": interval([wins[1]-baseline[key][1] for key,wins in experiments.items()]),
        }
    return result


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    league = read(previous / "automata-extended-20261118/league-confirm.json")
    target = league["configs"][league["ranking"][0]]
    opening_evidence = read(previous / "automata-initiative-mix-20261118/series-fresh-recency.json")
    low = min(int(amount) for amount in opening_evidence["openingBidCounts"])
    variants = {
        "recency": dict(minimumObservations=1, lookbackTurns=1, openingBidPrior=low),
        "quick-mode": dict(minimumObservations=1, lookbackTurns=3, openingBidPrior=low),
        "low-quartile": dict(forecastQuantile=.25, openingBidPrior=low),
        "low-recent": dict(forecastQuantile=.25, lookbackTurns=4, openingBidPrior=low),
        "minimum": dict(forecastQuantile=0.0, lookbackTurns=4, openingBidPrior=low),
        "median": dict(forecastQuantile=.5, openingBidPrior=low),
        "conserve": dict(forecastQuantile=.75, openingBidPrior=low+1),
    }
    campaign.stage("rival-screen", dict.fromkeys(variants, target), 128, CONDITION,
                   shard=64, baseline=target, counter_variants=variants)
    results = shifts(root / "rival-screen", variants)
    selected = sorted(results, key=lambda name: (
        results[name]["rival"]["mean"], -results[name]["target"]["mean"]), reverse=True)
    finalists = {name: variants[name] for name in selected[:2] if results[name]["rival"]["mean"] > 0}
    payload = {"outcomes": results, "finalists": finalists, "target": target}
    screen = root / "rival-screen.json"
    if resume and screen.exists():
        if read(screen) != payload:
            raise ValueError("The existing rival screen differs from its validated shards.")
    else:
        write(screen, payload)
    if not finalists or campaign.remaining() < 2400:
        return
    campaign.stage("rival-fresh", dict.fromkeys(finalists, target), 384, CONDITION,
                   shard=96, baseline=target, counter_variants=finalists)
    fresh = shifts(root / "rival-fresh", finalists)
    write(root / "rival-fresh.json", {"outcomes": fresh, "configs": finalists, "target": target})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
