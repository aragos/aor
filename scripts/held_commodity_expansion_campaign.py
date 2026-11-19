"""Verify a deployable commodity expansion policy from the first turn through game end."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import statistics

from initiative_campaign import Campaign, read, sha, write
from initiative_heterogeneous_campaign import lineup, trained_counter


REMOTE = "/home/aragos/aor-worker/held-commodity-expansion-20261119"
POLICIES = {
    "close": {"kind": "held-commodity-expansion-1", "maximumScoreGap": .65},
    "priority": {"kind": "held-commodity-expansion-1", "maximumScoreGap": 4.0},
}


def study(campaign, label, arms, plan, worlds):
    baseline = plan["baseline"]
    roster = {int(offset): policy for offset, policy in plan["rivals"].items()}
    cases = {
        "mixed": ("QUALIFIED", lineup(roster), None),
        "adaptive": ("REVEALED_UNDERCUT", lineup({k: v for k, v in roster.items() if k != 1}),
                     plan["counter"]),
        "familiar": ("REVEALED_UNDERCUT", None, {"openingBidPrior": 19}),
    }
    result = {name: {} for name in arms}
    for name, (ecology, seats, counter) in cases.items():
        options = dict.fromkeys(("control", *arms), counter) if counter else None
        outcomes = campaign.stage(
            f"{label}-{name}", dict.fromkeys(arms, baseline), worlds, (ecology,),
            shard=128, baseline=baseline, lineup=seats, counter_variants=options,
            commodity_expansion_variants=arms,
        )
        for arm in arms:
            result[arm][name] = outcomes[arm]["outcomes"][ecology]
    return result


def smoke(campaign, seed_path, plan):
    stage = campaign.root / "smoke"
    finished = stage / "result.json"
    if campaign.resume and finished.exists():
        if read(finished)["status"] != "exact":
            raise ValueError("The smoke study must reproduce both hosts.")
        return
    stage.mkdir()
    spec = read(seed_path)
    spec.pop("midgameBidRollouts", None)
    spec.update(
        count=16, replayIndices=[0], opponentStyle="QUALIFIED", candidateOnly=False,
        initiative=plan["baseline"], heldCommodityExpansion=POLICIES["priority"],
    )
    with ThreadPoolExecutor(2) as executor:
        futures = []
        for host in ("MAC", "DESKTOP"):
            source = stage / f"{host}.json"
            write(source, spec)
            futures.append(executor.submit(campaign.execute, stage, (host, source), host))
        for result in futures:
            result.result()

    def evidence(host):
        file = stage / host / "worlds.jsonl"
        return sorted((json.loads(line) for line in file.read_text().splitlines()),
                      key=lambda row: row["index"])

    rows = evidence("MAC")
    if rows != evidence("DESKTOP"):
        raise ValueError("Both workers must reproduce every pilot result.")
    different = sum(row["roles"]["CANDIDATE"]["scores"] != row["roles"]["CONTROL"]["scores"]
                    for row in rows)
    write(finished, {"status": "exact", "worlds": len(rows),
                     "candidateControlScoreDifferences": different})


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, batch_size=2, resume=resume)
    campaign.validate_runtime()
    anchor_path = previous / "automata-heterogeneous-20261118/table-fresh.json"
    evidence_path = previous / "automata-commodity-expansion-20261119/result.json"
    roster_path = anchor_path.with_name("roster.json")
    anchor = read(anchor_path)
    evidence = read(evidence_path)
    lookahead = evidence["full"]["effects"]["hold-and-expand-minus-hold"]
    if not anchor["passed"] or lookahead["lower95"] <= 0:
        raise ValueError("The study requires qualified baseline and positive matched lookahead.")
    roster = read(roster_path)
    plan = {
        "baseline": anchor["config"], "rivals": roster["rivals"],
        "counter": trained_counter(previous, roster["counter"]), "policies": POLICIES,
        "anchorSha256": sha(anchor_path), "rosterSha256": sha(roster_path),
        "lookaheadSha256": sha(evidence_path),
    }
    plan_path = root / "plan.json"
    if not plan_path.exists():
        write(plan_path, plan)
    elif read(plan_path) != plan:
        raise ValueError("The held-commodity study requires its original plan.")
    seed_path = previous / "automata-midgame-bid-lookahead-20261119/smoke/MAC.json"
    smoke(campaign, seed_path, plan)
    screen = study(campaign, "held-market-screen", POLICIES, plan, 256)
    ranking = []
    for name, tables in screen.items():
        effects = [point["mean"] for point in tables.values()]
        average = statistics.mean(effects)
        if min(effects) >= -.025 and average > .005:
            ranking.append((average, min(effects), name))
    write(root / "screen.json", {"effects": screen, "nominees": sorted(ranking, reverse=True)})
    if not ranking or campaign.remaining() < 3600:
        write(root / "result.json", {"passed": False, "stage": "screen"})
        return
    nominee = max(ranking)[-1]
    fresh = study(campaign, "held-market-fresh", {nominee: POLICIES[nominee]}, plan, 1024)
    outcomes = fresh[nominee]
    write(root / "result.json", {
        "passed": (min(row["lower95"] for row in outcomes.values()) > -.025
                   and all(row["mean"] > 0 for row in outcomes.values())
                   and sum(row["lower95"] > 0 for row in outcomes.values()) >= 2),
        "configuration": POLICIES[nominee], "outcomes": outcomes, "selected": nominee,
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--previous", required=True, type=Path)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
