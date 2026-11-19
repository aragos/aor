"""Recheck both fresh mixed-table comparisons and export the local preview."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil

from initiative_campaign import Campaign, interval
from initiative_preview_export import BASE_SHA256, actual_win, canonical, read, require, sha


PREVIOUS_MANIFEST = "539efa092255da39865aa6191a60818a7248b4dd0e09a078901bc8a2f0dff9f4"
TRAINING_SHA256 = "eb2690aeced7c9c47eaaebbc7016c5ae1aae19c5612f67d3fa8e9047b30089b2"
CASES = {
    "mixed-table": ("QUALIFIED", "b1917e93da110635180c6b2ef5e6bafed3f569f9cd78e6fccfee1acba6b22b1b"),
    "adaptive-table": (
        "REVEALED_UNDERCUT", "74f0ce43d29602dd5d8654a84d3f2007ced69196cbc46e48a5a19179c4a24e6d"
    ),
}
WORLD_COUNT = 768
SHARD_SIZE = 128


def collect(folder, protocol_hash, method, mode, bank, name, policy):
    scores, replays, evidence = {}, set(), {}
    for start in range(0, WORLD_COUNT, SHARD_SIZE):
        shard_name = f"{name}-0-{start}"
        source = folder / f"{shard_name}.json"
        shard = folder / shard_name
        request = read(source)
        require(
            request["protocolSha256"] == protocol_hash
            and request["seeds"] == bank
            and request["globalStart"] == start
            and request["count"] == SHARD_SIZE
            and request["replayIndices"] == [0, WORLD_COUNT - 1]
            and request["initiative"] == policy
            and request["opponentStyle"] == mode
            and request["candidateOnly"] is True
            and request["initiativeLineup"] == method["initiativeLineup"],
            "Every preview shard must match the frozen table, candidate and world range.",
        )
        if mode == "REVEALED_UNDERCUT":
            expected = method["counterVariants"][name]
            require(request["adaptiveOpponent"] == dict(
                    expected, kind="public-token-history-counter-1", targetedRivalCount=1),
                    "The adaptive opponent must use the trained opening and history setting.")
        else:
            require("adaptiveOpponent" not in request, "The mixed table uses fixed opponents.")
        Campaign.validate_result(shard, source)
        receipt = read(shard / "finished.json")
        require(receipt["roles"] == ["CANDIDATE"], "Every preview shard must have one evaluated role.")
        for filename, digest in receipt["files"].items():
            evidence[f"{name}/{start}/{filename}"] = digest
        for line in (shard / "worlds.jsonl").read_text().splitlines():
            row = json.loads(line)
            index = row["index"]
            require(row["seat"] == index % 4 and row["opponentStyle"] == mode and index not in scores,
                    "Every score must belong to its declared table, world and seat.")
            scores[index] = actual_win(row["roles"]["CANDIDATE"], row["seat"])
        replay_file = shard / "reproductions.jsonl"
        if replay_file.is_file():
            for line in replay_file.read_text().splitlines():
                replay = json.loads(line)
                require(replay["role"] == "CANDIDATE" and replay["matched"] and replay["canonicalSha256"]
                        and replay["index"] not in replays, "Every prescribed preview replay must match.")
                require(actual_win(replay["terminal"], replay["index"] % 4) == scores[replay["index"]],
                        "Each preview replay must reproduce the saved score.")
                replays.add(replay["index"])
    require(set(scores) == set(range(WORLD_COUNT)) and replays == {0, WORLD_COUNT - 1},
            "The preview requires a complete world bank and every prescribed replay.")
    return scores, evidence


def export(lab, prior, output):
    require(sha(prior / "manifest.json") == PREVIOUS_MANIFEST and
            sha(prior / "incumbent/manifest.json") == BASE_SHA256,
            "The preview must compare with the previously qualified live policy.")
    previous = read(prior / "qualification.json")["initiative"]
    result = read(lab / "table-fresh.json")
    name, policy = result["selected"], result["config"]
    require(result["passed"] is True and sha(lab / "lib/training.jar") == TRAINING_SHA256,
            "The mixed preview requires successful fresh evaluation on the exact JVM runtime.")
    comparisons, seeds = {}, set()
    for table, (mode, protocol_hash) in CASES.items():
        folder = lab / f"table-fresh-{table}"
        method_file = folder / "method.json"
        require(sha(method_file) == protocol_hash, "The declared fresh audit methods must match.")
        method = read(method_file)
        require(method["baseline"] == previous and method["configs"] == {name: policy} and
                method["runtime"]["training.jar"] == TRAINING_SHA256 and method["paired"] is False,
                "Both tables must compare the tested mixture with the previously qualified policy.")
        bank = method["banks"][mode]
        require(len(bank) == len(set(bank)) == WORLD_COUNT and seeds.isdisjoint(bank),
                "The two audits must have independent complete world banks.")
        seeds.update(bank)
        candidate, candidate_evidence = collect(folder, protocol_hash, method, mode, bank, name, policy)
        control, control_evidence = collect(folder, protocol_hash, method, mode, bank, "control", previous)
        difference = interval([candidate[index] - control[index] for index in range(WORLD_COUNT)])
        require(difference["lower95"] > 0 and difference["mean"] == result["outcomes"][name][table]["mean"],
                "Recomputed mixed table results must match the qualifying reported win gain.")
        comparisons[table] = {
            "worlds": [{"seed": seed, "candidate": candidate[index], "control": control[index]}
                       for index, seed in enumerate(bank)],
            "evidenceSha256": hashlib.sha256(canonical(candidate_evidence | control_evidence)).hexdigest(),
        }
    method_hash = hashlib.sha256("".join(f"{digest}\n" for _, digest in CASES.values()).encode()).hexdigest()
    output.mkdir()
    shutil.copytree(prior / "incumbent", output / "incumbent")
    qualification = output / "qualification.json"
    qualification.write_bytes(canonical(dict(kind="public-initiative-mixed-qualification-1",
        basePreviewSha256=BASE_SHA256, nativeJarSha256=TRAINING_SHA256, auditMethodSha256=method_hash,
        initiative=policy, comparisons=comparisons)))
    manifest = output / "manifest.json"
    manifest.write_bytes(canonical(dict(kind="coordinated-initiative-preview-1", schemaVersion=1,
        basePreview=dict(path="incumbent/manifest.json", sha256=BASE_SHA256),
        qualification=dict(path="qualification.json", sha256=sha(qualification)))))
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lab", type=Path, required=True)
    parser.add_argument("--prior-asset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    print(export(arguments.lab, arguments.prior_asset, arguments.output))
