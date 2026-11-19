"""Confirm whether cheaper peaceful markets outperform near-exhaustive attacks."""

import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, read, sha, write
from initiative_heterogeneous_campaign import trained_counter
from leading_rival_retargeting_campaign import study


REMOTE = "/home/aragos/aor-worker/peaceful-expansion-alternative-20261119"
POLICIES = {
    "early": {"kind": "peaceful-expansion-alternative-1", "throughEpoch": 1},
    "through-second": {"kind": "peaceful-expansion-alternative-1", "throughEpoch": 2},
}


def compare(campaign, name, policies, plan, count):
    return study(campaign, name, policies, plan, count,
                 variant_options={"peaceful_expansion_variants": policies})


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, batch_size=2, resume=resume)
    campaign.validate_runtime()
    live_path = previous / "automata-heterogeneous-20261118/table-fresh.json"
    roster_path = live_path.with_name("roster.json")
    evidence_path = previous / "automata-expansion-card-reserve-lookahead-20261119/result.json"
    live, roster, evidence = (read(file) for file in (live_path, roster_path, evidence_path))
    effect = evidence["competesWithAttack"]["effects"]["reserve-versus-ordinary"]["win"]
    if not live["passed"] or effect["lower95"] <= 0:
        raise ValueError("The strategy requires a confirmed matched gain when replacing attacks.")
    plan = {
        "baseline": live["config"], "rivals": roster["rivals"],
        "counter": trained_counter(previous, roster["counter"]), "policies": POLICIES,
        "liveSha256": sha(live_path), "rosterSha256": sha(roster_path),
        "lookaheadSha256": sha(evidence_path),
    }
    method = root / "plan.json"
    if not method.exists():
        write(method, plan)
    elif read(method) != plan:
        raise ValueError("Resumption requires the original substitute-market plan.")
    screen = compare(campaign, "peaceful-screen", POLICIES, plan, 256)
    ranking = []
    for name, tables in screen.items():
        effects = [row["mean"] for row in tables.values()]
        average = statistics.mean(effects)
        if min(effects) >= -.025 and average >= .01:
            ranking.append((average, min(effects), name))
    write(root / "screen.json", {"effects": screen, "nominees": sorted(ranking, reverse=True)})
    if not ranking or campaign.remaining() < 3600:
        write(root / "result.json", {"passed": False, "stage": "screen"})
        return
    choice = max(ranking)[-1]
    outcomes = compare(campaign, "peaceful-fresh", {choice: POLICIES[choice]}, plan, 1024)[choice]
    values = tuple(outcomes.values())
    write(root / "result.json", {
        "passed": (min(row["lower95"] for row in values) > -.025
                   and statistics.mean(row["mean"] for row in values) >= .015
                   and sum(row["lower95"] > 0 for row in values) >= 2),
        "selected": choice, "configuration": POLICIES[choice], "outcomes": outcomes,
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--previous", required=True, type=Path)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
