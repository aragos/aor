"""Checks for bounded whole-game search, the incumbent anchor and qualification gates."""

import argparse
import contextlib
import copy
import io
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import automata_experiments as experiments
import automata_policy_search as search


class PolicySearchTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.model = {"schemaVersion": 1, "weights": {
            "cash": 2.5, "planning.tokenValue.enabled": 1.0,
            "planning.tokenValue.v2.plan.income": .3,
            "planning.completeTokenAmounts": 1.0}}
        self.baseline = self.root / "baseline.json"
        experiments.document(self.baseline, self.model)

    def source_campaign(self):
        runtime = self.root / "distribution"
        (runtime / "lib").mkdir(parents=True)
        (runtime / "lib/training.jar").write_bytes(b"frozen-bytecode")
        java = self.root / "java"
        java.write_bytes(b"frozen-jdk")
        self.java = {"executable": str(java), "version": "JDK 21",
                     "files": {str(java): experiments.sha(java)}}
        source = self.root / "source"
        options = argparse.Namespace(
            output=source, runtime=runtime, baseline=self.baseline, profile=None,
            learning_mode="correction", java=str(java), seed=7100000, workers=1,
            maximum_seconds=900, opponent=[], territory_value_bundle=None,
            frozen_card_purchase_bundle=None, frozen_purchase_policy=None)
        with mock.patch.object(experiments, "java_identity", return_value=self.java), \
                contextlib.redirect_stdout(io.StringIO()):
            experiments.prepare(options)
        return source

    def prepare_options(self, source, **overrides):
        values = dict(from_campaign=source, output=self.root / "prepared", profile="smoke",
                      seed=7200000, workers=2, maximum_seconds=900)
        return argparse.Namespace(**dict(values, **overrides))

    def test_zero_correction_preserves_original_weights_without_mutating_source(self):
        original = copy.deepcopy(self.model)
        result = search.zero_correction(self.model)
        self.assertEqual(original, self.model)
        self.assertEqual(dict(original["weights"], **{search.GATE: 1.0}), result["weights"])
        self.assertEqual(original["schemaVersion"], result["schemaVersion"])

    def test_zero_correction_rejects_an_enabled_source_head(self):
        model = dict(self.model, weights=dict(self.model["weights"], **{search.GATE: 1.0}))
        with self.assertRaisesRegex(ValueError, "disabled correction"):
            search.zero_correction(model)

    def test_zero_correction_rejects_dormant_nonzero_coefficients(self):
        model = dict(self.model, weights=dict(self.model["weights"], **{
            search.GATE: 0.0, search.PREFIX + "v1.costFraction": .2}))
        with self.assertRaisesRegex(ValueError, "dormant correction"):
            search.zero_correction(model)

    def test_banks_reserve_distinct_actual_worlds(self):
        banks = search.seed_banks(7200000, search.PROFILES["search"], {
            "old": experiments.arena_seeds(7100000, 64)})
        seeds = [seed for bank in banks.values() for seed in bank]
        self.assertEqual(len(seeds), len(set(seeds)))
        self.assertEqual(experiments.arena_seeds(7200002, 512), banks["confirmation"])

    def test_banks_reject_source_overlap_even_at_a_different_stride_offset(self):
        source = {"old": experiments.arena_seeds(7100000, 4)}
        for offset in (7100000, 7100000 + 8191):
            with self.subTest(offset=offset), self.assertRaisesRegex(ValueError, "overlap"):
                search.seed_banks(offset, search.PROFILES["smoke"], source)

    def test_preparation_freezes_assets_and_the_finite_deadline(self):
        source = self.source_campaign()
        options = self.prepare_options(source)
        with mock.patch.object(experiments, "java_identity", return_value=self.java), \
                contextlib.redirect_stdout(io.StringIO()):
            campaign = search.prepare(options)
        protocol = experiments.read(options.output / "protocol.json")
        native = experiments.read(protocol["searchSource"])
        self.assertEqual(self.model, experiments.read(protocol["baseline"]))
        self.assertEqual(search.zero_correction(self.model), experiments.read(protocol["initial"]))
        self.assertEqual(protocol["initial"], native["candidate"])
        self.assertEqual(protocol["baseline"], native["incumbent"])
        self.assertEqual(search.PARAMETERS, native["parameters"])
        self.assertEqual(["search", "confirm"], [stage["id"] for stage in campaign["stages"]])
        self.assertEqual(["search"], campaign["stages"][1]["dependsOn"])
        self.assertEqual([675, 225], [stage["maximumSeconds"] for stage in campaign["stages"]])
        self.assertEqual(900, campaign["limits"]["maximumSeconds"])
        self.assertEqual(3 * experiments.GIB, campaign["limits"]["maximumRssBytes"])
        self.assertTrue(all(stage["maximumAttempts"] == 2 for stage in campaign["stages"]))
        for name in ("runtime/lib/training.jar", "scripts/automata_policy_search.py",
                     "inputs/baseline.json", "inputs/zero-correction.json", "protocol.json",
                     "sources/search.json", self.java["executable"]):
            self.assertEqual(experiments.sha(options.output / name), campaign["inputs"][name])

    def test_preparation_rejects_a_tampered_source_asset(self):
        source = self.source_campaign()
        (source / "runtime/lib/training.jar").write_bytes(b"changed-bytecode")
        with self.assertRaisesRegex(ValueError, "Source campaign input changed"):
            search.prepare(self.prepare_options(source))

    def test_preparation_rejects_unpinned_reused_assets(self):
        source = self.source_campaign()
        campaign = experiments.read(source / "campaign.json")
        del campaign["inputs"]["runtime/lib/training.jar"]
        experiments.document(source / "campaign.json", campaign, immutable=False)
        with self.assertRaisesRegex(ValueError, "Every reused"):
            search.source_inputs(source)

    def test_preparation_rejects_limits_outside_the_local_budget(self):
        source = self.source_campaign()
        for overrides in ({"workers": 0}, {"workers": 5}, {"workers": True},
                          {"maximum_seconds": 29}, {"maximum_seconds": 14401},
                          {"maximum_seconds": float("inf")}):
            with self.subTest(overrides=overrides), self.assertRaises(ValueError):
                search.prepare(self.prepare_options(source, **overrides))
        self.assertFalse((self.root / "prepared").exists())

    def test_search_requires_both_positive_means(self):
        for win, reward, expected in ((.1, .2, True), (0, .2, False), (.1, 0, False),
                                      (-.1, .2, False), (.1, -.2, False)):
            with self.subTest(win=win, reward=reward):
                metrics = {"winShareDifference": {"mean": win},
                           "rewardDifference": {"mean": reward}}
                self.assertEqual(expected, search.search_passes(metrics))

    def test_confirmation_requires_both_lower_bounds_and_enough_worlds(self):
        positive = {name: {"count": 512, "mean": .2, "lower95": .1}
                    for name in ("winShareDifference", "rewardDifference")}
        self.assertTrue(search.confirmation_passes({"profile": "search"}, positive))
        for name in positive:
            for changed in ({"lower95": 0}, {"lower95": -.1}, {"lower95": None}, {"count": 511}):
                metrics = dict(positive, **{name: dict(positive[name], **changed)})
                with self.subTest(metric=name, changed=changed):
                    self.assertFalse(search.confirmation_passes({"profile": "search"}, metrics))

    def test_smoke_never_qualifies_even_with_positive_confirmation(self):
        metrics = {name: {"count": 512, "lower95": .1}
                   for name in ("winShareDifference", "rewardDifference")}
        self.assertFalse(search.confirmation_passes({"profile": "smoke"}, metrics))

    def test_search_forwards_the_declared_worker_count(self):
        protocol = {"searchSource": "frozen-search.json", "workers": 3}
        selected = {"candidateId": 0, "overall": {
            "winShareDifference": {"mean": 0}, "rewardDifference": {"mean": 0}}}
        output = self.root / "run"
        with mock.patch.object(experiments, "native") as native, \
                mock.patch.object(search, "verify_search", return_value=selected), \
                mock.patch.object(search, "result"):
            search.search_stage(protocol, output)
        native.assert_called_once_with(protocol, "strategy-search", "frozen-search.json",
                                       output / "search", extra=("--workers", "3"))

    def test_native_search_receives_only_the_remaining_deadline(self):
        protocol = {"java": {"executable": "/frozen/java"}, "runtime": "/frozen/runtime",
                    "heapMiB": 1536, "workers": 3}
        with mock.patch.object(experiments, "remaining_seconds", return_value=90), \
                mock.patch.object(experiments.subprocess, "run", return_value=mock.Mock(returncode=0)) as run:
            experiments.native(protocol, "strategy-search", "source.json", self.root / "native",
                               extra=("--workers", "3"))
        command = run.call_args.args[0]
        self.assertEqual(.025, float(command[command.index("--hours") + 1]))
        self.assertEqual("3", command[command.index("--workers") + 1])
        self.assertEqual(1, command.count("--workers"))

    def test_search_report_records_measured_returns_without_qualification(self):
        protocol = {"profile": "search", "seedNote": "Reserved source worlds excluded.",
                    "intervalMethod": "Paired normal intervals."}
        metrics = {"selected": 2, "selection": {
            "winShareDifference": {"mean": .1, "lower95": .02, "upper95": .18, "count": 256},
            "rewardDifference": {"mean": .08, "lower95": .01, "upper95": .15, "count": 256}}}
        output = self.root / "reports"
        search.result(protocol, output, "search", True, "Selection passed.", metrics, [])
        text = (output / "search-report.txt").read_text()
        report = experiments.read(output / "search-report.json")
        self.assertIn("Selected candidate: 2", text)
        self.assertIn("Win-share difference: +10.0000 pp", text)
        self.assertIn("95% interval [+2.0000, +18.0000] pp; 256 paired worlds.", text)
        self.assertIn("Composite reward difference: +0.0800", text)
        self.assertIn("95% interval [+0.0100, +0.1500]; 256 paired worlds.", text)
        self.assertIn("independent confirmation is required", text)
        self.assertFalse(report["accepted"])
        self.assertFalse(report["productionActivation"])

    def native_search(self):
        """Two completed candidates with one fresh finalist and exact zero-anchor outcomes."""
        directory = self.root / "native"
        initial = search.zero_correction(self.model)
        protocol = {"searchSource": str(self.root / "search-source.json"), "workers": 2,
                    "candidates": 2, "finalists": 1, "baseline": str(self.baseline),
                    "parameters": search.PARAMETERS, "seed": 7200000,
                    "opponents": [str(self.baseline)],
                    "seedBanks": {name: experiments.arena_seeds(7200000 + offset, 2)
                                  for offset, name in enumerate(("screening", "selection"))}}
        identity = {"kind": "strategy-search", "signature": "frozen-search"}
        report = dict(identity, complete=True, selectionOnly=True, source=protocol["searchSource"],
                      workers=2, candidates=2, finalists=1, selectedCandidateId=1, banks={})
        experiments.document(directory / "initial.json", initial)
        experiments.document(directory / "incumbent.json", self.model)
        for offset, bank in enumerate(("screening", "selection")):
            seeds = protocol["seedBanks"][bank]
            report["banks"][bank] = {"purpose": "SCREENING", "seedOffset": 7200000 + offset,
                                     "pairsPerConfiguration": len(seeds), "seeds": seeds}
            rows = []
            for candidate_id in ([0, 1] if bank == "screening" else [1]):
                parameters = {item["name"]: .1 if candidate_id == 1 and index == 0 else 0.0
                              for index, item in enumerate(search.PARAMETERS)}
                model = dict(initial, weights=dict(initial["weights"], **parameters))
                experiments.document(directory / f"candidate-{candidate_id}.json", model)
                pairs = []
                for index, seed in enumerate(seeds):
                    incumbent = {"wins": [0.0] * 4, "rewards": [.1] * 4, "ranks": [2.0] * 4,
                                 "scores": [{"player": f"p{seat + 1}", "total": 100} for seat in range(4)],
                                 "decisions": 100, "elapsedMillis": 10}
                    candidate = copy.deepcopy(incumbent)
                    candidate["elapsedMillis"] = 20
                    candidate["wins"][index] += candidate_id
                    candidate["rewards"][index] += .5 * candidate_id
                    pair = {"index": index, "seed": seed, "seat": index,
                            "configuration": experiments.CONFIGURATION, "opponentIndices": [0] * 4,
                            "candidate": candidate, "incumbent": incumbent}
                    experiments.document(directory / f"{bank}-incumbent-{index}.json", incumbent)
                    experiments.document(directory / f"{bank}-candidate-{candidate_id}-pair-{index}.json", pair)
                    pairs.append(pair)
                rows.append({"candidateId": candidate_id,
                             "label": "zero-and-center" if candidate_id == 0 else "sample-1",
                             "parameters": parameters, "model": f"candidate-{candidate_id}.json",
                             "overall": dict(search.comparison(pairs), pairs=len(pairs))})
            report[bank] = sorted(rows, key=search.ranking)
        experiments.document(directory / "candidate.json", experiments.read(directory / "candidate-1.json"))
        experiments.document(directory / "search.json", report)
        experiments.document(directory / "state.json", dict(identity, phase="complete", selectedCandidateId=1))
        experiments.document(directory / "run.json", identity)
        experiments.document(directory / "finished.json", {"at": "2026-11-09T00:00:00Z"})
        return protocol, directory

    def test_completed_native_result_accepts_elapsed_time_differences_for_zero_anchor(self):
        protocol, directory = self.native_search()
        self.assertEqual(1, search.verify_search(protocol, directory)["candidateId"])

    def test_native_result_requires_every_declared_pair(self):
        protocol, directory = self.native_search()
        (directory / "selection-candidate-1-pair-1.json").unlink()
        with self.assertRaises(FileNotFoundError):
            search.verify_search(protocol, directory)

    def test_zero_anchor_rejects_changed_gameplay_even_when_return_deltas_are_zero(self):
        protocol, directory = self.native_search()
        path = directory / "screening-candidate-0-pair-0.json"
        pair = experiments.read(path)
        pair["candidate"]["scores"][0]["total"] += 1
        experiments.document(path, pair, immutable=False)
        with self.assertRaisesRegex(ValueError, "exact incumbent gameplay parity"):
            search.verify_search(protocol, directory)

    def test_native_result_rejects_incorrect_screening_rank(self):
        protocol, directory = self.native_search()
        path = directory / "search.json"
        report = experiments.read(path)
        report["screening"] = list(reversed(report["screening"]))
        experiments.document(path, report, immutable=False)
        with self.assertRaisesRegex(ValueError, "finalists|ranking"):
            search.verify_search(protocol, directory)

    def test_native_result_rejects_an_inconsistent_selected_model(self):
        protocol, directory = self.native_search()
        experiments.document(directory / "candidate.json", experiments.read(directory / "candidate-0.json"),
                             immutable=False)
        with self.assertRaisesRegex(ValueError, "selected candidate"):
            search.verify_search(protocol, directory)


if __name__ == "__main__":
    unittest.main()
