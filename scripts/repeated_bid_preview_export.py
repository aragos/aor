"""Verify paired repeated-bid qualification and export an immutable local preview."""

import argparse
from pathlib import Path
import re
import shutil
import statistics

from capital_geography_preview_export import canonical, check, digest, worlds
from initiative_campaign import interval, read, sha
from weighted_opening_campaign import selection_context


BASE = "9b12cafae44a4f9af59fb616a06809efc28292a3b60157173a463a4ff4d15c0d"
CASES = ("ordinary", "fixed-17", "fixed-16", "rolling")
ARMS = ("control", "response")
MODE = {"kind": "phase-aware-bids-1", "profile": "repeated-undercut"}
LIVE = Path("server/src/main/kotlin/dev/aragos/aor/RepeatedUndercutBid.kt")
WORLDS = 768


def normalized(source):
    return re.sub(r"\s+", "", source)


def verify_policy(native):
    source = native.read_text()
    live = LIVE.read_text()
    check("REPEATED_UNDERCUT(\"repeated-undercut\", 5, 1.4)" in source,
          "The online strategy requires the tested native policy and weights.")
    selection = source.split("        val turn =", 1)[1].split("        choices.add(", 1)[0]
    selection = selection.replace("decision.observation", "observation").replace("decision.seed", "seed")
    selection = selection.replace("profile == PhaseBidProfile.REPEATED_UNDERCUT && ", "")
    selection = selection.replace("profile.maximumChange", "5").replace("profile.positionWeight", "1.4")
    selection = selection.replace("if (profile == PhaseBidProfile.CASH_CONSCIOUS) .21 else .12", ".12")
    deployed = live.split("        val turn =", 1)[1].split("        return Command.BuyTokens(picked)", 1)[0]
    for left, right in ((selection, deployed), *(
        (source.split(marker, 1)[1].split(end, 1)[0], live.split(marker, 1)[1].split(end, 1)[0])
        for marker, end in (("private enum class Intent", "    }"),
                            ("private fun repeatedUndercut", "\n}\n"))
    )):
        check(normalized(left) == normalized(right),
              "The live bid selection must reproduce the qualified bounds, sampling and public inputs.")


def verify_protocol(stage, declared, basis, plan, attack, case, native):
    ordinary = case == "ordinary"
    style, context, _ = selection_context(basis, "mixed" if ordinary else "known-16", WORLDS, True)
    counter = context.get("counter")
    if case == "rolling":
        counter = {"minimumObservations": 2, "lookbackTurns": 4, "openingBidPrior": 17}
    opponent = None if ordinary else {"kind": "leading-rival-table-1", "offsets": [1], "policy": attack}
    forecast = {"fixed-17": 18, "fixed-16": 17}.get(case)
    check(declared["configs"] == {"response": basis["baseline"]} and
          declared["baseline"] == basis["baseline"] and
          declared["initiativeLineup"] == context["lineup"] and
          declared["secondCapitalGeographyVariants"] == list(ARMS) and
          declared["leadingRivalRetargetingVariants"] == dict.fromkeys(ARMS, attack) and
          declared["finalAdvanceVariants"] == dict.fromkeys(ARMS, plan["finishPolicy"]) and
          declared["phaseBidVariants"] == {"response": MODE} and
          declared.get("counterVariants") == (dict.fromkeys(ARMS, counter) if counter else None) and
          declared.get("seriesMidgamePriors") ==
          (dict.fromkeys(ARMS, [forecast] * WORLDS) if forecast else None) and
          declared.get("rivalTargetingLineups") ==
          (dict.fromkeys(ARMS, opponent) if opponent else None) and
          declared["runtime"]["training.jar"] == native,
          "The paired trial must differ only in the repeated-bid response.")
    for role in ARMS:
        for path in stage.glob(f"{role}-0-*.json"):
            if path.name.endswith(".receipt.json"):
                continue
            request = read(path)
            check(request.get("phaseAwareBids") == (MODE if role == "response" else None) and
                  request["finalAdvancePlan"] == plan["finishPolicy"] and
                  request["leadingRivalRetargeting"] == attack,
                  "Every native request must retain the verified strategy and experiment arm.")
    return style


def export(lab, previous, prior, output, source):
    checked = read(lab / "result.json")
    check(checked["passed"] and checked["guardIncluded"], "Publish only the qualified full strategy.")
    check(sha(prior / "manifest.json") == BASE, "Use the exact guarded-finish preview as the base.")
    verify_policy(source)
    for jar in ("core-jvm.jar", "ai-jvm.jar"):
        check(sha(prior.parent / "runtime/lib" / jar) == sha(lab / "lib" / jar),
              "The simulation and live incumbent must share exact inference and engine bytes.")
    basis = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    attack_path = previous / "automata-retargeting-coevolution-20261119/result.json"
    finish_path = previous / "automata-guarded-finishes-20261119/result.json"
    attack, finish = read(attack_path), read(finish_path)
    plan = read(lab / "plan.json")
    check(attack["passed"] and finish["passed"] and plan["bidCandidate"] == MODE and
          plan["worlds"] == WORLDS and plan["targetingSourceSha256"] == sha(attack_path) and
          plan["finishSourceSha256"] == sha(finish_path) and plan["finishPolicy"] == finish["policy"],
          "The bid test requires the exact established targeting and finish policies.")
    seen, comparisons, effects = set(), {}, {}
    native = sha(lab / "lib/training.jar")
    for case in CASES:
        stage = lab / f"repeated-undercut-fresh-{case}"
        method = stage / "method.json"
        declared = read(method)
        style = verify_protocol(stage, declared, basis, plan, attack["configuration"], case, native)
        bank = declared["banks"][style]
        check(len(bank) == len(set(bank)) == WORLDS and seen.isdisjoint(bank),
              "Each opponent needs a distinct matched world bank.")
        seen.update(bank)
        before, earlier = worlds(stage, "control", bank, style, sha(method),
                                 "second-capital-geography-1", WORLDS)
        after, later = worlds(stage, "response", bank, style, sha(method),
                              "second-capital-geography-1", WORLDS)
        rows = []
        for index, seed in enumerate(bank):
            first, second = before[index], after[index]
            seat = first["seat"]
            a, b = (row["diagnostics"]["CANDIDATE"] for row in (first, second))
            check(first["seed"] == second["seed"] == seed and seat == second["seat"] and
                  a["openingBid"] == b["openingBid"], "The mid-game rule must retain the exact opening.")
            if case == "ordinary":
                check(a["chosenPolicyDecisionsSha256"] == b["chosenPolicyDecisionsSha256"] and
                      first["roles"] == second["roles"],
                      "The ordinary matchup must reproduce every incumbent choice and result.")
            rows.append({"seed": seed, "control": first["roles"]["CANDIDATE"]["wins"][seat],
                         "candidate": second["roles"]["CANDIDATE"]["wins"][seat]})
        effects[case] = interval([row["candidate"] - row["control"] for row in rows])
        check(effects[case] == checked["outcomes"][case]["own"],
              "Native receipts must reproduce the reported qualification exactly.")
        comparisons[case] = {"worlds": rows, "evidenceSha256": digest(earlier | later)}
    check(min(effect["lower95"] for effect in effects.values()) > -.03 and
          effects["fixed-17"]["lower95"] > 0.0 and
          statistics.mean(effect["mean"] for effect in effects.values()) >= .012,
          "The audited bid trials must satisfy the prospectively registered admission limits.")
    output.mkdir()
    shutil.copytree(prior, output / "guarded-preview")
    evidence = output / "qualification.json"
    evidence.write_bytes(canonical({"kind": "repeated-undercut-qualification-1",
                                    "basePreviewSha256": BASE, "policySha256": sha(LIVE),
                                    "nativeJarSha256": native, "comparisons": comparisons}))
    manifest = output / "manifest.json"
    manifest.write_bytes(canonical({
        "kind": "coordinated-repeated-undercut-preview-1", "schemaVersion": 1,
        "basePreview": {"path": "guarded-preview/manifest.json", "sha256": BASE},
        "qualification": {"path": "qualification.json", "sha256": sha(evidence)},
    }))
    return manifest


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    for name in ("lab", "previous", "prior", "output", "training-source"):
        cli.add_argument(f"--{name}", type=Path, required=True)
    args = cli.parse_args()
    print(export(args.lab.resolve(), args.previous.resolve(), args.prior.resolve(),
                 args.output.resolve(), args.training_source.resolve()))
