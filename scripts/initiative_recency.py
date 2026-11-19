"""Screen recency weights and bounded bid mixtures against the live preview."""
import argparse
from pathlib import Path

from initiative_campaign import Campaign, read, write


REMOTE = "/home/aragos/aor-worker/initiative-mix-20261118"
LINEUPS = ("REVEALED_UNDERCUT", "INDEPENDENT_INITIATIVE")


def predictability_drop(case):
    def reduction(metric):
        return metric["baseline"] - metric["candidate"]

    undercut, mirrored = (case["outcomes"][lineup] for lineup in LINEUPS)
    return min(reduction(undercut["counterAccuracy"]), reduction(mirrored["bidModeShare"]))


def main(root, primary, reuse_screen):
    campaign = Campaign(root, remote=REMOTE)
    campaign.validate_runtime()
    if campaign.remaining() < 820 and not reuse_screen:
        write(root / "result.json", {"status": "insufficient-time"})
        return
    initial = read(primary / "result.json")
    if not initial.get("passed"):
        raise ValueError("The recency screen needs the admitted preview comparison.")
    live = initial["comparison"]["config"]
    choices = {
        "history-half": dict(live, historyDecay=0.5),
        "history-gradual": dict(live, historyDecay=0.75),
        "near-ties": dict(live, bidScoreTolerance=0.2),
        "opening-and-near-ties": dict(live, bidScoreTolerance=0.2, openingSpread=2),
        "wider-mixture": dict(live, maximumTokenLoss=3, bidScoreTolerance=0.35, openingSpread=2),
    }
    screen = campaign.stage("history-screen", choices, 176, LINEUPS,
                            shard=88, rival=live, baseline=live, reuse_complete=reuse_screen)
    eligible = [case for case in screen.values()
                if min(row["mean"] for row in case["outcomes"].values()) >= -0.025
                and (max(row["mean"] for row in case["outcomes"].values()) >= 0.025
                     or predictability_drop(case) >= 0.08)]
    if not eligible or campaign.remaining() < 580:
        write(root / "result.json", {"status": "history-screen-complete", "screen": screen})
        return
    best = max(eligible, key=lambda case: (case["adaptive"], predictability_drop(case)))
    fresh = campaign.stage("history-fresh", {"recency": best["config"]}, 384, LINEUPS,
                           rival=live, baseline=live)
    write(root / "result.json", {"status": "history-fresh-complete", "screen": screen, "fresh": fresh})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--primary", type=Path, required=True)
    parser.add_argument("--reuse-screen", action="store_true",
                        help="validate and summarize previously completed screen shards")
    args = parser.parse_args()
    main(args.root.resolve(), args.primary.resolve(), args.reuse_screen)
