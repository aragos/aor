#!/usr/bin/env python3
"""Run a frozen, local campaign DAG without an LLM or shell.

Commands run from the specification directory. Explicit inputs resolve there too; pin
all code, runtime distributions, models and source data used by commands. Output paths
must stay inside the output directory. Limits are sampled, so transient overshoot is
possible. Commands must keep their descendants in the owned process group. SIGINT and
SIGTERM clean that group; SIGKILL and machine failure cannot guarantee cleanup.

Limits and stages may declare absoluteDeadlineEpoch as finite Unix seconds. This
additional ceiling only shortens duration-based deadlines; a past ceiling prevents
new work. Omission preserves the original duration and resume behavior.
"""

import argparse
import fcntl
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time


HELPER_PATH = Path(__file__).resolve().with_name("train-opponents.py")
HELPER_SPEC = importlib.util.spec_from_file_location("campaign_processes", HELPER_PATH)
processes = importlib.util.module_from_spec(HELPER_SPEC)
sys.modules[HELPER_SPEC.name] = processes
HELPER_SPEC.loader.exec_module(processes)
TERMINAL = {"passed", "rejected", "skipped"}
CONTROL_FILES = {"campaign-manifest.json", "campaign-state.json", "campaign-report.json",
                 "campaign-report.txt", "campaign-spec.json", ".campaign.lock"}
SAMPLE_SECONDS = 1.0
DISK_SAMPLE_SECONDS = 5.0


class CampaignStop(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_json(path):
    return json.loads(path.read_text(), parse_constant=lambda value: invalid(value))


def invalid(message):
    raise ValueError(message)


def atomic_text(path, contents):
    descriptor, temporary = tempfile.mkstemp(prefix=".campaign-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w") as stream:
            stream.write(contents)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def atomic_json(path, value):
    atomic_text(path, json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n")


def positive_number(value, name, allow_zero=False):
    if (isinstance(value, bool) or not isinstance(value, (int, float))
            or not math.isfinite(value) or value < 0 or (value == 0 and not allow_zero)):
        raise ValueError(f"{name} must be a finite {'nonnegative' if allow_zero else 'positive'} number.")


def validate_absolute_deadline(owner, name):
    if "absoluteDeadlineEpoch" in owner:
        value = owner["absoluteDeadlineEpoch"]
        if type(value) not in (int, float) or not math.isfinite(value):
            raise ValueError(f"{name}.absoluteDeadlineEpoch must be finite Unix seconds.")


def result_path(output, value):
    if not isinstance(value, str) or not value:
        raise ValueError("Each result must be a relative file path.")
    path = Path(value)
    if (not path.parts or path.is_absolute() or ".." in path.parts
            or path.parts[0] in CONTROL_FILES):
        raise ValueError(f"Unsafe stage result path: {value}")
    resolved = (output / path).resolve()
    if output not in resolved.parents:
        raise ValueError(f"Stage result escapes output: {value}")
    return resolved


def validate_spec(spec, output):
    if (not isinstance(spec, dict) or spec.get("schemaVersion") != 1
            or spec.get("kind") != "automata-campaign"):
        raise ValueError("Expected schemaVersion 1 and kind automata-campaign.")
    limits = spec["limits"]
    for name in ("maximumSeconds", "maximumDataBytes", "minimumFreeBytes", "maximumRssBytes"):
        positive_number(limits[name], name, allow_zero=name == "minimumFreeBytes")
    validate_absolute_deadline(limits, "limits")
    stages = spec["stages"]
    if not isinstance(stages, list) or not stages:
        raise ValueError("Campaign stages must be a nonempty list.")
    seen, results = set(), set()
    for stage in stages:
        name = stage["id"]
        if not isinstance(name, str) or not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}", name):
            raise ValueError("Stage ids must contain 1–80 letters, digits, underscores or hyphens.")
        if name in seen:
            raise ValueError(f"Duplicate stage id: {name}")
        dependencies = stage.get("dependsOn", [])
        if not isinstance(dependencies, list) or any(item not in seen for item in dependencies):
            raise ValueError(f"Dependencies of {name} must name preceding stages.")
        command = stage["command"]
        if (not isinstance(command, list) or not command
                or any(not isinstance(item, str) or not item or "\0" in item for item in command)):
            raise ValueError(f"Stage {name} needs a nonempty command argv list.")
        positive_number(stage["maximumSeconds"], f"{name}.maximumSeconds")
        validate_absolute_deadline(stage, name)
        if type(stage.get("maximumAttempts", 1)) is not int or stage.get("maximumAttempts", 1) not in (1, 2):
            raise ValueError("maximumAttempts must be 1 or 2; retries require explicit --resume.")
        path = result_path(output, stage["result"])
        if path in results or path.name.endswith(".log"):
            raise ValueError("Stage result paths must be distinct and cannot use log names.")
        seen.add(name)
        results.add(path)
    if not isinstance(spec.get("inputs", {}), dict):
        raise ValueError("inputs must map source paths to SHA-256 hashes.")


def identity(spec, source, output):
    files = {Path(__file__).resolve(), HELPER_PATH, Path(sys.executable).resolve()}
    declared = {}
    for value, expected in spec.get("inputs", {}).items():
        if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise ValueError(f"Invalid input SHA-256 for {value}.")
        path = (source.parent / value).resolve()
        if path == output or output in path.parents:
            raise ValueError("Frozen campaign inputs cannot be inside the output directory.")
        if sha256(path) != expected:
            raise ValueError(f"Input changed: {path}")
        declared[str(path)] = expected
    for stage in spec["stages"]:
        executable = stage["command"][0]
        if "{output}" in executable:
            raise ValueError("Stage executables must be frozen outside the campaign output.")
        path = ((source.parent / executable).resolve() if "/" in executable
                else Path(shutil.which(executable) or invalid(f"Executable missing: {executable}")))
        if not path.is_file() or not os.access(path, os.X_OK):
            raise ValueError(f"Stage executable is unavailable: {path}")
        files.add(path.resolve())
    return {"source": str(source), "sourceSha256": sha256(source),
            "python": sys.version, "workingDirectory": str(source.parent),
            "files": {**{str(path): sha256(path) for path in sorted(files)}, **declared}}


def report(output, state):
    atomic_json(output / "campaign-report.json", {
        "schemaVersion": 1, "kind": "automata-campaign-report",
        "status": state["status"], "summary": state.get("summary", ""),
        "startedEpoch": state["startedEpoch"], "deadlineEpoch": state["deadlineEpoch"],
        "updatedEpoch": time.time(), "stages": state["stages"],
        "scientificRejections": [key for key, item in state["stages"].items()
                                 if item["status"] == "rejected"],
    })
    elapsed = max(0, time.time() - state["startedEpoch"])
    budget = state["deadlineEpoch"] - state["startedEpoch"]
    lines = [f"Campaign: {state['status']}", state.get("summary", ""),
             f"Wall time: {elapsed:.1f}s / {budget:.1f}s original allowance",
             "Campaign execution does not activate policies.", ""]
    lines.extend(f"{name}: {entry['status']} (attempts {entry['attempts']}) — "
                 f"{entry.get('summary', '')}" for name, entry in state["stages"].items())
    atomic_text(output / "campaign-report.txt", "\n".join(lines) + "\n")


def checkpoint(output, state):
    state["updatedEpoch"] = time.time()
    atomic_json(output / "campaign-state.json", state)
    report(output, state)


def load_campaign(spec, source, output, resume):
    pins = identity(spec, source, output)
    if resume:
        state = read_json(output / "campaign-state.json")
        manifest_path = output / "campaign-manifest.json"
        manifest = read_json(manifest_path)
        if sha256(manifest_path) != state["manifestSha256"]:
            raise ValueError("Campaign manifest changed; refusing resume.")
        if pins != manifest["identity"] or sha256(output / "campaign-spec.json") != pins["sourceSha256"]:
            raise ValueError("Campaign specification, source code or runtime changed; refusing resume.")
        if (state["deadlineEpoch"] != manifest["deadlineEpoch"]
                or state["startedEpoch"] != manifest["startedEpoch"]):
            raise ValueError("Campaign clock changed; refusing resume.")
        for stage in spec["stages"]:
            entry = state["stages"][stage["id"]]
            if entry["status"] == "running":
                # A killed supervisor can leave a live process group. Never start a duplicate.
                if any(not sample.state.startswith("Z") for sample in
                       processes.sample_group(entry["processGroup"])):
                    raise ValueError("Previous stage process group is still live; stop it before resume.")
                entry["status"] = "interrupted"
        verify_completed(spec, state, output)
        return state
    occupied = [path for path in output.iterdir() if path.name != ".campaign.lock"]
    if occupied:
        raise ValueError("New campaigns require an empty output directory; use --resume.")
    started = time.time()
    deadline = started + spec["limits"]["maximumSeconds"]
    if "absoluteDeadlineEpoch" in spec["limits"]:
        deadline = min(deadline, spec["limits"]["absoluteDeadlineEpoch"])
    manifest = {"schemaVersion": 1, "identity": pins, "startedEpoch": started,
                "deadlineEpoch": deadline}
    (output / "campaign-spec.json").write_bytes(source.read_bytes())
    atomic_json(output / "campaign-manifest.json", manifest)
    return {"schemaVersion": 1, "manifestSha256": sha256(output / "campaign-manifest.json"),
            "startedEpoch": started, "deadlineEpoch": manifest["deadlineEpoch"],
            "status": "ready", "stages": {stage["id"]: {"status": "pending", "attempts": 0}
                                          for stage in spec["stages"]}}


def check_resources(output, limits):
    total = 0
    for directory, directories, names in os.walk(output):
        for name in directories + names:
            path = Path(directory) / name
            if path.is_symlink():
                raise CampaignStop("resource-limit", f"Symlinks are forbidden in campaign output: {path}")
        for name in names:
            try:
                total += (Path(directory) / name).stat().st_size
            except FileNotFoundError:
                pass  # A producer can atomically replace temporary files during sampling.
        if total > limits["maximumDataBytes"]:
            raise CampaignStop("resource-limit", "Campaign data byte limit reached.")
    if shutil.disk_usage(output).free < limits["minimumFreeBytes"]:
        raise CampaignStop("resource-limit", "Campaign minimum free disk reserve reached.")
    return total


def validate_result(path, output):
    result = read_json(path)
    if (not isinstance(result, dict) or result.get("schemaVersion") != 1
            or result.get("status") not in {"passed", "rejected"}
            or not isinstance(result.get("summary"), str)):
        raise ValueError(f"Invalid stage result contract: {path}")
    artifacts = result.get("artifacts", {})
    if not isinstance(artifacts, dict):
        raise ValueError(f"Stage artifacts must map output-relative paths to SHA-256 hashes: {path}")
    for name, expected in artifacts.items():
        if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise ValueError(f"Invalid artifact SHA-256: {name}")
        if sha256(result_path(output, name)) != expected:
            raise ValueError(f"Accepted stage artifact changed: {name}")
    return result


def verify_completed(spec, state, output):
    for stage in spec["stages"]:
        entry = state["stages"][stage["id"]]
        if entry["status"] in {"passed", "rejected"}:
            path = result_path(output, stage["result"])
            if sha256(path) != entry["resultSha256"]:
                raise ValueError(f"Completed result changed: {stage['id']}")
            validate_result(path, output)


def monitor(owned, entry, output, spec, state, stopped):
    deadline = entry["deadlineEpoch"]
    monotonic_deadline = time.monotonic() + max(0, deadline - time.time())
    previous_alarm = signal.getsignal(signal.SIGALRM)
    last_disk_check = time.monotonic()
    graceful = False

    def deadline_reached(_number, _frame):
        owned.send(signal.SIGKILL)
        raise CampaignStop("deadline", "Original stage or campaign deadline reached.")

    signal.signal(signal.SIGALRM, deadline_reached)
    signal.setitimer(signal.ITIMER_REAL, max(0.000001, deadline - time.time()))
    try:
        while True:
            if stopped[0]:
                graceful = True
                raise CampaignStop("interrupted", f"Received signal {stopped[0]}.")
            if time.time() >= deadline or time.monotonic() >= monotonic_deadline:
                raise CampaignStop("deadline", "Original stage or campaign deadline reached.")
            samples = processes.sample_group(owned.process.pid)
            leaders = [sample for sample in samples if sample.pid == owned.process.pid]
            if not leaders:
                raise RuntimeError("Cannot observe the owned campaign process-group leader.")
            rss = sum(sample.rss_bytes for sample in samples)
            entry["peakRssBytes"] = max(entry.get("peakRssBytes", 0), rss)
            if rss >= spec["limits"]["maximumRssBytes"]:
                raise CampaignStop("resource-limit", "Campaign process-group resident memory limit reached.")
            if time.monotonic() - last_disk_check >= DISK_SAMPLE_SECONDS:
                entry["observedDataBytes"] = check_resources(output, spec["limits"])
                checkpoint(output, state)
                last_disk_check = time.monotonic()
            if leaders[0].state.startswith("Z"):
                # Terminate any leftover descendants before reaping the leader and releasing its PID.
                return owned.terminate(immediate=True)
            time.sleep(SAMPLE_SECONDS)
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous_alarm)
        if not owned.reaped:
            owned.terminate(immediate=not graceful)


def verify_identity(spec, source, output, pins):
    if identity(spec, source, output) != pins or read_json(source) != spec:
        raise ValueError("Frozen campaign input, source code or runtime changed during execution.")


def run_stage(stage, entry, spec, source, output, state, stopped, pins):
    if entry["attempts"] >= stage.get("maximumAttempts", 1):
        raise CampaignStop("failed", f"Stage {stage['id']} exhausted its permitted attempts.")
    verify_identity(spec, source, output, pins)
    verify_completed(spec, state, output)
    now = time.time()
    entry.setdefault("startedEpoch", now)
    entry.setdefault("deadlineEpoch", min(state["deadlineEpoch"], now + stage["maximumSeconds"]))
    if "absoluteDeadlineEpoch" in stage:
        entry["deadlineEpoch"] = min(entry["deadlineEpoch"], state["deadlineEpoch"],
                                     now + stage["maximumSeconds"], stage["absoluteDeadlineEpoch"])
    if time.time() >= entry["deadlineEpoch"]:
        raise CampaignStop("deadline", f"Stage {stage['id']} exhausted its original deadline.")
    check_resources(output, spec["limits"])
    path = result_path(output, stage["result"])
    # An interrupted attempt's unaccepted result cannot become a new attempt's success.
    if path.exists():
        archive = path.with_name(f"{path.name}.attempt-{entry['attempts']}")
        if archive.exists():
            raise ValueError(f"Cannot overwrite an archived stage result: {archive}")
        path.rename(archive)
    entry.update(status="running", attempts=entry["attempts"] + 1)
    checkpoint(output, state)
    environment = dict(os.environ, AOR_CAMPAIGN_OUTPUT=str(output),
                       AOR_CAMPAIGN_STAGE_ID=stage["id"],
                       AOR_CAMPAIGN_DEADLINE_EPOCH=str(state["deadlineEpoch"]),
                       AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH=str(entry["deadlineEpoch"]))
    command = [item.replace("{output}", str(output)) for item in stage["command"]]
    with (output / f"{stage['id']}.log").open("ab", buffering=0) as log:
        child = subprocess.Popen(command, cwd=source.parent, env=environment,
                                 stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                 start_new_session=True)
        owned = processes.OwnedGroup(child)
        try:
            entry["processGroup"] = child.pid
            checkpoint(output, state)
            code = monitor(owned, entry, output, spec, state, stopped)
        finally:
            if not owned.reaped:
                owned.terminate(immediate=True)
    entry["exitCode"] = code
    if code != 0:
        raise CampaignStop("failed", f"Stage {stage['id']} exited with code {code}.")
    check_resources(output, spec["limits"])
    verify_identity(spec, source, output, pins)
    verify_completed(spec, state, output)
    result = validate_result(path, output)
    entry.update(status=result["status"], summary=result["summary"],
                 result=stage["result"], resultSha256=sha256(path), finishedEpoch=time.time())
    if "metrics" in result:
        entry["metrics"] = result["metrics"]
    if "artifacts" in result:
        entry["artifacts"] = result["artifacts"]


def execute(spec, source, output, state):
    stopped = [0]
    handlers = {number: signal.getsignal(number)
                for number in (signal.SIGINT, signal.SIGTERM, signal.SIGCHLD)}
    for number in (signal.SIGINT, signal.SIGTERM):
        signal.signal(number, lambda received, _frame: stopped.__setitem__(0, received))
    signal.signal(signal.SIGCHLD, signal.SIG_DFL)
    entry = None
    try:
        pins = read_json(output / "campaign-manifest.json")["identity"]
        state.update(status="running", summary="Campaign stages are running.")
        checkpoint(output, state)
        for stage in spec["stages"]:
            entry = state["stages"][stage["id"]]
            if entry["status"] in TERMINAL:
                continue
            if stopped[0]:
                raise CampaignStop("interrupted", f"Received signal {stopped[0]}.")
            if time.time() >= state["deadlineEpoch"]:
                raise CampaignStop("deadline", "Original campaign deadline reached.")
            blocked = [name for name in stage.get("dependsOn", [])
                       if state["stages"][name]["status"] != "passed"]
            if blocked:
                entry.update(status="skipped", summary=f"Dependencies did not pass: {', '.join(blocked)}")
            else:
                run_stage(stage, entry, spec, source, output, state, stopped, pins)
            checkpoint(output, state)
        state.update(status="completed", summary="No eligible declared experiments remain; "
                     "all stages finished or were skipped after a rejected dependency.")
        return 0
    except Exception as error:
        status = error.status if isinstance(error, CampaignStop) else "failed"
        if entry is not None and entry["status"] not in TERMINAL:
            entry.update(status=status, summary=str(error))
        state.update(status=status, summary=str(error))
        return 128 + stopped[0] if stopped[0] else 1
    finally:
        for number, handler in handlers.items():
            signal.signal(number, handler)
        checkpoint(output, state)


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--status", action="store_true")
    options = parser.parse_args(arguments)
    output = options.output.expanduser().resolve()
    if options.status:
        print((output / "campaign-report.txt").read_text(), end="")
        return 0
    if options.spec is None:
        parser.error("--spec is required unless --status is used.")
    source = options.spec.expanduser().resolve()
    if source == output or output in source.parents:
        parser.error("The frozen specification must be outside the output directory.")
    output.mkdir(parents=True, exist_ok=True)
    with (output / ".campaign.lock").open("a+") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("Another campaign controller owns this output directory.", file=sys.stderr)
            return 1
        try:
            spec = read_json(source)
            validate_spec(spec, output)
            state = load_campaign(spec, source, output, options.resume)
        except (OSError, ValueError, KeyError, TypeError) as error:
            # Preserve existing durable state on failed validation; publish the refusal separately.
            atomic_json(output / "campaign-report.json", {
                "schemaVersion": 1, "kind": "automata-campaign-report", "status": "refused",
                "summary": str(error), "updatedEpoch": time.time(),
            })
            atomic_text(output / "campaign-report.txt", f"Campaign: refused\n{error}\n")
            print(str(error), file=sys.stderr)
            return 1
        result = execute(spec, source, output, state)
        print(f"Campaign {state['status']}: {state['summary']}")
        return result


if __name__ == "__main__":
    sys.exit(main())
