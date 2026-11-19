"""Qualify phase-aware bids after several clearly revealed low rival purchases."""

import argparse
from pathlib import Path
import statistics
import time

from initiative_campaign import Campaign, read, sha, write
from phase_aware_bids import score
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/repeated-undercut-response-20261119"
CASES = ("ordinary", "fixed-17", "fixed-16", "rolling")
WORLDS = 768
MODE = {"kind": "phase-aware-bids-1", "profile": "repeated-undercut"}
ARMS = ("control", "response")


def table(campaign, case, anchor, attack, guard):
    ordinary = case == "ordinary"
    style, setup, _ = selection_context(anchor, "mixed" if ordinary else "known-16", WORLDS, True)
    forecast = {"fixed-17": 18, "fixed-16": 17}.get(case)
    counter = setup.get("counter")
    if case == "rolling":
        counter = {"minimumObservations": 2, "lookbackTurns": 4, "openingBidPrior": 17}
    stage = f"repeated-undercut-fresh-{case}"
    campaign.stage(
        stage, {"response": anchor["baseline"]}, WORLDS, (style,), shard=128,
        baseline=anchor["baseline"], lineup=setup["lineup"], capital_geography_variants=ARMS,
        leading_rival_variants=dict.fromkeys(ARMS, attack),
        final_advance_variants=dict.fromkeys(ARMS, guard) if guard else None,
        phase_bid_variants={"response": MODE},
        counter_variants=dict.fromkeys(ARMS, counter) if counter else None,
        series_midgame_priors=dict.fromkeys(ARMS, [forecast] * WORLDS) if forecast is not None else None,
        rival_targeting_lineups=dict.fromkeys(
            ARMS, {"kind": "leading-rival-table-1", "offsets": [1], "policy": attack},
        ) if not ordinary else None,
    )
    return score(campaign.root / stage, "response", WORLDS)


def main(root, previous, resume, wait):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    bid_proof = previous / "automata-phase-aware-bids-20261119/result.json"
    finish_proof = previous / "automata-guarded-finishes-20261119/result.json"
    while wait and (not bid_proof.exists() or not finish_proof.exists()) and campaign.remaining() > 4100:
        time.sleep(20)
    if not bid_proof.exists() or campaign.remaining() < 4100:
        write(root / "result.json", {"passed": False, "status": "prerequisite-not-ready"})
        return
    bid = read(bid_proof)
    if bid["selected"] != "wide" or bid["outcomes"]["fixed-17"]["own"]["lower95"] <= 0:
        write(root / "result.json", {"passed": False, "status": "prepared-rival-benefit-unverified"})
        return
    finish = read(finish_proof) if finish_proof.exists() else {"passed": False}
    guard = finish["policy"] if finish["passed"] else None
    anchor = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    live_file = previous / "automata-retargeting-coevolution-20261119/result.json"
    live = read(live_file)
    if not live["passed"]:
        raise ValueError("The phase comparison requires the newest independently qualified pressure rule.")
    plan = {"worlds": WORLDS, "bidCandidate": MODE, "targetingSourceSha256": sha(live_file),
            "fixedRivalProofSha256": sha(bid_proof), "finishPolicy": guard,
            "finishSourceSha256": sha(finish_proof) if finish_proof.exists() else None}
    path = root / "plan.json"
    if not path.exists():
        write(path, plan)
    elif read(path) != plan:
        raise ValueError("Retain the same latest attack and finish policies for all matched arms.")
    outcomes = {case: table(campaign, case, anchor, live["configuration"], guard) for case in CASES}
    estimates = [row["own"] for row in outcomes.values()]
    passed = (min(row["lower95"] for row in estimates) > -.03
              and outcomes["fixed-17"]["own"]["lower95"] > 0.0
              and statistics.mean(row["mean"] for row in estimates) >= .012)
    write(root / "result.json", {"passed": passed, "outcomes": outcomes, "guardIncluded": guard is not None})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--wait", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume, args.wait)
