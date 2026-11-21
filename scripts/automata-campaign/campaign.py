"""Prepare, smoke-test, or resume the two-host current-rules Hard league."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import fcntl
import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import random
import shlex
import shutil
import subprocess
import sys
import threading
import time

from scores import estimate, review
from worker import atomic, canonical, execute, identity

HERE = Path(__file__).resolve().parent
COUNTS = {3: 64, 4: 64, 5: 64, 6: 56}
FILES = {
    3: "three-player-continuation-model.json",
    4: "continuation-model.json",
    5: "continuation-model.json",
    6: "broad-model.json",
}
BASE = dict(markerScale=1.0, commodityScale=1.0, earlyShift=0.0, expansion=[])
GAME_LIMIT = 5_000_000
DISCOVERY_LIMIT = 4_400_000


def load(path):
    return json.loads(path.read_bytes())


def save(path, data):
    atomic(path, canonical(data))


def remote(args, command, data=None, timeout=None):
    return subprocess.run(
        [
            "ssh",
            "-o",
            "BatchMode=yes",
            "-o",
            "ConnectTimeout=8",
            "-o",
            "ServerAliveInterval=20",
            "-o",
            "ServerAliveCountMax=3",
            args.callisto,
            shlex.join(list(map(str, command))),
        ],
        input=data,
        stdout=subprocess.PIPE,
        check=True,
        timeout=timeout,
    ).stdout


def prepare(args):
    project = HERE.parent.parent
    root = args.output
    root.mkdir(parents=True, exist_ok=True)
    bundle = root / "bundle"
    if not bundle.exists():
        from tempfile import TemporaryDirectory

        with TemporaryDirectory(dir=root) as temp:
            staged = Path(temp) / "bundle"
            shutil.copytree(project / "training/build/install/training/lib", staged / "lib")
            shutil.copytree(
                project / "web/public/assets/automata/aor-hard-2026-11-20-1", staged / "assets"
            )
            for suffix in ("*.py", "*.java", "*.mjs"):
                for source in HERE.glob(suffix):
                    shutil.copyfile(source, staged / source.name)
            reference = args.reference_bundle
            if reference is None:
                shutil.copytree(project / "server/build/install/server/lib", staged / "browser/lib")
                shutil.copytree(
                    project / "ai/build/dist/js/productionLibrary",
                    staged / "browser/js",
                    ignore=shutil.ignore_patterns("*.map"),
                )
            else:
                reference_manifest = load(reference / "manifest.json")
                for name, digest in reference_manifest.items():
                    if not name.startswith(("lib/", "browser/")):
                        continue
                    contents = (reference / name).read_bytes()
                    if hashlib.sha256(contents).hexdigest() != digest:
                        raise ValueError(f"Reference package integrity failure for {name}.")
                    target = staged / ("reference" if name.startswith("lib/") else "") / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(contents)
                for name in (
                    "reference/lib/core-jvm.jar",
                    "reference/lib/training.jar",
                    "browser/js",
                    "browser/lib",
                ):
                    if not (staged / name).exists():
                        raise ValueError(f"The release reference package is missing {name}.")
                save(staged / "reference.json", {"bundle": identity(reference_manifest)})
            save(
                staged / "starting.json",
                {str(c): "market" if c in args.market else "shipped" for c in COUNTS},
            )
            manifest = {
                p.relative_to(staged).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                for p in sorted(staged.rglob("*"))
                if p.is_file()
            }
            save(staged / "manifest.json", manifest)
            staged.rename(bundle)
    manifest = load(bundle / "manifest.json")
    for name, digest in manifest.items():
        if hashlib.sha256((bundle / name).read_bytes()).hexdigest() != digest:
            raise ValueError(f"Package integrity failure for {name}.")
    if args.action == "prepare" and set(args.market) != {
        int(c) for c, s in load(bundle / "starting.json").items() if s == "market"
    }:
        raise ValueError(
            "The prepared package has a different starting roster. Choose a fresh output directory."
        )
    if args.reference_bundle is not None:
        expected_reference = identity(load(args.reference_bundle / "manifest.json"))
        if (
            "reference.json" not in manifest
            or load(bundle / "reference.json")["bundle"] != expected_reference
        ):
            raise ValueError("The prepared league has a different release reference.")
    args.remote_root = f"{args.remote_base.rstrip('/')}/{identity(manifest)[:20]}/{root.name}"
    try:
        remote(args, ["mkdir", "-p", args.remote_root + "/bundle"])
        subprocess.run(
            [
                "rsync",
                "-az",
                "--exclude=browser/",
                "--exclude=__pycache__/",
                str(bundle) + "/",
                f"{args.callisto}:{args.remote_root}/bundle/",
            ],
            check=True,
        )
    except (OSError, subprocess.SubprocessError):
        if args.action == "prepare":
            raise
        print(
            "Callisto staging is unavailable; local workers can recover eligible shards.",
            file=sys.stderr,
        )
    return identity(manifest)


class ShardUnavailable(Exception):
    pass


class BudgetExpired(Exception):
    pass


class Campaign:
    def __init__(self, args, bundle):
        self.args = args
        self.root = args.output
        self.pilot = args.action == "pilot"
        self.bundle = bundle
        path = self.root / "state.json"
        if path.exists():
            self.state = load(path)
            if self.state["bundle"] != bundle or self.state["pilot"] != self.pilot:
                raise ValueError("The saved state belongs to a different campaign.")
        else:
            self.state = dict(
                start=time.time(), bundle=bundle, pilot=self.pilot, charged=0, actual=0, attempts=0
            )
            save(path, self.state)
        self.base = load(self.root / "bundle/starting.json")
        self.guard = threading.Lock()
        self.locks = {host: threading.Lock() for host in ("mac", "callisto")}

    def bank(
        self,
        count,
        phase,
        first,
        repeats,
        control,
        candidate,
        adopter="market",
        futures=0,
        final=False,
    ):
        until = int(self.state["start"] + (3600 if self.pilot else (7 if final else 6) * 86400))
        # The small pipeline trial and the production league must never share worlds.
        if not self.pilot:
            first += 10_000
        number = 2 if self.pilot else COUNTS[count]
        common = dict(
            bundle=self.bundle,
            count=count,
            control=control,
            candidate=candidate,
            adopter=adopter,
            futures=futures,
            final=final,
            until=until,
            pilot=self.pilot,
            phase=phase,
        )
        # One shard per host even in the pilot. Host assignment changes neither seats nor chance.
        chunks = []
        step = min(2 if futures else 4, math.ceil(repeats / 2))
        for offset in range(0, repeats, step):
            chunks.append(dict(common, first=first + offset, repeats=min(step, repeats - offset)))

        def run(job, primary):
            key = identity(job)
            cached = self.root / "receipts" / (key + ".gz")
            expected = number * (4 if final else 2) * job["repeats"]

            def validate(data):
                result = json.loads(gzip.decompress(data))
                rows = result["rows"]
                if (
                    result["job"] != key
                    or len(rows) != expected
                    or len({r["seed"] for r in rows}) != expected
                ):
                    raise ValueError("Incomplete or mismatched shard receipt.")
                return rows

            if cached.exists():
                return validate(cached.read_bytes())
            hosts = (primary, "callisto" if primary == "mac" else "mac")
            maximum = expected * (4 * futures if futures else 2)
            for attempt in range(3):
                host = hosts[attempt % 2]
                with self.locks[host]:
                    with self.guard:
                        limit = GAME_LIMIT if final else DISCOVERY_LIMIT
                        if time.time() >= until or self.state["charged"] + maximum > limit:
                            raise BudgetExpired()
                        self.state["charged"] += maximum
                        self.state["attempts"] += 1
                        save(self.root / "state.json", self.state)
                    try:
                        if host == "mac":
                            data = execute(self.root, job, host).read_bytes()
                        else:
                            data = remote(
                                self.args,
                                [
                                    "python3",
                                    self.args.remote_root + "/bundle/worker.py",
                                    self.args.remote_root,
                                    host,
                                ],
                                canonical(job),
                                max(10, until - time.time() + 30),
                            )
                        rows = validate(data)
                    except (OSError, subprocess.SubprocessError, ValueError) as failure:
                        if attempt == 2:
                            raise ShardUnavailable(
                                f"Shard {key} failed across both hosts: {failure}"
                            ) from failure
                        continue
                    actual = sum(r.get("games", 2) for r in rows)
                    with self.guard:
                        atomic(cached, data)
                        self.state["charged"] -= maximum - actual
                        self.state["actual"] += actual
                        save(self.root / "state.json", self.state)
                    return rows

        # Let each host claim the next available shard. Restore seed-block order before fitting.
        pending = iter(enumerate(chunks))

        def drain(host):
            completed = []
            while True:
                with self.guard:
                    item = next(pending, None)
                if item is None:
                    return completed
                index, job = item
                completed.append((index, run(job, host)))

        with ThreadPoolExecutor(max_workers=2) as pool:
            completed = [item for host in pool.map(drain, self.locks) for item in host]
        rows = [row for _, shard in sorted(completed) for row in shard]
        if len({r["seed"] for r in rows}) != len(rows):
            raise ValueError("Repeated world across shards in one comparison.")
        return rows

    def fit(self, rows):
        payload = canonical(rows)

        def cpu():
            return subprocess.run(
                [self.args.cpu_python, self.root / "bundle/fit.py", "cpu"],
                input=payload,
                stdout=subprocess.PIPE,
                check=True,
                timeout=900,
            ).stdout

        def cuda():
            return remote(
                self.args,
                [self.args.gpu_python, self.args.remote_root + "/bundle/fit.py", "cuda"],
                payload,
                900,
            )

        first, fallback = (cpu, cuda) if self.args.fit_first == "cpu" else (cuda, cpu)
        try:
            return json.loads(first())
        except (OSError, subprocess.SubprocessError):
            return json.loads(fallback())

    def export(self, count, policy, prefix=""):
        asset = load(self.root / "bundle/assets" / FILES[count])
        if isinstance(policy, dict):
            asset["marketProfile"] = policy
        path = self.root / "checkpoints" / f"{prefix}{count}.json"
        atomic(path, canonical(asset))
        return dict(
            file=str(path.relative_to(self.root)),
            sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
            market=policy != "shipped",
            bytes=path.stat().st_size,
        )

    def browser(self, count, asset):
        bundle = self.root / "bundle"
        return json.loads(
            subprocess.run(
                [
                    "node",
                    bundle / "browser-parity.mjs",
                    bundle,
                    str(count),
                    bundle / "assets" / FILES[count],
                    self.root / asset["file"],
                    str(asset["market"]).lower(),
                ],
                stdout=subprocess.PIPE,
                check=True,
                timeout=600,
            ).stdout
        )

    def generation(self, count, gen, champ):
        pilot = self.pilot
        profile = champ if isinstance(champ, dict) else BASE
        candidates = []
        rng = random.Random(20261121 + gen * 17 + count)
        for _ in range(1 if pilot else 5):
            candidates.append(
                dict(
                    profile,
                    markerScale=round(
                        min(2, max(0.25, profile["markerScale"] + rng.uniform(-0.5, 0.5))), 4
                    ),
                    commodityScale=round(
                        min(2, max(0.25, profile["commodityScale"] + rng.uniform(-0.4, 0.4))), 4
                    ),
                    earlyShift=round(
                        min(0.25, max(-0.25, profile["earlyShift"] + rng.uniform(-0.13, 0.13))), 4
                    ),
                )
            )
        report = dict(count=count, generation=gen, incumbent=champ)
        # Labels measure deviations from the exact continuation policy that will deploy the correction.
        teacher = "market" if champ == "shipped" else champ
        if gen % 2 == 0:
            reps = 2 if pilot else math.ceil(4096 / (2 * COUNTS[count]))
            rows = self.bank(
                count,
                "rollout",
                400000 + gen * 128,
                reps,
                teacher,
                teacher,
                teacher,
                1 if pilot else 4,
            )
            fit = self.fit(rows)
            report["fit"] = fit
            if fit["admitted"]:
                candidates.append(dict(profile, expansion=fit["expansion"]))
            if pilot and "expansion" in fit:
                report["trialExport"] = self.export(
                    count, dict(profile, expansion=fit["expansion"]), prefix="pilot-fit-"
                )
        screen = []
        for proposal in candidates:
            rows = self.bank(
                count,
                "screen",
                10000 + gen * 128,
                2 if pilot else math.ceil(512 / (2 * COUNTS[count])),
                champ,
                proposal,
                teacher,
            )
            screen.append(dict(profile=proposal, estimate=estimate(rows)))
        screen.sort(key=lambda item: item["estimate"]["gain"], reverse=True)
        report["screen"] = screen
        confirmed = []
        for item in screen[:2]:
            if item["estimate"]["gain"] <= 0 and not pilot:
                continue
            proposal = item["profile"]
            rows = self.bank(
                count,
                "confirm",
                200000 + gen * 128,
                2 if pilot else math.ceil(4096 / (2 * COUNTS[count])),
                champ,
                proposal,
                teacher,
            )
            confirmed.append(dict(profile=proposal, **review(rows, False)))
        report["confirmed"] = confirmed
        winner = next(
            (
                r["profile"]
                for r in sorted(confirmed, key=lambda r: r["overall"]["gain"], reverse=True)
                if r["qualified"]
            ),
            champ,
        )
        report["winner"] = winner
        return report

    def run(self):
        counts = list(COUNTS)
        champs = {c: self.base[str(c)] for c in counts}
        stale = {c: 0 for c in counts}
        frozen = (self.root / "finalists.json").exists()
        for gen in range(0 if frozen else (1 if self.pilot else 64)):
            try:
                for count in counts:
                    if stale[count] >= 6:
                        continue
                    path = self.root / "rounds" / f"{gen}-{count}.json"
                    try:
                        report = (
                            load(path)
                            if path.exists()
                            else self.generation(count, gen, champs[count])
                        )
                    except ShardUnavailable as failure:
                        report = dict(
                            count=count,
                            generation=gen,
                            winner=champs[count],
                            incomplete=str(failure),
                        )
                    save(path, report)
                    stale[count] = stale[count] + 1 if champs[count] == report["winner"] else 0
                    champs[count] = report["winner"]
                if all(v >= 6 for v in stale.values()):
                    break
            except BudgetExpired:
                break
        # Persist the freeze before requesting any sealed comparisons.
        path = self.root / "finalists.json"
        if path.exists():
            champs = {int(k): v for k, v in load(path).items()}
        else:
            save(path, champs)
        for count, champ in champs.items():
            path = self.root / "final" / f"{count}.json"
            if path.exists():
                continue
            starting = self.base[str(count)]
            report = {}
            for i, control in enumerate(dict.fromkeys(["shipped", starting])):
                if champ == control and not self.pilot:
                    continue
                reps = 2 if self.pilot else math.ceil(16384 / (4 * COUNTS[count]))
                try:
                    rows = self.bank(
                        count,
                        "final",
                        800000 + i * 1000,
                        reps,
                        control,
                        champ,
                        starting,
                        final=True,
                    )
                except ShardUnavailable as failure:
                    report[control] = dict(qualified=False, incomplete=str(failure))
                    break
                except BudgetExpired:
                    report[control] = dict(
                        qualified=False, incomplete="Campaign budget or deadline exhausted"
                    )
                    break
                report[control] = review(rows, control == "shipped")
            accepted = bool(report) and all(r["qualified"] for r in report.values())
            candidate = self.export(count, champ, prefix="finalist-")
            try:
                parity = self.browser(count, candidate)
            except (OSError, subprocess.SubprocessError) as failure:
                parity = dict(withinBudget=False, error=str(failure))
            if self.pilot:
                trial = self.root / "rounds" / f"0-{count}.json"
                asset = load(trial).get("trialExport")
                if asset:
                    parity["rolloutExport"] = self.browser(count, asset)
            accepted = accepted and candidate["bytes"] <= 150000 and parity["withinBudget"]
            save(
                path,
                dict(
                    comparisons=report,
                    browser=parity,
                    qualified=accepted,
                    checkpoint=self.export(count, champ if accepted else starting),
                ),
            )
        print(json.dumps(self.state, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "pilot", "run"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--reference-bundle", type=Path,
        help="Frozen release-matched evaluator and browser package for a new campaign.",
    )
    parser.add_argument("--callisto", default="aragos@192.168.86.44")
    parser.add_argument("--remote-base", default="/home/aragos/aor-worker/current-rules-leagues")
    parser.add_argument(
        "--gpu-python",
        default="/home/aragos/aor-worker/venvs/purchase-value-cuda-20261111/bin/python",
    )
    parser.add_argument("--fit-first", choices=("cpu", "cuda"), default="cpu")
    cpu_venv = Path.home() / "Projects/aor/.aor-training/venvs/purchase-cpu-20261111/bin/python"
    parser.add_argument(
        "--cpu-python", default=str(cpu_venv) if cpu_venv.is_file() else sys.executable
    )
    parser.add_argument("--market", type=int, nargs="*", choices=sorted(COUNTS), default=[])
    args = parser.parse_args()
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    with (args.output / "coordinator.lock").open("a+") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        digest = prepare(args)
        print("Package", digest, flush=True)
        if args.action != "prepare":
            pinned = args.output / "bundle" / "campaign.py"
            if Path(__file__).resolve() != pinned:
                # flock is not inherited across exec. The packaged process acquires its own lock.
                os.execv(sys.executable, [sys.executable, str(pinned), *sys.argv[1:]])
            Campaign(args, digest).run()


if __name__ == "__main__":
    main()
