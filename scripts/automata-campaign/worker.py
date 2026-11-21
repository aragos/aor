"""Execute or recover one content-addressed JVM shard on either campaign host."""

import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time


def canonical(data):
    return json.dumps(data, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def identity(data):
    return hashlib.sha256(canonical(data)).hexdigest()


def atomic(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as out:
        out.write(data)
        out.flush()
        os.fsync(out.fileno())
    os.replace(out.name, path)


def execute(root, job, host):
    root = Path(root).resolve()
    bundle = root / "bundle"
    key = identity(job)
    directory = root / "jobs" / key
    directory.mkdir(parents=True, exist_ok=True)
    receipt = directory / "receipt.gz"
    with (directory / "worker.lock").open("a+") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if receipt.exists():
            return receipt
        manifest = json.loads((bundle / "manifest.json").read_bytes())
        if identity(manifest) != job["bundle"]:
            raise ValueError("The requested job identifies a different campaign package.")
        for name, digest in manifest.items():
            if name.startswith("browser/"):
                continue
            if hashlib.sha256((bundle / name).read_bytes()).hexdigest() != digest:
                raise ValueError(f"Campaign package checksum mismatch for {name}")
        workers, cores, floor = {"mac": (6, 10, 10), "callisto": (10, 16, 20)}[host]
        if shutil.disk_usage(root).free < floor * 1024**3:
            raise OSError(f"{host} has less than its {floor} GiB disk reserve.")
        remaining = int(job["until"] - time.time())
        if remaining <= 0:
            raise TimeoutError("The campaign allocation deadline passed.")
        atomic(directory / "spec.json", canonical(dict(job, seconds=min(remaining, 43200))))
        with (directory / "java.log").open("ab") as log:
            subprocess.run(
                [
                    "java",
                    "-Xmx6g",
                    f"-XX:ActiveProcessorCount={workers+2}",
                    "-XX:+UseParallelGC",
                    "-XX:ParallelGCThreads=2",
                    "-cp",
                    str(bundle / "lib" / "*"),
                    "dev.aragos.aor.training.TrainingMainKt",
                    "checkpoint-bank",
                    str(bundle / "assets"),
                    str(directory),
                    str(directory / "spec.json"),
                    str(workers),
                ],
                check=True,
                timeout=min(remaining, 43200) + 15,
                env={**os.environ, "AOR_TRAINING_MACHINE_CPUS": str(cores)},
                stdout=log,
                stderr=subprocess.STDOUT,
            )
        raw = directory / "rows.json"
        rows = json.loads(raw.read_bytes())
        if (
            not rows
            or len(rows) != len({r["seed"] for r in rows})
            or any(r["seed"] >= 0 for r in rows)
        ):
            raise ValueError("A shard requires unique negative-domain world seeds.")
        atomic(receipt, gzip.compress(canonical(dict(job=key, host=host, rows=rows)), mtime=0))
        raw.unlink()
    return receipt


if __name__ == "__main__":
    spec = json.load(sys.stdin)
    result = execute(Path(sys.argv[1]), spec, sys.argv[2])
    sys.stdout.buffer.write(result.read_bytes())
