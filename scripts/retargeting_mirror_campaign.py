"""Check how a table of retargeting rivals affects winning and table feedback."""

import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_heterogeneous_campaign import lineup
from initiative_opening_strategy import load_games, with_mix


REMOTE = "/home/aragos/aor-worker/retargeting-mirror-20261119"
COUNTERFACTUALS = ("one", "all", "mirror")


def score(campaign, label, plan, opening, count):
    hero = with_mix(plan["baseline"], [opening]) if opening is not None else plan["baseline"]
    roster = lineup({int(offset): value for offset, value in plan["rivals"].items()})
    offsets = {"one": [1], "all": [1, 2, 3], "mirror": [1, 2, 3]}
    tables = {name: {"kind": "leading-rival-table-1", "offsets": seats, "policy": plan["policy"]}
              for name, seats in offsets.items()}
    stage = f"retargeting-table-{label}"
    campaign.stage(stage, dict.fromkeys(COUNTERFACTUALS, hero), count, ("QUALIFIED",),
                   baseline=hero, lineup=roster, shard=128,
                   leading_rival_variants={"mirror": plan["policy"]},
                   leading_rival_tables=tables)
    rows = {name: load_games(campaign.root / stage, name)
            for name in ("control", *COUNTERFACTUALS)}
    evidence, wins = {}, {}
    for name, records in rows.items():
        diagnostics = []
        win = []
        for i in range(count):
            row = records[i]
            root = row["diagnostics"]["CANDIDATE"]
            original = rows["control"][i]
            opening_bid = original["diagnostics"]["CANDIDATE"]["openingBid"]
            if row["seed"] != original["seed"] or root["openingBid"] != opening_bid:
                raise ValueError("Changing expansion competitors must preserve every first bid.")
            if opening is not None and root["openingBid"]["bid"] != opening:
                raise ValueError("The independent exploiter must play its declared opening amount.")
            seat = row["seat"]
            if set(root.get("retargetingRivalSeats", [])) != {
                (seat + offset) % 4 for offset in offsets.get(name, ())
            }:
                raise ValueError("The declared rivals must occupy precisely the specified seats.")
            scores = row["roles"]["CANDIDATE"]["wins"]
            diagnostics.append(root)
            win.append(scores[seat])
        wins[name] = win
        evidence[name] = {
            "averageWinShare": statistics.mean(win),
            "averageControlledAttacks": statistics.mean(s["controlledAttacks"] for s in diagnostics),
            "averageControlledAttacksOnCurrentLeader": statistics.mean(
                s["controlledAttacksOnCurrentPublicLeader"] for s in diagnostics),
        }
    contrasts = {}
    for label, a, b in (
        ("oneRival", "one", "control"), ("threeRivals", "all", "control"),
        ("learningSameRule", "mirror", "all"), ("fullMirror", "mirror", "control"),
    ):
        contrasts[label] = interval([x - y for x, y in zip(wins[a], wins[b], strict=True)])
    return {"roleEvidence": evidence, "contrasts": contrasts}


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    prepared = previous / "automata-credible-leader-pressure-20261119"
    origin = read(prepared / "plan.json")
    screen = read(prepared / "screen.json")
    if not screen["nominees"] or screen["nominees"][0][2] != "near-lead":
        raise ValueError("The mirror requires the fixed retargeting finalist.")
    plan = {
        "baseline": origin["baseline"], "rivals": origin["rivals"],
        "policy": origin["policies"]["near-lead"],
        "sourceSha256": sha(prepared / "plan.json"), "screenSha256": sha(prepared / "screen.json"),
        "cases": {"ordinary": None, "prepared-16": 16, "prepared-18": 18},
        "worldsPerCase": 512,
    }
    path = root / "plan.json"
    if not path.exists():
        write(path, plan)
    elif read(path) != plan:
        raise ValueError("The roster study must keep its original forecast and seat positions.")
    points = {label: score(campaign, label, plan, opening, plan["worldsPerCase"])
              for label, opening in plan["cases"].items()}
    write(root / "result.json", {"worldsPerCase": plan["worldsPerCase"], "outcomes": points})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--previous", required=True, type=Path)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
