"""Local-only whitelist export of an effective canonical engine history, never a server save."""
import argparse
import hashlib
import json
import os
from pathlib import Path


def digest(data):
    return hashlib.sha256(data).hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON field")
        result[key] = value
    return result


def sanitize(document, source_sha):
    if type(document.get("baseRevision")) is not int or document["baseRevision"] != 0 or \
            "previousArchive" not in document or document["previousArchive"] is not None:
        raise ValueError("A complete effective history without an archive prefix is required")
    original = document["header"]
    header = {key: original[key] for key in
              ("creatorId", "rulesVersion", "dataVersion", "drawRenaissanceFromTurn")}
    header["players"] = [{key: player[key] for key in ("id", "name")}
                         for player in original["players"]]
    if any(not isinstance(player[key], str) or not player[key]
           for player in header["players"] for key in ("id", "name")):
        raise ValueError("Engine player identities and names must be nonempty text")
    if any(not isinstance(header[key], str) or not header[key]
           for key in ("creatorId", "rulesVersion", "dataVersion")):
        raise ValueError("Engine header versions and creator must be nonempty text")
    if type(header["drawRenaissanceFromTurn"]) is not int:
        raise ValueError("Renaissance activation must be an integer")
    entries = []
    for revision in document["revisions"]:
        replacement = revision["replaceFrom"]
        if replacement is not None:
            if type(replacement) is not int or not 0 <= replacement <= len(entries):
                raise ValueError("Replacement index must address the preceding effective history")
            entries = entries[:replacement]
        values = revision["entries"]
        if not isinstance(values, list) or any(
                not isinstance(value, str) or not value or "\n" in value or "\r" in value
                for value in values):
            raise ValueError("Every canonical history entry must be one nonempty text line")
        entries.extend(values)
    if not entries:
        raise ValueError("A recorded history must contain entries")
    return {"schemaVersion": 1, "kind": "private-engine-history", "sourceSha256": source_sha,
            "header": header, "entries": entries}


def export(source, output, expected_sha):
    if source.resolve() == output.resolve():
        raise FileExistsError("The original save cannot be the sanitized output")
    original = source.read_bytes()
    if digest(original) != expected_sha:
        raise ValueError("Original source bytes differ from the approved source pin")
    document = json.loads(original, object_pairs_hook=unique_object)
    result = sanitize(document, expected_sha)
    output.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(output.parent, 0o700)
    with os.fdopen(os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
        json.dump(result, stream, sort_keys=True, indent=2)
        stream.write("\n")
    if source.read_bytes() != original:
        raise ValueError("The original source changed during sanitization")
    return {"sourceSha256": expected_sha, "historySha256": digest(output.read_bytes()),
            "effectiveEntries": len(result["entries"]), "originalUnchanged": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--source-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(export(args.source, args.output, args.source_sha256), sort_keys=True))


if __name__ == "__main__":
    main()
