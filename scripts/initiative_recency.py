"""Screen recency weights against a public undercutter and the live preview lineup."""
import argparse
from pathlib import Path

from initiative_campaign import Campaign, read, write


REMOTE = "/home/aragos/aor-worker/initiative-recency-20261118"
LINEUPS = ("REVEALED_UNDERCUT", "INDEPENDENT_INITIATIVE")


def main(root, primary):
    campaign = Campaign(root, remote=REMOTE)
    campaign.validate_runtime()
    if campaign.remaining() < 600:
        write(root / "result.json", {"status": "insufficient-time"})
        return
    initial = read(primary / "result.json")
    if not initial.get("passed"):
        raise ValueError("The recency screen needs the admitted preview comparison.")
    live = initial["comparison"]["config"]
    choices = {name: dict(live, historyDecay=decay) for name, decay in (
        ("newest", 0.0), ("half", 0.5), ("gradual", 0.75),
    )}
    screen = campaign.stage("history-screen", choices, 176, LINEUPS,
                            shard=88, rival=live, baseline=live)
    eligible = [case for case in screen.values()
                if min(row["mean"] for row in case["outcomes"].values()) >= -0.015
                and max(row["mean"] for row in case["outcomes"].values()) >= 0.025]
    if not eligible or campaign.remaining() < 580:
        write(root / "result.json", {"status": "history-screen-complete", "screen": screen})
        return
    best = max(eligible, key=lambda case: case["adaptive"])
    fresh = campaign.stage("history-fresh", {"recency": best["config"]}, 384, LINEUPS,
                           rival=live, baseline=live)
    write(root / "result.json", {"status": "history-fresh-complete", "screen": screen, "fresh": fresh})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--primary", type=Path, required=True)
    args = parser.parse_args()
    main(args.root.resolve(), args.primary.resolve())
