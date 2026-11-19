"""Build a local initiative preview from a complete, independently rechecked fresh audit."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import statistics
import math


ECOSYSTEMS = ("QUALIFIED", "REVEALED_UNDERCUT", "REVEALED_TIE_UNDERCUT")
PAIRS = 768
BASE_SHA256 = "b812daf05170b3a41ee5164667dcb24877b6ced582dc9475d89e18e16c815f81"
TRAINING_SHA256 = "a179ea273c8153a5cd9b61f4f4bd2ec7851b6ca1d62f1764c34105637bb73fed"


def require(value, message):
    if not value:
        raise ValueError(message)


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def canonical(value):
    return json.dumps(value,sort_keys=True,separators=(",",":"),allow_nan=False).encode()


def read(path):
    return json.loads(path.read_bytes())


def actual_win(role, seat):
    scores = role["scores"]
    require(len(scores) == 4 and {row["player"] for row in scores} == {"p1","p2","p3","p4"},
            "An initiative audit requires the four authoritative seat scores.")
    winners = {row["player"] for row in scores if row["winner"] is True}
    win = 1.0 / len(winners) if f"p{seat+1}" in winners else 0.0
    require(win == role["wins"][seat], "The saved winner share must match the authority score.")
    return win


def comparison(folder, index, mode, bank, method_path, policy):
    worlds = {}
    evidence = {}
    replays = set()
    for start in range(0,PAIRS,96):
        name = f"winner-{index}-{start}"
        path = folder / f"{name}.json"
        config = read(path)
        shard = folder / name
        done = read(shard / "finished.json")
        require(config["protocolSha256"] == sha(method_path) and config["seeds"] == bank and
                config["initiative"] == policy and config["opponentStyle"] == mode and
                config["globalStart"] == start and config["count"] == 96 and
                config["candidateOnly"] is False and done["sourceSha256"] == sha(path) and
                done["complete"] is True and done["roles"] == ["CANDIDATE","CONTROL"],
                "An initiative shard must bind the original declared candidate, comparison and seed range.")
        require(all(sha(shard/name) == digest for name,digest in done["files"].items()) and
                done["attemptedGames"] == done["completedGames"] == done["scientificTerminalExecutions"],
                "A complete initiative shard must independently match its result receipt.")
        for name in ("prepared.json","worlds.jsonl"):
            evidence[f"{start}/{name}"] = sha(shard/name)
        rows = [json.loads(line) for line in (shard/"worlds.jsonl").read_text().splitlines()]
        require(sorted(row["index"] for row in rows) == list(range(start,start+96)),
                "Every planned initiative world must be present exactly once.")
        for row in rows:
            i = row["index"]
            require(row["seed"] == bank[i] and row["seat"] == i%4 and
                    row["scienceSha256"] == done["scienceSha256"] and row["opponentStyle"] == mode,
                    "Initiative evidence has a mismatched world identity or role.")
            worlds[i] = dict(seed=row["seed"],candidate=actual_win(row["roles"]["CANDIDATE"], row["seat"]),
                             control=actual_win(row["roles"]["CONTROL"], row["seat"]))
        file = shard / "reproductions.jsonl"
        if file.exists():
            evidence[f"{start}/reproductions.jsonl"] = sha(file)
            for line in file.read_text().splitlines():
                replay = json.loads(line)
                identity = (replay["index"],replay["role"])
                require(replay["matched"] and replay["canonicalSha256"] and identity not in replays,
                        "A prescribed initiative game reproduction must match exactly once.")
                replays.add(identity)
    require(replays == {(i,role) for i in (0,PAIRS-1) for role in ("CANDIDATE","CONTROL")},
            "The audit must retain all prescribed complete-game replays.")
    rows = [worlds[i] for i in range(PAIRS)]
    gains = [row["candidate"]-row["control"] for row in rows]
    lower = statistics.mean(gains) - 1.96*statistics.stdev(gains)/math.sqrt(PAIRS)
    require(lower > (-.075 if mode == "QUALIFIED" else 0),
            f"The independently validated lower bound did not clear its gate for {mode}.")
    return dict(worlds=rows,evidenceSha256=hashlib.sha256(canonical(evidence)).hexdigest())


def export(lab, incumbent_asset, output):
    method_path = lab / "fresh-audit/method.json"
    method = read(method_path)
    result = read(lab / "result.json")
    require(result["status"] == "fresh-audit-complete" and result["passed"] is True and
            method["runtime"]["training.jar"] == TRAINING_SHA256 and
            sha(lab/"lib/training.jar") == TRAINING_SHA256 and
            sha(incumbent_asset/"manifest.json") == BASE_SHA256,
            "Preview export requires the successful exact initiative audit and incumbent.")
    policy = method["configs"]["winner"]
    require(method["paired"] is True and method["replayIndices"] == [0,PAIRS-1] and
            policy == result["comparison"]["config"], "The exported profile differs from the frozen audit.")
    banks = method["banks"]
    all_seeds = [seed for bank in banks.values() for seed in bank]
    require(set(banks) == set(ECOSYSTEMS) and len(all_seeds) == len(set(all_seeds)) == PAIRS*3 and
            all(len(bank)==PAIRS for bank in banks.values()),
            "Preview admission requires distinct complete original opponent banks.")
    comparisons = {mode:comparison(method_path.parent,i,mode,banks[mode],method_path,policy)
                   for i,mode in enumerate(ECOSYSTEMS)}
    output.mkdir()
    shutil.copytree(incumbent_asset,output/"incumbent")
    qualification = output / "qualification.json"
    qualification.write_bytes(canonical(dict(kind="public-initiative-qualification-1",
        basePreviewSha256=BASE_SHA256,nativeJarSha256=TRAINING_SHA256,
        auditMethodSha256=sha(method_path),initiative=policy,comparisons=comparisons)))
    manifest = output / "manifest.json"
    manifest.write_bytes(canonical(dict(kind="coordinated-initiative-preview-1",schemaVersion=1,
        basePreview=dict(path="incumbent/manifest.json",sha256=BASE_SHA256),
        qualification=dict(path="qualification.json",sha256=sha(qualification)))))
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lab",type=Path,required=True)
    parser.add_argument("--incumbent-asset",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    args = parser.parse_args()
    print(export(args.lab,args.incumbent_asset,args.output))
