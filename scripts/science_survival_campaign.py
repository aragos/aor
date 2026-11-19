"""Check whether saving vulnerable seats with science improves whole-game wins."""

import argparse
import json
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, write


REMOTE = "/home/aragos/aor-worker/science-survival-20261119"


def policy(distance, price):
    return {"kind": "near-chaos-science-1", "maximumDistance": distance, "maximumPrice": price}


def gather(folder, prefix):
    rows = {}
    for path in folder.glob(f"{prefix}-0-*/worlds.jsonl"):
        with path.open() as stream:
            for line in stream:
                row = json.loads(line)
                index, seat = row["index"], row["seat"]
                own = f"p{seat + 1}"
                terminal = row["roles"]["CANDIDATE"]
                if index in rows:
                    raise ValueError("Science survival duplicated a simulation world.")
                rows[index] = (
                    row["seed"],
                    next(score["total"] for score in terminal["scores"] if score["player"] == own) is None,
                    row["diagnostics"]["CANDIDATE"]["scienceSurvivalOverrides"],
                )
    return rows


def evaluate(campaign, name, choices, worlds, baseline):
    interpretation = {role: {} for role in choices}
    for stage, style, prior in (("ordinary","QUALIFIED",None), ("familiar","REVEALED_UNDERCUT",19)):
        label = f"{name}-{stage}"
        results = campaign.stage(
            label, dict.fromkeys(choices, baseline), worlds, (style,), baseline=baseline,
            opening_bid_prior=prior, science_survival=choices, shard=64,
        )
        folder = campaign.root / label
        control = gather(folder, "control")
        for candidate, result in results.items():
            group = gather(folder, candidate)
            if control.keys() != group.keys() or any(row[0] != control[i][0] for i,row in group.items()):
                raise ValueError("Science survival requires matched world banks.")
            interpretation[candidate][stage] = {
                "wins": result["outcomes"][style],
                "chaos": interval([int(state[1])-int(control[i][1]) for i,state in group.items()]),
                "overrides": sum(state[2] for state in group.values()),
                "gamesChanged": sum(state[2] > 0 for state in group.values()),
            }
    return interpretation


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    qualified = read(previous / "automata-heterogeneous-20261118/table-fresh.json")
    if qualified["passed"] is not True:
        raise ValueError("The science survival intervention requires the qualified preview.")
    baseline = qualified["config"]
    choices = {"within-two":policy(2,20), "cheap-within-four":policy(4,12),
               "within-four":policy(4,25), "within-six":policy(6,25)}
    screen = evaluate(campaign, "science-screen", choices, 192, baseline)
    candidates = []
    for name, rows in screen.items():
        gains = [row["wins"]["mean"] for row in rows.values()]
        if min(gains) >= 0 and statistics.mean(gains) >= .015 and all(
            row["gamesChanged"] > 0 for row in rows.values()
        ):
            candidates.append((min(gains), statistics.mean(gains), name))
    report = {"screen": screen, "candidates": choices, "nominees": sorted(candidates, reverse=True)}
    screen_path = root / "screen.json"
    if resume and screen_path.is_file():
        if read(screen_path) != json.loads(json.dumps(report)):
            raise ValueError("The science screen differs from its verified earlier evaluation.")
    else:
        write(screen_path, report)
    if not candidates or campaign.remaining() < 2200:
        write(root / "result.json", {"status":"science-screen-complete", "nominee":None})
        return
    nominee = max(candidates)[-1]
    fresh = evaluate(campaign, "science-fresh", {nominee: choices[nominee]}, 512, baseline)
    write(root / "result.json", {"status":"science-fresh-complete", "nominee":nominee, "fresh":fresh,
                                 "passed":all(row["wins"]["lower95"] > 0 for row in fresh[nominee].values())})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
