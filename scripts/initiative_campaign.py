"""Bounded two-host search over bid initiative and expansion follow-through."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
from io import BytesIO
import json
import math
from pathlib import Path
import queue
import shlex
import shutil
import statistics
import subprocess
import tarfile
import tempfile
import threading
import time


MAC_JAVA = ("/Users/aragos/.gradle/jdks/azul_systems__inc_-21-aarch64-os_x.2/"
            "zulu21.50.19-ca-jdk21.0.11-macosx_aarch64/Contents/Home/bin/java")
HOST = "aragos@192.168.86.44"
REMOTE = "/home/aragos/aor-worker/initiative-cards-20261118"
SSH = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=8",
       "-o", "ServerAliveInterval=10", "-o", "ServerAliveCountMax=3", HOST]
MODE = "dev.aragos.aor.training.TrainingMainKt"
ECOLOGIES = ("QUALIFIED", "REVEALED_UNDERCUT", "INDEPENDENT_INITIATIVE")


def read(path):
    return json.loads(path.read_bytes())


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write(path, record, *, replace=False):
    part = path.with_suffix(path.suffix + ".tmp") if replace else path
    with part.open("w" if replace else "x") as stream:
        json.dump(record, stream, sort_keys=True, indent=2, allow_nan=False)
    if replace:
        part.replace(path)


def cfg(loss, penalty, participation, contest, attack):
    return dict(kind="public-initiative-1", maximumTokenLoss=loss, tokenPenalty=penalty,
                participation=participation, requireAffordableContest=contest, attackBonus=attack)


def seed_bank(label, count):
    return [-1 - (int.from_bytes(hashlib.sha256(f"aor-initiative-v1/{label}/{i}".encode()).digest()[:4],
                                 "big") % ((1 << 31) - 1)) for i in range(count)]


def interval(values):
    mean = statistics.mean(values)
    se = statistics.stdev(values) / math.sqrt(len(values)) if len(values) > 1 else 0.0
    return dict(mean=mean, lower95=mean-1.96*se, upper95=mean+1.96*se, worlds=len(values))


def score(records, controls, role="CANDIDATE"):
    index = lambda row: (row["index"], row["seed"], row["seat"])
    if [index(row) for row in records] != [index(row) for row in controls]:
        raise ValueError("Candidate and control must share the complete original world bank.")
    result = interval([row["roles"][role]["wins"][row["seat"]] -
                       base["roles"]["CANDIDATE"]["wins"][base["seat"]]
                       for row, base in zip(records, controls, strict=True)])
    changes = [r["diagnostics"][role] for r in records]
    baselines = [r["diagnostics"]["CANDIDATE"] for r in controls]
    metrics = ("controlledAttacks", "controlledExpansionCardsBought",
               "controlledDiscardedPoolAtPass", "controlledCashCommittedToTokens")
    result["behavior"] = {
        key: dict(candidate=statistics.mean(row[key] for row in changes),
                  baseline=statistics.mean(row[key] for row in baselines)) for key in metrics
    }
    count = lambda rows: sum(sum(row["controlledBidCounts"].values()) for row in rows)
    at_21 = lambda rows: sum(row["controlledBidCounts"].get("21", 0) for row in rows)
    result["bid21Share"] = dict(candidate=at_21(changes)/max(1, count(changes)),
                                baseline=at_21(baselines)/max(1, count(baselines)))
    first_ratio = lambda rows: sum(r["controlledOrderPositions"].get("1", 0) for r in rows) / max(
        1, sum(sum(r["controlledOrderPositions"].values()) for r in rows))
    result["firstOrderShare"] = dict(candidate=first_ratio(changes), baseline=first_ratio(baselines))
    return result


class Campaign:
    def __init__(self, root, resume=False, recover_desktop_job=None):
        self.root = root.resolve()
        self.deadline = read(self.root / "window.json")["endEpoch"]
        self.runtime = read(self.root / "runtime.json")
        self.template = read(self.root / "template.json")
        self.stop = threading.Event()
        self.resume = resume
        self.recover_desktop_job = recover_desktop_job

    def remaining(self):
        return self.deadline - time.time()

    def validate_runtime(self):
        lib = self.root / "lib"
        if set(self.runtime) != {p.name for p in lib.glob("*.jar")}:
            raise ValueError("Frozen JVM libraries differ from the campaign manifest.")
        for name, digest in self.runtime.items():
            if sha(lib / name) != digest:
                raise ValueError(f"Frozen runtime changed for {name}.")

    def call(self, cmd, *, stdout=None, input_bytes=None):
        remaining = self.remaining()
        if remaining < 2 or self.stop.is_set():
            raise RuntimeError("No additional tasks may start after the original deadline.")
        return subprocess.run(cmd, check=True, timeout=remaining, input=input_bytes, stdout=stdout)

    def execute(self, stage, task, host):
        name, source = task
        stage_dir = stage.name
        relative_source = f"{stage_dir}/{name}.json"
        relative_output = f"{stage_dir}/{name}"
        target = stage / name
        if target.exists():
            raise ValueError("Refusing to overwrite a native result directory.")
        worker_count, heap, java = (6, "5g", MAC_JAVA) if host == "MAC" else (12, "7g", "/usr/bin/java")
        if host == "MAC" and shutil.disk_usage(self.root).free < 16*(1024**3):
            raise RuntimeError("Mac free disk space fell below the 16 GiB floor.")
        folder = str(self.root) if host == "MAC" else REMOTE
        command = ["env", "-u", "JAVA_TOOL_OPTIONS", "-u", "JDK_JAVA_OPTIONS", "-u", "_JAVA_OPTIONS",
                   java, f"-Xmx{heap}", "-Xms128m", f"-XX:ActiveProcessorCount={worker_count}",
                   "-cp", f"{folder}/lib/*", MODE, "--mode", "robust-token-screen-chunk",
                   "--source", f"{folder}/{relative_source}", "--output", f"{folder}/{relative_output}",
                   "--workers", str(worker_count), "--seconds", str(min(32400, int(self.remaining())))]
        started = time.monotonic()
        with (stage / f"{name}.log").open("ab" if self.resume else "xb") as log:
            if host == "MAC":
                self.call(command, stdout=log)
            else:
                remote_path = f"{REMOTE}/{relative_source}"
                cmd = "mkdir -p " + shlex.quote(f"{REMOTE}/{stage_dir}") + " && cat > " + shlex.quote(remote_path)
                self.call(SSH + [cmd], input_bytes=source.read_bytes(), stdout=log)
                self.call(SSH + [shlex.join(command)], stdout=log)
                self.collect_desktop(stage, task)
        self.record_result(stage, task, host, time.monotonic() - started)

    def collect_desktop(self, stage, task):
        name, source = task
        archive = subprocess.check_output(SSH + [
            f"cd {shlex.quote(REMOTE + '/' + stage.name)} && tar -czf - {shlex.quote(name)}"],
            timeout=min(60, max(2, self.remaining())))
        with tempfile.TemporaryDirectory(dir=stage) as directory:
            with tarfile.open(fileobj=BytesIO(archive), mode="r:gz") as stream:
                stream.extractall(directory, filter="data")
            recovered = Path(directory) / name
            self.validate_result(recovered, source)
            recovered.rename(stage / name)

    @staticmethod
    def validate_result(target, source):
        name = target.name
        done = read(target / "finished.json")
        definition = read(source)
        if not done["complete"] or done["sourceSha256"] != sha(source):
            raise ValueError(f"{name} output no longer matches its original complete request.")
        if any(sha(target / file) != digest for file, digest in done["files"].items()):
            raise ValueError(f"{name} has modified result files.")
        rows = [json.loads(line) for line in (target / "worlds.jsonl").read_text().splitlines()]
        rows.sort(key=lambda row: row["index"])
        wanted = range(definition["globalStart"], definition["globalStart"]+definition["count"])
        if not len(rows) == len(wanted) or any(row["index"] != i or
            row["seed"] != definition["seeds"][i] or row["scienceSha256"] != done["scienceSha256"]
            for i, row in zip(wanted, rows, strict=True)):
            raise ValueError(f"{name} incomplete seed/index/science identity.")
        return done["completedGames"]

    def record_result(self, stage, task, host, seconds):
        name, source = task
        receipt = dict(host=host, seconds=seconds, games=self.validate_result(stage / name, source))
        write(stage / f"{name}.receipt.json", receipt)
        print(json.dumps(dict(stage=stage.name, job=name, **receipt)), flush=True)

    def freeze(self, path, value):
        if self.resume and path.exists():
            if read(path) != value:
                raise ValueError(f"Original declaration differs for {path}.")
        else:
            write(path, value)

    def stage(self, label, configs, count, ecologies, *, paired=False, shard=96, rival=None, baseline=None):
        if self.remaining() < 300:
            raise RuntimeError("The original window has too little time for a new stage.")
        stage = self.root / label
        stage.mkdir(exist_ok=self.resume)
        banks = {mode: seed_bank(f"{label}/{mode}", count) for mode in ecologies}
        streams = [seed for bank in banks.values() for seed in bank]
        if len(set(streams)) != len(streams):
            raise ValueError("Each declared stage bank requires distinct seed streams.")
        declared = dict(stage=label, configs=configs, banks=banks, paired=paired,
                        replayIndices=[0,count-1], runtime=self.runtime, endEpoch=self.deadline, rival=rival, baseline=baseline)
        self.freeze(stage / "method.json", declared)
        choices = configs if paired else {"control": baseline, **configs}
        jobs = []
        for key, policy in choices.items():
            for case, mode in enumerate(ecologies):
                for start in range(0, count, shard):
                    name = f"{key}-{case}-{start}"
                    source = stage / f"{name}.json"
                    spec = dict(self.template, protocolSha256=sha(stage / "method.json"),
                                seeds=banks[mode], globalStart=start, count=min(shard, count-start),
                                opponentStyle="QUALIFIED" if mode == "INDEPENDENT_INITIATIVE" else mode,
                                candidateOnly=not paired,
                                replayIndices=declared["replayIndices"])
                    spec = {key: dict(value, path="../"+value["path"]) if isinstance(value,dict) and
                            set(value)=={"path", "sha256"} else value for key,value in spec.items()}
                    if mode.startswith("REVEALED_"):
                        spec["adaptiveOpponent"] = dict(kind="public-token-history-counter-1",
                            minimumObservations=2,lookbackTurns=8,targetedRivalCount=1)
                    if mode == "INDEPENDENT_INITIATIVE":
                        if rival is None:
                            raise ValueError("This ecology requires its frozen independently tuned rival.")
                        spec["initiativeRival"] = dict(kind="public-initiative-rival-1",offset=1,policy=rival)
                    if policy is not None:
                        spec["initiative"] = policy
                    self.freeze(source, spec)
                    jobs.append((name, source))
        todo = queue.SimpleQueue()
        for job in jobs:
            name, source = job
            if self.resume and (stage / f"{name}.receipt.json").exists():
                games = self.validate_result(stage / name, source)
                if games != read(stage / f"{name}.receipt.json")["games"]:
                    raise ValueError(f"Original game count differs for {name}.")
            elif self.resume and self.recover_desktop_job == f"{label}/{name}":
                started = time.monotonic()
                self.collect_desktop(stage, job)
                self.record_result(stage, job, "DESKTOP", time.monotonic() - started)
            else:
                todo.put(job)

        def run_host(host):
            try:
                while not self.stop.is_set():
                    try:
                        task = todo.get_nowait()
                    except queue.Empty:
                        return
                    self.execute(stage, task, host)
            except Exception:
                self.stop.set()
                raise

        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(run_host, host) for host in ("MAC", "DESKTOP")]
            for future in futures:
                future.result()

        def records(name, case):
            result = []
            for start in range(0, count, shard):
                target = stage / f"{name}-{case}-{start}" / "worlds.jsonl"
                result.extend(json.loads(line) for line in target.read_text().splitlines())
            return sorted(result, key=lambda row: row["index"])

        outcomes = {}
        for key, config in configs.items():
            summary = {}
            for case, mode in enumerate(ecologies):
                current = records(key, case)
                if paired:
                    control = [dict(row, roles={"CANDIDATE":row["roles"]["CONTROL"]},
                                    diagnostics={"CANDIDATE":row["diagnostics"]["CONTROL"]})
                               for row in current]
                else:
                    control = records("control", case)
                summary[mode] = score(current, control)
            ordinary = summary.get("QUALIFIED", next(iter(summary.values())))["mean"]
            adaptive_rows = [row["mean"] for mode, row in summary.items() if mode != "QUALIFIED"]
            adaptive = statistics.mean(adaptive_rows) if adaptive_rows else ordinary
            outcomes[key] = dict(config=config, outcomes=summary, ordinary=ordinary, adaptive=adaptive,
                                 rankScore=.5*(ordinary+adaptive))
        self.freeze(stage / "summary.json", outcomes)
        return outcomes


def ranked(outcomes):
    return sorted(outcomes.values(), key=lambda row: (row["rankScore"], row["adaptive"]), reverse=True)


def main(root, initial, resume, recover_desktop_job):
    campaign = Campaign(root, resume=resume, recover_desktop_job=recover_desktop_job)
    campaign.validate_runtime()
    if campaign.remaining() < 1000:
        write(root / "result.json", dict(status="insufficient-time-for-card-screen"))
        return
    earlier = read(initial / "result.json")
    base = earlier["best"] if earlier["status"] == "development-no-nomination" else earlier["comparison"]
    base = base["config"]
    runs = {
        "cheap-card-light": dict(base, cardBonus=1.0),
        "cheap-card-medium": dict(base, cardBonus=2.5),
        "cheap-card-strong": dict(base, cardBonus=5.0),
        "cards-before-attacks": dict(base, attackBonus=0.0, cardBonus=3.5),
        "attack-and-cards": dict(base, attackBonus=max(2.0,base["attackBonus"]), cardBonus=3.5),
        "card-bonus-only": dict(cfg(0,.5,0.,False,0.), cardBonus=3.5),
    }
    conditions = ECOLOGIES[:2]
    fitted = campaign.stage("cheap-card-screen", runs, 176, conditions, shard=88, baseline=base)
    winner = ranked(fitted)[0]
    if winner["adaptive"] < .025 or winner["ordinary"] < -.025 or campaign.remaining() < 950:
        write(root/"result.json",dict(status="card-screen-complete",differenceFromBase=winner))
        return
    audit = campaign.stage("cheap-card-fresh-audit", {"card":winner["config"],"qualified94":None},
                           448, conditions, shard=112, baseline=base)
    write(root/"result.json",dict(status="card-fresh-audit-complete",differenceFromBase=audit))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--initial", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--recover-desktop-job", metavar="STAGE/JOB",
                        help="collect a completed Ubuntu shard after a lost SSH connection")
    args = parser.parse_args()
    if args.recover_desktop_job and not args.resume:
        parser.error("--recover-desktop-job requires --resume")
    main(args.root.resolve(), args.initial.resolve(), args.resume, args.recover_desktop_job)
