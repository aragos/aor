"""Completed pilot receipt and seed provenance boundaries for the next learning stage."""
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import expansion_arena as arena
import prepare_expansion_evolution as prepare


def metrics(reward=.1, win=.1):
    return {"rewardDifference": {"mean": reward}, "winShareDifference": {"mean": win}}


class EvolutionPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.protocol_path = self.root / "protocol.json"
        self.output = self.root / "run"
        self.models = {}
        for name in ("lambda-0.1", "lambda-1.0", "lambda-10.0"):
            path = self.root / (name + ".json")
            prepare.document(path, {"name": name})
            self.models[name] = str(path)
        selection = self.root / "selection.json"
        prepare.document(selection, {"eligibleRivalModelFiles": ["lambda-0.1.json"], "auditConsulted": False})
        finished = self.root / "finished.json"
        prepare.document(finished, {"selectionSha256": prepare.sha(selection),
            "models": {name + ".json": prepare.sha(path) for name, path in self.models.items()}})
        self.protocol = {"kind": "expansion-imitation-screen", "workers": 6,
            "bank": {"count": 128}, "models": self.models, "frozenFiles": {},
            "fitSelection": str(selection), "fitFinished": str(finished),
            "eligibleBeforeScreen": ["lambda-0.1"], "sources": self.models}
        prepare.document(self.protocol_path, self.protocol)
        self.report = {"descriptiveOnly": True, "qualificationPassed": False,
            "productionActivation": False, "protocolSha256": prepare.sha(self.protocol_path),
            "eligibleBeforeScreen": ["lambda-0.1"], "eligibleAfterCollapseExclusion": ["lambda-0.1"],
            "metrics": {name: metrics() for name in self.models}}
        self.publish()

    def publish(self, artifacts=None):
        prepare.document(self.output / "screen-report.json", self.report, immutable=False)
        prepare.document(self.output / "results/screen.json", {"status": "passed", "metrics": self.report,
            "artifacts": artifacts or {}}, immutable=False)

    def verifier(self, protocol, source, directory, bank):
        return [{"fixture": True}], self.report["metrics"][directory.name]

    def validate(self):
        with mock.patch.object(arena, "verify_arena", side_effect=self.verifier), \
                mock.patch.object(arena, "verify_common_baseline"):
            return prepare.screen_survivors(self.protocol_path, self.output)

    def test_verified_screen_can_only_keep_prior_eligible_models(self):
        _, survivors = self.validate()
        self.assertEqual(survivors, ["lambda-0.1"])

    def test_predeclared_collapse_rule_removes_prior_eligible_model(self):
        self.report["metrics"]["lambda-0.1"] = metrics(reward=-.051)
        self.report["eligibleAfterCollapseExclusion"] = []
        self.publish()
        self.assertEqual(self.validate()[1], [])

    def test_better_screened_ineligible_model_cannot_be_promoted(self):
        self.report["metrics"]["lambda-1.0"] = metrics(.5, .5)
        self.report["eligibleAfterCollapseExclusion"] = ["lambda-1.0"]
        self.publish()
        with self.assertRaisesRegex(ValueError, "cannot change.*collapse"):
            self.validate()

    def test_screen_cannot_retain_collapsed_model(self):
        self.report["metrics"]["lambda-0.1"] = metrics(win=-.11)
        self.publish()
        with self.assertRaisesRegex(ValueError, "cannot change.*collapse"):
            self.validate()

    def test_model_bytes_must_match_completed_fitter_receipt(self):
        Path(self.models["lambda-0.1"]).write_text("changed")
        with self.assertRaisesRegex(ValueError, "exact completed imitation fit"):
            self.validate()

    def test_changed_completed_screen_artifact_is_rejected(self):
        self.publish({"screen-report.json": "0" * 64})
        with self.assertRaisesRegex(ValueError, "artifact changed"):
            self.validate()

    def test_receipt_cannot_pin_file_outside_screen_output(self):
        self.publish({"../protocol.json": prepare.sha(self.protocol_path)})
        with self.assertRaisesRegex(ValueError, "escaped"):
            self.validate()

    def test_terminal_receipt_is_required_before_preparing_new_parents(self):
        (self.output / "results/screen.json").unlink()
        with self.assertRaises(FileNotFoundError):
            self.validate()


if __name__ == "__main__":
    unittest.main()
