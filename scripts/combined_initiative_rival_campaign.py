"""Separate a rival's learned earlier initiative from the attacks that exploit that initiative."""

import argparse
from pathlib import Path
import statistics

from initiative_campaign import Campaign, interval, read, seed_bank, sha, write
from initiative_opening_strategy import load_games
from initiative_series_rivals import GAMES_PER_GROUP
from midgame_rematch_campaign import earlier_games, public_reveals
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/combined-initiative-rival-20261119"
WORLDS = 1024
ARMS = ("control", "bid", "attack", "both")
TARGETING = ("attack", "both")
LEARNED = ("bid", "both")


def report(folder, earlier):
    data = {name: load_games(folder, name) for name in ARMS}
    rivals, own, initiative = ({name: [] for name in ARMS} for _ in range(3))
    for i in range(WORLDS):
        baseline = data["control"][i]
        seat = baseline["seat"]
        left = (seat + 1) % 4
        identity = (baseline["seed"], baseline["diagnostics"]["CANDIDATE"]["openingBid"])
        for name, sequenced in data.items():
            row = sequenced[i]
            diagnostic = row["diagnostics"]["CANDIDATE"]
            if row["seat"] != seat or (row["seed"], diagnostic["openingBid"]) != identity:
                raise ValueError("All four interventions must preserve initial setup and sealed opening.")
            if diagnostic.get("retargetingRivalSeats", []) != ([left] if name in TARGETING else []):
                raise ValueError("Only the designated adapting opponent may get the stronger attack rule.")
            for turn, event in public_reveals(row).items():
                if 3 <= turn <= 10:
                    if name in LEARNED and event["prediction"] != earlier[i]:
                        raise ValueError("A rival may learn only from its four completed public games.")
                    initiative[name].append(event["rivalBeforeTarget"])
            wins = row["roles"]["CANDIDATE"]["wins"]
            rivals[name].append(wins[left])
            own[name].append(wins[seat])

    def effects(values):
        changes = {name: [value - base for value, base in zip(values[name], values["control"], strict=True)]
                   for name in ARMS[1:]}
        changes["extraFromCombination"] = [
            combined - bid - expansion for combined, bid, expansion in zip(
                changes["both"], changes["bid"], changes["attack"], strict=True,
            )
        ]
        return {name: interval([statistics.mean(deltas[i:i + GAMES_PER_GROUP])
                               for i in range(0, WORLDS, GAMES_PER_GROUP)])
                for name, deltas in changes.items()}

    return {"rivalWinDelta": effects(rivals), "controlledWinDelta": effects(own),
            "midgameRivalEarlier": {name: statistics.mean(values) for name, values in initiative.items()}}


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    capital = previous / "automata-second-capital-geography-20261119"
    if not read(capital / "result.json")["passed"]:
        raise ValueError("Model the rival against the verified current-capital strategy.")
    recovered = previous / "automata-midgame-rematch-20261119/result.json"
    old_reveal = read(recovered)["midgameReveals"]
    if old_reveal["learned-midgame"]["rivalEarlier"] - old_reveal["control"]["rivalEarlier"] < .03:
        raise ValueError("Verify that the learned bid actually earns earlier initiative before combining it.")
    source = previous / "automata-targeting-evolution-20261119/second.json"
    search = read(source)
    winner = search["candidates"][search["ranking"][0]["name"]]
    live = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    past = capital / "capital-geography-fresh-experienced"
    records, earlier = earlier_games(past)
    style, setup, _ = selection_context(live, "experienced", WORLDS, True)
    rival_policy = {"kind": "leading-rival-table-1", "offsets": [1], "policy": winner}
    seats = {"current": live["retargeting"], "expanded-targeting": winner}
    plan = {
        "historySha256": sha(past / "method.json"), "midgameEffectSha256": sha(recovered),
        "chosenAttackSha256": sha(source), "previousGames": records, "rivalTargeting": rival_policy,
        "targetPolicies": seats, "worlds": WORLDS,
    }
    declaration = root / "plan.json"
    if not declaration.exists():
        write(declaration, plan)
    elif read(declaration) != plan:
        raise ValueError("Keep the frozen previously learned bids and existing expansion candidate.")
    outcomes = {}
    for name, policy in seats.items():
        stage = f"combined-adversary-fresh-{name}"
        if set(seed_bank(f"{stage}/{style}", WORLDS)) & {row["seed"] for row in records}:
            raise ValueError("The evaluated rival never learns from its scored games.")
        campaign.stage(
            stage, dict.fromkeys(ARMS[1:], live["baseline"]), WORLDS, (style,), shard=128,
            baseline=live["baseline"], lineup=setup["lineup"],
            counter_variants=dict.fromkeys(ARMS, setup["counter"]),
            series_priors=dict.fromkeys(ARMS, setup["priors"]),
            series_responses=dict.fromkeys(ARMS, setup["responses"]),
            series_midgame_priors=dict.fromkeys(LEARNED, earlier),
            leading_rival_variants=dict.fromkeys(ARMS, policy), capital_geography_variants=ARMS,
            rival_targeting_lineups=dict.fromkeys(TARGETING, rival_policy),
        )
        outcomes[name] = report(root / stage, earlier)
    write(root / "result.json", {"worldsPerTable": WORLDS, "outcomes": outcomes})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
