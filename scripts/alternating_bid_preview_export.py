"""Bind the wider public bid response to exact matched qualification and holdout receipts."""

import argparse
from pathlib import Path
import shutil
import statistics

from capital_geography_preview_export import canonical, check, digest, worlds
from initiative_campaign import interval, read, sha
from initiative_heterogeneous_campaign import lineup
from repeated_bid_preview_export import LIVE, MODE, normalized
from weighted_opening_campaign import selection_context


BASE = "bbfd7576375af65c3ab68d2e02c01d65e6fe947a93c454c81a350c6897ad70de"
WORLDS = 640
PEERS = [2, 3]
WIDER = dict(MODE, profile="alternating-undercut")
INITIAL = {"ordinary": None, "alternate-16-18": [16, 18], "alternate-17-19": [17, 19]}
HOLDOUT = {"alternate-17-18": [17, 18], "cycle-16-19-17-20": [16, 19, 17, 20], "rolling": None}


def verify_policy(native):
    source, live = native.read_text(), LIVE.read_text()
    simple = lambda text, start, end: text.split(start, 1)[1].split(end, 1)[0]
    opening = simple(source, "        val turn =", "        when (profile)").replace(
        "decision.observation", "observation")
    adapters = {
        "decision.observation": "observation",
        "decision.seed": "seed",
        "profile.maximumChange": "5",
        "profile.positionWeight": "1.4",
        "if (profile == PhaseBidProfile.CASH_CONSCIOUS) .21 else .12": ".12",
    }
    selection = simple(source, "        val forecast =", "        choices.add(")
    for old, new in adapters.items():
        selection = selection.replace(old, new)
    check("ALTERNATING_UNDERCUT(\"alternating-undercut\",5,1.4)" in normalized(source)
          and normalized("!repeatedUndercut(history, actor) && !alternatingUndercut(history, actor)")
          in normalized(simple(source, "PhaseBidProfile.ALTERNATING_UNDERCUT ->", "else -> Unit")),
          "The compiled native profile must use the tested public-history gate and weights.")
    check(normalized("!repeatedUndercut(history, actor) && "
                     "!(detectAlternation && alternatingUndercut(history, actor))") in normalized(live),
          "The deployed gate must activate the same native behavior without stacking two adjustments.")
    for reference, deployed in (
        (opening, simple(live, "        val turn =", "        if (\n")),
        (selection, simple(live, "        val forecast =", "        return Command.BuyTokens(picked)")),
        (simple(source, "private enum class Intent", "    }"),
         simple(live, "private enum class Intent", "    }")),
        (source.split("private fun repeatedUndercut", 1)[1],
         live.split("private fun repeatedUndercut", 1)[1]),
    ):
        check(normalized(reference) == normalized(deployed),
              "The portable adapter must exactly reproduce native bid selection and public inputs.")


def protocol(stage, method, case, bids, initial, basis, attack, guard, native):
    ordinary = case == "ordinary"
    arms = ("control", "response", "alternating") if initial else ("control", "alternating")
    modes = {"response": MODE, "alternating": WIDER} if initial else {
        "control": MODE, "alternating": WIDER,
    }
    style, context, _ = selection_context(basis, "mixed" if ordinary else "known-16", WORLDS, True)
    roster = dict.fromkeys(PEERS, basis["baseline"])
    if ordinary:
        roster = {1: basis["rivals"]["1"], **roster}
    counter = (dict(context["counter"], midgamePriorCycle=[bid + 1 for bid in bids]) if bids else
               {"minimumObservations": 2, "lookbackTurns": 4, "openingBidPrior": 17})
    targeting = {"kind": "leading-rival-table-1", "offsets": [1, *PEERS], "policy": attack}
    check(method["configs"] == dict.fromkeys(arms[1:], basis["baseline"]) and
          method["baseline"] == basis["baseline"] and method["initiativeLineup"] == lineup(roster) and
          method["previewPeerOffsets"] == PEERS and
          method["leadingRivalRetargetingVariants"] == dict.fromkeys(arms, attack) and
          method["secondCapitalGeographyVariants"] == list(arms) and
          method["finalAdvanceVariants"] == dict.fromkeys(arms, guard) and
          method["phaseBidVariants"] == modes and
          method["rivalTargetingLineups"] == dict.fromkeys(arms, targeting) and
          method.get("counterVariants") == (None if ordinary else dict.fromkeys(arms, counter)) and
          method["runtime"]["training.jar"] == native,
          "All compared automated seats must differ only in their verified bidding response.")
    expected_counter = None if ordinary else {
        "kind": "public-token-history-counter-1", "targetedRivalCount": 1, **counter,
    }
    for role in arms:
        for path in stage.glob(f"{role}-0-*.json"):
            if path.name.endswith(".receipt.json"):
                continue
            request = read(path)
            check(request.get("phaseAwareBids") == modes.get(role) and
                  request.get("adaptiveOpponent") == expected_counter and
                  request["previewPeerOffsets"] == PEERS and
                  request["initiativeLineup"] == lineup(roster) and
                  request["finalAdvancePlan"] == guard and
                  request["rivalTargetingLineup"] == targeting and
                  request["leadingRivalRetargeting"] == attack,
                  "Native shard inputs must preserve the arm, simulated peer policies and opponent.")
    return style


def export(lab, holdout, previous, prior, output, source):
    initial = read(lab / "actual-win-share.json")
    fresh = read(holdout / "result.json")
    check(initial["passed"] and fresh["passed"], "Both original and withheld bid comparisons must qualify.")
    check(sha(prior / "manifest.json") == BASE, "The preview must extend the exact existing bid response.")
    verify_policy(source)
    native = sha(lab / "lib/training.jar")
    for jar in ("training.jar", "core-jvm.jar", "ai-jvm.jar"):
        check(sha(lab / "lib" / jar) == sha(holdout / "lib" / jar),
              "Original and withheld opponents must use the same native executable.")
        if jar != "training.jar":
            check(sha(lab / "lib" / jar) == sha(prior.parent / "runtime/lib" / jar),
                  "The engine and inference models must share the installed preview's exact bytes.")
    basis = read(previous / "automata-weighted-opening-with-pressure-20261119/plan.json")
    attack = read(previous / "automata-retargeting-coevolution-20261119/result.json")["configuration"]
    guard = read(previous / "automata-guarded-finishes-20261119/result.json")["policy"]
    check(read(holdout / "plan.json")["initialPlanSha256"] == sha(lab / "plan.json"),
          "The held-out comparison must retain the original version of the bid response.")
    comparisons, effects, seen = {}, {}, set()
    for cases, folder, prefix, control, proof in (
        (INITIAL, lab, "alternating-bid-fresh", "response", initial),
        (HOLDOUT, holdout, "alternating-bid-holdout-fresh", "control", fresh),
    ):
        for case, bids in cases.items():
            stage = folder / f"{prefix}-{case}"
            method = stage / "method.json"
            declared = read(method)
            style = protocol(
                stage, declared, case, bids, control == "response", basis, attack, guard, native,
            )
            bank = declared["banks"][style]
            check(len(bank) == len(set(bank)) == WORLDS and seen.isdisjoint(bank),
                  "All six tables require disjoint banks with complete paired results.")
            seen.update(bank)
            left, before = worlds(stage, control, bank, style, sha(method),
                                  "second-capital-geography-1", WORLDS)
            right, after = worlds(stage, "alternating", bank, style, sha(method),
                                  "second-capital-geography-1", WORLDS)
            rows = []
            differentials = []
            for index, seed in enumerate(bank):
                a, b = left[index], right[index]
                seat = a["seat"]
                actors = [player for player in range(4) if player != (seat + 1) % 4]
                check(a["seed"] == b["seed"] == seed and seat == b["seat"] and
                      a["diagnostics"]["CANDIDATE"]["openingBid"] ==
                      b["diagnostics"]["CANDIDATE"]["openingBid"],
                      "Changing a midgame response must retain the exact seed, seat and opening.")
                old, new = (row["roles"]["CANDIDATE"]["wins"] for row in (a, b))
                rows.append({"seed": seed, "control": sum(old[i] for i in actors),
                             "candidate": sum(new[i] for i in actors)})
                differentials.append(sum(new[i] - old[i] for i in actors))
            report = proof["outcomes"][case]
            if control == "response":
                report = report["alternatingVersusCurrent"]
            effects[case] = interval(differentials)
            check(effects[case] == report["automationWinShareEffect"],
                  "Complete native outcomes must reproduce the awarded team win comparison.")
            comparisons[case] = {"worlds": rows, "evidenceSha256": digest(before | after)}
    original_stress = [effects[case] for case in INITIAL if case != "ordinary"]
    holdout_stress = [effects[case] for case in HOLDOUT if case != "rolling"]
    check(effects["ordinary"]["lower95"] > -.02 and
          min(row["lower95"] for row in original_stress) > -.03 and
          statistics.mean(row["mean"] for row in original_stress) >= .02 and
          any(row["lower95"] > 0 for row in original_stress) and
          min(effects[case]["lower95"] for case in HOLDOUT) > -.025 and
          statistics.mean(row["mean"] for row in holdout_stress) >= .015 and
          any(row["lower95"] > 0 for row in holdout_stress),
          "Both complete seed banks must meet the preregistered alternating-bid gates.")
    output.mkdir()
    shutil.copytree(prior, output / "repeated-preview")
    evidence = output / "qualification.json"
    evidence.write_bytes(canonical({"kind": "alternating-bid-qualification-1",
                                    "basePreviewSha256": BASE, "nativeJarSha256": native,
                                    "policySha256": sha(LIVE), "comparisons": comparisons}))
    manifest = output / "manifest.json"
    manifest.write_bytes(canonical({
        "kind": "coordinated-alternating-bid-preview-1", "schemaVersion": 1,
        "basePreview": {"path": "repeated-preview/manifest.json", "sha256": BASE},
        "qualification": {"path": "qualification.json", "sha256": sha(evidence)},
    }))
    return manifest


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    for name in ("lab", "holdout", "previous", "prior", "output", "training-source"):
        cli.add_argument(f"--{name}", type=Path, required=True)
    args = cli.parse_args()
    print(export(args.lab.resolve(), args.holdout.resolve(), args.previous.resolve(),
                 args.prior.resolve(), args.output.resolve(), args.training_source.resolve()))
