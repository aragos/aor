"""Measure the bidding response when all three automated seats use the same preview policy."""

import argparse
from pathlib import Path

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_heterogeneous_campaign import lineup
from initiative_opening_strategy import load_games
from repeated_undercut_campaign import ARMS, MODE
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/table-bid-adoption-20261119"
CASES = ("fixed-17", "rolling")
WORLDS = 640
PEERS = [2, 3]


def compare(folder):
    before, after = (load_games(folder, role) for role in ARMS)
    if set(before) != set(after) or set(before) != set(range(WORLDS)):
        raise ValueError("Measure the entire common seed bank with three automated seats.")
    challenger = []
    tracked = []
    for index in range(WORLDS):
        a, b = before[index], after[index]
        seat = a["seat"]
        if ((a["seed"], seat) != (b["seed"], b["seat"]) or
                a["diagnostics"]["CANDIDATE"]["openingBid"] !=
                b["diagnostics"]["CANDIDATE"]["openingBid"]):
            raise ValueError("Both arms must reproduce the exact same seats and first bids.")
        old, new = (row["roles"]["CANDIDATE"]["wins"] for row in (a, b))
        human = (seat + 1) % 4
        challenger.append(old[human] - new[human])
        tracked.append(new[seat] - old[seat])
    return {"automationWinShareEffect": interval(challenger), "trackedSeatEffect": interval(tracked)}


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    response_file = previous / "automata-repeated-undercut-response-20261119/result.json"
    finish_file = previous / "automata-guarded-finishes-20261119/result.json"
    attack_file = previous / "automata-retargeting-coevolution-20261119/result.json"
    if not all(read(path)["passed"] for path in (response_file, finish_file, attack_file)):
        raise ValueError("Table adoption must preserve each qualified component.")
    anchor = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    attack, guard = read(attack_file)["configuration"], read(finish_file)["policy"]
    preview_lineup = lineup(dict.fromkeys(PEERS, anchor["baseline"]))
    plan = {"qualifiedBidResponseSha256": sha(response_file), "finishSha256": sha(finish_file),
            "attackSha256": sha(attack_file), "baseline": anchor["baseline"],
            "peers": PEERS, "worlds": WORLDS, "cases": CASES}
    declaration = root / "plan.json"
    if not declaration.exists():
        write(declaration, {**plan, "cases": list(CASES)})
    elif read(declaration) != {**plan, "cases": list(CASES)}:
        raise ValueError("The table trial must keep the original peers and opponent cases on resume.")
    style, setup, _ = selection_context(anchor, "known-16", WORLDS, True)
    outcomes = {}
    for case in CASES:
        fixed = case == "fixed-17"
        counter = setup["counter"] if fixed else {
            "minimumObservations": 2, "lookbackTurns": 4, "openingBidPrior": 17,
        }
        stage = f"table-bid-adoption-fresh-{case}"
        campaign.stage(
            stage, {"response": anchor["baseline"]}, WORLDS, (style,), shard=128,
            baseline=anchor["baseline"], lineup=preview_lineup, preview_peer_offsets=PEERS,
            leading_rival_variants=dict.fromkeys(ARMS, attack),
            final_advance_variants=dict.fromkeys(ARMS, guard), capital_geography_variants=ARMS,
            phase_bid_variants={"response": MODE}, counter_variants=dict.fromkeys(ARMS, counter),
            series_midgame_priors=dict.fromkeys(ARMS, [18] * WORLDS) if fixed else None,
            rival_targeting_lineups=dict.fromkeys(
                ARMS, {"kind": "leading-rival-table-1", "offsets": [1, *PEERS], "policy": attack},
            ),
        )
        outcomes[case] = compare(root / stage)
        write(root / "outcomes.json", outcomes)
    write(root / "result.json", {"diagnostic": True, "outcomes": outcomes})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
