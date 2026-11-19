"""Compare targeted commodity retention with the frozen live four-head policy."""
import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, read, write


REMOTE = "/home/aragos/aor-worker/commodity-lookahead-20261118"
ECOLOGIES = ("QUALIFIED", "REVEALED_UNDERCUT")


def config(weight, growth, payout):
    return {"kind": "affordable-commodity-retention-1", "futureWeight": weight,
            "minimumGrowth": growth, "currentPayoutLimit": payout}


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    live = read(previous / "automata-initiative-20261118/result.json")["comparison"]["config"]
    proposed = {
        "zero-receipt-light": config(.03, 8, 0),
        "zero-receipt-strong": config(.10, 8, 0),
        "small-receipt": config(.05, 12, 10),
    }
    screen = campaign.stage(
        "commodity-screen", dict.fromkeys(proposed, live), 128, ECOLOGIES,
        shard=64, baseline=live, card_timing=proposed,
    )
    ranked = sorted(screen, key=lambda name: statistics.mean(
        row["mean"] for row in screen[name]["outcomes"].values()), reverse=True)
    nominee = ranked[0]
    gains = [row["mean"] for row in screen[nominee]["outcomes"].values()]
    payload = {"screen": screen, "planned": proposed}
    record = root / "screen-result.json"
    if resume and record.exists():
        if read(record) != payload:
            raise ValueError("The commodity screen no longer matches its validated results.")
    else:
        write(record, payload)
    if min(gains) < -.025 or statistics.mean(gains) < .015 or campaign.remaining() < 2400:
        return
    results = campaign.stage(
        "commodity-fresh", {nominee: live}, 384, ECOLOGIES, shard=96,
        baseline=live, card_timing={nominee: proposed[nominee]},
    )
    write(root / "result.json", {"fresh": results, "timing": proposed[nominee]})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
