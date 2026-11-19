"""Train bounded bid variation with recent initiative evidence and the live attack rule."""

import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, read, sha, write
from midgame_bid_mixes_campaign import CANDIDATES as PREVIOUS, match, mix
from midgame_rematch_campaign import earlier_games


REMOTE = "/home/aragos/aor-worker/midgame-bid-order-gates-20261119"
DEVELOP = 384
FRESH = 896
SOURCE_WORLDS = 1280
MINIMUM_MODE_REDUCTION = .07


def guarded(bids, weights, *, quorum=True, leader=False):
    return {**mix(bids, weights, use_order=True), "requireRepeatedEvidence": quorum,
            "focusScoreLeader": leader}


CANDIDATES = {
    "earlier-original": PREVIOUS["earlier"],
    "any-rival": guarded([16, 17, 18, 19, 20], [2, 3, 8, 3, 2], quorum=False),
    "repeated-order": guarded([16, 17, 18, 19, 20], [2, 3, 8, 3, 2]),
    "repeated-earlier": guarded([15, 16, 17, 18], [1, 3, 2, 6]),
    "leader-priority": guarded([16, 17, 18, 19], [3, 2, 7, 2], leader=True),
    "extra-stock": guarded([18, 19, 20, 21], [6, 2, 3, 1]),
}


def nomination(screen):
    ranked = []
    for name, tables in screen.items():
        effects = [row["own"]["mean"] for row in tables.values()]
        if (min(effects) >= -.02 and statistics.mean(effects) >= .005
                and min(row["modalShareReduction"] for row in tables.values()) >= MINIMUM_MODE_REDUCTION):
            ranked.append((.6 * min(effects) + .4 * statistics.mean(effects), name))
    return max(ranked)[1] if ranked else None


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    qualified = previous / "automata-capital-evolved-targeting-20261119"
    proof = read(qualified / "result.json")
    deployed = read(previous / "local-preview-18782/launch.json")
    if not proof["passed"] or not deployed["healthy"]:
        raise ValueError("This bidding study uses the exact wider attack rule already qualified for human play.")
    basis = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    anchor = {**basis, "retargeting": proof["configuration"]}
    history = qualified / "capital-evolved-targeting-fresh-experienced"
    previous_dev, forecast_dev = earlier_games(
        history, count=DEVELOP, arm="evolved", worlds_per_stage=SOURCE_WORLDS,
    )
    previous_fresh, forecast_fresh = earlier_games(
        history, start=DEVELOP, count=FRESH, arm="evolved", worlds_per_stage=SOURCE_WORLDS,
    )
    plan = {"currentPreviewSha256": deployed["assetSha256"],
            "historySha256": sha(history / "method.json"), "targeting": proof["configuration"],
            "candidates": CANDIDATES, "developmentWorlds": DEVELOP, "freshWorlds": FRESH,
            "developmentPreviousGames": previous_dev, "freshPreviousGames": previous_fresh}
    manifest = root / "plan.json"
    if not manifest.exists():
        write(manifest, plan)
    elif read(manifest) != plan:
        raise ValueError("A resumed order study must preserve all methods and prior histories.")
    screening = match(
        campaign, "order-gates-develop", CANDIDATES, DEVELOP, anchor, previous_dev, forecast_dev,
    )
    choice = nomination(screening)
    write(root / "screen.json", {"selected": choice, "outcomes": screening})
    if not choice or campaign.remaining() < 2100:
        write(root / "result.json", {"passed": False, "stage": "screen", "selected": choice})
        return
    fresh = match(
        campaign, "order-gates-fresh", {choice: CANDIDATES[choice]}, FRESH, anchor,
        previous_fresh, forecast_fresh,
    )[choice]
    outcome = [case["own"] for case in fresh.values()]
    passed = (min(case["lower95"] for case in outcome) > -.025
              and statistics.mean(case["mean"] for case in outcome) >= .01
              and fresh["repeated-games"]["own"]["lower95"] > 0
              and min(case["modalShareReduction"] for case in fresh.values()) >= MINIMUM_MODE_REDUCTION)
    write(root / "result.json", {"passed": passed, "selected": choice,
                               "configuration": CANDIDATES[choice], "outcomes": fresh})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
