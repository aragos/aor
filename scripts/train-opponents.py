#!/usr/bin/env python3
"""Run the offline trainer with measured CPU, resident-memory and elapsed-time limits.

Works on macOS and Linux using only Python's standard library. Run installDist first;
the trainer never connects to the application server. Logs, checkpoints and status stay
in --output. SIGINT/SIGTERM stop only the new process group created by this supervisor.
"""

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import fcntl
import json
import math
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time


GIB = 1024 ** 3
MIB = 1024 ** 2
RESOURCE_FRACTION = 0.8
MAX_HOURS = 72.0
SAMPLE_SECONDS = 0.25
STATUS_SECONDS = 5.0
REPOSITORY = Path(__file__).resolve().parent.parent
PACKAGED_TRAINER = Path(__file__).resolve().with_name("training")
TRAINER = (PACKAGED_TRAINER if PACKAGED_TRAINER.is_file()
           else REPOSITORY / "training/build/install/training/bin/training")


@dataclass(frozen=True)
class Hardware:
    cpus: int
    memory_bytes: int


@dataclass(frozen=True)
class Budget:
    cpu_cores: float
    memory_bytes: int
    heap_bytes: int
    workers: int
    active_processors: int


@dataclass(frozen=True)
class ProcessSample:
    pid: int
    identity: str
    cpu_seconds: float
    rss_bytes: int
    state: str


def parse_options(arguments):
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=("Examples:\n"
                "  %(prog)s --mode train --output .aor-training/run --hours 48\n"
                "  %(prog)s --mode train --output .aor-training/run --resume\n"
                "  %(prog)s --output .aor-training/run --status\n"
                "Background: nohup python3 %(prog)s --mode train --hours 48 "
                ">.aor-training-supervisor.log 2>&1 &\n"
                "Follow trainer output with tail -f OUTPUT/trainer.log. "
                "Send SIGTERM to the supervisor PID for graceful shutdown."),
    )
    parser.add_argument("--mode", choices=("coverage", "train", "evaluate"), default="coverage")
    parser.add_argument("--output", type=Path, default=Path(".aor-training/run"))
    parser.add_argument("--hours", type=float, default=12.0)
    parser.add_argument("--workers", type=int)
    parser.add_argument("--model", type=Path)
    parser.add_argument("--configurations", type=int)
    parser.add_argument("--seeds", type=int)
    parser.add_argument("--seed", type=int)
    parser.add_argument("--rounds", type=int)
    parser.add_argument("--batch", type=int)
    parser.add_argument("--difficulty", choices=("EASY", "NORMAL", "HARD"))
    parser.add_argument("--resume", action="store_true", help="Require native resumable state.json.")
    parser.add_argument("--status", action="store_true", help="Show durable status without launching.")
    options = parser.parse_args(arguments)
    if not math.isfinite(options.hours) or not 0 < options.hours <= MAX_HOURS:
        parser.error("--hours must be finite, positive and at most 72.")
    for name, minimum, maximum in (
            ("configurations", 1, 128), ("seeds", 1, 1000), ("seed", 0, 100_000_000),
            ("rounds", 0, 100000), ("batch", 1, 128)):
        value = getattr(options, name)
        if value is not None and not minimum <= value <= maximum:
            parser.error(f"--{name} must be between {minimum} and {maximum}.")
    if options.workers is not None and options.workers < 1:
        parser.error("--workers must be positive.")
    options.output = options.output.expanduser().resolve()
    if options.model is not None:
        options.model = options.model.expanduser().resolve()
    return options


def detect_hardware():
    cpus = os.cpu_count()
    if cpus is None or cpus < 1:
        raise RuntimeError("Cannot determine this machine's logical CPU count.")
    if sys.platform == "darwin":
        memory_bytes = int(subprocess.check_output(
            ["sysctl", "-n", "hw.memsize"], text=True, timeout=5).strip())
    elif sys.platform.startswith("linux"):
        memory_bytes = os.sysconf("SC_PHYS_PAGES") * os.sysconf("SC_PAGE_SIZE")
        # A container's memory allowance may be smaller than the physical machine.
        for path in (Path("/sys/fs/cgroup/memory.max"),
                     Path("/sys/fs/cgroup/memory/memory.limit_in_bytes")):
            try:
                value = path.read_text().strip()
                if value.isdigit() and int(value) > 0:
                    memory_bytes = min(memory_bytes, int(value))
            except OSError:
                pass
    else:
        raise RuntimeError("This training supervisor supports macOS and Linux.")
    if memory_bytes < 160 * MIB:
        raise RuntimeError("At least 160 MiB of detected memory is required for the trainer.")
    return Hardware(cpus, memory_bytes)


def resource_budget(hardware, requested_workers=None):
    maximum_workers = max(1, math.floor(hardware.cpus * RESOURCE_FRACTION) - 2)
    workers = maximum_workers if requested_workers is None else requested_workers
    if not 1 <= workers <= maximum_workers:
        raise ValueError(f"Use between 1 and {maximum_workers} training workers on this machine.")
    return Budget(
        cpu_cores=hardware.cpus * RESOURCE_FRACTION,
        memory_bytes=math.floor(hardware.memory_bytes * RESOURCE_FRACTION),
        heap_bytes=min(math.floor(hardware.memory_bytes * 0.4 / MIB) * MIB, 24 * GIB),
        workers=workers,
        active_processors=max(1, math.floor(hardware.cpus * RESOURCE_FRACTION)),
    )


def timestamp(value):
    return datetime.fromtimestamp(value, timezone.utc).isoformat().replace("+00:00", "Z")


def parse_timestamp(value):
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("Training timestamps must include their time zone.")
    return parsed.timestamp()


def read_metadata(output):
    path = output / "supervisor.json"
    return json.loads(path.read_text()) if path.exists() else {}


def deadlines(output, hours, now):
    """Retain the earliest 72-hour allowance, including a preceding direct JVM run."""
    previous = read_metadata(output)
    starts = [now]
    if previous.get("startedAt"):
        starts.append(parse_timestamp(previous["startedAt"]))
    started_path = output / "started-at.txt"
    if started_path.exists():
        starts.append(parse_timestamp(started_path.read_text().strip()))
    started = min(starts)
    allowance = started + MAX_HOURS * 3600
    if previous.get("allowanceDeadline"):
        allowance = min(allowance, parse_timestamp(previous["allowanceDeadline"]))
    deadline = min(allowance, now + hours * 3600)
    if deadline <= now:
        raise ValueError("This output directory has exhausted its original 72-hour allowance.")
    return started, allowance, deadline


def write_metadata(output, metadata):
    descriptor, temporary = tempfile.mkstemp(prefix=".supervisor-", suffix=".json", dir=output)
    try:
        with os.fdopen(descriptor, "w") as stream:
            json.dump(metadata, stream, indent=2, sort_keys=True)
            stream.write("\n")
        os.replace(temporary, output / "supervisor.json")
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def lock_output(output):
    stream = (output / ".supervisor.lock").open("a+")
    try:
        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        stream.close()
        raise RuntimeError("A training supervisor already owns this output directory.")
    return stream


def status(output):
    metadata = read_metadata(output)
    if not metadata:
        raise ValueError(f"No training supervisor status exists in {output}.")
    try:
        with lock_output(output):
            metadata["supervisorActive"] = False
    except RuntimeError:
        metadata["supervisorActive"] = True
    print(json.dumps(metadata, indent=2, sort_keys=True))


def trainer_arguments(options, budget, remaining_hours):
    model = options.model
    if options.resume and not (options.output / "state.json").is_file():
        raise ValueError("--resume requires the trainer's state.json in --output.")
    arguments = [str(TRAINER), "--mode", options.mode, "--output", str(options.output),
                 "--workers", str(budget.workers), "--hours", str(remaining_hours)]
    if model is not None:
        arguments += ["--model", str(model)]
    for name in ("configurations", "seeds", "seed", "rounds", "batch", "difficulty"):
        value = getattr(options, name)
        if value is not None:
            arguments += ["--" + name, str(value)]
    return arguments


def trainer_environment(hardware, budget):
    environment = dict(os.environ)
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        environment.pop(name, None)
    environment["AOR_TRAINING_MACHINE_CPUS"] = str(hardware.cpus)
    environment["TRAINING_OPTS"] = ""
    environment["JAVA_OPTS"] = " ".join((
        "-Xms64m", f"-Xmx{budget.heap_bytes // MIB}m",
        f"-XX:MaxDirectMemorySize={min(budget.heap_bytes // 8, GIB) // MIB}m",
        f"-XX:ActiveProcessorCount={budget.active_processors}",
        f"-XX:ParallelGCThreads={budget.active_processors}",
        f"-XX:ConcGCThreads={max(1, budget.active_processors // 2)}",
        "-XX:+ExitOnOutOfMemoryError",
    ))
    return environment


def parse_cpu_time(value):
    days, clock = value.split("-", 1) if "-" in value else ("0", value)
    fields = clock.split(":")
    if not 1 <= len(fields) <= 3:
        raise ValueError(f"Unrecognized process CPU time: {value}")
    seconds = int(days) * 86400 + sum(float(field) * 60 ** index
                                     for index, field in enumerate(reversed(fields)))
    if not math.isfinite(seconds) or seconds < 0:
        raise ValueError("Process CPU time must be finite and nonnegative.")
    return seconds


def parse_ps_group(contents, group):
    samples = []
    for line in contents.splitlines():
        fields = line.split(maxsplit=5)
        if len(fields) < 6:
            raise RuntimeError("Incomplete process telemetry from ps.")
        pid, process_group = int(fields[0]), int(fields[1])
        if process_group == group:
            samples.append(ProcessSample(pid, f"{pid}:{fields[5]}", parse_cpu_time(fields[2]),
                                         int(fields[3]) * 1024, fields[4]))
    return samples


def sample_group(group):
    if sys.platform.startswith("linux"):
        samples = []
        ticks, page_bytes = os.sysconf("SC_CLK_TCK"), os.sysconf("SC_PAGE_SIZE")
        for path in Path("/proc").iterdir():
            if not path.name.isdigit():
                continue
            try:
                raw = (path / "stat").read_text()
                fields = raw[raw.rfind(")") + 2:].split()
                if int(fields[2]) != group:
                    continue
                pid = int(path.name)
                samples.append(ProcessSample(
                    pid, f"{pid}:{fields[19]}", (int(fields[11]) + int(fields[12])) / ticks,
                    int(fields[21]) * page_bytes, fields[0],
                ))
            except (FileNotFoundError, ProcessLookupError, PermissionError):
                continue
        return samples
    result = subprocess.run(
        ["ps", "-A", "-o", "pid=,pgid=,time=,rss=,stat=,lstart="],
        check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5,
    )
    return parse_ps_group(result.stdout, group)


class CpuController:
    """Token debt converts aggregate process CPU seconds to a measured group duty cycle."""

    def __init__(self, cores, started):
        self.cores = cores
        self.previous_at = started
        self.previous_total = 0.0
        self.balance = 0.0
        self.seen = {}

    def observe(self, now, samples):
        for process in samples:
            self.seen[process.identity] = max(self.seen.get(process.identity, 0.0),
                                              process.cpu_seconds)
        total = sum(self.seen.values())
        elapsed = max(0.0, now - self.previous_at)
        self.balance = min(self.cores * SAMPLE_SECONDS, self.balance + self.cores * elapsed)
        self.balance -= total - self.previous_total
        self.previous_at, self.previous_total = now, total
        return max(0.0, -self.balance / self.cores)


class OwnedGroup:
    """Never reap the leader until all signals finish, so its PID cannot be recycled.

    A zombie leader still reserves its process-group identifier. Do not call Popen.poll
    or wait elsewhere: after reap(), no method may send another group signal.
    """

    def __init__(self, process):
        self.process = process
        self.reaped = False
        self.stopped = False

    def send(self, number):
        if self.reaped:
            return False
        try:
            if os.getpgid(self.process.pid) != self.process.pid:
                raise RuntimeError("The training child no longer owns its original process group.")
            os.killpg(self.process.pid, number)
        except ProcessLookupError:
            return False
        return True

    def stop(self):
        if not self.stopped:
            self.stopped = self.send(signal.SIGSTOP)

    def resume(self):
        if self.stopped:
            self.send(signal.SIGCONT)
            self.stopped = False

    def reap(self):
        self.reaped = True
        return self.process.wait(timeout=10)

    def terminate(self, immediate=False):
        if self.reaped:
            return self.process.returncode
        if immediate:
            self.send(signal.SIGKILL)
        else:
            self.resume()
            self.send(signal.SIGTERM)
            until = time.monotonic() + 5.0
            while time.monotonic() < until:
                try:
                    if not any(not row.state.startswith("Z") for row in sample_group(self.process.pid)):
                        break
                except (OSError, RuntimeError, subprocess.SubprocessError):
                    break
                time.sleep(0.1)
            # The unreaped leader still reserves this exact group ID, including after exit.
            self.send(signal.SIGKILL)
        return self.reap()


class DeadlineReached(Exception):
    pass


def supervise(owned, budget, deadline, metadata, output, stop_requested):
    process = owned.process
    started = time.monotonic()
    monotonic_deadline = started + max(0, deadline - time.time())
    controller = CpuController(budget.cpu_cores, started)
    last_status = 0.0
    previous_alarm = signal.getsignal(signal.SIGALRM)

    def hard_deadline(_number, _frame):
        owned.send(signal.SIGKILL)
        raise DeadlineReached("The training deadline has elapsed.")

    signal.signal(signal.SIGALRM, hard_deadline)
    signal.setitimer(signal.ITIMER_REAL, max(0.000001, deadline - time.time()))
    try:
        while True:
            now = time.monotonic()
            if now >= monotonic_deadline or time.time() >= deadline:
                metadata["status"] = "deadline"
                owned.terminate(immediate=True)
                return 124
            if stop_requested[0]:
                metadata["status"] = "interrupted"
                owned.terminate()
                return 128 + stop_requested[0]
            samples = sample_group(process.pid)
            if not any(row.pid == process.pid for row in samples):
                raise RuntimeError("Cannot observe the owned training process-group leader.")
            delay = controller.observe(now, samples)
            rss = sum(row.rss_bytes for row in samples)
            metadata.update(
                observedCpuSeconds=controller.previous_total,
                observedRssBytes=rss,
                peakRssBytes=max(metadata.get("peakRssBytes", 0), rss),
                elapsedSeconds=now - started,
                updatedAt=timestamp(time.time()),
            )
            if rss >= budget.memory_bytes:
                metadata["status"] = "memory-limit"
                owned.terminate(immediate=True)
                return 137
            if not any(not row.state.startswith("Z") for row in samples):
                code = owned.reap()
                metadata["status"] = "completed" if code == 0 else "failed"
                return code if code >= 0 else 128 - code
            if delay > 0:
                owned.stop()
            else:
                owned.resume()
            metadata["pausedForCpu"] = owned.stopped
            metadata["status"] = "running"
            if now - last_status >= STATUS_SECONDS:
                write_metadata(output, metadata)
                last_status = now
            time.sleep(min(SAMPLE_SECONDS, delay if delay > 0 else SAMPLE_SECONDS,
                           max(0, monotonic_deadline - time.monotonic())))
    except DeadlineReached:
        metadata["status"] = "deadline"
        owned.terminate(immediate=True)
        return 124
    except BaseException:
        metadata["status"] = "monitor-error"
        owned.terminate(immediate=True)
        raise
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous_alarm)
        metadata["updatedAt"] = timestamp(time.time())
        metadata["pausedForCpu"] = False
        write_metadata(output, metadata)


def main(arguments=None):
    options = parse_options(sys.argv[1:] if arguments is None else arguments)
    if options.status:
        status(options.output)
        return 0
    if not TRAINER.is_file() or not os.access(TRAINER, os.X_OK):
        raise RuntimeError("Build the standalone trainer first: ./gradlew :training:installDist")
    hardware = detect_hardware()
    budget = resource_budget(hardware, options.workers)
    options.output.mkdir(parents=True, exist_ok=True)
    with lock_output(options.output):
        now = time.time()
        started, allowance, deadline = deadlines(options.output, options.hours, now)
        command = trainer_arguments(options, budget, (deadline - now) / 3600)
        metadata = dict(
            startedAt=timestamp(started), allowanceDeadline=timestamp(allowance),
            deadline=timestamp(deadline), updatedAt=timestamp(now), status="starting",
            supervisorPid=os.getpid(), mode=options.mode, machineCpus=hardware.cpus,
            machineMemoryBytes=hardware.memory_bytes, cpuLimitCores=budget.cpu_cores,
            rssLimitBytes=budget.memory_bytes, heapBytes=budget.heap_bytes,
            workers=budget.workers, sampleSeconds=SAMPLE_SECONDS,
            log=str(options.output / "trainer.log"),
        )
        write_metadata(options.output, metadata)
        print(f"Offline training: {budget.workers} workers; "
              f"CPU ≤{budget.cpu_cores:g}/{hardware.cpus} logical cores (sampled duty cycle); "
              f"RSS stop at {budget.memory_bytes / GIB:.2f} GiB; "
              f"heap {budget.heap_bytes / GIB:.2f} GiB; deadline {timestamp(deadline)}.", flush=True)
        print(f"Status: {options.output / 'supervisor.json'}; log: {metadata['log']}", flush=True)
        stop_requested = [0]
        previous_handlers = {}
        for number in (signal.SIGINT, signal.SIGTERM):
            previous_handlers[number] = signal.getsignal(number)
            signal.signal(number, lambda received, _frame: stop_requested.__setitem__(0, received))
        # Explicitly retain exited children until OwnedGroup has finished all group signals.
        previous_handlers[signal.SIGCHLD] = signal.getsignal(signal.SIGCHLD)
        signal.signal(signal.SIGCHLD, signal.SIG_DFL)
        try:
            with (options.output / "trainer.log").open("ab", buffering=0) as log:
                log.write(f"\nSupervisor launch {timestamp(now)} mode={options.mode}\n".encode())
                process = subprocess.Popen(
                    command, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                    env=trainer_environment(hardware, budget), start_new_session=True,
                )
                owned = OwnedGroup(process)
                try:
                    metadata["trainerPid"] = process.pid
                    metadata["processGroup"] = process.pid
                    write_metadata(options.output, metadata)
                    result = supervise(owned, budget, deadline, metadata, options.output, stop_requested)
                    metadata["exitCode"] = result
                    metadata["finishedAt"] = timestamp(time.time())
                    write_metadata(options.output, metadata)
                    print(f"Training {metadata['status']} (exit {result}).", flush=True)
                    return result
                finally:
                    if not owned.reaped:
                        owned.terminate(immediate=True)
        except BaseException:
            if metadata["status"] == "starting":
                metadata["status"] = "launch-error"
                metadata["updatedAt"] = timestamp(time.time())
                write_metadata(options.output, metadata)
            raise
        finally:
            for number, handler in previous_handlers.items():
                signal.signal(number, handler)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"Training supervisor: {error}", file=sys.stderr)
        sys.exit(1)
