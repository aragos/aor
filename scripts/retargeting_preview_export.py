"""Independently audit the ordinary and experienced-rival retargeting results."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import statistics

from initiative_campaign import Campaign, interval
from initiative_preview_export import actual_win, canonical, read, require, sha


BASE = "a5c2c88cf9d5de620304cd976c92c41dea5d1026e8cc9c27fe8436c98d917da4"
CASES = {
    "mixed": ("retargeting-fresh-mixed", "QUALIFIED", 1024),
    "adaptive": ("retargeting-fresh-adaptive", "REVEALED_UNDERCUT", 1024),
    "familiar": ("retargeting-fresh-familiar", "REVEALED_UNDERCUT", 1024),
    "experienced": ("retargeting-adversarial-experienced", "REVEALED_UNDERCUT", 512),
    "known16": ("retargeting-adversarial-known-16", "REVEALED_UNDERCUT", 768),
}


def collect(folder, method, protocol, bank, ecology, role, baseline, configured):
    points, replays, evidence = {}, set(), {}
    amount = len(bank)
    for offset in range(0, amount, 128):
        name = f"{role}-0-{offset}"
        source = folder / f"{name}.json"
        shard = folder / name
        expected = {
            "protocolSha256": protocol, "seeds": bank, "globalStart": offset,
            "count": min(128, amount - offset), "replayIndices": [0, amount - 1],
            "initiative": baseline, "opponentStyle": ecology, "candidateOnly": True,
            "leadingRivalRetargeting": configured, "initiativeLineup": method.get("initiativeLineup"),
            "seriesOpeningPriors": method.get("seriesOpeningPriors", {}).get(role),
            "seriesOpeningResponses": method.get("seriesOpeningResponses", {}).get(role),
        }
        declared = read(source)
        require(all(declared.get(key) == value for key, value in expected.items()),
                "Each validation shard must match the admitted candidate, roster and seed bank.")
        Campaign.validate_result(shard, source)
        receipt = read(shard / "finished.json")
        require(receipt["roles"] == ["CANDIDATE"], "The audit needs equivalent seat-labeled roles.")
        evidence[f"{role}/{offset}/source"] = sha(source)
        for file, digest in receipt["files"].items():
            evidence[f"{role}/{offset}/{file}"] = digest
        for line in (shard / "worlds.jsonl").read_text().splitlines():
            row = json.loads(line)
            index, seat = row["index"], row["seat"]
            require(index not in points and seat == index % 4 and row["opponentStyle"] == ecology,
                    "The terminal score must belong to its original seat and opponent population.")
            detail = row["diagnostics"]["CANDIDATE"]
            opening = detail["openingBid"]
            series = declared.get("seriesOpeningResponses")
            known = declared.get("adaptiveOpponent", {}).get("openingBidPrior")
            if series or known is not None:
                proof = next(entry for entry in detail["publicTokenCounter"]["decisionProofs"]
                             if entry["phase"] == "order-revealed" and entry["turn"] == 1)
                group = "lateBid" if opening["capitalPosition"] >= 2 else "earlyBid"
                expected_bid = series[index][group] if series else known - 1
                require(proof["actualRivalBid"] == expected_bid,
                        "The saved challenger actually played its declared countermove.")
            points[index] = (actual_win(row["roles"]["CANDIDATE"], seat), opening)
        replay = shard / "reproductions.jsonl"
        if replay.is_file():
            for line in replay.read_text().splitlines():
                row = json.loads(line)
                i = row["index"]
                require(row["role"] == "CANDIDATE" and row["matched"] and row["canonicalSha256"]
                        and i not in replays and actual_win(row["terminal"], i % 4) == points[i][0],
                        "Every scheduled replay must reproduce the saved winner share.")
                replays.add(i)
    require(set(points) == set(range(amount)) and replays == {0, amount - 1},
            "A fresh comparison requires full games, exact reproduction, and no replaced seed.")
    return points, evidence


def export(lab, stress, prior, output):
    live = read(prior / "qualification.json")
    base_path = prior / "manifest.json"
    require(sha(base_path) == BASE, "A retargeting preview must preserve the exact live policy.")
    normal, adversarial = read(lab / "result.json"), read(stress / "result.json")
    config = normal["configuration"]
    label = normal["selected"]
    require(normal["passed"] is True and adversarial["passed"] is True
            and config == adversarial["configuration"],
            "The same retargeting policy must pass both ordinary and informed-rival qualification.")

    method_hashes, comparisons, seeds = {}, {}, set()
    for name, (stage, ecology, count) in CASES.items():
        experienced = name in {"experienced", "known16"}
        root = stress if experienced else lab
        selected = "retargeted" if experienced else label
        folder = root / stage
        path = folder / "method.json"
        method = read(path)
        method_hashes[name] = protocol = sha(path)
        bank = method["banks"][ecology]
        require(len(bank) == len(set(bank)) == count and seeds.isdisjoint(bank),
                "The five qualification comparisons need disjoint complete banks.")
        seeds.update(bank)
        baseline = live["initiative"]
        require(method["baseline"] == baseline and method["configs"] == {selected: baseline}
                and method["leadingRivalRetargetingVariants"] == {selected: config}
                and method["runtime"]["training.jar"] == sha(root / "lib/training.jar"),
                "The audit requires the exact policy, reference, and frozen native build.")
        candidate, first = collect(folder, method, protocol, bank, ecology, selected, baseline, config)
        control, second = collect(folder, method, protocol, bank, ecology, "control", baseline, None)
        require(all(candidate[i][1] == control[i][1] for i in range(count)),
                "Retargeting must preserve every matched opening bid exactly.")
        changes = [candidate[i][0] - control[i][0] for i in range(count)]
        if name == "experienced":
            changes = [statistics.mean(changes[i:i+4]) for i in range(0, count, 4)]
        expected = (adversarial["outcomes"][name]["ownWinDelta"] if experienced
                    else normal["outcomes"][name])
        checked = interval(changes)
        require(all(abs(checked[key] - expected[key]) < 1e-12
                    for key in ("mean", "lower95", "upper95")),
                "Independently recomputed win statistics must match the completed comparison.")
        comparisons[name] = {
            "worlds": [{"seed": seed, "candidate": candidate[i][0], "control": control[i][0]}
                       for i, seed in enumerate(bank)],
            "evidenceSha256": hashlib.sha256(canonical(first | second)).hexdigest(),
        }
    for library in ("ai-jvm.jar", "core-jvm.jar"):
        require(sha(lab / "lib" / library) == sha(stress / "lib" / library),
                "The adversarial experiment must preserve the identical inference and rule libraries.")
    audit = hashlib.sha256(canonical(method_hashes)).hexdigest()
    output.mkdir()
    shutil.copytree(prior, output / "mixed-preview")
    certificate = output / "qualification.json"
    certificate.write_bytes(canonical({
        "kind": "public-leading-rival-qualification-1", "basePreviewSha256": BASE,
        "nativeJarSha256": sha(lab / "lib/training.jar"), "auditSha256": audit,
        "retargeting": config, "comparisons": comparisons,
    }))
    manifest = output / "manifest.json"
    manifest.write_bytes(canonical({
        "kind": "coordinated-leading-rival-preview-1", "schemaVersion": 1,
        "basePreview": {"path": "mixed-preview/manifest.json", "sha256": BASE},
        "qualification": {"path": "qualification.json", "sha256": sha(certificate)},
    }))
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lab", type=Path, required=True)
    parser.add_argument("--stress", type=Path, required=True)
    parser.add_argument("--prior-asset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(export(args.lab, args.stress, args.prior_asset, args.output))
