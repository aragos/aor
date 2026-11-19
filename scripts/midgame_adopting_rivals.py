"""Challenge a qualified bid mix against strong rivals who can learn its new bids."""

import argparse
from collections import Counter, defaultdict
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, seed_bank, sha, write
from initiative_opening_strategy import load_games
from initiative_series_rivals import GAMES_PER_GROUP
from midgame_rematch_campaign import earlier_games, public_reveals
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/midgame-adopting-rivals-20261119"
ARMS = ("control", "mix")
CASES = {"prepared-17": (1024, [1]), "rematch-after-adoption": (768, [1, 2, 3])}
SOURCE = "midgame-mixtures-order-gates-fresh-unprepared"


def assess(stage, worlds, priors, history_groups, offsets):
    rows = {arm: load_games(stage, arm) for arm in ARMS}
    effects = defaultdict(list)
    modes = {arm: Counter() for arm in ARMS}
    for index in range(worlds):
        a, b = (rows[arm][index] for arm in ARMS)
        opening = [row["diagnostics"]["CANDIDATE"]["openingBid"] for row in (a, b)]
        seat = a["seat"]
        if (a["seed"], seat, opening[0]) != (b["seed"], b["seat"], opening[1]):
            raise ValueError("The sealed openings must stay identical within each paired test.")
        expected = sorted((seat + offset) % 4 for offset in offsets)
        for row in (a, b):
            rivals = row["diagnostics"]["CANDIDATE"]["retargetingRivalSeats"]
            if sorted(rivals) != expected:
                raise ValueError("Every paired match must preserve the declared adopting rivals.")
        original_win, candidate_win = (row["roles"]["CANDIDATE"]["wins"][seat] for row in (a, b))
        cluster = (index // GAMES_PER_GROUP) % history_groups if history_groups else index
        effects[cluster].append(candidate_win - original_win)
        for arm in ARMS:
            for turn, value in public_reveals(rows[arm][index]).items():
                if 3 <= turn <= 7:
                    if value["prediction"] != priors[arm][index]:
                        raise ValueError("Each rival must use its own frozen public-game forecast.")
                    modes[arm][value["actualTargetBid"]] += 1
    share = {arm: max(counts.values()) / counts.total() for arm, counts in modes.items()}
    return {"worlds": worlds, "ownWin": interval([statistics.mean(group) for group in effects.values()]),
            "modeReduction": share["control"] - share["mix"], "modeShares": share}


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    source = previous / "automata-midgame-bid-order-gates-20261119"
    qualified = read(source / "result.json")
    if not qualified["passed"]:
        write(root / "result.json", {"passed": False, "status": "no-fresh-bid-qualification"})
        return
    live = read(previous / "automata-capital-evolved-targeting-20261119/result.json")["configuration"]
    anchor = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    strategy = qualified["configuration"]
    worlds = CASES["rematch-after-adoption"][0]
    histories, values = {}, {}
    for arm, origin in (("control", "control"), ("mix", qualified["selected"])):
        histories[arm], values[arm] = earlier_games(
            source / SOURCE, count=worlds, arm=origin, worlds_per_stage=896,
        )
    plan = {"sourceSha256": sha(source / "result.json"), "attackPolicy": live,
            "historyBankSha256": sha(source / SOURCE / "method.json"),
            "histories": histories, "modeForecasts": values, "bidMix": strategy}
    method = root / "plan.json"
    if not method.exists():
        write(method, plan)
    elif read(method) != plan:
        raise ValueError("A resume must retain the qualified mix and both public training records.")
    outcomes = {}
    for case, (count, offsets) in CASES.items():
        rematch = case == "rematch-after-adoption"
        style, setup, _ = selection_context(anchor, "experienced" if rematch else "known-16", count, True)
        priors = values if rematch else dict.fromkeys(ARMS, [18] * count)
        stage = f"midgame-adopting-rivals-fresh-{case}"
        used_seeds = {row["seed"] for records in histories.values() for row in records}
        if set(seed_bank(f"{stage}/{style}", count)) & used_seeds:
            raise ValueError("The adapting rivals must learn only from different completed games.")
        campaign.stage(
            stage, {"mix": anchor["baseline"]}, count, (style,), shard=128,
            baseline=anchor["baseline"], lineup=setup["lineup"],
            capital_geography_variants=ARMS, leading_rival_variants=dict.fromkeys(ARMS, live),
            rival_targeting_lineups=dict.fromkeys(
                ARMS, {"kind": "leading-rival-table-1", "offsets": offsets, "policy": live},
            ),
            counter_variants=dict.fromkeys(ARMS, setup["counter"]),
            series_priors=dict.fromkeys(ARMS, setup["priors"]) if rematch else None,
            series_responses=dict.fromkeys(ARMS, setup["responses"]) if rematch else None,
            series_midgame_priors=priors, midgame_bid_variants={"mix": strategy},
        )
        groups = len(anchor["history"]) // (2 * GAMES_PER_GROUP) if rematch else None
        outcomes[case] = assess(root / stage, count, priors, groups, offsets)
    means = [part["ownWin"] for part in outcomes.values()]
    passed = (min(part["lower95"] for part in means) > -.025
              and statistics.mean(part["mean"] for part in means) >= .01
              and all(part["modeReduction"] >= .07 for part in outcomes.values()))
    write(root / "result.json", {"passed": passed, "outcomes": outcomes})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
