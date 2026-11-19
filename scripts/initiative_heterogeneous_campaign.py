"""Compare automation against tables with several distinct fixed and learning rivals."""
import argparse
import json
from pathlib import Path
import statistics

from initiative_campaign import Campaign, read, write
from initiative_league import rank


REMOTE = "/home/aragos/aor-worker/initiative-heterogeneous-20261118"


def lineup(policies):
    return {"kind": "public-initiative-heterogeneous-1", "rivals": [
        {"offset": offset, "policy": policy} for offset, policy in policies.items()
    ]}


def trained_counter(previous, default):
    file = previous / "automata-learned-rival-20261118/rival-fresh.json"
    if not file.exists():
        return default
    fitted = read(file)
    eligible = [name for name, row in fitted["outcomes"].items() if row["rival"]["lower95"] > 0]
    if not eligible:
        return default
    winner = max(eligible, key=lambda name: fitted["outcomes"][name]["rival"]["mean"])
    return fitted["configs"][winner]


def compare(campaign, label, policies, count, baseline, rival_roster, counter):
    results = {name: {} for name in policies}
    conditions = (
        ("mixed-table", "QUALIFIED", lineup(rival_roster), None),
        ("adaptive-table", "REVEALED_UNDERCUT",
         lineup({offset: policy for offset, policy in rival_roster.items() if offset != 1}),
         {name: counter for name in ("control", *policies)}),
    )
    for key, ecology, table, options in conditions:
        rows = campaign.stage(f"{label}-{key}", policies, count, (ecology,), shard=128,
                              baseline=baseline, lineup=table, counter_variants=options)
        for name, row in rows.items():
            results[name][key] = row["outcomes"][ecology]
    return results


def freeze_roster(root, previous, resume):
    plan = root / "roster.json"
    if resume and plan.exists():
        record = read(plan)
        return (record["baseline"], record["policies"],
                {int(offset): policy for offset, policy in record["rivals"].items()}, record["counter"])
    initial = read(previous / "automata-initiative-20261118/result.json")
    league = read(previous / "automata-extended-20261118/league-confirm.json")
    deep = read(previous / "automata-initiative-mirror-20261118/extra-result.json")
    baseline = initial["comparison"]["config"]
    chosen = league["configs"][league["ranking"][0]]
    runner_up = league["configs"][league["ranking"][1]]
    independent = deep["response"]["response"]["config"]
    opponents = {1: chosen, 2: runner_up, 3: independent}
    economy = previous / "automata-auction-economy-20261118/economy-confirm.json"
    proposals = {f"league-{name}": league["configs"][name] for name in league["ranking"][:2]}
    if economy.exists():
        fitted = read(economy)
        proposals.update({f"economy-{name}":fitted["configs"][name] for name in fitted["ranking"][:3]})
    duplicates = set()
    policies = {}
    for name, policy in proposals.items():
        identity = json.dumps(policy, sort_keys=True)
        if identity not in duplicates:
            policies[name] = policy
            duplicates.add(identity)
    counter = trained_counter(previous, {"openingBidPrior": 19})
    write(plan, {"baseline": baseline, "policies": policies, "rivals": opponents, "counter":counter})
    return baseline, policies, opponents, counter


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, resume=resume)
    campaign.validate_runtime()
    baseline, policies, opponents, counter = freeze_roster(root, previous, resume)
    screen = compare(campaign, "table-screen", policies, 256, baseline, opponents, counter)
    candidates = [name for name in rank(screen) if min(row["mean"] for row in screen[name].values()) >= 0
                  and statistics.mean(row["mean"] for row in screen[name].values()) >= .03]
    result = root / "table-screen.json"
    payload = {"outcomes": screen, "configs": policies, "counter": counter, "nominees": candidates}
    if resume and result.exists():
        if read(result) != payload:
            raise ValueError("The previous lineup screen no longer matches its validated shards.")
    else:
        write(result, payload)
    if not candidates or campaign.remaining() < 2700:
        return
    selected = candidates[0]
    fresh = compare(campaign, "table-fresh", {selected:policies[selected]}, 768,
                    baseline, opponents, counter)
    write(root / "table-fresh.json", {"outcomes": fresh, "config": policies[selected], "selected":selected,
                                     "passed":all(row["lower95"]>0 for row in fresh[selected].values())})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
