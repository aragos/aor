"""Audit the exact fresh capital trials before packaging a local preview."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil

from initiative_campaign import Campaign, read, sha
from initiative_opening_strategy import load_games
from second_capital_geography_campaign import ENVIRONMENTS, WORLDS, measure
from weighted_opening_campaign import selection_context


BASE = "6ae7f2033f72a4830e70a6f88271a2cf72fb3a8d1778608615bf010eb0cfe97b"
POLICY = "second-capital-geography-1"


def canonical(data):
    return (json.dumps(data, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n").encode()


def digest(data):
    return hashlib.sha256(canonical(data)).hexdigest()


def check(condition, message):
    if not condition:
        raise ValueError(message)


def worlds(stage, role, bank, style, plan_sha, geometry, world_count=WORLDS):
    receipts = {}
    for path in sorted(stage.glob(f"{role}-0-*.json")):
        if path.name.endswith(".receipt.json"):
            continue
        spec = read(path)
        check(spec["protocolSha256"] == plan_sha and spec["seeds"] == bank and
              spec["opponentStyle"] == style and spec.get("secondCapitalGeography") == geometry,
              "Native requests must match the original seed bank and the selected capital rule.")
        result = path.with_suffix("")
        receipt = stage / f"{result.name}.receipt.json"
        check(Campaign.validate_result(result, path) == read(receipt)["games"],
              "The original native receipt must bind every completed result and scheduled replay.")
        receipts[result.name] = sha(result / "finished.json")
    rows = load_games(stage, role)
    check(len(rows) == world_count and len(receipts) == (world_count + 127) // 128,
          "Each arm must include every scheduled native shard.")
    return rows, receipts


def export(lab, previous, prior, output):
    base = prior / "manifest.json"
    check(sha(base) == BASE, "The preview must extend the exact live expansion-targeting policy.")
    final = read(lab / "result.json")
    check(final["passed"] and final["worldsPerTable"] == WORLDS,
          "The capital rule requires a completed four-table qualification.")
    manifest = read(lab / "plan.json")
    parity = lab / "live-anchor-parity/result.json"
    anchor = read(parity)
    policy_jar = lab / "lib/ai-jvm.jar"
    native_jar = lab / "lib/training.jar"
    check(anchor["status"] == "exact" and anchor["worlds"] == 128 and anchor["replays"] == 1 and
          anchor["researchPolicyJarSha256"] == sha(policy_jar),
          "The independently rerun live-policy anchor must remain exact.")
    earlier = previous / "automata-weighted-opening-with-pressure-20261119/plan.json"
    old = read(earlier)
    check(manifest["rivalSourceSha256"] == sha(earlier),
          "The matched worlds must use the fixed current rival roster.")
    books, audits, comparisons = set(), {}, {}
    for mode in ENVIRONMENTS:
        stage = lab / f"capital-geography-fresh-{mode}"
        path = stage / "method.json"
        method = read(path)
        style, _, _ = selection_context(old, mode, WORLDS, True)
        bank = method["banks"][style]
        check(len(bank) == len(set(bank)) == WORLDS and books.isdisjoint(bank),
              "The fresh tests must use four disjoint game banks.")
        books.update(bank)
        check(method["secondCapitalGeographyVariants"] == ["geography"] and
              method["leadingRivalRetargetingVariants"] == dict.fromkeys(
                  ("control", "geography"), old["retargeting"]) and
              method["configs"] == {"geography": old["baseline"]} and
              method["baseline"] == old["baseline"] and
              method["runtime"]["training.jar"] == sha(native_jar),
              "The original campaign must preserve the same policy and compiled runtime in both arms.")
        audits[mode] = sha(path)
        controls, before = worlds(stage, "control", bank, style, audits[mode], None)
        chosen, after = worlds(stage, "geography", bank, style, audits[mode], POLICY)
        groups = 64 if mode == "experienced" else None
        check(measure(stage, WORLDS, groups) == final["outcomes"][mode],
              "Independent recomputation must reproduce the qualification statistics.")
        results = []
        for index, seed in enumerate(bank):
            reference, trial = controls[index], chosen[index]
            actor = reference["seat"]
            check(reference["seed"] == trial["seed"] == seed and actor == trial["seat"],
                  "The two tested policies must face the same original world and seat.")
            opening = reference["diagnostics"]["CANDIDATE"]["openingBid"]
            candidate = trial["diagnostics"]["CANDIDATE"]["openingBid"]
            results.append({
                "seed": seed, "position": opening["capitalPosition"],
                "changed": opening["capital"] != candidate["capital"],
                "control": reference["roles"]["CANDIDATE"]["wins"][actor],
                "candidate": trial["roles"]["CANDIDATE"]["wins"][actor],
            })
        comparisons[mode] = {"worlds": results, "evidenceSha256": digest(before | after)}
    output.mkdir()
    shutil.copytree(prior, output / "leading-rival-preview")
    certificate = output / "qualification.json"
    certificate.write_bytes(canonical({
        "kind": POLICY, "basePreviewSha256": BASE, "nativeJarSha256": sha(native_jar),
        "auditSha256": digest({"fresh": audits, "liveAnchor": sha(parity)}),
        "comparisons": comparisons,
    }))
    destination = output / "manifest.json"
    destination.write_bytes(canonical({
        "kind": "coordinated-second-capital-preview-1", "schemaVersion": 1,
        "basePreview": {"path": "leading-rival-preview/manifest.json", "sha256": BASE},
        "qualification": {"path": "qualification.json", "sha256": sha(certificate)},
    }))
    return destination


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("lab", "previous", "prior", "output"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    args = parser.parse_args()
    print(export(args.lab.resolve(), args.previous.resolve(), args.prior.resolve(), args.output.resolve()))
