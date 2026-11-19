"""Measure which parts of the verified counter help it win."""
import argparse
from pathlib import Path

from initiative_campaign import Campaign, read, write
from initiative_learned_rival_campaign import CONDITION, REMOTE, shifts


def main(root, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    reference = read(root / "rival-fresh.json")
    available = [name for name, row in reference["outcomes"].items() if row["rival"]["lower95"] > 0]
    if not available:
        raise ValueError("The ablation needs an independently verified stronger rival.")
    winner = max(available, key=lambda name: reference["outcomes"][name]["rival"]["mean"])
    combined = reference["configs"][winner]
    opening = combined["openingBidPrior"]
    variants = {
        "opening-only": {"openingBidPrior":opening},
        "tracking-only": {key:value for key,value in combined.items() if key != "openingBidPrior"},
        "combined": combined,
    }
    target = reference["target"]
    campaign.stage("rival-ablation", dict.fromkeys(variants,target), 192, CONDITION,
                   shard=96, baseline=target, counter_variants=variants)
    evidence = shifts(root / "rival-ablation", variants)
    write(root / "rival-ablation.json", {"rival":winner,"variants":variants,"paired":evidence})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(),args.resume)
