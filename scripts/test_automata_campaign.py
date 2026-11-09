"""Tiny local subprocess tests for campaign policy, durability and owned-process cleanup."""

import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest


CONTROLLER = Path(__file__).with_name("automata_campaign.py").resolve()
WORKLOAD = '''import hashlib, json, os, pathlib, signal, subprocess, sys, time
output = pathlib.Path(os.environ["AOR_CAMPAIGN_OUTPUT"])
stage = os.environ["AOR_CAMPAIGN_STAGE_ID"]
mode = sys.argv[1]
with (output / "trace.txt").open("a") as stream:
    stream.write(stage + "\\n")
if mode == "retry" and not (output / "retried").exists():
    (output / "retried").write_text("first attempt")
    sys.exit(4)
if mode == "missing":
    sys.exit(0)
if mode == "disk":
    (output / "large.bin").write_bytes(b"x" * 1_000_000)
if mode in ("tree", "orphan"):
    child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
    (output / "child.pid").write_text(str(child.pid))
if mode in ("sleep", "tree", "disk"):
    (output / "ready").write_text(str(os.getpid()))
    if mode == "tree" and (output / "retried").exists():
        pass
    else:
        time.sleep(60)
result = {"schemaVersion": 1, "status": "rejected" if mode == "reject" else "passed",
          "summary": mode, "metrics": {"stage": stage}}
if mode == "artifact":
    artifact = output / "accepted-model.json"
    artifact.write_text("frozen artifact")
    result["artifacts"] = {artifact.name: hashlib.sha256(artifact.read_bytes()).hexdigest()}
if mode == "mutate":
    with pathlib.Path(__file__).open("a") as stream:
        stream.write("\\n# changed input\\n")
(output / (stage + ".json")).write_text(json.dumps(result))
'''


class AutomataCampaignTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.output = self.root / "run"
        self.source = self.root / "campaign.json"
        self.workload = self.root / "workload.py"
        self.workload.write_text(WORKLOAD)
        self.spec = {
            "schemaVersion": 1, "kind": "automata-campaign",
            "limits": {"maximumSeconds": 30, "maximumDataBytes": 10_000_000,
                       "minimumFreeBytes": 0, "maximumRssBytes": 500_000_000},
            "inputs": {"workload.py": hashlib.sha256(self.workload.read_bytes()).hexdigest()},
            "stages": [],
        }
        self.children = []

    def tearDown(self):
        for process in self.children:
            if process.poll() is None:
                process.terminate()
                try:
                    process.communicate(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.communicate(timeout=5)
        self.temporary.cleanup()

    def stage(self, name, mode="pass", dependencies=(), attempts=1, seconds=20):
        self.spec["stages"].append({
            "id": name, "command": [sys.executable, str(self.workload), mode],
            "result": f"{name}.json", "maximumSeconds": seconds,
            "maximumAttempts": attempts, "dependsOn": list(dependencies),
        })

    def command(self, resume=False):
        return [sys.executable, str(CONTROLLER), "--spec", str(self.source),
                "--output", str(self.output)] + (["--resume"] if resume else [])

    def run_campaign(self, resume=False):
        if not resume:
            self.source.write_text(json.dumps(self.spec))
        return subprocess.run(self.command(resume), stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, text=True, timeout=10)

    def launch(self):
        self.source.write_text(json.dumps(self.spec))
        process = subprocess.Popen(self.command(), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   text=True)
        self.children.append(process)
        return process

    def read(self, name="campaign-state.json"):
        return json.loads((self.output / name).read_text())

    def await_file(self, name):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            path = self.output / name
            if path.is_file():
                return path
            time.sleep(0.02)
        self.fail(f"Stage did not create {name}")

    def assert_process_stopped(self, pid):
        result = subprocess.run(["ps", "-p", str(pid), "-o", "stat="],
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertTrue(not result.stdout.strip() or result.stdout.strip().startswith("Z"),
                        f"Process {pid} is still running: {result.stdout}")

    def test_rejection_skips_dependents_and_continues_independent_work(self):
        self.stage("diagnostic", "reject")
        self.stage("dependent", dependencies=("diagnostic",))
        self.stage("independent")
        result = self.run_campaign()
        self.assertEqual(result.returncode, 0, result.stderr)
        state = self.read()
        self.assertEqual(state["status"], "completed")
        self.assertEqual([item["status"] for item in state["stages"].values()],
                         ["skipped", "rejected", "passed"])
        self.assertEqual((self.output / "trace.txt").read_text().splitlines(),
                         ["diagnostic", "independent"])
        self.assertEqual(self.read("campaign-report.json")["scientificRejections"], ["diagnostic"])

    def test_zero_exit_without_result_is_infrastructure_failure(self):
        self.stage("missing", "missing")
        self.stage("later")
        result = self.run_campaign()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.read()["stages"]["later"]["status"], "pending")
        self.assertEqual(self.read()["status"], "failed")

    def test_explicit_resume_retries_once_and_preserves_both_deadlines(self):
        self.stage("retry", "retry", attempts=2)
        self.stage("later", dependencies=("retry",))
        self.assertNotEqual(self.run_campaign().returncode, 0)
        first = self.read()
        self.assertEqual(first["stages"]["retry"]["attempts"], 1)
        resumed = self.run_campaign(resume=True)
        self.assertEqual(resumed.returncode, 0, resumed.stderr)
        second = self.read()
        self.assertEqual(second["deadlineEpoch"], first["deadlineEpoch"])
        self.assertEqual(second["stages"]["retry"]["deadlineEpoch"],
                         first["stages"]["retry"]["deadlineEpoch"])
        self.assertEqual(second["stages"]["retry"]["attempts"], 2)
        self.assertEqual(second["stages"]["later"]["status"], "passed")

    def test_resume_does_not_repeat_completed_stages(self):
        self.stage("done")
        self.assertEqual(self.run_campaign().returncode, 0)
        self.assertEqual(self.run_campaign(resume=True).returncode, 0)
        self.assertEqual((self.output / "trace.txt").read_text(), "done\n")
        self.assertEqual(self.read()["stages"]["done"]["attempts"], 1)

    def test_exhausted_attempt_does_not_restart(self):
        self.stage("failed", "missing")
        self.assertNotEqual(self.run_campaign().returncode, 0)
        self.assertNotEqual(self.run_campaign(resume=True).returncode, 0)
        self.assertEqual((self.output / "trace.txt").read_text(), "failed\n")
        self.assertIn("exhausted", self.read()["summary"])

    def test_changed_input_refuses_resume_without_rewriting_state(self):
        self.stage("done")
        self.assertEqual(self.run_campaign().returncode, 0)
        before = (self.output / "campaign-state.json").read_bytes()
        self.workload.write_text(WORKLOAD + "\n# changed\n")
        result = self.run_campaign(resume=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Input changed", result.stderr)
        self.assertEqual((self.output / "campaign-state.json").read_bytes(), before)
        self.assertEqual(self.read("campaign-report.json")["status"], "refused")

    def test_changed_completed_result_refuses_resume(self):
        self.stage("done")
        self.assertEqual(self.run_campaign().returncode, 0)
        (self.output / "done.json").write_text('{"schemaVersion":1,"status":"rejected","summary":"x"}')
        result = self.run_campaign(resume=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Completed result changed", result.stderr)

    def test_changed_accepted_artifact_refuses_resume(self):
        self.stage("done", "artifact")
        self.assertEqual(self.run_campaign().returncode, 0)
        (self.output / "accepted-model.json").write_text("changed artifact")
        result = self.run_campaign(resume=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Accepted stage artifact changed", result.stderr)

    def test_changed_input_during_stage_stops_before_acceptance(self):
        self.stage("mutate", "mutate")
        self.stage("later", dependencies=("mutate",))
        result = self.run_campaign()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.read()["stages"]["mutate"]["status"], "failed")
        self.assertEqual(self.read()["stages"]["later"]["status"], "pending")
        self.assertIn("Input changed", self.read()["summary"])

    def test_changed_specification_refuses_resume(self):
        self.stage("done")
        self.assertEqual(self.run_campaign().returncode, 0)
        self.spec["limits"]["maximumSeconds"] = 100
        self.source.write_text(json.dumps(self.spec))
        result = self.run_campaign(resume=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("specification, source code or runtime changed", result.stderr)

    def test_stage_deadline_cannot_be_renewed_by_resume(self):
        self.stage("slow", "sleep", seconds=0.3, attempts=2)
        self.assertNotEqual(self.run_campaign().returncode, 0)
        original = self.read()
        self.assertEqual(original["status"], "deadline")
        self.assertNotEqual(self.run_campaign(resume=True).returncode, 0)
        resumed = self.read()
        self.assertEqual(resumed["stages"]["slow"]["attempts"], 1)
        self.assertEqual(resumed["stages"]["slow"]["deadlineEpoch"],
                         original["stages"]["slow"]["deadlineEpoch"])

    def test_campaign_deadline_stops_pending_stages(self):
        self.spec["limits"]["maximumSeconds"] = 0.3
        self.stage("slow", "sleep", attempts=2)
        self.stage("later")
        self.assertNotEqual(self.run_campaign().returncode, 0)
        self.assertEqual(self.read()["status"], "deadline")
        self.assertNotEqual(self.run_campaign(resume=True).returncode, 0)
        self.assertEqual(self.read()["stages"]["later"]["status"], "pending")
        self.assertEqual(self.read()["stages"]["slow"]["attempts"], 1)

    def test_signal_stops_owned_descendants_and_allows_bounded_resume(self):
        self.stage("tree", "tree", attempts=2)
        process = self.launch()
        child_pid = int(self.await_file("child.pid").read_text())
        leader_pid = int(self.await_file("ready").read_text())
        process.send_signal(signal.SIGTERM)
        process.communicate(timeout=5)
        self.assertEqual(process.returncode, 128 + signal.SIGTERM)
        self.assert_process_stopped(child_pid)
        self.assert_process_stopped(leader_pid)
        self.assertEqual(self.read()["status"], "interrupted")
        (self.output / "retried").write_text("resume now")
        resumed = self.run_campaign(resume=True)
        self.assertEqual(resumed.returncode, 0, resumed.stderr)
        self.assert_process_stopped(int((self.output / "child.pid").read_text()))

    def test_successful_leader_does_not_leave_background_descendants(self):
        self.stage("orphan", "orphan")
        result = self.run_campaign()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_process_stopped(int((self.output / "child.pid").read_text()))

    def test_sampled_disk_limit_stops_stage(self):
        self.spec["limits"]["maximumDataBytes"] = 100_000
        self.stage("disk", "disk")
        result = self.run_campaign()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.read()["status"], "resource-limit")
        self.assertIn("data byte", self.read()["summary"])

    def test_sampled_memory_limit_stops_stage(self):
        self.spec["limits"]["maximumRssBytes"] = 1
        self.stage("memory", "sleep")
        result = self.run_campaign()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.read()["status"], "resource-limit")
        self.assertIn("resident memory", self.read()["summary"])

    def test_free_disk_reserve_prevents_launch(self):
        self.spec["limits"]["minimumFreeBytes"] = 10**18
        self.stage("disk")
        result = self.run_campaign()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.read()["status"], "resource-limit")
        self.assertFalse((self.output / "trace.txt").exists())

    def test_lock_prevents_concurrent_controller_and_status_is_read_only(self):
        self.stage("sleep", "sleep")
        process = self.launch()
        self.await_file("ready")
        before = (self.output / "campaign-state.json").read_bytes()
        result = self.run_campaign(resume=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Another campaign controller", result.stderr)
        status = subprocess.run([sys.executable, str(CONTROLLER), "--output", str(self.output),
                                 "--status"], capture_output=True, text=True, timeout=5)
        self.assertEqual(status.returncode, 0, status.stderr)
        self.assertIn("Campaign: running", status.stdout)
        self.assertEqual((self.output / "campaign-state.json").read_bytes(), before)
        process.terminate()
        process.communicate(timeout=5)

    def test_result_cannot_escape_output(self):
        self.stage("escape")
        self.spec["stages"][0]["result"] = "../escape.json"
        result = self.run_campaign()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Unsafe stage result", result.stderr)
        self.assertFalse((self.output / "trace.txt").exists())


if __name__ == "__main__":
    unittest.main()
