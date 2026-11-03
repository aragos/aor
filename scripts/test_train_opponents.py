"""Tests for the standalone offline trainer's measured resource supervision."""

from contextlib import redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import signal
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch


spec = importlib.util.spec_from_file_location(
    "train_opponents", Path(__file__).with_name("train-opponents.py"))
training = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = training
spec.loader.exec_module(training)


class OptionsAndBudgetTest(unittest.TestCase):
    def test_worker_heap_and_cpu_limits_are_derived_from_actual_hardware(self):
        budget = training.resource_budget(training.Hardware(10, 64 * training.GIB))

        self.assertEqual(budget.workers, 6)
        self.assertEqual(budget.cpu_cores, 8.0)
        self.assertEqual(budget.active_processors, 8)
        self.assertEqual(budget.heap_bytes, 24 * training.GIB)
        self.assertLessEqual(budget.memory_bytes, 64 * training.GIB * 0.8)

    def test_small_machine_keeps_one_worker_but_duty_cycle_still_caps_cpu(self):
        budget = training.resource_budget(training.Hardware(1, training.GIB))

        self.assertEqual(budget.workers, 1)
        self.assertEqual(budget.cpu_cores, 0.8)
        self.assertLessEqual(budget.heap_bytes, training.GIB * 0.4)

    def test_requested_workers_cannot_exceed_reserved_thread_budget(self):
        with self.assertRaisesRegex(ValueError, "between 1 and 6"):
            training.resource_budget(training.Hardware(10, 64 * training.GIB), 7)

    def test_nonfinite_or_excessive_elapsed_budgets_are_rejected(self):
        for value in ("nan", "inf", "0", "-1", "73"):
            with self.subTest(value=value), redirect_stdout(io.StringIO()), \
                    patch("sys.stderr", new_callable=io.StringIO):
                with self.assertRaises(SystemExit):
                    training.parse_options(["--hours", value])

    def test_direct_trainer_flags_are_forwarded_without_a_separator(self):
        options = training.parse_options([
            "--mode", "train", "--output", ".aor-training/example", "--hours", "48",
            "--workers", "4", "--rounds", "96", "--configurations", "128", "--seed", "7",
        ])
        budget = training.resource_budget(training.Hardware(10, 64 * training.GIB), options.workers)

        arguments = training.trainer_arguments(options, budget, 47.5)

        self.assertEqual(arguments[arguments.index("--workers") + 1], "4")
        self.assertEqual(arguments[arguments.index("--hours") + 1], "47.5")
        self.assertEqual(arguments[arguments.index("--rounds") + 1], "96")
        self.assertNotIn("--", arguments)

    def test_native_resume_does_not_replace_the_original_training_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            (output / "state.json").write_text("{}")
            (output / "checkpoint.json").write_text("{}")
            options = training.parse_options(["--mode", "train", "--output", directory, "--resume"])
            budget = training.resource_budget(training.Hardware(10, 64 * training.GIB))

            arguments = training.trainer_arguments(options, budget, 12)

            self.assertNotIn("--model", arguments)
            self.assertNotIn("--resume", arguments)

    def test_missing_native_resume_state_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            options = training.parse_options(["--output", directory, "--resume"])
            budget = training.resource_budget(training.Hardware(10, 64 * training.GIB))

            with self.assertRaisesRegex(ValueError, "state.json"):
                training.trainer_arguments(options, budget, 12)

    def test_inherited_jvm_options_cannot_override_the_supervisor_budget(self):
        hardware = training.Hardware(10, 64 * training.GIB)
        budget = training.resource_budget(hardware)
        with patch.dict(training.os.environ, {
                "JAVA_TOOL_OPTIONS": "-Xmx999g", "_JAVA_OPTIONS": "-Xmx999g",
                "JDK_JAVA_OPTIONS": "-Xmx999g", "TRAINING_OPTS": "-Xmx999g"}):
            environment = training.trainer_environment(hardware, budget)

        self.assertNotIn("JAVA_TOOL_OPTIONS", environment)
        self.assertNotIn("_JAVA_OPTIONS", environment)
        self.assertNotIn("JDK_JAVA_OPTIONS", environment)
        self.assertEqual(environment["TRAINING_OPTS"], "")
        self.assertIn("-Xmx24576m", environment["JAVA_OPTS"])
        self.assertEqual(environment["AOR_TRAINING_MACHINE_CPUS"], "10")


class DeadlineAndStatusTest(unittest.TestCase):
    def test_resume_never_extends_an_earlier_allowance(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            now = 1_800_000_000.0
            started = now - 24 * 3600
            (output / "started-at.txt").write_text(training.timestamp(started))
            training.write_metadata(output, {
                "startedAt": training.timestamp(started + 100),
                "allowanceDeadline": training.timestamp(now + 6 * 3600),
            })

            actual_start, allowance, deadline = training.deadlines(output, 48, now)

            self.assertEqual(actual_start, started)
            self.assertEqual(allowance, now + 6 * 3600)
            self.assertEqual(deadline, allowance)

    def test_requested_invocation_budget_can_be_shorter_than_the_total_allowance(self):
        with tempfile.TemporaryDirectory() as directory:
            started, allowance, deadline = training.deadlines(Path(directory), 2, 1000)

            self.assertEqual(started, 1000)
            self.assertEqual(allowance, 1000 + 72 * 3600)
            self.assertEqual(deadline, 1000 + 2 * 3600)

    def test_expired_original_allowance_cannot_be_reset(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            (output / "started-at.txt").write_text(training.timestamp(1000))

            with self.assertRaisesRegex(ValueError, "exhausted"):
                training.deadlines(output, 72, 1000 + 73 * 3600)

    def test_output_lock_prevents_overlapping_runs_in_one_checkpoint_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with training.lock_output(output):
                with self.assertRaisesRegex(RuntimeError, "already owns"):
                    training.lock_output(output)
            with training.lock_output(output):
                pass

    def test_status_uses_held_lock_and_never_signals_a_recorded_pid(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            training.write_metadata(output, {"status": "running", "supervisorPid": 999999})
            with training.lock_output(output), redirect_stdout(io.StringIO()) as stream, \
                    patch.object(training.os, "killpg") as kill:
                training.status(output)

            self.assertTrue(json.loads(stream.getvalue())["supervisorActive"])
            kill.assert_not_called()


class ProcessMeasurementTest(unittest.TestCase):
    def test_linux_samples_count_all_threads_cpu_and_resident_pages(self):
        with tempfile.TemporaryDirectory() as directory:
            process_path = Path(directory) / "10"
            process_path.mkdir()
            fields = ["0"] * 22
            for index, value in ((0, "R"), (2, "10"), (11, "200"), (12, "50"),
                                 (19, "123456"), (21, "1000")):
                fields[index] = value
            (process_path / "stat").write_text("10 (a (strange) command) " + " ".join(fields))
            with patch.object(training.sys, "platform", "linux"), \
                    patch.object(training.Path, "iterdir", return_value=[process_path]), \
                    patch.object(training.os, "sysconf", side_effect=lambda name: {
                        "SC_CLK_TCK": 100, "SC_PAGE_SIZE": 4096}[name]):
                samples = training.sample_group(10)

        self.assertEqual(samples, [training.ProcessSample(10, "10:123456", 2.5, 4096000, "R")])

    def test_cpu_times_accept_mac_linux_and_long_running_process_formats(self):
        for value, expected in (("0:02.54", 2.54), ("01:02:03", 3723),
                                ("2-03:04:05.50", 183845.5)):
            with self.subTest(value=value):
                self.assertEqual(training.parse_cpu_time(value), expected)

    def test_mac_samples_include_only_owned_group_and_track_process_start_identity(self):
        rows = ("10 10 0:02.50 1024 S Tue Nov 3 12:00:00 2026\n"
                "11 10 0:01.20 2048 R Tue Nov 3 12:00:01 2026\n"
                "99 99 1:00.00 9999 R Tue Nov 3 12:00:00 2026\n")

        samples = training.parse_ps_group(rows, 10)

        self.assertEqual([sample.pid for sample in samples], [10, 11])
        self.assertAlmostEqual(sum(sample.cpu_seconds for sample in samples), 3.7)
        self.assertEqual(sum(sample.rss_bytes for sample in samples), 3072 * 1024)
        self.assertEqual(samples[0].identity, "10:Tue Nov 3 12:00:00 2026")

    def test_aggregate_cpu_above_eighty_percent_creates_measured_stop_debt(self):
        controller = training.CpuController(8, 0)

        delay = controller.observe(0.25, [sample(cpu=2.5)])
        after_pause = controller.observe(0.3125, [sample(cpu=2.5)])
        next_delay = controller.observe(0.5625, [sample(cpu=5.0)])

        self.assertAlmostEqual(delay, 0.0625)
        self.assertEqual(after_pause, 0)
        self.assertAlmostEqual(next_delay, 0.0625)

    def test_idle_time_cannot_accumulate_unbounded_burst_credit(self):
        controller = training.CpuController(8, 0)
        controller.observe(100, [sample(cpu=0)])

        delay = controller.observe(100.25, [sample(cpu=3)])

        self.assertAlmostEqual(delay, 0.125)

    def test_exited_children_keep_their_observed_cpu_cost(self):
        controller = training.CpuController(8, 0)
        controller.observe(1, [sample(cpu=1), sample(pid=11, cpu=2)])

        controller.observe(2, [sample(cpu=1.5)])

        self.assertEqual(controller.previous_total, 3.5)

    def test_reused_child_pid_with_new_start_time_has_separate_cpu_accounting(self):
        controller = training.CpuController(8, 0)
        controller.observe(1, [sample(pid=11, cpu=2)])

        controller.observe(2, [training.ProcessSample(11, "11:new-start", 0.5, 1024, "R")])

        self.assertEqual(controller.previous_total, 2.5)


def sample(pid=10, cpu=0, rss=1024, state="R"):
    return training.ProcessSample(pid, f"{pid}:start", cpu, rss, state)


class OwnedGroupTest(unittest.TestCase):
    def test_signals_target_only_the_live_owned_group_and_stop_after_reaping(self):
        process = Mock(pid=10)
        process.wait.return_value = 0
        owned = training.OwnedGroup(process)
        with patch.object(training.os, "getpgid", return_value=10), \
                patch.object(training.os, "killpg") as kill:
            owned.stop()
            owned.stop()
            owned.resume()
            owned.reap()
            owned.stop()

        self.assertEqual([call.args for call in kill.call_args_list],
                         [(10, signal.SIGSTOP), (10, signal.SIGCONT)])
        process.poll.assert_not_called()
        process.wait.assert_called_once()

    def test_lost_group_identity_cannot_signal_an_unrelated_process(self):
        owned = training.OwnedGroup(Mock(pid=10))
        with patch.object(training.os, "getpgid", return_value=99), \
                patch.object(training.os, "killpg") as kill:
            with self.assertRaisesRegex(RuntimeError, "original process group"):
                owned.stop()

        kill.assert_not_called()


class SupervisionTest(unittest.TestCase):
    def run_supervisor(self, samples=None, stop_signal=0, alarm=False):
        owned = Mock(process=Mock(pid=10), stopped=False)
        owned.reap.return_value = 0
        metadata = {}
        budget = training.resource_budget(training.Hardware(1, training.GIB))
        handlers = {}

        def register(number, handler):
            handlers[number] = handler

        def observe(_group):
            if alarm:
                handlers[signal.SIGALRM](signal.SIGALRM, None)
            if isinstance(samples, Exception):
                raise samples
            return samples

        with patch.object(training.time, "monotonic", return_value=1.0), \
                patch.object(training.time, "time", return_value=1000.0), \
                patch.object(training, "sample_group", side_effect=observe), \
                patch.object(training, "write_metadata"), \
                patch.object(training.signal, "signal", side_effect=register), \
                patch.object(training.signal, "setitimer"):
            result = training.supervise(owned, budget, 2000, metadata, Path("unused"), [stop_signal])
        return owned, metadata, result

    def test_memory_limit_kills_only_owned_job_before_more_work(self):
        owned, metadata, result = self.run_supervisor(samples=[sample(rss=training.GIB)])

        self.assertEqual(result, 137)
        self.assertEqual(metadata["status"], "memory-limit")
        owned.terminate.assert_called_once_with(immediate=True)

    def test_normal_completion_reaps_without_sending_stop_to_an_exited_pid(self):
        owned, metadata, result = self.run_supervisor(samples=[sample(state="Z")])

        self.assertEqual(result, 0)
        self.assertEqual(metadata["status"], "completed")
        owned.stop.assert_not_called()
        owned.terminate.assert_not_called()

    def test_interrupt_requests_graceful_owned_group_cleanup(self):
        owned, metadata, result = self.run_supervisor(stop_signal=signal.SIGTERM)

        self.assertEqual(result, 128 + signal.SIGTERM)
        self.assertEqual(metadata["status"], "interrupted")
        owned.terminate.assert_called_once_with()

    def test_hard_deadline_kills_even_when_process_sampling_is_in_progress(self):
        owned, metadata, result = self.run_supervisor(samples=[], alarm=True)

        self.assertEqual(result, 124)
        self.assertEqual(metadata["status"], "deadline")
        owned.send.assert_called_once_with(signal.SIGKILL)
        owned.terminate.assert_called_once_with(immediate=True)

    def test_telemetry_failure_fails_closed_instead_of_running_unbounded(self):
        with self.assertRaisesRegex(RuntimeError, "telemetry unavailable"):
            self.run_supervisor(samples=RuntimeError("telemetry unavailable"))


if __name__ == "__main__":
    unittest.main()
