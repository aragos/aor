"""Validate the qualified tactic against independently trained model distributions."""

import argparse
import json
from pathlib import Path
import shutil
import statistics

from initiative_campaign import Campaign, read, sha, write
from initiative_heterogeneous_campaign import lineup
from initiative_opening_strategy import load_games


REMOTE = "/home/aragos/aor-worker/model-ecology-20261119"


def policy_paths(bundle):
    definition = read(bundle)
    if definition["kind"] != "coordinated-es-bundle-1":
        raise ValueError("An opponent must be a complete frozen C/P/E/T actor.")
    result = {}
    for head, asset in definition["heads"].items():
        path = (bundle.parent / asset["path"]).resolve(strict=True)
        if not path.is_relative_to(bundle.parent.resolve()) or sha(path) != asset["sha256"]:
            raise ValueError("The archived model head must match its checked portable package.")
        result[head] = path
    return result


def identity(bundle):
    paths = policy_paths(bundle)
    dictionary = {name: record["dictionarySha256"] for name, record in read(bundle)["heads"].items()}
    values = {}
    for head, source in paths.items():
        actor = read(source)
        values[head] = (actor["valueWeights"], actor["valueBias"])
    return dictionary, values


def old_performance(folder, expected_hash):
    all_rows = []
    files = sorted(folder.glob("chunk-*/native/worlds.jsonl"))
    for source in files:
        prepared = read(source.with_name("prepared.json"))
        complete = read(source.with_name("finished.json"))
        science = prepared["scienceIdentity"]
        if (science["candidateBundleSha256"] != expected_hash
                or not complete["complete"] or complete["files"]["worlds.jsonl"] != sha(source)):
            raise ValueError("Archived strength must use actual matched outcomes from the retained model.")
        all_rows.extend(json.loads(line) for line in source.read_text().splitlines())
    if len(all_rows) < 80:
        return None
    own = [row["roles"]["CANDIDATE"]["wins"][row["seat"]] for row in all_rows]
    observed = [row["diagnostics"]["CANDIDATE"] for row in all_rows]
    return {
        "worlds": len(all_rows), "win": statistics.mean(own),
        "attacks": statistics.mean(x["controlledAttacks"] for x in observed),
        "expansionCards": statistics.mean(x["controlledExpansionCardsBought"] for x in observed),
        "unused": statistics.mean(x["controlledDiscardedPoolAtPass"] for x in observed),
    }


def prepare(root, previous):
    league = previous / "automata-robust-league-20261117"
    base = league / "mac-v3/models"
    reference = base / "final-mean-6/bundle.json"
    contract = identity(reference)
    contenders = {}
    for bundle in sorted((base / "generation-6/members").glob("*/bundle.json")):
        if identity(bundle) != contract:
            continue
        member = bundle.parent.name
        archived = league / "execution/chunks/generation-6" / member
        evidence = old_performance(archived, sha(bundle))
        if evidence:
            contenders[member] = bundle, evidence
    if len(contenders) < 4:
        raise ValueError("Opponent selection requires a meaningful archived strong population.")
    ordered = sorted(contenders.items(), key=lambda item: item[1][1]["win"], reverse=True)
    strongest = ordered[0][1]
    if strongest[1]["win"] <= .25:
        raise ValueError("Archived alternatives must have credible prior competitive strength.")
    other = max(
        (data for _, data in ordered[1:len(ordered)//2]),
        key=lambda data: sum(abs(data[1][k] - strongest[1][k]) / scale
                             for k, scale in (("attacks", 10), ("expansionCards", .5), ("unused", 3))),
    )
    earlier = (previous / "automata-coordinated-win-continuation-20261114/postrun/desktop"
               / "execution/run/study/final-portable/final/bundle.json")
    if identity(earlier) != contract:
        raise ValueError("The independently trained older policy must retain the supported value and feature shape.")
    rivals = {
        "strong-member": strongest,
        "different-member": other,
        "independent-run": (earlier, {"source": "archived complete win-focused fit"}),
    }
    roster = {}
    for name, (bundle, evidence) in rivals.items():
        target = root / "assets" / "rival-models" / name
        shutil.copytree(bundle.parent, target)
        published = target / "bundle.json"
        policy_paths(published)
        roster[name] = {
            "path": str(published.relative_to(root)), "sha256": sha(published), "evidence": evidence,
            "sourceName": bundle.parent.name,
        }
    if len({item["sha256"] for item in roster.values()} | {sha(reference)}) != 4:
        raise ValueError("All challengers must be different from one another and the current model.")
    write(root / "roster.json", roster)


def main(root, previous, resume):
    campaign = Campaign(root, remote=REMOTE, background=True, batch_size=2, resume=resume)
    campaign.validate_runtime()
    portfolio = read(root / "roster.json")
    live_path = previous / "automata-credible-leader-pressure-20261119/result.json"
    current = read(live_path)
    if not current["passed"]:
        raise ValueError("Use the independently qualified current policy.")
    research = read(live_path.with_name("plan.json"))
    base = research["baseline"]
    seats = lineup({int(offset): policy for offset, policy in research["rivals"].items()})
    plan = {"baseline": base, "retargeting": current["configuration"],
            "rivals": portfolio, "qualificationSha256": sha(live_path), "worldsPerRoster": 768}
    manifest = root / "plan.json"
    if not manifest.exists():
        write(manifest, plan)
    elif read(manifest) != plan:
        raise ValueError("Model validation requires the exact previously chosen archived rivals.")
    effects = {}
    for name, rival in portfolio.items():
        subject = {key: rival[key] for key in ("path", "sha256")}
        if sha(root / subject["path"]) != subject["sha256"]:
            raise ValueError("Frozen competitor bytes changed before test dispatch.")
        stage = f"model-ecology-{name}"
        result = campaign.stage(
            stage, {"retargeting": base}, plan["worldsPerRoster"], ("QUALIFIED",),
            shard=128, baseline=base, lineup=seats,
            leading_rival_variants={"retargeting": plan["retargeting"]}, control_bundle=subject,
        )
        fields = {}
        for lane in ("control", "retargeting"):
            rows = load_games(root / stage, lane)
            bids = {i: row["diagnostics"]["CANDIDATE"]["openingBid"] for i, row in rows.items()}
            if "bids" in fields and fields["bids"] != bids:
                raise ValueError("Only the actor's chosen attack target may change this counterfactual.")
            fields["bids"] = bids
            fields[lane] = statistics.mean(row["roles"]["CANDIDATE"]["wins"][row["seat"]]
                                           for row in rows.values())
        effects[name] = {"winDelta": result["retargeting"]["outcomes"]["QUALIFIED"],
                         "baselineWin": fields["control"], "retargetedWin": fields["retargeting"]}
    write(root / "result.json", {"outcomes": effects, "worldsPerRoster": plan["worldsPerRoster"]})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--prepare", action="store_true")
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    if args.prepare:
        prepare(args.root.resolve(), args.previous.resolve())
    else:
        main(args.root.resolve(), args.previous.resolve(), args.resume)
