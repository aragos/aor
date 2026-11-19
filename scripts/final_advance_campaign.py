"""Measure whether finishing the advance set converts real score leads into wins."""

import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_opening_strategy import load_games
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/final-advance-plans-20261119"
FULL_ADVANCE_SCORE = 2100
POLICIES = {f"margin-{margin}": {"kind": "final-advance-plan-1", "margin": margin}
            for margin in (0, 100, 250)}


def evaluate(campaign, label, case, policies, count, anchor, live):
    style, setup, _ = selection_context(anchor, case, count, True)
    arms = ("control", *policies)
    stage = f"final-advance-{label}"
    campaign.stage(
        stage, dict.fromkeys(policies, anchor["baseline"]), count, (style,), shard=128,
        baseline=anchor["baseline"], lineup=setup["lineup"],
        counter_variants=dict.fromkeys(arms, setup["counter"]) if "counter" in setup else None,
        capital_geography_variants=arms, leading_rival_variants=dict.fromkeys(arms, live),
        final_advance_variants=policies,
    )
    directory = campaign.root / stage
    before = load_games(directory, "control")
    stats = {}
    for name in policies:
        changed = load_games(directory, name)
        deltas, adjusted, completed = [], [], []
        able_to_complete, last_overridden, ended_ahead = 0, 0, 0
        for index in range(count):
            left, right = before[index], changed[index]
            a, b = [row["diagnostics"]["CANDIDATE"] for row in (left, right)]
            seat = left["seat"]
            if (left["seed"], seat, a["openingBid"]) != (right["seed"], right["seat"], b["openingBid"]):
                raise ValueError("The legal finishing sequence must preserve all paired openings.")
            base, candidate = (row["roles"]["CANDIDATE"] for row in (left, right))
            delta = candidate["wins"][seat] - base["wins"][seat]
            deltas.append(delta)
            able_to_complete += b["finalAdvancePlans"] > 0
            last_overridden += b["finalAdvanceLastBuyOverrides"] > 0
            has_full_set = candidate["scores"][seat]["advancePoints"] == FULL_ADVANCE_SCORE
            had_full_set = base["scores"][seat]["advancePoints"] == FULL_ADVANCE_SCORE
            completed.append(int(has_full_set) - int(had_full_set))
            if b["finalAdvanceOverrides"]:
                adjusted.append(delta)
                ended_ahead += has_full_set and candidate["wins"][seat] > 0
            elif delta != 0:
                raise ValueError("A trial with no changed command must reproduce the incumbent's win.")
        stats[name] = {
            "worlds": count, "legalCompletionWorlds": able_to_complete,
            "overriddenWorlds": len(adjusted), "overrodeFinalAdvanceWorlds": last_overridden,
            "finishedAndWonAfterOverride": ended_ahead,
            "fullSetEffect": interval(completed), "winEffect": interval(deltas),
            "affectedWinEffect": interval(adjusted) if adjusted else None,
        }
    return stats


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    reference = previous / "automata-capital-evolved-targeting-20261119/result.json"
    proof = read(reference)
    if not proof["passed"]:
        raise ValueError("Begin from the qualified current automata strategy.")
    anchor = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    plan = {"policies": POLICIES, "referenceSha256": sha(reference), "develop": 768, "fresh": 1024}
    path = root / "plan.json"
    if not path.exists():
        write(path, plan)
    elif read(path) != plan:
        raise ValueError("Keep the original control and candidate finishing policies on resumption.")
    live = proof["configuration"]
    scores = evaluate(campaign, "develop", "mixed", POLICIES, 768, anchor, live)
    shortlist = [name for name, stats in scores.items() if stats["overriddenWorlds"] >= 6
                 and stats["winEffect"]["mean"] >= .002 and stats["fullSetEffect"]["mean"] > 0]
    selected = max(shortlist, key=lambda name: scores[name]["winEffect"]["lower95"]) if shortlist else None
    write(root / "screen.json", {"selected": selected, "outcomes": scores})
    if not selected or campaign.remaining() < 2000:
        write(root / "result.json", {"passed": False, "stage": "screen", "selected": selected,
                                   "outcomes": scores})
        return
    fresh = {case: evaluate(campaign, f"fresh-{case}", case, {selected: POLICIES[selected]},
                            1024, anchor, live)[selected] for case in ("mixed", "known-16")}
    intervals = [score["winEffect"] for score in fresh.values()]
    passed = (min(row["lower95"] for row in intervals) > -.02
              and statistics.mean(row["mean"] for row in intervals) >= .01
              and any(row["lower95"] > 0 for row in intervals))
    write(root / "result.json", {"passed": passed, "selected": selected, "outcomes": fresh})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
