"""Compare dice-only attack targeting with public Cathedral-aware alternatives."""

import argparse
from pathlib import Path

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_opening_strategy import load_games
from targeting_evolution import cases


REMOTE = "/home/aragos/aor-worker/cathedral-aware-targeting-20261119"
ENVIRONMENTS = ("mixed", "known-16")
WORLDS = 640


def changed_worlds(root, arms):
    changes = {}
    for name in ENVIRONMENTS:
        stage = root / f"targeting-evolution-cathedral-{name}"
        controls = load_games(stage, "control")
        changes[name] = {}
        for arm in arms:
            tests = load_games(stage, arm)
            deltas = []
            for index, before in controls.items():
                after = tests[index]
                key = "chosenPolicyDecisionsSha256"
                if before["diagnostics"]["CANDIDATE"][key] == after["diagnostics"]["CANDIDATE"][key]:
                    continue
                seat = before["seat"]
                deltas.append(after["roles"]["CANDIDATE"]["wins"][seat] -
                              before["roles"]["CANDIDATE"]["wins"][seat])
            changes[name][arm] = {
                "changed": len(deltas),
                "conditionalWinEffect": interval(deltas) if deltas else None,
            }
    return changes


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, batch_size=2, background=True, resume=resume)
    campaign.validate_runtime()
    reference = previous / "automata-credible-leader-pressure-20261119"
    qualification = read(reference / "result.json")
    if not qualification["passed"]:
        raise ValueError("Start from the independently qualified attack target rule.")
    original = read(reference / "plan.json")
    ordinary = qualification["configuration"]
    arms = {name: dict(ordinary, cathedralOdds=name.upper()) for name in ("defense", "both")}
    plan = {
        "baseline": original["baseline"], "rivals": original["rivals"], "counter": original["counter"],
        "retargeting": ordinary, "qualificationSha256": sha(reference / "result.json"),
        "arms": arms, "worlds": WORLDS,
    }
    manifest = root / "plan.json"
    if not manifest.exists():
        write(manifest, plan)
    elif read(manifest) != plan:
        raise ValueError("The resumed study must use the same fixed policy candidates.")
    outcomes = cases(campaign, "cathedral", plan, arms, ENVIRONMENTS, WORLDS)
    changes = changed_worlds(root, arms)
    nominees = [
        arm for arm, result in outcomes.items()
        if all(point["lower95"] > -.025 for point in result.values())
        and any(point["lower95"] > 0 for point in result.values())
        and all(changes[name][arm]["changed"] >= 10 for name in ENVIRONMENTS)
    ]
    write(root / "result.json", {
        "worldsPerTable": WORLDS, "outcomes": outcomes, "changedWorlds": changes,
        "nominees": nominees,
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
