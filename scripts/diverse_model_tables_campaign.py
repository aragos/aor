"""Cross-play the strongest tested rules against three different trained models at once."""

import argparse
from pathlib import Path

from initiative_campaign import Campaign, interval, read, sha, write
from initiative_heterogeneous_campaign import lineup
from initiative_opening_strategy import load_games
from second_capital_geography_campaign import measure


REMOTE = "/home/aragos/aor-worker/diverse-model-tables-20261119"
WORLDS = 1024
NAMES = ("control", "geography", "before-targeting")
REFERENCE = "assets/models/final-mean-6/bundle.json"


def roster(root, previous):
    archived = previous / "automata-model-ecology-20261119"
    results = read(archived / "result.json")
    if set(results["outcomes"]) != {"strong-member", "different-member", "independent-run"}:
        raise ValueError("Reuse the exact tested portable models from the prior independent study.")
    released = read(archived / "roster.json")
    chosen = {}
    for name, entry in released.items():
        if sha(root / entry["path"]) != entry["sha256"]:
            raise ValueError("An archived competitor changed after its completed evaluation.")
        chosen[name] = {"path": "../" + entry["path"], "sha256": entry["sha256"]}
    chosen["current-base"] = {"path": "../" + REFERENCE, "sha256": sha(root / REFERENCE)}
    return {
        label: {
            "kind": "rotating-complete-rivals-1",
            "bundles": [{"offset": offset, "bundle": chosen[name]}
                        for offset, name in enumerate(names, start=1)],
        }
        for label, names in (
            ("current-diverse", ("strong-member", "different-member", "current-base")),
            ("independent-mix", ("strong-member", "independent-run", "different-member")),
        )
    }


def effects(stage, original_roster):
    rows = {name: load_games(stage, name) for name in NAMES}
    for i in range(WORLDS):
        expected = {
            str((i % 4 + 1 + ((item["offset"] - 1 + i % 3) % 3)) % 4): item["bundle"]["sha256"]
            for item in original_roster["bundles"]
        }
        if any(arm[i]["diagnostics"]["CANDIDATE"]["rivalModelBundles"] != expected
               for arm in rows.values()):
            raise ValueError("Every world must bind the same three different trained rivals to its seats.")
    pressure, combined = [], []
    for i in range(WORLDS):
        old, targeted, complete = (rows[name][i] for name in ("before-targeting", "control", "geography"))
        seat = old["seat"]
        if len({game["seed"] for game in (old, targeted, complete)}) != 1:
            raise ValueError("Model mixtures must use the same legal setup and chance starts.")
        if (old["diagnostics"]["CANDIDATE"]["openingBid"]
                != targeted["diagnostics"]["CANDIDATE"]["openingBid"]):
            raise ValueError("Only the attack policy may differ in the earlier baseline comparison.")
        win = lambda game: game["roles"]["CANDIDATE"]["wins"][seat]
        pressure.append(win(targeted) - win(old))
        combined.append(win(complete) - win(old))
    return {"pressure": interval(pressure), "capitalOnPressure": measure(stage, WORLDS),
            "combined": interval(combined)}


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    preview = read(previous / "local-preview-18781/launch.json")
    evaluation = previous / "automata-second-capital-geography-20261119/result.json"
    base = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    if not preview["healthy"] or not read(evaluation)["passed"]:
        raise ValueError("Cross-play must use the currently qualified and deployed policy.")
    tables = roster(root, previous)
    manifest = root / "plan.json"
    sealed = {"tables": tables, "worlds": WORLDS, "previewSha256": preview["assetSha256"],
              "previousEvaluationSha256": sha(evaluation)}
    if not manifest.exists():
        write(manifest, sealed)
    elif read(manifest) != sealed:
        raise ValueError("The cross-play must preserve its three model subjects and seat rotations.")
    opposition = lineup({int(offset): config for offset, config in base["rivals"].items()})
    tested = {}
    for name, competitors in tables.items():
        stage = f"diverse-model-table-{name}"
        campaign.stage(
            stage, dict.fromkeys(NAMES[1:], base["baseline"]), WORLDS, ("QUALIFIED",),
            baseline=base["baseline"], lineup=opposition, shard=128,
            leading_rival_variants=dict.fromkeys(NAMES[:2], base["retargeting"]),
            capital_geography_variants=("geography",), rival_model_roster=competitors,
        )
        tested[name] = effects(root / stage, competitors)
    write(root / "result.json", {"worldsPerTable": WORLDS, "outcomes": tested})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    main(args.root.resolve(), args.previous.resolve(), args.resume)
