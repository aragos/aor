"""Compare bidding policies across fixed and learning opponents on separate world banks."""
import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, read, write


REMOTE = "/home/aragos/aor-worker/initiative-extended-20261118"


def roster(mix):
    return {
        "mixture": mix,
        "conservative": dict(mix, maximumTokenLoss=2, bidScoreTolerance=0.2),
        "one-token": dict(mix, maximumTokenLoss=1, bidScoreTolerance=0.2, openingSpread=1),
        "no-opening-mix": dict(mix, openingSpread=0),
        "wide-opening": dict(mix, openingSpread=3),
        "recent-rows": dict(mix, historyDecay=0.45),
        "protect-expansion": dict(mix, tokenPenalty=0.7),
        "four-token": dict(mix, maximumTokenLoss=4),
        "four-token-wide": dict(mix, maximumTokenLoss=4, bidScoreTolerance=0.6),
        "very-wide": dict(mix, maximumTokenLoss=5, bidScoreTolerance=0.75, openingSpread=3),
    }


def compare(campaign, label, candidates, worlds, live, rival, opening_bid):
    cases = (
        ("ordinary", "QUALIFIED", None, None),
        ("adaptive", "REVEALED_UNDERCUT", None, None),
        ("familiar", "REVEALED_UNDERCUT", None, opening_bid),
        ("champion-lineup", "INDEPENDENT_INITIATIVE", rival, None),
    )
    summaries = {name: {} for name in candidates}
    for case, style, opponent, prior in cases:
        rows = campaign.stage(
            f"{label}-{case}", candidates, worlds, (style,), shard=128,
            rival=opponent, baseline=live, opening_bid_prior=prior,
        )
        for name, data in rows.items():
            summaries[name][case] = data["outcomes"][style]
    return summaries


def rank(summaries):
    def score(name):
        gains = [row["mean"] for row in summaries[name].values()]
        return 0.7 * min(gains) + 0.3 * statistics.mean(gains)

    return sorted(summaries, key=score, reverse=True)


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    if campaign.remaining() < 3600:
        raise ValueError("The combined league needs at least one hour for its two cohorts.")
    first = read(previous / "automata-initiative-20261118/result.json")
    mix_folder = previous / "automata-initiative-mix-20261118"
    fresh = read(mix_folder / "history-fresh/summary.json")["recency"]
    openings = read(mix_folder / "series-fresh-recency.json")["openingBidCounts"]
    live = first["comparison"]["config"]
    mixture = fresh["config"]
    opening_bid = min(int(bid) for bid in openings)
    candidates = roster(mixture)
    screen = compare(campaign, "league-screen", candidates, 128, live, mixture, opening_bid)
    finalists = {name: candidates[name] for name in rank(screen)[:4]}
    screen_result = root / "league-screen.json"
    screen_payload = {"outcomes": screen, "finalists": finalists}
    if resume and screen_result.exists():
        if read(screen_result) != screen_payload:
            raise ValueError("The completed screen no longer matches its verified shards.")
    else:
        write(screen_result, screen_payload)
    confirmation = compare(campaign, "league-confirm", finalists, 256, live, mixture, opening_bid)
    write(root / "league-confirm.json", {
        "outcomes": confirmation,
        "ranking": rank(confirmation),
        "configs": finalists,
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
