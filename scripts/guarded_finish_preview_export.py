"""Bind the exact guarded final-advance rule to two verified fresh matchups."""

import argparse
from pathlib import Path
import shutil

from capital_geography_preview_export import canonical, check, digest, worlds
from initiative_campaign import interval, read, sha
from weighted_opening_campaign import selection_context


BASE = "81cabbabc39721924422ccfc0f83e7d0d76468f508eaa93d720f0e819b8556af"
CASES = ("mixed", "known-16")
GUARD = {"kind": "final-advance-plan-1", "margin": 250, "minimumPassLead": 100}
WORLDS = 1536


def planner_definition(path):
    return path.read_text().split("/** An exact same-phase", 1)[1].split(
        "/** Let the usual policy", 1
    )[0].rstrip()


def export(lab, previous, prior, output, source):
    checked = read(lab / "result.json")
    check(checked["passed"] and checked["policy"] == GUARD,
          "Publish only the exact qualified finishing guard.")
    check(sha(prior / "manifest.json") == BASE, "Use the exact live early attack model as a base.")
    solver = Path("core/src/commonMain/kotlin/dev/aragos/aor/FinalAdvancePlan.kt")
    check(planner_definition(source) == planner_definition(solver),
          "The online quote must run the same complete solver tested in native games.")
    for jar in ("core-jvm.jar", "ai-jvm.jar"):
        check(sha(prior.parent / "runtime/lib" / jar) == sha(lab / "lib" / jar),
              "The native simulations and live incumbent must share their inference bytes.")
    basis = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    anchor = previous / "automata-retargeting-coevolution-20261119/result.json"
    attack = read(anchor)["configuration"]
    frozen = read(lab / "plan.json")
    check(frozen["attackProofSha256"] == sha(anchor) and frozen["attacks"] == attack
          and frozen["policy"] == GUARD and frozen["winNoninferiorityMargin"] == .01,
          "The adopted rule and qualification limits must match the original protocol.")
    comparisons, completion, seen = {}, {}, set()
    native = sha(lab / "lib/training.jar")
    for case in CASES:
        style, context, _ = selection_context(basis, case, WORLDS, True)
        stage = lab / f"final-advance-guarded-fresh-{case}"
        method = stage / "method.json"
        declared = read(method)
        bank = declared["banks"][style]
        arms = ("control", "guarded")
        check(len(bank) == len(set(bank)) == WORLDS and seen.isdisjoint(bank),
              "Each fresh comparison must retain its independent matched seed bank.")
        seen.update(bank)
        expected_counter = dict.fromkeys(arms, context["counter"]) if "counter" in context else None
        check(declared["leadingRivalRetargetingVariants"] == dict.fromkeys(arms, attack) and
              declared["configs"] == {"guarded": basis["baseline"]} and
              declared["baseline"] == basis["baseline"] and
              declared["initiativeLineup"] == context["lineup"] and
              declared["secondCapitalGeographyVariants"] == list(arms) and
              declared["finalAdvanceVariants"] == {"guarded": GUARD} and
              declared.get("counterVariants") == expected_counter and
              declared["runtime"]["training.jar"] == native,
              "The finish mechanism must be the only changed policy in each paired matchup.")
        before, earlier = worlds(stage, "control", bank, style, sha(method),
                                 "second-capital-geography-1", WORLDS)
        after, guarded = worlds(stage, "guarded", bank, style, sha(method),
                                "second-capital-geography-1", WORLDS)
        evidence, completions = [], []
        for index, seed in enumerate(bank):
            a, b = before[index], after[index]
            seat = a["seat"]
            check(a["seed"] == b["seed"] == seed and seat == b["seat"] and
                  a["diagnostics"]["CANDIDATE"]["openingBid"] ==
                  b["diagnostics"]["CANDIDATE"]["openingBid"],
                  "A final purchase change must retain the opening and exact seed.")
            x, y = [row["roles"]["CANDIDATE"] for row in (a, b)]
            evidence.append({"seed": seed, "control": x["wins"][seat], "candidate": y["wins"][seat]})
            completions.append(int(y["scores"][seat]["advancePoints"] == 2100) -
                               int(x["scores"][seat]["advancePoints"] == 2100))
        win = interval([row["candidate"] - row["control"] for row in evidence])
        full = interval(completions)
        check(win == checked["outcomes"][case]["winEffect"]
              and full == checked["outcomes"][case]["fullSetEffect"],
              "Audit must reproduce both win and final advance completion effects.")
        comparisons[case] = {"worlds": evidence, "evidenceSha256": digest(earlier | guarded)}
        completion[case] = full["lower95"]
    output.mkdir()
    shutil.copytree(prior, output / "attack-preview")
    certificate = output / "qualification.json"
    certificate.write_bytes(canonical({
        "kind": "guarded-finish-qualification-1", "basePreviewSha256": BASE,
        "solverSha256": sha(solver), "nativeJarSha256": native, "guard": GUARD,
        "comparisons": comparisons, "completionLowerBounds": completion,
    }))
    manifest = output / "manifest.json"
    manifest.write_bytes(canonical({
        "kind": "coordinated-guarded-finish-preview-1", "schemaVersion": 1,
        "basePreview": {"path": "attack-preview/manifest.json", "sha256": BASE},
        "qualification": {"path": "qualification.json", "sha256": sha(certificate)},
    }))
    return manifest


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    for name in ("lab", "previous", "prior", "output", "training-source"):
        cli.add_argument(f"--{name}", type=Path, required=True)
    args = cli.parse_args()
    print(export(args.lab.resolve(), args.previous.resolve(), args.prior.resolve(),
                 args.output.resolve(), args.training_source.resolve()))
