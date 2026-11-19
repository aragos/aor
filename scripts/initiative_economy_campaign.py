"""Compare turn-order decisions with and without plan-dependent resource valuation."""
import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, read, write
from initiative_league import compare, rank


REMOTE = "/home/aragos/aor-worker/initiative-economy-20261118"


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    if campaign.remaining() < 3600:
        raise ValueError("The economic bid experiment needs an hour for its two cohorts.")
    league = read(previous / "automata-extended-20261118/league-confirm.json")
    name = league["ranking"][0]
    baseline = league["configs"][name]
    openings = read(previous / "automata-initiative-mix-20261118/series-fresh-recency.json")
    opening_bid = min(int(bid) for bid in openings["openingBidCounts"])
    proposed = {
        "light-retention": dict(baseline, planWeight=.03),
        "strong-retention": dict(baseline, planWeight=.10),
        "one-extra": dict(baseline, maximumExtraTokens=1, planWeight=.05),
        "three-extra": dict(baseline, maximumExtraTokens=3, planWeight=.05),
        "three-extra-strong": dict(baseline, maximumExtraTokens=3, planWeight=.10),
        "five-extra-strong": dict(baseline, maximumExtraTokens=5, planWeight=.10),
        "reserve-cash": dict(baseline, maximumExtraTokens=3, planWeight=.08, extraCashPenalty=.60),
    }
    screen = compare(campaign, "economy-screen", proposed, 128, baseline, baseline, opening_bid)
    winners = [key for key in rank(screen) if statistics.mean(
        outcome["mean"] for outcome in screen[key].values()) >= .01]
    finalists = {key: proposed[key] for key in winners[:3]}
    screen_payload = {"outcomes": screen, "baseline": baseline, "finalists": finalists}
    screen_result = root / "economy-screen.json"
    if resume and screen_result.exists():
        if read(screen_result) != screen_payload:
            raise ValueError("The completed economic screen differs from its verified shards.")
    else:
        write(screen_result, screen_payload)
    if not finalists or campaign.remaining() < 2400:
        return
    confirmation = compare(campaign, "economy-confirm", finalists, 256,
                           baseline, baseline, opening_bid)
    write(root / "economy-confirm.json", {
        "outcomes": confirmation, "baselineName": name, "baseline": baseline,
        "ranking": rank(confirmation), "configs": finalists,
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
