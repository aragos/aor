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
    def test_arena_requires_its_frozen_specification(self):
        with self.assertRaises(SystemExit):
            training.parse_options(["--mode", "arena"])

    def test_arena_rejects_settings_outside_its_specification(self):
        for flag in ("--pairs", "--samples", "--seed", "--model", "--rounds"):
            with self.subTest(flag=flag), self.assertRaises(SystemExit):
                training.parse_options(["--mode", "arena", "--source", "/tmp/spec.json", flag, "1"])

    def test_search_specification_is_forwarded_to_the_trainer(self):
        options = training.parse_options([
            "--mode", "strategy-search", "--source", "/tmp/search.json",
            "--output", "/tmp/search-output",
        ])
        self.assertEqual(options.mode, "strategy-search")
        self.assertEqual(options.source, Path("/tmp/search.json").resolve())

    def test_search_requires_its_frozen_specification(self):
        with self.assertRaises(SystemExit):
            training.parse_options(["--mode", "strategy-search"])

    def test_token_value_pilot_forwards_only_its_frozen_specification_and_budget(self):
        options = training.parse_options([
            "--mode", "token-value-pilot", "--source", "/tmp/token-value-spec.json",
            "--output", "/tmp/token-value-output", "--workers", "3",
        ])
        budget = training.resource_budget(training.Hardware(10, 64 * training.GIB), 3)

        arguments = training.trainer_arguments(options, budget, 2.5)

        self.assertEqual(arguments[arguments.index("--mode") + 1], "token-value-pilot")
        self.assertEqual(arguments[arguments.index("--source") + 1],
                         str(Path("/tmp/token-value-spec.json").resolve()))
        self.assertEqual(arguments[arguments.index("--workers") + 1], "3")
        self.assertEqual(arguments[arguments.index("--hours") + 1], "2.5")

    def test_token_value_pilot_requires_its_specification(self):
        with self.assertRaises(SystemExit):
            training.parse_options(["--mode", "token-value-pilot"])

    def test_token_value_pilot_rejects_cli_overrides_of_frozen_settings(self):
        for flag in ("--pairs", "--samples", "--seed", "--model", "--rounds"):
            with self.subTest(flag=flag), self.assertRaises(SystemExit):
                training.parse_options([
                    "--mode", "token-value-pilot", "--source", "/tmp/token-value-spec.json",
                    flag, "1",
                ])

    def test_strategy_replay_limits_the_single_inspection_job(self):
        options = training.parse_options([
            "--mode", "strategy-replay", "--source", "/tmp/spec.json",
            "--output", "/tmp/replay", "--index", "2", "--side", "incumbent",
            "--pair", "/tmp/pair-2.json",
        ])

        self.assertEqual(options.workers, 1)
        self.assertEqual(options.hours, 1.0)
        arguments = training.trainer_arguments(
            options, training.resource_budget(training.Hardware(10, 64 * training.GIB), 1), 1.0)
        self.assertEqual(arguments[arguments.index("--index") + 1], "2")
        self.assertEqual(arguments[arguments.index("--side") + 1], "incumbent")
        self.assertEqual(arguments[arguments.index("--pair") + 1], str(Path("/tmp/pair-2.json").resolve()))

    def test_purchase_collection_preserves_the_specification_and_resource_budget(self):
        options = training.parse_options([
            "--mode", "purchase-collect", "--source", "/tmp/purchase-spec.json",
            "--output", "/tmp/purchase-output", "--workers", "2",
        ])
        budget = training.resource_budget(training.Hardware(10, 64 * training.GIB), 2)

        arguments = training.trainer_arguments(options, budget, 1.5)

        self.assertEqual(arguments, [
            str(training.TRAINER), "--mode", "purchase-collect",
            "--output", str(Path("/tmp/purchase-output").resolve()),
            "--workers", "2", "--hours", "1.5",
            "--source", str(Path("/tmp/purchase-spec.json").resolve()),
        ])

    def test_purchase_collection_requires_its_frozen_specification(self):
        with self.assertRaises(SystemExit):
            training.parse_options(["--mode", "purchase-collect"])

    def test_purchase_collection_rejects_cli_changes_to_frozen_training_settings(self):
        for flag in ("--seed", "--model", "--difficulty", "--rounds", "--samples"):
            with self.subTest(flag=flag), self.assertRaises(SystemExit):
                training.parse_options([
                    "--mode", "purchase-collect", "--source", "/tmp/purchase-spec.json",
                    flag, "1",
                ])

    def test_replay_index_is_rejected_in_other_modes(self):
        with self.assertRaises(SystemExit):
            training.parse_options(["--mode", "pilot", "--index", "1"])

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

    def test_pilot_flags_are_forwarded_with_resource_limits(self):
        options = training.parse_options([
            "--mode", "pilot", "--output", ".aor-training/pilot", "--model", "model.json",
            "--workers", "4", "--seed", "17", "--benchmark-games", "8", "--positions", "64",
            "--samples", "8", "--pairs", "512",
        ])
        budget = training.resource_budget(training.Hardware(10, 64 * training.GIB), options.workers)

        arguments = training.trainer_arguments(options, budget, 3.5)

        for flag, expected in (("--mode", "pilot"), ("--workers", "4"), ("--hours", "3.5"),
                               ("--seed", "17"), ("--benchmark-games", "8"), ("--positions", "64"),
                               ("--samples", "8"), ("--pairs", "512"),
                               ("--model", str(Path("model.json").resolve()))):
            with self.subTest(flag=flag):
                self.assertEqual(arguments[arguments.index(flag) + 1], expected)
        self.assertNotIn("--", arguments)

    def test_unspecified_pilot_counts_leave_defaults_to_the_trainer(self):
        options = training.parse_options(["--mode", "pilot"])
        budget = training.resource_budget(training.Hardware(10, 64 * training.GIB))

        arguments = training.trainer_arguments(options, budget, 4)

        for flag in ("--benchmark-games", "--positions", "--samples", "--pairs"):
            self.assertNotIn(flag, arguments)

    def test_diagnostic_flags_are_forwarded_with_resource_limits(self):
        options = training.parse_options([
            "--mode", "pilot-diagnose", "--source", ".aor-training/pilot-round-2",
            "--output", ".aor-training/diagnostic", "--workers", "4", "--samples", "32",
            "--seed", "20261107",
        ])
        budget = training.resource_budget(training.Hardware(10, 64 * training.GIB), options.workers)

        arguments = training.trainer_arguments(options, budget, 1.5)

        for flag, expected in (
                ("--mode", "pilot-diagnose"), ("--workers", "4"), ("--hours", "1.5"),
                ("--samples", "32"), ("--seed", "20261107"),
                ("--source", str(Path(".aor-training/pilot-round-2").resolve()))):
            with self.subTest(flag=flag):
                self.assertEqual(arguments[arguments.index(flag) + 1], expected)
        self.assertNotIn("--model", arguments)

    def test_unspecified_diagnostic_counts_leave_defaults_to_the_trainer(self):
        options = training.parse_options([
            "--mode", "pilot-diagnose", "--source", ".aor-training/pilot-round-2",
        ])
        budget = training.resource_budget(training.Hardware(10, 64 * training.GIB))

        arguments = training.trainer_arguments(options, budget, 2)

        self.assertNotIn("--samples", arguments)
        self.assertNotIn("--seed", arguments)

    def test_diagnostic_requires_a_source_pilot(self):
        with patch("sys.stderr", new_callable=io.StringIO):
            with self.assertRaises(SystemExit):
                training.parse_options(["--mode", "pilot-diagnose"])

    def test_diagnostic_rejects_options_that_would_change_the_source_experiment(self):
        for flag, value in (
                ("--model", "model.json"), ("--benchmark-games", "1"), ("--positions", "2"),
                ("--pairs", "1"), ("--configurations", "1"), ("--seeds", "1"),
                ("--rounds", "1"), ("--batch", "1"), ("--difficulty", "HARD")):
            with self.subTest(flag=flag), patch("sys.stderr", new_callable=io.StringIO):
                with self.assertRaises(SystemExit):
                    training.parse_options([
                        "--mode", "pilot-diagnose", "--source", ".aor-training/pilot-round-2",
                        flag, value,
                    ])

    def test_source_is_rejected_outside_diagnostic_mode(self):
        for mode in ("coverage", "train", "evaluate", "pilot"):
            with self.subTest(mode=mode), patch("sys.stderr", new_callable=io.StringIO):
                with self.assertRaises(SystemExit):
                    training.parse_options(["--mode", mode, "--source", ".aor-training/pilot"])

    def test_diagnostic_output_cannot_overlap_its_source(self):
        for source, output in (("pilot", "pilot"), ("pilot", "pilot/diagnostic"),
                               ("diagnostic/pilot", "diagnostic")):
            with self.subTest(source=source, output=output), \
                    patch("sys.stderr", new_callable=io.StringIO):
                with self.assertRaises(SystemExit):
                    training.parse_options([
                        "--mode", "pilot-diagnose", "--source", source, "--output", output,
                    ])

    def test_pilot_counts_must_stay_within_their_supported_bounds(self):
        for flag, minimum, maximum in (("--benchmark-games", 1, 10000), ("--positions", 2, 10000),
                                       ("--samples", 1, 256), ("--pairs", 1, 100000)):
            for value in ("0", "-1", str(minimum - 1), str(maximum + 1)):
                with self.subTest(flag=flag, value=value), \
                        patch("sys.stderr", new_callable=io.StringIO):
                    with self.assertRaises(SystemExit):
                        training.parse_options(["--mode", "pilot", flag, value])

    def test_pilot_options_are_rejected_in_legacy_modes(self):
        for mode in ("coverage", "train", "evaluate"):
            for flag in ("--benchmark-games", "--positions", "--samples", "--pairs"):
                with self.subTest(mode=mode, flag=flag), \
                        patch("sys.stderr", new_callable=io.StringIO):
                    with self.assertRaises(SystemExit):
                        training.parse_options(["--mode", mode, flag, "2"])

    def test_minimal_pilot_accepts_separate_training_and_validation_positions(self):
        options = training.parse_options(["--mode", "pilot", "--positions", "2"])

        self.assertEqual(options.positions, 2)

    def test_legacy_tuning_options_are_rejected_in_pilot_mode(self):
        for flag, value in (("--configurations", "1"), ("--seeds", "1"), ("--rounds", "1"),
                            ("--batch", "1"), ("--difficulty", "HARD")):
            with self.subTest(flag=flag), patch("sys.stderr", new_callable=io.StringIO):
                with self.assertRaises(SystemExit):
                    training.parse_options(["--mode", "pilot", flag, value])

    def test_default_coverage_invocation_has_no_pilot_flags(self):
        options = training.parse_options([])
        budget = training.resource_budget(training.Hardware(10, 64 * training.GIB))

        arguments = training.trainer_arguments(options, budget, 12)

        self.assertEqual(arguments, [str(training.TRAINER), "--mode", "coverage", "--output",
                                    str(options.output), "--workers", "6", "--hours", "12"])

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

    def test_pilot_resume_preserves_native_checkpoint_without_selecting_another_model(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            (output / "state.json").write_text("{}")
            options = training.parse_options(["--mode", "pilot", "--output", directory, "--resume"])
            budget = training.resource_budget(training.Hardware(10, 64 * training.GIB))

            arguments = training.trainer_arguments(options, budget, 4)

            self.assertNotIn("--model", arguments)
            self.assertNotIn("--resume", arguments)
            self.assertEqual(arguments[arguments.index("--mode") + 1], "pilot")

    def test_diagnostic_resume_requires_its_own_checkpoint_and_retains_source(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "diagnostic"
            output.mkdir()
            (output / "state.json").write_text("{}")
            source = Path(directory) / "pilot"
            options = training.parse_options([
                "--mode", "pilot-diagnose", "--source", str(source),
                "--output", str(output), "--resume",
            ])
            budget = training.resource_budget(training.Hardware(10, 64 * training.GIB))

            arguments = training.trainer_arguments(options, budget, 2)

            self.assertEqual(arguments[arguments.index("--source") + 1], str(source.resolve()))
            self.assertNotIn("--resume", arguments)
            self.assertNotIn("--model", arguments)

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
