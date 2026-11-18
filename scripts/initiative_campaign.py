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
import threading
import time


MAC_JAVA = ("/Users/aragos/.gradle/jdks/azul_systems__inc_-21-aarch64-os_x.2/"
            "zulu21.50.19-ca-jdk21.0.11-macosx_aarch64/Contents/Home/bin/java")
HOST = "aragos@192.168.86.44"
REMOTE = "/home/aragos/aor-worker/initiative-20261118"
SSH = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=8", HOST]
MODE = "dev.aragos.aor.training.TrainingMainKt"
ECOLOGIES = ("QUALIFIED", "REVEALED_UNDERCUT", "REVEALED_TIE_UNDERCUT")


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


COARSE = [
    cfg(1, .5, 1., False, 0.),
    cfg(2, .5, 1., False, 0.),
    cfg(4, .5, 1., False, 0.),
    cfg(4, .5, .5, False, 0.),
    cfg(4, .5, 1., True, 0.),
    cfg(4, .5, .5, True, 0.),
    cfg(2, .5, 1., False, 2.),
    cfg(4, .5, 1., True, 2.),
    cfg(4, .5, .5, True, 4.),
    cfg(0, .5, 0., False, 2.),
    cfg(0, .5, 0., False, 4.),
    cfg(0, .5, 0., False, 8.),
]


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
    def __init__(self, root):
        self.root = root.resolve()
        self.deadline = read(self.root / "window.json")["endEpoch"]
        self.runtime = read(self.root / "runtime.json")
        self.template = read(self.root / "template.json")
        self.stop = threading.Event()
        self.counters = {}

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
        with (stage / f"{name}.log").open("xb") as log:
            if host == "MAC":
                self.call(command, stdout=log)
            else:
                remote_path = f"{REMOTE}/{relative_source}"
                cmd = "mkdir -p " + shlex.quote(f"{REMOTE}/{stage_dir}") + " && cat > " + shlex.quote(remote_path)
                self.call(SSH + [cmd], input_bytes=source.read_bytes(), stdout=log)
                self.call(SSH + [shlex.join(command)], stdout=log)
                archive = subprocess.check_output(SSH + [
                    f"cd {shlex.quote(REMOTE + '/' + stage_dir)} && tar -czf - {shlex.quote(name)}"],
                    timeout=max(2,self.remaining()))
                with tarfile.open(fileobj=BytesIO(archive), mode="r:gz") as stream:
                    stream.extractall(stage, filter="data")
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
        receipt = dict(host=host, seconds=time.monotonic()-started, games=done["completedGames"])
        write(stage / f"{name}.receipt.json", receipt)
        print(json.dumps(dict(stage=stage_dir, job=name, **receipt)), flush=True)

    def stage(self, label, configs, count, ecologies, *, paired=False, shard=96):
        if self.remaining() < 300:
            raise RuntimeError("The original window has too little time for a new stage.")
        stage = self.root / label
        stage.mkdir()
        banks = {mode: seed_bank(f"{label}/{mode}", count) for mode in ecologies}
        streams = [seed for bank in banks.values() for seed in bank]
        if len(set(streams)) != len(streams):
            raise ValueError("Each declared stage bank requires distinct seed streams.")
        declared = dict(stage=label, configs=configs, banks=banks, paired=paired,
                        replayIndices=[0,count-1], runtime=self.runtime, endEpoch=self.deadline)
        write(stage / "method.json", declared)
        choices = configs if paired else {"control": None, **configs}
        jobs = []
        for key, policy in choices.items():
            for case, mode in enumerate(ecologies):
                for start in range(0, count, shard):
                    name = f"{key}-{case}-{start}"
                    source = stage / f"{name}.json"
                    spec = dict(self.template, protocolSha256=sha(stage / "method.json"),
                                seeds=banks[mode], globalStart=start, count=min(shard, count-start),
                                opponentStyle=mode, candidateOnly=not paired,
                                replayIndices=declared["replayIndices"])
                    spec = {key: dict(value, path="../"+value["path"]) if isinstance(value,dict) and
                            set(value)=={"path", "sha256"} else value for key,value in spec.items()}
                    if mode != "QUALIFIED":
                        spec["adaptiveOpponent"] = dict(kind="public-token-history-counter-1",
                            minimumObservations=2,lookbackTurns=8,targetedRivalCount=1)
                    if policy is not None:
                        spec["initiative"] = policy
                    write(source, spec)
                    jobs.append((name, source))
        todo = queue.SimpleQueue()
        for job in jobs:
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
            ordinary = summary["QUALIFIED"]["mean"]
            adaptive = statistics.mean(row["mean"] for key, row in summary.items() if key != "QUALIFIED")
            outcomes[key] = dict(config=config, outcomes=summary, ordinary=ordinary, adaptive=adaptive,
                                 rankScore=.5*(ordinary+adaptive))
        write(stage / "summary.json", outcomes)
        return outcomes


def ranked(outcomes):
    return sorted(outcomes.values(), key=lambda row: (row["rankScore"], row["adaptive"]), reverse=True)


def refine(winners):
    options = {}
    for row in winners:
        base = row["config"]
        changes = [base, dict(base, tokenPenalty=max(.0,base["tokenPenalty"]-.35)),
                   dict(base, tokenPenalty=min(3.,base["tokenPenalty"]+.65)),
                   dict(base, participation=.75),
                   dict(base, attackBonus=0.0 if base["attackBonus"] else 3.0)]
        for config in changes:
            options.setdefault(json.dumps(config, sort_keys=True),config)
    return {f"r{i:02d}": item for i,item in enumerate(options.values())}


def main(root, operation):
    campaign = Campaign(root)
    campaign.validate_runtime()
    if operation == "smoke":
        campaign.stage("smoke", {"initiative": COARSE[7]}, 8, ECOLOGIES[:2], paired=True, shard=4)
        return
    if not (root / "smoke/summary.json").is_file():
        raise ValueError("Both hosts must pass the bounded smoke before starting the search.")
    coarse = campaign.stage("coarse", {f"c{i:02d}": value for i, value in enumerate(COARSE)},
                            192, ECOLOGIES, shard=96)
    nearby = campaign.stage("refine", refine(ranked(coarse)[:3]), 224, ECOLOGIES, shard=112)
    merged = ranked(nearby)
    final = campaign.stage("confirmation", {f"f{i}": row["config"] for i,row in enumerate(merged[:3])},
                           384, ECOLOGIES, shard=96)
    winner = ranked(final)[0]
    if winner["adaptive"] <= 0 or winner["ordinary"] < -.075:
        write(root / "result.json", dict(status="development-no-nomination", best=winner))
        return
    write(root / "nomination.json", winner)
    audit = campaign.stage("fresh-audit", {"winner":winner["config"]}, 768, ECOLOGIES, paired=True)
    checked = audit["winner"]["outcomes"]
    passed = checked["QUALIFIED"]["lower95"] > -.075 and all(
        checked[mode]["lower95"] > 0 for mode in ECOLOGIES[1:])
    write(root / "result.json", dict(status="fresh-audit-complete", passed=passed, comparison=audit["winner"]))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("operation", choices=("smoke", "search"))
    args = parser.parse_args()
    main(args.root.resolve(), args.operation)
