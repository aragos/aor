"""Lossless purchase rows: verify a durable archive before removing any original.

Batch receipts bind the archive and its immutable manifest. An interrupted batch
can restore rows without replacing existing evidence; completed batches need no
extraction. Legacy cold-storage manifests are verified against original receipts.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tarfile

import automata_experiments as experiments


read, sha, require = experiments.read, experiments.sha, experiments.require
MANIFEST = "collection-storage.json"
ARCHIVE = "collection-games.tar.gz"
BLOCK = 1024 * 1024


def stream_digest(stream):
    digest, size = hashlib.sha256(), 0
    for block in iter(lambda: stream.read(BLOCK), b""):
        digest.update(block)
        size += len(block)
    return digest.hexdigest(), size


def sync_directory(directory):
    descriptor = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def publish(path, write, expected=None):
    """Publish new evidence without overwriting; stale owned temporary bytes are disposable."""
    temporary = path.with_name(path.name + ".writing")
    require(not path.exists() and not temporary.is_symlink(), "Storage publication cannot replace evidence.")
    try:
        with temporary.open("wb") as stream:
            write(stream)
            stream.flush()
            os.fsync(stream.fileno())
        require(expected is None or sha(temporary) == expected, "Restored purchase row changed.")
        os.link(temporary, path)
        sync_directory(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


def verify_members(archive, expected, digest):
    require(archive.is_file() and not archive.is_symlink() and sha(archive) == digest,
            "Purchase archive checksum changed.")
    seen = set()
    with tarfile.open(archive, "r:gz") as source:
        for member in source:
            require(member.isfile() and member.name in expected and member.name not in seen,
                    "Purchase archive has an unexpected, duplicate or non-file member.")
            seen.add(member.name)
            with source.extractfile(member) as stream:
                actual, size = stream_digest(stream)
            require(actual == expected[member.name]["sha256"] and size == expected[member.name]["size"],
                    "Purchase archive member differs from its original row: " + member.name)
    require(seen == set(expected) and sha(archive) == digest, "Purchase archive coverage or bytes changed.")


def verify(batch):
    batch = Path(batch)
    manifest = read(batch / MANIFEST)
    collection = batch / "collection"
    require(manifest.get("schemaVersion") == 1 and manifest.get("kind") == "purchase-game-storage"
            and manifest.get("archive") == ARCHIVE, "Unsupported purchase storage manifest.")
    require(manifest["collectionSha256"] == sha(collection / "collection.json")
            and manifest["finishedSha256"] == sha(collection / "finished.json"),
            "Purchase storage collection identity changed.")
    hashes = read(collection / "finished.json")["gameSha256"]
    require(len(hashes) == read(collection / "collection.json")["games"]
            and set(manifest["files"]) == {f"game-{index}.json" for index in range(len(hashes))}
            and all(manifest["files"][f"game-{index}.json"]["sha256"] == digest
                    for index, digest in enumerate(hashes)), "Purchase storage differs from completed row hashes.")
    verify_members(batch / ARCHIVE, manifest["files"], manifest["archiveSha256"])
    return manifest


def archive(batch):
    batch = Path(batch)
    collection = batch / "collection"
    if not (batch / MANIFEST).exists():
        hashes = read(collection / "finished.json")["gameSha256"]
        files = {f"game-{index}.json": {"sha256": digest,
                 "size": (collection / f"game-{index}.json").stat().st_size}
                 for index, digest in enumerate(hashes)}
        require(len(hashes) == read(collection / "collection.json")["games"]
                and {path.name for path in collection.glob("game-*.json")} == set(files),
                "Archive requires every completed purchase row.")
        for name, item in files.items():
            path = collection / name
            require(path.is_file() and not path.is_symlink() and sha(path) == item["sha256"],
                    "Original purchase row differs from completion: " + name)
        target = batch / ARCHIVE
        if not target.exists():
            def write(stream):
                with tarfile.open(fileobj=stream, mode="w:gz", compresslevel=6) as destination:
                    for name in files:
                        destination.add(collection / name, arcname=name, recursive=False)
            publish(target, write)
        manifest = {"schemaVersion": 1, "kind": "purchase-game-storage", "archive": ARCHIVE,
                    "archiveSha256": sha(target), "collectionSha256": sha(collection / "collection.json"),
                    "finishedSha256": sha(collection / "finished.json"), "files": files}
        verify_members(target, files, manifest["archiveSha256"])
        publish(batch / MANIFEST, lambda stream: stream.write(
            (json.dumps(manifest, sort_keys=True, indent=2) + "\n").encode()))
    manifest = verify(batch)
    require({path.name for path in collection.glob("game-*.json")} <= set(manifest["files"]),
            "Unexpected raw purchase rows cannot be archived.")
    for name, item in manifest["files"].items():
        path = collection / name
        if path.exists():
            require(not path.is_symlink() and sha(path) == item["sha256"],
                    "Purchase row changed before archive removal: " + name)
            path.unlink()
    sync_directory(collection)
    for name in (ARCHIVE, MANIFEST):
        (batch / (name + ".writing")).unlink(missing_ok=True)
    return manifest


def restore(batch):
    batch = Path(batch)
    manifest = verify(batch)
    with tarfile.open(batch / ARCHIVE, "r:gz") as archive_file:
        for member in archive_file:
            target = batch / "collection" / member.name
            if target.exists():
                require(not target.is_symlink() and sha(target) == manifest["files"][member.name]["sha256"],
                        "Existing purchase row differs; restoration cannot overwrite evidence.")
            else:
                def write(stream):
                    with archive_file.extractfile(member) as source:
                        shutil.copyfileobj(source, stream, BLOCK)
                publish(target, write, manifest["files"][member.name]["sha256"])
            target.with_name(target.name + ".writing").unlink(missing_ok=True)


def archived_parent_rows(root, train_receipt):
    """Bind legacy external archives to immutable train/batch/collection receipts, without restoring."""
    root = Path(root)
    store = root / "storage-archives"
    if not (store / "index.json").exists():
        return {}, []
    index = read(store / "index.json")
    require(index["sourceRoot"] == str(root)
            and index["sourceTrainReceiptSha256"] == sha(root / "run/results/train.json"),
            "Parent storage index belongs to a different training receipt.")
    rows, evidence = {}, [store / "index.json"]
    for batch, record in index["archives"].items():
        require(re.fullmatch(r"batch-\d{2}", batch) is not None and record["manifest"] == batch + ".json",
                "Unsafe parent storage batch path.")
        manifest_path = store / record["manifest"]
        require(sha(manifest_path) == record["manifestSha256"], "Parent storage manifest checksum changed.")
        manifest = read(manifest_path)
        directory = root / "run/batches" / batch
        receipt_path = directory / "receipt.json"
        require(manifest["sourceRoot"] == str(root) and manifest["batch"] == batch
                and manifest["receiptPath"] == str(receipt_path)
                and manifest["receiptSha256"] == sha(receipt_path)
                == train_receipt["artifacts"][str(receipt_path.relative_to(root / "run"))]
                and manifest["archive"] == batch + ".tar.gz"
                and manifest["archiveSha256"] == record["archiveSha256"], "Parent storage receipt binding changed.")
        receipt = read(receipt_path)
        hashes = read(directory / "collection/finished.json")["gameSha256"]
        require(len(hashes) == read(directory / "collection/collection.json")["games"],
                "Parent archive count differs from collection metadata.")
        expected = {f"run/batches/{batch}/collection/game-{index}.json": digest
                    for index, digest in enumerate(hashes)}
        require(set(manifest["files"]) == set(expected), "Parent archive has unexpected row coverage.")
        for name, digest in expected.items():
            relative = str(Path(name).relative_to("run"))
            require(manifest["files"][name]["sha256"] == digest == train_receipt["artifacts"][relative]
                    == receipt["artifacts"][str(Path(name).relative_to("run/batches/" + batch))],
                    "Parent archive differs from immutable original row hashes.")
            rows[relative] = digest
        verify_members(store / manifest["archive"], manifest["files"], record["archiveSha256"])
        evidence.extend((manifest_path, store / manifest["archive"]))
    return rows, evidence


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("archive", "verify", "restore"))
    parser.add_argument("batch", type=Path)
    arguments = parser.parse_args()
    {"archive": archive, "verify": verify, "restore": restore}[arguments.mode](arguments.batch)
