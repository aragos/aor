"""Test the evolved attack rule with the deployed geographic capital choice."""

import argparse
from collections import defaultdict
from pathlib import Path
import statistics

from diverse_model_tables_campaign import roster
from initiative_campaign import Campaign, interval, read, sha, write
from initiative_opening_strategy import load_games
from initiative_series_rivals import GAMES_PER_GROUP
from weighted_opening_campaign import selection_context


REMOTE = "/home/aragos/aor-worker/capital-evolved-targeting-20261119"
ENVIRONMENTS = ("mixed", "known-16", "independent-mix", "experienced")
WORLDS = 1280


def effects(stage, experienced, opening_expectations=None, trained=None):
    control, changed = (load_games(stage, mode) for mode in ("control", "evolved"))
    deltas, rival_deltas = [], []
    clusters, rival_clusters = defaultdict(list), defaultdict(list)
    for i in range(WORLDS):
        a, b = control[i], changed[i]
        diagnostics = [row["diagnostics"]["CANDIDATE"] for row in (a, b)]
        before, after = [row["openingBid"] for row in diagnostics]
        if (a["seed"], a["seat"], before) != (b["seed"], b["seat"], after):
            raise ValueError("An attack-only policy must preserve capital selection and the opening bid.")
        if opening_expectations:
            key = "lateBid" if before["capitalPosition"] >= 2 else "earlyBid"
            for row in diagnostics:
                revealed = next(proof for proof in row["publicTokenCounter"]["decisionProofs"]
                                if proof["turn"] == 1 and proof["phase"] == "order-revealed")
                expected = opening_expectations[i][key] if experienced else opening_expectations
                if (revealed["actualRivalBid"] != expected or
                        experienced and revealed["prediction"] != trained[i][key]):
                    raise ValueError("Each challenger must follow its frozen legal sealed response.")
        seat = a["seat"]
        win = lambda row, player: row["roles"]["CANDIDATE"]["wins"][player]
        own = win(b, seat) - win(a, seat)
        rival = win(b, (seat + 1) % 4) - win(a, (seat + 1) % 4)
        deltas.append(own)
        rival_deltas.append(rival)
        if experienced:
            group = (i // GAMES_PER_GROUP) % 64
            clusters[group].append(own)
            rival_clusters[group].append(rival)
    means = lambda values: [statistics.mean(cluster) for cluster in values.values()]
    return {"worlds": WORLDS, "ownWin": interval(means(clusters) if experienced else deltas),
            "leftRivalWin": interval(means(rival_clusters) if experienced else rival_deltas)}


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    training = previous / "automata-targeting-evolution-20261119/second.json"
    finalist = read(training)
    best = finalist["ranking"][0]["name"]
    evolved = finalist["candidates"][best]
    deployed = read(previous / "local-preview-18781/launch.json")
    if not deployed["healthy"]:
        raise ValueError("Test the rule on the already qualified working capital preview.")
    anchor = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    opponents = roster(root, previous)["independent-mix"]
    plan = {
        "finalistSha256": sha(training), "evolved": evolved, "reference": anchor["retargeting"],
        "currentPreviewSha256": deployed["assetSha256"], "worlds": WORLDS,
        "cases": list(ENVIRONMENTS), "modelRoster": opponents,
    }
    manifest = root / "plan.json"
    if not manifest.exists():
        write(manifest, plan)
    elif read(manifest) != plan:
        raise ValueError("A resumed combination must retain its original winning development candidate.")
    arms = ("control", "evolved")
    metrics = {}
    for mode in ENVIRONMENTS:
        style, situation, _ = selection_context(anchor, mode, WORLDS, True)
        label = f"capital-evolved-targeting-fresh-{mode}"
        campaign.stage(
            label, {"evolved": anchor["baseline"]}, WORLDS, (style,), shard=128,
            baseline=anchor["baseline"], lineup=situation["lineup"],
            counter_variants=dict.fromkeys(arms, situation["counter"]) if "counter" in situation else None,
            series_priors=dict.fromkeys(arms, situation["priors"]) if "priors" in situation else None,
            series_responses=dict.fromkeys(arms, situation["responses"]) if "responses" in situation else None,
            leading_rival_variants={"control": plan["reference"], "evolved": evolved},
            capital_geography_variants=arms,
            rival_model_roster=opponents if mode == "independent-mix" else None,
        )
        metrics[mode] = effects(root / label, mode == "experienced",
                                situation.get("responses", 16 if mode == "known-16" else None),
                                situation.get("priors"))
    wins = [result["ownWin"] for result in metrics.values()]
    write(root / "result.json", {
        "passed": (min(case["lower95"] for case in wins) > -.025
                   and sum(case["lower95"] > 0 for case in wins) >= 2
                   and statistics.mean(case["mean"] for case in wins) >= .025),
        "configuration": evolved, "outcomes": metrics, "worldsPerCase": WORLDS,
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
