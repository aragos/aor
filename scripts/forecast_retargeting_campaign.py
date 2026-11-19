"""Measure the whole-game effect of a public forecast versus a score-leader target."""

import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, read, sha, write
from leading_rival_retargeting_campaign import study


REMOTE = "/home/aragos/aor-worker/forecast-retargeting-20261119"


def compare(campaign, label, plan, arms, worlds):
    overrides = {"leading_rival_variants": {"control": plan["referencePolicy"], **arms}}
    return study(campaign, label, arms, plan, worlds, variant_options=overrides)


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, batch_size=2, background=True, resume=resume)
    campaign.validate_runtime()
    path = previous / "automata-credible-leader-pressure-20261119"
    anchor = read(path / "result.json")
    original = read(path / "plan.json")
    forecast_path = previous / "automata-public-threat-model-20261119/result.json"
    trained = read(forecast_path)
    if not anchor["passed"] or not trained["nominateThreatModel"]:
        raise ValueError("Only the independently supported targeting rule and forecast may enter this test.")
    reference = anchor["configuration"]
    guess = {"kind": "public-threat-linear-1", "weights": trained["weights"]}
    arms = {
        "forecast-near-score": dict(reference, forecast=guess),
        "forecast-near-win": dict(reference, forecast=dict(guess, maximumForecastGap=.18)),
    }
    plan = {
        "baseline": original["baseline"], "rivals": original["rivals"], "counter": original["counter"],
        "referencePolicy": reference, "arms": arms,
        "anchorSha256": sha(path / "result.json"), "forecastSha256": sha(forecast_path),
    }
    declaration = root / "plan.json"
    if not declaration.exists():
        write(declaration, plan)
    elif read(declaration) != plan:
        raise ValueError("The same forecast and reference policy must be preserved on resumption.")
    screen = compare(campaign, "forecast-targeting-screen", plan, arms, 384)
    choices = []
    for name, tables in screen.items():
        effects = [row["mean"] for row in tables.values()]
        mean = statistics.mean(effects)
        if min(effects) >= -.025 and mean >= .01:
            choices.append((mean, min(effects), name))
    write(root / "screen.json", {"outcomes": screen, "nominees": sorted(choices, reverse=True)})
    if not choices or campaign.remaining() < 3600:
        write(root / "result.json", {"passed": False, "stage": "screen"})
        return
    name = max(choices)[-1]
    final = compare(campaign, "forecast-targeting-fresh", plan, {name: arms[name]}, 1024)[name]
    points = tuple(final.values())
    write(root / "result.json", {
        "passed": (min(row["lower95"] for row in points) > -.025
                   and statistics.mean(row["mean"] for row in points) >= .015
                   and sum(row["lower95"] > 0 for row in points) >= 2),
        "selected": name, "configuration": arms[name], "outcomes": final,
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--previous", required=True, type=Path)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
