"""Test last-turn expansion choices using the projected final score margin."""
import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, read, write


REMOTE = "/home/aragos/aor-worker/final-expansion-20261118"


def config(scale, bonus, own, public):
    return {"kind":"last-turn-score-margin-1", "marginScale":scale, "bonus":bonus,
            "ownedCommodityWeight":own, "publicCommodityWeight":public}


def compare(campaign, label, candidates, worlds, baseline):
    result = {name:{} for name in candidates}
    for case, style, prior in (("ordinary","QUALIFIED",None), ("familiar","REVEALED_UNDERCUT",19)):
        outcomes = campaign.stage(f"{label}-{case}", dict.fromkeys(candidates, baseline), worlds,
                                  (style,), shard=64 if worlds == 192 else 96, baseline=baseline,
                                  opening_bid_prior=prior, final_expansion=candidates)
        for name, score in outcomes.items():
            result[name][case] = score["outcomes"][style]
    return result


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    league = read(previous / "automata-extended-20261118/league-confirm.json")
    baseline = league["configs"][league["ranking"][0]]
    candidates = {
        "income-only": config(35., 4., 0., 0.),
        "held-card": config(35., 4., .6, 0.),
        "public-exposure": config(35., 4., .6, .20),
        "hold-the-lead": config(65., 8., .8, .25),
    }
    screen = compare(campaign, "last-turn-screen", candidates, 192, baseline)
    def gain(name):
        return statistics.mean(outcome["mean"] for outcome in screen[name].values())

    nominee = max(screen, key=gain)
    admitted = gain(nominee) >= .015 and min(o["mean"] for o in screen[nominee].values()) >= -.025
    payload = {"comparison": screen, "configs": candidates, "nominee": nominee, "admitted": admitted}
    result = root / "screen-result.json"
    if resume and result.exists():
        if read(result) != payload:
            raise ValueError("The last-turn screen differs from its verified results.")
    else:
        write(result, payload)
    if admitted and campaign.remaining() >= 2700:
        fresh = compare(campaign, "last-turn-fresh", {nominee:candidates[nominee]}, 384, baseline)
        write(root / "result.json", {"comparison":fresh, "config":candidates[nominee]})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
