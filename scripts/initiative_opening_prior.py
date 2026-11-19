"""Test the opening mix against a rival that has already learned the usual opening bid."""
import argparse
from pathlib import Path

from initiative_campaign import Campaign, read, sha, write


REMOTE = "/home/aragos/aor-worker/initiative-opening-prior-20261118"


def main(root, primary, mix):
    campaign = Campaign(root, remote=REMOTE)
    campaign.validate_runtime()
    if campaign.remaining() < 470:
        write(root / "result.json", {"status": "insufficient-time"})
        return
    first = read(primary / "result.json")
    evidence = primary / "series-forecast-preview.json"
    observed = read(evidence)
    openings = observed["openingBidCounts"]
    if not first["passed"] or not openings:
        raise ValueError("The opening prior requires the admitted preview's simulated history.")
    opening_bid = int(max(openings, key=openings.__getitem__))
    screen = read(mix / "history-screen/summary.json")
    candidates = [screen[key] for key in ("opening-and-near-ties", "wider-mixture")]
    strongest = max(candidates, key=lambda row: row["adaptive"])["config"]
    choices = {"whole-mix": strongest, "later-only": dict(strongest, openingSpread=0)}
    results = campaign.stage(
        "known-opening", choices, 192, ("REVEALED_UNDERCUT",), shard=64,
        baseline=first["comparison"]["config"], opening_bid_prior=opening_bid,
    )
    write(root / "result.json", {"status": "opening-prior-complete", "comparison": results,
                                "priorEvidenceSha256": sha(evidence)})

    check_deeper_rival(campaign, primary, first["comparison"]["config"], strongest)


def check_deeper_rival(campaign, primary, baseline, candidate):
    if campaign.remaining() < 345:
        return
    mirror = primary.parent / "automata-initiative-mirror-20261118"
    validation = read(mirror / "extra-result.json")
    response = validation["response"]["response"]
    if response["outcomes"]["QUALIFIED"]["lower95"] <= 0:
        return
    comparison = campaign.stage(
        "deeper-rival", {"mixture": candidate}, 192, ("INDEPENDENT_INITIATIVE",),
        shard=64, baseline=baseline, rival=response["config"], rival_offsets=(1,),
    )
    write(campaign.root / "deeper-rival/result.json", comparison)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--primary", type=Path, required=True)
    parser.add_argument("--mix", type=Path, required=True)
    args = parser.parse_args()
    main(args.root.resolve(), args.primary.resolve(), args.mix.resolve())
