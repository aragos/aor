"""Qualify comparable leader attacks as an isolated expansion policy."""

import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, read, sha, write
from initiative_heterogeneous_campaign import lineup, trained_counter
from initiative_opening_strategy import load_games


REMOTE = "/home/aragos/aor-worker/credible-leader-pressure-20261119"
POLICIES = {
    "near-lead": {"kind": "leading-rival-retargeting-1", "maximumTrailingPoints": 99,
                  "requireGreaterCommodityDenial": False},
    "commodity-stakes": {"kind": "leading-rival-retargeting-1", "maximumTrailingPoints": 99,
                          "requireGreaterCommodityDenial": True},
}


def study(campaign, label, policies, plan, count, *, variant_options=None):
    baseline = plan["baseline"]
    rivals = {int(offset): policy for offset, policy in plan["rivals"].items()}
    scopes = {
        "mixed": ("QUALIFIED", lineup(rivals), None),
        "adaptive": ("REVEALED_UNDERCUT", lineup({k: v for k, v in rivals.items() if k != 1}),
                     plan["counter"]),
        "familiar": ("REVEALED_UNDERCUT", None, {"openingBidPrior": 19}),
    }
    summaries = {policy: {} for policy in policies}
    overrides = variant_options or {"leading_rival_variants": policies}
    for name, (ecology, roster, counter) in scopes.items():
        options = dict.fromkeys(("control", *policies), counter) if counter else None
        folder = f"{label}-{name}"
        result = campaign.stage(
            folder, dict.fromkeys(policies, baseline), count, (ecology,), shard=128,
            baseline=baseline, lineup=roster, counter_variants=options,
            **overrides,
        )
        control = load_games(campaign.root / folder, "control")
        for policy in policies:
            alternative = load_games(campaign.root / folder, policy)
            outcomes = result[policy]["outcomes"][ecology]
            diagnostics = {}
            for arm, rows in (("control", control), ("candidate", alternative)):
                diagnostics[arm] = {
                    "attacksOnCurrentLeader": statistics.mean(
                        row["diagnostics"]["CANDIDATE"]["controlledAttacksOnCurrentPublicLeader"]
                        for row in rows.values()
                    ),
                    "attackDecisionsWithLeaderOpportunity": statistics.mean(
                        row["diagnostics"]["CANDIDATE"]["controlledLeaderAttackDecisionOpportunities"]
                        for row in rows.values()
                    ),
                }
            if any(alternative[i]["diagnostics"]["CANDIDATE"]["openingBid"] !=
                   control[i]["diagnostics"]["CANDIDATE"]["openingBid"] for i in control):
                raise ValueError("A market targeting experiment must preserve the exact first bid.")
            summaries[policy][name] = dict(outcomes, pressure=diagnostics)
    return summaries


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, batch_size=2, resume=resume)
    campaign.validate_runtime()
    live_path = previous / "automata-heterogeneous-20261118/table-fresh.json"
    roster_path = live_path.with_name("roster.json")
    evidence_path = previous / "automata-rival-denial-lookahead-20261119/result.json"
    live = read(live_path)
    roster = read(roster_path)
    evidence = read(evidence_path)
    signal = evidence["effects"]["score-leader"]["all"]["ownWinShareDelta"]
    if not live["passed"] or signal["mean"] <= 0 or signal["lower95"] < -.025:
        raise ValueError("A targeting confirmation needs the exact paired empirical signal.")
    plan = {
        "baseline": live["config"], "rivals": roster["rivals"],
        "counter": trained_counter(previous, roster["counter"]),
        "policies": POLICIES, "baselineSha256": sha(live_path),
        "rosterSha256": sha(roster_path), "lookaheadSha256": sha(evidence_path),
    }
    path = root / "plan.json"
    if not path.exists():
        write(path, plan)
    elif read(path) != plan:
        raise ValueError("Attack retargeting requires the original models, priors and thresholds.")
    first = study(campaign, "retargeting-screen", POLICIES, plan, 256)
    candidates = []
    for name, tables in first.items():
        effects = [row["mean"] for row in tables.values()]
        average = statistics.mean(effects)
        if min(effects) >= -.025 and average >= .01:
            candidates.append((average, min(effects), name))
    write(root / "screen.json", {
        "outcomes": first, "nominees": sorted(candidates, reverse=True),
    })
    if not candidates or campaign.remaining() < 3600:
        write(root / "result.json", {"passed": False, "stage": "screen"})
        return
    finalist = max(candidates)[-1]
    outcomes = study(campaign, "retargeting-fresh", {finalist: POLICIES[finalist]}, plan, 1024)[finalist]
    values = tuple(outcomes.values())
    write(root / "result.json", {
        "passed": (min(row["lower95"] for row in values) > -.025
                   and statistics.mean(row["mean"] for row in values) >= .015
                   and sum(row["lower95"] > 0 for row in values) >= 2),
        "selected": finalist, "configuration": POLICIES[finalist], "outcomes": outcomes,
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--previous", required=True, type=Path)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
