"""Audit the target policy against ordinary, one-adopter and three-adopter tables."""

import argparse
from pathlib import Path
import shutil

from capital_geography_preview_export import canonical, check, digest, worlds
from initiative_campaign import interval, read, sha
from weighted_opening_campaign import selection_context


BASE = "09fffe9479450b5e9b2bd9cd741f09fb1da79acf820d5e2f5b5907d64514b0e8"
CASES = {"none-adopt": ("mixed", []), "one-adopts": ("known-16", [1]),
         "all-adopt": ("mixed", [1, 2, 3])}
WORLDS = 1024


def export(lab, previous, prior, output):
    final = read(lab / "result.json")
    check(final["passed"], "Only an independently qualified successor can be packaged.")
    check(sha(prior / "manifest.json") == BASE, "The exact live preview must be the base.")
    for jar in ("core-jvm.jar", "ai-jvm.jar"):
        check(sha(prior.parent / "runtime/lib" / jar) == sha(lab / "lib" / jar),
              "Both offline and online policies require the same engine and inference bytes.")
    anchor = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    qualified = previous / "automata-capital-evolved-targeting-20261119/result.json"
    frozen = read(lab / "plan.json")
    incumbent = read(qualified)["configuration"]
    control = "control"
    selected = final["selected"]
    policy = final["configuration"]
    native = sha(lab / "lib/training.jar")
    check(frozen["referenceSha256"] == sha(qualified) and frozen["reference"] == incumbent and
          frozen["variants"][selected] == policy and frozen["freshWorldsPerCase"] == WORLDS,
          "The selected rule and benchmark worlds must have been stated prospectively.")
    seen, comparisons = set(), {}
    for case, (setup, offsets) in CASES.items():
        stage = lab / f"retargeting-coevolution-fresh-{case}"
        method = stage / "method.json"
        declared = read(method)
        style, situation, _ = selection_context(anchor, setup, WORLDS, True)
        bank = declared["banks"][style]
        roster = {"kind": "leading-rival-table-1", "offsets": offsets, "policy": incumbent}
        expected_roster = dict.fromkeys((control, selected), roster) if offsets else None
        check(len(bank) == len(set(bank)) == WORLDS and seen.isdisjoint(bank),
              "Each held-out opponent must have a disjoint complete paired world bank.")
        seen.update(bank)
        check(declared["configs"] == {selected: anchor["baseline"]} and
              declared["baseline"] == anchor["baseline"] and
              declared["initiativeLineup"] == situation["lineup"] and
              declared["leadingRivalRetargetingVariants"] == {control: incumbent, selected: policy} and
              declared.get("rivalTargetingLineups") == expected_roster and
              declared["secondCapitalGeographyVariants"] == [control, selected] and
              declared["runtime"]["training.jar"] == native,
              "Only the acting seat's qualified attack parameters may change between the matched arms.")
        former, before = worlds(stage, control, bank, style, sha(method),
                                "second-capital-geography-1", WORLDS)
        winner, after = worlds(stage, selected, bank, style, sha(method),
                               "second-capital-geography-1", WORLDS)
        evidence = []
        for index, seed in enumerate(bank):
            a, b = former[index], winner[index]
            first, second = [row["diagnostics"]["CANDIDATE"] for row in (a, b)]
            seat = a["seat"]
            expected = sorted((seat + offset) % 4 for offset in offsets)
            check(a["seed"] == b["seed"] == seed and seat == b["seat"] and
                  first["openingBid"] == second["openingBid"] and
                  all(sorted(row.get("retargetingRivalSeats", [])) == expected for row in (first, second)),
                  "Both matched games must preserve the recorded opening and adopting opponents.")
            evidence.append({"seed": seed, "control": a["roles"]["CANDIDATE"]["wins"][seat],
                             "candidate": b["roles"]["CANDIDATE"]["wins"][seat]})
        own = interval([row["candidate"] - row["control"] for row in evidence])
        checked = final["outcomes"][case]["winGain"]
        check(all(checked[key] == value for key, value in own.items()),
              "The independent audit must reproduce the original matched winner intervals.")
        comparisons[case] = {"worlds": evidence, "evidenceSha256": digest(before | after)}
    output.mkdir()
    shutil.copytree(prior, output / "evolved-preview")
    certificate = output / "qualification.json"
    certificate.write_bytes(canonical({
        "kind": "adopting-targeting-qualification-1", "basePreviewSha256": BASE,
        "nativeJarSha256": native, "policy": policy, "comparisons": comparisons,
    }))
    manifest = output / "manifest.json"
    manifest.write_bytes(canonical({
        "kind": "coordinated-adopting-targeting-preview-1", "schemaVersion": 1,
        "basePreview": {"path": "evolved-preview/manifest.json", "sha256": BASE},
        "qualification": {"path": "qualification.json", "sha256": sha(certificate)},
    }))
    return manifest


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    for name in ("lab", "previous", "prior", "output"):
        cli.add_argument(f"--{name}", type=Path, required=True)
    args = cli.parse_args()
    print(export(args.lab.resolve(), args.previous.resolve(), args.prior.resolve(), args.output.resolve()))
