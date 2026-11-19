"""Verify the fresh targeting comparisons and build a pinned local preview package."""

import argparse
from pathlib import Path
import shutil

from capital_evolved_targeting_campaign import ENVIRONMENTS, WORLDS, effects
from capital_geography_preview_export import canonical, check, digest, worlds
from initiative_campaign import read, sha
from weighted_opening_campaign import selection_context


BASE = "b0c26ece3b090559c735f627f55cc3ebb2fe44a40c09923841e068909569054f"
CAPITAL = "second-capital-geography-1"


def export(lab, previous, prior, output):
    checked = read(lab / "result.json")
    combined = read(lab / "plan.json")
    standalone = previous / "automata-targeting-evolution-20261119/result.json"
    separate = read(standalone)
    check(checked["passed"] and separate["passed"] and
          checked["configuration"] == separate["configuration"] == combined["evolved"],
          "The exact expansion candidate must pass both independent qualification rounds.")
    check(sha(prior / "manifest.json") == combined["currentPreviewSha256"] == BASE,
          "Use the exact deployed capital policy as the incumbent archive.")
    parity = lab / "live-anchor-parity/result.json"
    anchor = read(parity)
    check(anchor["status"] == "exact" and anchor["worlds"] == 128 and anchor["replays"] == 1
          and anchor["researchPolicyJarSha256"] == sha(lab / "lib/ai-jvm.jar"),
          "The original live policy must reproduce in the new native qualification runtime.")
    anchor_plan = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    native = sha(lab / "lib/training.jar")
    cases, audits, seen = {}, {}, set()
    for mode in ENVIRONMENTS:
        stage = lab / f"capital-evolved-targeting-fresh-{mode}"
        method = stage / "method.json"
        science = read(method)
        style, context, _ = selection_context(anchor_plan, mode, WORLDS, True)
        bank = science["banks"][style]
        check(len(bank) == len(set(bank)) == WORLDS and seen.isdisjoint(bank),
              "All four supported fresh opponents must use distinct complete banks.")
        seen.update(bank)
        check(science["leadingRivalRetargetingVariants"] == {
                    "control": combined["reference"], "evolved": combined["evolved"]} and
              science["configs"] == {"evolved": anchor_plan["baseline"]} and
              science["baseline"] == anchor_plan["baseline"] and
              science["secondCapitalGeographyVariants"] == ["control", "evolved"] and
              science["runtime"]["training.jar"] == native,
              "The paired comparator must keep the same exact capital rule and tested runtime.")
        if mode == "independent-mix":
            check(science["rivalModelRoster"] == combined["modelRoster"],
                  "Use exactly the declared three complete diverse opponents.")
        audits[mode] = sha(method)
        former, first = worlds(stage, "control", bank, style, audits[mode], CAPITAL, WORLDS)
        current, second = worlds(stage, "evolved", bank, style, audits[mode], CAPITAL, WORLDS)
        recalculated = effects(stage, mode == "experienced",
                              context.get("responses", 16 if mode == "known-16" else None),
                              context.get("priors"))
        check(recalculated == checked["outcomes"][mode],
              "The independent audit must reproduce the exact matched effect in each matchup.")
        rows = []
        for index, seed in enumerate(bank):
            left, right = former[index], current[index]
            a, b = (row["diagnostics"]["CANDIDATE"] for row in (left, right))
            check(left["seed"] == right["seed"] == seed and left["seat"] == right["seat"] and
                  a.get("rivalModelBundles") == b.get("rivalModelBundles") and
                  a["openingBid"] == b["openingBid"],
                  "Only targeting may vary across the paired games.")
            seat = left["seat"]
            rows.append({"seed": seed, "control": left["roles"]["CANDIDATE"]["wins"][seat],
                         "candidate": right["roles"]["CANDIDATE"]["wins"][seat]})
        cases[mode] = {"worlds": rows, "evidenceSha256": digest(first | second)}
    output.mkdir()
    shutil.copytree(prior, output / "capital-preview")
    certificate = output / "qualification.json"
    certificate.write_bytes(canonical({
        "kind": "evolved-targeting-qualification-1", "basePreviewSha256": BASE,
        "nativeJarSha256": native, "policy": combined["evolved"], "comparisons": cases,
        "auditSha256": digest({"fresh": audits, "independent": sha(standalone), "liveAnchor": sha(parity)}),
    }))
    manifest = output / "manifest.json"
    manifest.write_bytes(canonical({
        "kind": "coordinated-evolved-targeting-preview-1", "schemaVersion": 1,
        "basePreview": {"path": "capital-preview/manifest.json", "sha256": BASE},
        "qualification": {"path": "qualification.json", "sha256": sha(certificate)},
    }))
    return manifest


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    for name in ("lab", "previous", "prior", "output"):
        cli.add_argument(f"--{name}", type=Path, required=True)
    args = cli.parse_args()
    print(export(args.lab.resolve(), args.previous.resolve(), args.prior.resolve(), args.output.resolve()))
