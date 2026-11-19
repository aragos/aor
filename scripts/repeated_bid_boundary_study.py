"""Measure how a qualified bid response fares against other fixed and changing rival populations."""

import argparse
from pathlib import Path

from initiative_campaign import Campaign, read, sha, write
from phase_aware_bids import score
from repeated_undercut_campaign import ARMS, MODE
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/repeated-bid-boundary-20261119"
WORLDS = 640
BIDS = {"fixed-15": (15,), "fixed-18": (18,), "fixed-19": (19,),
        "mixed-budgets": (14, 15, 16, 17, 18, 19, 20, 21)}


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    prior = previous / "automata-repeated-undercut-response-20261119"
    source = read(prior / "result.json")
    if not source["passed"] or not source["guardIncluded"]:
        raise ValueError("The boundary study requires the independently qualified bidding policy.")
    anchor = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    attack_path = previous / "automata-retargeting-coevolution-20261119/result.json"
    attack, guard = read(attack_path)["configuration"], read(prior / "plan.json")["finishPolicy"]
    style, setup, _ = selection_context(anchor, "known-16", WORLDS, True)
    plan = {"bidResponse": MODE, "qualifiedResultSha256": sha(prior / "result.json"),
            "sourceRuntime": read(prior / "runtime.json"), "attackSha256": sha(attack_path),
            "finishPolicy": guard, "worlds": WORLDS,
            "challengerBids": {name: list(bids) for name, bids in BIDS.items()}}
    frozen = root / "plan.json"
    if not frozen.exists():
        write(frozen, plan)
    elif read(frozen) != plan:
        raise ValueError("The boundary study must preserve its budgets and exact runtime on resume.")
    if campaign.runtime != plan["sourceRuntime"]:
        raise ValueError("The qualification and the boundary study must use identical executables.")
    outcomes = {}
    for case, budgets in BIDS.items():
        stage = f"repeated-bid-boundary-fresh-{case}"
        forecasts = [budgets[index % len(budgets)] + 1 for index in range(WORLDS)]
        campaign.stage(
            stage, {"response": anchor["baseline"]}, WORLDS, (style,), shard=128,
            baseline=anchor["baseline"], lineup=setup["lineup"], capital_geography_variants=ARMS,
            leading_rival_variants=dict.fromkeys(ARMS, attack),
            final_advance_variants=dict.fromkeys(ARMS, guard), phase_bid_variants={"response": MODE},
            counter_variants=dict.fromkeys(ARMS, setup["counter"]),
            series_midgame_priors=dict.fromkeys(ARMS, forecasts),
            rival_targeting_lineups=dict.fromkeys(
                ARMS, {"kind": "leading-rival-table-1", "offsets": [1], "policy": attack},
            ),
        )
        outcomes[case] = score(root / stage, "response", WORLDS)
        write(root / "outcomes.json", outcomes, replace=True)
    write(root / "result.json", {"diagnostic": True, "outcomes": outcomes})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
