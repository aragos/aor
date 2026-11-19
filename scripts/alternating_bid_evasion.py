"""Test whether a prepared rival can recover wins by hiding its repeated low bids."""

import argparse
from pathlib import Path

from alternating_bid_rivals import MODELS
from initiative_campaign import Campaign, interval, read, sha, write
from initiative_heterogeneous_campaign import lineup
from initiative_opening_strategy import load_games
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/alternating-bid-evasion-20261119"
WORLDS = 768
PEERS = [2, 3]
STAGE = "alternating-bid-evasion-fresh"
PATTERNS = {"control": [17, 17], "pairs": [17, 17, 20, 20],
            "wide": [15, 17, 18, 22], "outside": [14, 21]}


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    withheld = previous / "automata-alternating-bid-holdout-20261119/result.json"
    if not read(withheld)["passed"]:
        raise ValueError("The evasion test needs the qualified alternating-bid response.")
    basis = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    attack = read(previous / "automata-retargeting-coevolution-20261119/result.json")["configuration"]
    guard = read(previous / "automata-guarded-finishes-20261119/result.json")["policy"]
    declaration = root / "plan.json"
    plan = {"worlds": WORLDS, "challengerBids": PATTERNS, "qualificationSha256": sha(withheld),
            "automata": MODELS["alternating"], "finish": guard, "attack": attack}
    if not declaration.exists():
        write(declaration, plan)
    elif read(declaration) != plan:
        raise ValueError("A resumed challenge must face the exact same target rules and bid cycles.")
    style, setup, _ = selection_context(basis, "known-16", WORLDS, True)
    candidates = dict.fromkeys((name for name in PATTERNS if name != "control"), basis["baseline"])
    campaign.stage(
        STAGE, candidates, WORLDS, (style,), shard=128, baseline=basis["baseline"],
        lineup=lineup(dict.fromkeys(PEERS, basis["baseline"])), preview_peer_offsets=PEERS,
        leading_rival_variants=dict.fromkeys(PATTERNS, attack),
        final_advance_variants=dict.fromkeys(PATTERNS, guard), capital_geography_variants=PATTERNS,
        phase_bid_variants=dict.fromkeys(PATTERNS, MODELS["alternating"]),
        counter_variants={name:dict(setup["counter"], midgamePriorCycle=[bid+1 for bid in pattern])
                          for name,pattern in PATTERNS.items()},
        rival_targeting_lineups=dict.fromkeys(
            PATTERNS, {"kind": "leading-rival-table-1", "offsets": [1, *PEERS], "policy": attack},
        ),
    )
    folder = root / STAGE
    before = load_games(folder, "control")
    results = {}
    for name in candidates:
        after = load_games(folder, name)
        if set(before) != set(after) or set(before) != set(range(WORLDS)):
            raise ValueError("Each prepared rival must face the same complete simulation bank.")
        changes, challenger_wins, fixed_wins = [], [], []
        for index in range(WORLDS):
            a, b = before[index], after[index]
            seat = a["seat"]
            if ((a["seed"], seat, a["diagnostics"]["CANDIDATE"]["openingBid"]) !=
                    (b["seed"], b["seat"], b["diagnostics"]["CANDIDATE"]["openingBid"])):
                raise ValueError("A later evasion pattern must preserve the original matchup and bid.")
            challenger = (seat + 1) % 4
            old, new = (row["roles"]["CANDIDATE"]["wins"][challenger] for row in (a, b))
            changes.append(new - old)
            fixed_wins.append(old)
            challenger_wins.append(new)
        results[name] = {"challengerDelta": interval(changes),
                         "fixed17WinShare": interval(fixed_wins),
                         "challengerWinShare": interval(challenger_wins)}
    write(root / "result.json", {"diagnostic": True, "outcomes": results})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
