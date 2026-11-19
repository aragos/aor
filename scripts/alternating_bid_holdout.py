"""Check the wider bid response against independent bid cycles and an adaptive opponent."""

import argparse
from pathlib import Path
import statistics

from alternating_bid_rivals import MODELS
from initiative_campaign import Campaign, read, sha, write
from initiative_heterogeneous_campaign import lineup
from table_bid_adoption import PEERS, compare
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/alternating-bid-holdout-20261119"
CASES = {"alternate-17-18": [17, 18], "cycle-16-19-17-20": [16, 19, 17, 20], "rolling": None}
WORLDS = 640


def main(root, previous, resume, two_challengers):
    remote = REMOTE.replace("holdout", "two-challengers") if two_challengers else REMOTE
    campaign = Campaign(root, remote=remote, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    lab = previous / "automata-alternating-bid-rivals-20261119"
    prior = read(lab / "plan.json")
    if campaign.runtime != read(lab / "runtime.json") or prior["bidPolicies"] != MODELS:
        raise ValueError("The held-out comparison must preserve the exact executable and policies.")
    anchor = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    attack = read(previous / "automata-retargeting-coevolution-20261119/result.json")["configuration"]
    guard = read(previous / "automata-guarded-finishes-20261119/result.json")["policy"]
    peer_offsets = [3] if two_challengers else PEERS
    cases = {"alternate-17-18": [17, 18], "rolling": None} if two_challengers else CASES
    challengers = (1, 2) if two_challengers else (1,)
    peers = lineup(dict.fromkeys(peer_offsets, anchor["baseline"]))
    plan = {"initialPlanSha256": sha(lab / "plan.json"), "cases": cases, "worlds": WORLDS,
            "minimumLower95": -.025, "minimumStressMean": .015}
    if two_challengers:
        plan["challengerOffsets"] = list(challengers)
    declaration = root / "plan.json"
    if not declaration.exists():
        write(declaration, plan)
    elif read(declaration) != plan:
        raise ValueError("A resume must use the originally held-out opponent forecasts.")
    parties = ("control", "alternating")
    outcomes = {}
    style, setup, _ = selection_context(anchor, "known-16", WORLDS, True)
    for case, cycle in cases.items():
        if cycle is None:
            counter = {"minimumObservations": 2, "lookbackTurns": 4, "openingBidPrior": 17}
        else:
            counter = dict(setup["counter"], midgamePriorCycle=[bid + 1 for bid in cycle])
        if two_challengers:
            counter["targetedRivalCount"] = len(challengers)
        prefix = "two-challengers" if two_challengers else "holdout"
        stage = f"alternating-bid-{prefix}-fresh-{case}"
        campaign.stage(
            stage, {"alternating": anchor["baseline"]}, WORLDS, (style,), shard=128,
            baseline=anchor["baseline"], lineup=peers, preview_peer_offsets=peer_offsets,
            leading_rival_variants=dict.fromkeys(parties, attack),
            final_advance_variants=dict.fromkeys(parties, guard), capital_geography_variants=parties,
            phase_bid_variants={"control": MODELS["response"], "alternating": MODELS["alternating"]},
            counter_variants=dict.fromkeys(parties, counter),
            rival_targeting_lineups=dict.fromkeys(
                parties, {"kind": "leading-rival-table-1",
                          "offsets": [*challengers, *peer_offsets], "policy": attack},
            ),
        )
        outcomes[case] = compare(root / stage, parties, challengers)
        write(root / "outcomes.json", outcomes, replace=True)
    effects = {case: row["automationWinShareEffect"] for case, row in outcomes.items()}
    stress = [effect for case, effect in effects.items() if case != "rolling"]
    passed = (min(effect["lower95"] for effect in effects.values()) > plan["minimumLower95"]
              and statistics.mean(effect["mean"] for effect in stress) >= plan["minimumStressMean"]
              and any(effect["lower95"] > 0.0 for effect in stress))
    result = {"diagnostic": True} if two_challengers else {"passed": passed}
    write(root / "result.json", {**result, "outcomes": outcomes})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--two-challengers", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume, args.two_challengers)
