"""Execute the predeclared collection and reproductions owned by one pilot host."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read(path):
    return json.loads(path.read_bytes())


def collect(root, host, java):
    manifest = read(root / "execution.json")
    protocol = root / "protocol.json"
    if digest(protocol) != manifest["protocolSha256"]:
        raise ValueError("The pilot protocol changed after freezing.")
    plan = read(protocol)
    deadline = plan["globalDeadlineEpoch"]
    limits = plan["hosts"][host]
    if shutil.disk_usage(root).free < limits["minimumFreeBytes"]:
        raise RuntimeError("Free disk space is below the pilot floor.")
    manifest_lib = manifest["runtime"]
    lib = root / "lib"
    if {jar.name for jar in lib.glob("*.jar")} != set(manifest_lib):
        raise ValueError("The host runtime jar inventory changed.")
    for name, expected in manifest_lib.items():
        if digest(lib / name) != expected:
            raise ValueError(f"The frozen runtime differs for {name}.")

    workers = limits["workers"]
    environment = {k: v for k, v in os.environ.items()
                   if k not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")}
    environment["AOR_TRAINING_MACHINE_CPUS"] = str(os.cpu_count() or 1)
    java_command = [
        java, "-Xms128m", f"-Xmx{limits['heapMiB']}m", f"-XX:ActiveProcessorCount={workers}",
        "-cp", str(lib / "*"), "dev.aragos.aor.training.TrainingMainKt",
    ]

    def invoke(stage, source, output, log, options):
        remaining = deadline - time.time()
        if remaining < 2:
            raise RuntimeError("The original pilot deadline has expired.")
        command = java_command + [
            "--mode", f"token-anchored-paired-{stage}", "--source", str(source),
            "--output", str(output), "--seconds", str(min(21600, int(remaining))),
        ] + options
        with log.open("x") as stream:
            process = subprocess.Popen(command, stdout=stream, stderr=subprocess.STDOUT,
                                       start_new_session=True, env=environment)
            try:
                code = process.wait(timeout=remaining)
                if code != 0:
                    raise RuntimeError(f"Native {stage} failed with exit {code}. See {log}.")
            finally:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()

    for row in manifest["chunks"]:
        if row["host"] != host:
            continue
        source = root / row["source"]
        if digest(source) != row["sha256"]:
            raise ValueError("The original chunk source changed.")
        request = read(source)
        chunk = root / f"chunk-{row['index']}"
        chunk.mkdir()
        invoke("collect", source, chunk / "native", chunk / "collect.log",
               ["--workers", str(workers)])
        finished = read(chunk / "native" / "finished.json")
        if not finished["complete"] or finished["backboneGames"] != request["count"]:
            raise ValueError("The native chunk did not retain its exact original collection.")
        owned = range(request["globalStart"], request["globalStart"] + request["count"])
        for index in request["replayIndices"]:
            if index in owned:
                label = f"replay-{index}"
                invoke("reproduce", source, chunk / label, chunk / f"{label}.log",
                       ["--index", str(index), "--original", str(chunk / "native")])
        print(json.dumps({"chunk": row["index"], "host": host,
                          "ready": finished["readyRoots"],
                          "terminalGames": finished["scientificTerminalGames"]}), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--host", choices=("MAC", "DESKTOP"), required=True)
    parser.add_argument("--java", required=True)
    args = parser.parse_args()
    signal.signal(signal.SIGTERM, lambda *_: signal.raise_signal(signal.SIGINT))
    collect(args.root.resolve(), args.host, args.java)
