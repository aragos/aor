"""Bounded two-host search over bid initiative and expansion follow-through."""
import argparse
from collections import Counter
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
REMOTE = "/home/aragos/aor-worker/initiative-mirror-20261118"
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
    def bid_mode_share(rows):
        counts = Counter()
        for row in rows:
            counts.update(row["controlledBidCounts"])
        return max(counts.values(), default=0) / max(1, counts.total())

    result["bidModeShare"] = dict(candidate=bid_mode_share(changes),
                                  baseline=bid_mode_share(baselines))
    def counter_accuracy(rows):
        counters = [row["publicTokenCounter"] for row in rows if row.get("publicTokenCounter")]
        predictions = sum(counter["resolvedPredictions"] for counter in counters)
        hits = sum(counter["predictionHits"] for counter in counters)
        return hits / predictions if predictions else None

    result["counterAccuracy"] = dict(candidate=counter_accuracy(changes),
                                     baseline=counter_accuracy(baselines))
    return result


class WorkerUnavailable(RuntimeError):
    """A transport or resource interruption that allows a shard to run on the other worker."""


class Campaign:
    def __init__(self, root, *, remote=REMOTE, mac_reserve_gib=10, resume=False, background=False,
                 batch_size=1):
        if not 1 <= batch_size <= 4:
            raise ValueError("A campaign can assign at most four native shards to a worker at a time.")
        self.root = root.resolve()
        self.remote = remote
        self.mac_reserve = mac_reserve_gib * (1024 ** 3)
        self.resume = resume
        self.background = background
        self.batch_size = batch_size
        self.deadline = read(self.root / "window.json")["endEpoch"]
        self.runtime = read(self.root / "runtime.json")
        self.template = read(self.root / "template.json")
        self.stop = threading.Event()

    def remaining(self):
        return self.deadline - time.time()

    def validate_runtime(self):
        lib = self.root / "lib"
        if set(self.runtime) != {p.name for p in lib.glob("*.jar")}:
            raise ValueError("Frozen JVM libraries differ from the campaign manifest.")
        for name, digest in self.runtime.items():
            if sha(lib / name) != digest:
                raise ValueError(f"Frozen runtime changed for {name}.")

    def call(self, cmd, *, stdout=None, input_bytes=None, limit=None):
        remaining = self.remaining()
        if remaining < 2 or self.stop.is_set():
            raise RuntimeError("No additional tasks may start after the original deadline.")
        if limit is not None:
            remaining = min(remaining, limit)
        return subprocess.run(cmd, check=True, timeout=remaining, input=input_bytes, stdout=stdout)

    def desktop_call(self, command, **options):
        try:
            return self.call(SSH + [command], **options)
        except subprocess.TimeoutExpired as error:
            raise WorkerUnavailable("The Ubuntu worker exceeded its shard transport limit.") from error
        except subprocess.CalledProcessError as error:
            if error.returncode != 255:
                raise
            raise WorkerUnavailable("The Ubuntu worker's SSH connection was interrupted.") from error

    def fetch_desktop(self, stage, name, source):
        parent = shlex.quote(f"{self.remote}/{stage.name}")
        archive = self.desktop_call(f"cd {parent} && tar -czf - {shlex.quote(name)}",
                                    stdout=subprocess.PIPE, limit=60).stdout
        with tempfile.TemporaryDirectory(dir=stage) as directory:
            with tarfile.open(fileobj=BytesIO(archive), mode="r:gz") as stream:
                stream.extractall(directory, filter="data")
            target = Path(directory) / name
            self.validate_result(target, source)
            target.rename(stage / name)

    def native_command(self, host, mode, arguments):
        mac, desktop = (3, 6) if self.background else (8, 16)
        worker_count, heap, java = (mac, "5g", MAC_JAVA) if host == "MAC" else (desktop, "7g", "/usr/bin/java")
        if host == "MAC" and shutil.disk_usage(self.root).free < self.mac_reserve:
            raise WorkerUnavailable("Mac free disk space fell below the campaign reserve.")
        folder = str(self.root) if host == "MAC" else self.remote
        command = ["env", "-u", "JAVA_TOOL_OPTIONS", "-u", "JDK_JAVA_OPTIONS", "-u", "_JAVA_OPTIONS",
                   java, f"-Xmx{heap}", "-Xms128m", f"-XX:ActiveProcessorCount={worker_count}",
                   "-cp", f"{folder}/lib/*", MODE, "--mode", mode, *arguments,
                   "--workers", str(worker_count), "--seconds", str(min(32400, int(self.remaining())))]
        if self.background:
            command = ["/usr/bin/nice", "-n", "8", *command]
        return command

    def execute(self, stage, task, host):
        name, source = task
        stage_dir = stage.name
        relative_source = f"{stage_dir}/{name}.json"
        relative_output = f"{stage_dir}/{name}"
        target = stage / name
        if target.exists():
            raise ValueError("Refusing to overwrite a native result directory.")
        folder = str(self.root) if host == "MAC" else self.remote
        command = self.native_command(host, "robust-token-screen-chunk",
                                      ["--source", f"{folder}/{relative_source}",
                                       "--output", f"{folder}/{relative_output}"])
        started = time.monotonic()
        with (stage / f"{name}.log").open("ab") as log:
            if host == "MAC":
                self.call(command, stdout=log)
            else:
                stale = self.desktop_shard_state(relative_output) if self.resume else b"missing"
                if stale == b"partial":
                    raise WorkerUnavailable(f"Ubuntu has incomplete earlier evidence for shard {name}.")
                if stale == b"missing":
                    remote_path = shlex.quote(f"{self.remote}/{relative_source}")
                    parent = shlex.quote(f"{self.remote}/{stage_dir}")
                    cmd = f"mkdir -p {parent} && cat > {remote_path}"
                    self.desktop_call(cmd, input_bytes=source.read_bytes(), stdout=log, limit=45)
                    games = read(source)["count"]
                    self.desktop_call(shlex.join(command), stdout=log, limit=max(600, 3 * games))
                self.fetch_desktop(stage, name, source)
        games = self.validate_result(target, source)
        receipt = dict(host=host, seconds=time.monotonic() - started, games=games)
        write(stage / f"{name}.receipt.json", receipt)
        print(json.dumps(dict(stage=stage_dir, job=name, **receipt)), flush=True)

    def execute_batch(self, stage, tasks, host):
        if len(tasks) == 1:
            self.execute(stage, tasks[0], host)
            return
        if any((stage / name).exists() for name, _ in tasks):
            raise ValueError("Refusing to overwrite a completed native result directory in a batch.")
        started = time.monotonic()
        with tempfile.TemporaryDirectory(dir=stage, prefix=".native-batch-") as scratch:
            temporary = Path(scratch)
            plan = temporary / "plan.json"
            write(plan, {"kind": "robust-token-batch-1", "jobs": [
                {"source": f"../{source.name}", "output": name} for name, source in tasks]})
            base = str(self.root) if host == "MAC" else self.remote
            stage_relative = f"{stage.name}/{temporary.name}"
            command = self.native_command(host, "robust-token-batch",
                                          ["--plan", f"{base}/{stage_relative}/plan.json"])
            log_path = stage / f"{temporary.name.lstrip('.')}.log"
            with log_path.open("ab") as log:
                if host == "MAC":
                    self.call(command, stdout=log)
                    output = temporary
                else:
                    output = self.desktop_batch(
                        stage, tasks, base, stage_relative, temporary, plan, command, log)
            validated = [(name, self.validate_result(output / name, source)) for name, source in tasks]
            elapsed = time.monotonic() - started
            for name, games in validated:
                (output / name).rename(stage / name)
                receipt = {"host": host, "seconds": elapsed / len(validated), "games": games,
                           "batchSeconds": elapsed, "batchSize": len(validated)}
                write(stage / f"{name}.receipt.json", receipt)
            print(json.dumps({"stage": stage.name, "jobs": [name for name, _ in validated],
                              "host": host, "seconds": elapsed,
                              "games": sum(games for _, games in validated)}), flush=True)

    def desktop_batch(self, stage, tasks, base, relative, temporary, plan, command, log):
        request_bytes = BytesIO()
        with tarfile.open(fileobj=request_bytes, mode="w") as archive:
            archive.add(plan, arcname=f"{relative}/plan.json", recursive=False)
            for _, source in tasks:
                archive.add(source, arcname=f"{stage.name}/{source.name}", recursive=False)
        parent = shlex.quote(f"{base}/{relative}")
        root = shlex.quote(base)
        self.desktop_call(f"mkdir -p {parent} && cd {root} && tar -xf -",
                          input_bytes=request_bytes.getvalue(), stdout=log, limit=60)
        self.desktop_call(shlex.join(command), stdout=log, limit=600 * len(tasks))
        response = self.desktop_call(
            f"cd {parent} && tar -czf - {shlex.join([name for name, _ in tasks])}",
            stdout=subprocess.PIPE, limit=90).stdout
        with tarfile.open(fileobj=BytesIO(response), mode="r:gz") as archive:
            archive.extractall(temporary, filter="data")
        return temporary

    def desktop_shard_state(self, relative_output):
        target = shlex.quote(f"{self.remote}/{relative_output}")
        return self.desktop_call(
            f"if test -f {target}/finished.json; then printf complete; "
            f"elif test -e {target}; then printf partial; else printf missing; fi",
            stdout=subprocess.PIPE, limit=45,
        ).stdout

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

    def stage(self, label, configs, count, ecologies, *, paired=False, shard=96, rival=None,
              rival_offsets=(1,2,3), baseline=None, opening_bid_prior=None, reuse_complete=False,
              counter_variants=None, lineup=None, capital_variants=None, series_priors=None,
              leading_rival_variants=None):
        if self.remaining() < 300:
            raise RuntimeError("The original window has too little time for a new stage.")
        stage = self.root / label
        stage.mkdir(exist_ok=self.resume or reuse_complete)

        def declare(path, value):
            if not reuse_complete and not (self.resume and path.exists()):
                write(path, value)
            elif read(path) != value:
                raise ValueError(f"The existing declaration does not match {path}.")
        banks = {mode: seed_bank(f"{label}/{mode}", count) for mode in ecologies}
        streams = [seed for bank in banks.values() for seed in bank]
        if len(set(streams)) != len(streams):
            raise ValueError("Each declared stage bank requires distinct seed streams.")
        declared = dict(stage=label, configs=configs, banks=banks, paired=paired,
                        replayIndices=[0,count-1], runtime=self.runtime, endEpoch=self.deadline,
                        rival=rival, rivalOffsets=list(rival_offsets) if rival is not None else [], baseline=baseline)
        if opening_bid_prior is not None:
            declared["openingBidPrior"] = opening_bid_prior
        if counter_variants:
            declared["counterVariants"] = counter_variants
        if lineup:
            declared["initiativeLineup"] = lineup
        if capital_variants:
            declared["capitalAuctionVariants"] = capital_variants
        if series_priors:
            declared["seriesOpeningPriors"] = series_priors
        if leading_rival_variants:
            declared["leadingRivalRetargetingVariants"] = leading_rival_variants
        declare(stage / "method.json", declared)
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
                        if opening_bid_prior is not None:
                            spec["adaptiveOpponent"]["openingBidPrior"] = opening_bid_prior
                        if counter_variants:
                            spec["adaptiveOpponent"].update(counter_variants.get(key, {}))
                    if mode == "INDEPENDENT_INITIATIVE":
                        if rival is None:
                            raise ValueError("This ecology requires its frozen independently tuned rival.")
                        spec["initiativeRival"] = dict(kind="public-initiative-lineup-1",offsets=list(rival_offsets),policy=rival)
                    if policy is not None:
                        spec["initiative"] = policy
                    if lineup:
                        spec["initiativeLineup"] = lineup
                    if capital_variants and key in capital_variants:
                        spec["capitalAuction"] = capital_variants[key]
                    if series_priors and key in series_priors:
                        spec["seriesOpeningPriors"] = series_priors[key]
                    if leading_rival_variants and key in leading_rival_variants:
                        spec["leadingRivalRetargeting"] = leading_rival_variants[key]
                    declare(source, spec)
                    jobs.append((name, source))
        pending = []
        for name, source in jobs:
            receipt = stage / f"{name}.receipt.json"
            target = stage / name
            if self.resume and not receipt.exists() and target.exists():
                if (target / "finished.json").is_file():
                    games = self.validate_result(target, source)
                    write(receipt, {"host": "RECOVERED", "seconds": 0.0, "games": games,
                                    "recoveredCompletedResult": True})
                else:
                    evidence = Path(tempfile.mkdtemp(dir=stage, prefix=".interrupted-"))
                    target.rename(evidence / name)
            if reuse_complete or self.resume and receipt.exists():
                games = self.validate_result(stage / name, source)
                if read(receipt)["games"] != games:
                    raise ValueError(f"The existing receipt does not match {name}.")
            else:
                pending.append((name, source))
        if pending:
            self.execute_jobs(stage, pending)

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
        declare(stage / "summary.json", outcomes)
        return outcomes

    def execute_jobs(self, stage, jobs):
        todo = queue.SimpleQueue()
        for job in jobs:
            todo.put(job)

        def run_host(host):
            try:
                while not self.stop.is_set():
                    try:
                        batch = [todo.get_nowait()]
                    except queue.Empty:
                        return
                    for _ in range(self.batch_size - 1):
                        try:
                            batch.append(todo.get_nowait())
                        except queue.Empty:
                            break
                    try:
                        self.execute_batch(stage, batch, host)
                    except WorkerUnavailable as error:
                        for task in batch:
                            todo.put(task)
                        print(json.dumps(dict(stage=stage.name, jobs=[task[0] for task in batch], host=host,
                                              workerUnavailable=str(error))), flush=True)
                        return host
            except Exception:
                self.stop.set()
                raise

        with ThreadPoolExecutor(max_workers=2) as pool:
            hosts = ("MAC", "DESKTOP")
            futures = [pool.submit(run_host, host) for host in hosts]
            unavailable = {future.result() for future in futures}
        for host in hosts:
            if not todo.empty() and host not in unavailable:
                run_host(host)
        if not todo.empty():
            raise WorkerUnavailable("All eligible workers stopped with incomplete simulation shards.")


def ranked(outcomes):
    return sorted(outcomes.values(), key=lambda row: (row["rankScore"], row["adaptive"]), reverse=True)


def main(root, initial):
    campaign = Campaign(root)
    campaign.validate_runtime()
    first = read(initial / "result.json")
    if campaign.remaining() < 650 or first["status"] != "fresh-audit-complete" or not first["passed"]:
        write(root / "result.json", dict(status="no-admitted-policy-or-time-for-mirror"))
        return
    champion = first["comparison"]["config"]
    count = 384
    result = campaign.stage("mirrored-table", {"policy":champion}, count,
                            ECOLOGIES[-1:], paired=True, rival=champion)
    folder = root / "mirrored-table"
    evidence = [json.loads(line) for start in range(0,count,96)
        for line in (folder / f"policy-0-{start}" / "worlds.jsonl").read_text().splitlines()]
    tiers = {}
    for role in ("CANDIDATE","CONTROL"):
        win = [row["roles"][role]["wins"][row["seat"]] for row in evidence]
        bid_counts = {}
        for row in evidence:
            for bid,n in row["diagnostics"][role]["controlledBidCounts"].items():
                bid_counts[bid] = bid_counts.get(bid,0)+n
        tiers[role] = dict(winShare=statistics.mean(win),
            allChaosWorlds=sum(not any(row["roles"][role]["wins"]) for row in evidence),
            bidMode=max(bid_counts,key=bid_counts.__getitem__),
            bidModeShare=max(bid_counts.values())/sum(bid_counts.values()))
    write(root / "result.json", dict(status="mirrored-table-complete", results=tiers,
        pairedComparison=result, initialResultSha256=sha(initial/"result.json")))

    run_deeper_rival(campaign, champion, initial)
    if campaign.remaining() >= 720:
        overbid_styles = ("REVEALED_OVERBID", "REVEALED_TIE_OVERBID")
        overbid = campaign.stage("late-order-audit", {"policy": champion}, 384,
                                 overbid_styles, paired=True)
        write(root / "late-order-audit/result.json", overbid)


def run_deeper_rival(campaign, champion, initial):
    if campaign.remaining() < 1000:
        return
    root = campaign.root
    response_path = initial.parent / "automata-initiative-crossplay-20261118/independent-response/summary.json"
    if not response_path.is_file():
        return
    # This rival had the same development score as the cheaper one but can give up three tokens.
    responder = read(response_path)["r1"]["config"]
    check = campaign.stage("deeper-undercut-check", {"response":responder},
                           384, ECOLOGIES[:1], paired=True)
    response_result = check["response"]["outcomes"]["QUALIFIED"]
    if response_result["lower95"] <= 0 or campaign.remaining() < 450:
        write(root / "extra-result.json", dict(status="deeper-undercut-screen-complete", response=check))
        return
    challenge = campaign.stage("deeper-undercut-crossplay", {"policy":champion},
                               512, ECOLOGIES[-1:], paired=True, rival=responder, rival_offsets=(1,))
    write(root / "extra-result.json", dict(status="deeper-undercut-crossplay-complete",
        response=check, comparison=challenge))
    if campaign.remaining() < 650:
        return
    mixed = response_path.parent.parent / "mixed-result.json"
    if not mixed.is_file():
        return
    alternative = read(mixed)
    if not alternative.get("passed"):
        return
    wider = alternative["audit"]["selected"]["config"]
    if wider == champion:
        return
    direct = campaign.stage("deeper-undercut-direct", {"wider":wider}, 384,
                            ECOLOGIES[-1:], rival=responder, rival_offsets=(1,), baseline=champion)
    write(root / "deeper-undercut-direct/result.json", dict(status="direct-comparison-complete", results=direct))



if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--initial", type=Path, required=True)
    args = parser.parse_args()
    main(args.root.resolve(), args.initial.resolve())
