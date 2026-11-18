"""Admit the complete bounded probe, fit three cases and score sealed public predictions."""
import argparse
import math
from pathlib import Path
import statistics
import subprocess
import sys
import time

import purchase_policy as numeric
import token_anchored_corpus as corpus_owner
import token_anchored_data as data
import token_anchored_plan as protocol_owner
import token_anchored_policy as policy
import token_paired_data as target_owner


shared = protocol_owner.shared


def write(path, value):
    with path.open("xb") as out:
        out.write(shared.canonical(value) + b"\n")
    return shared.subject(path)


def admit(root, plan):
    manifest = shared.read(root / "execution.json")
    shared.require(manifest["protocolSha256"] == plan.sha256,
                   "The probe execution must retain its exact predeclared protocol.")
    subjects = []
    for row in manifest["chunks"]:
        source = root / row["source"]
        shared.require(shared.file_sha(source) == row["sha256"], "Original probe source changed.")
        index, host = row["index"], row["host"]
        folder = root / f"chunk-{index}"
        receipt = corpus_owner.native_chunk(plan, source, folder / "native", 1, index, host)
        bounds = receipt["range"]
        wanted = range(bounds["globalStart"], bounds["globalStart"] + bounds["count"])
        receipt["reproductions"] = [
            corpus_owner.reproduce(receipt, i, folder / f"replay-{i}")
            for i in plan.record["rounds"][0]["replayIndices"] if i in wanted
        ]
        subjects.append(write(folder / "admission.json", receipt))
    combined = corpus_owner.assemble(plan, 1, subjects)
    return write(root / "corpus.json", combined)


def fit_cases(root, plan, corpus):
    shared.require(corpus.record["exposurePassed"],
                   "The complete probe has too few branching training positions to fit.")
    for case in plan.record["fitCases"]:
        identifier = case["id"]
        output = root / identifier
        command = [sys.executable, str(Path(__file__).with_name("token_anchored_fit.py")),
                   "--protocol", str(root / "protocol.json"), "--corpus", corpus.subject["path"],
                   "--original-corpus", corpus.subject["path"],
                   "--original-actor", str(root / "initial.json"),
                   "--current-actor", str(root / "initial.json"),
                   "--dictionary", str(root / "preflight/dictionary.json"),
                   "--output", str(output), "--fit-case", identifier,
                   "--deadline-epoch", str(plan.record["globalDeadlineEpoch"])]
        remaining = plan.record["globalDeadlineEpoch"] - time.time()
        shared.require(remaining > 0, "The predeclared probe deadline expired before fitting.")
        with (root / f"{identifier}.log").open("x") as log:
            subprocess.run(command, check=True, timeout=remaining, stdout=log, stderr=subprocess.STDOUT)
        completion = shared.read(output / "numeric-complete.json")
        subject = completion["fitCompletion"]
        done = shared.read(shared.checked_subject(subject))
        model = shared.subject(output / "policy.json")
        shared.require(done["outputModelSha256"] == model["sha256"] and
                       done["identity"]["fitCase"] == case, "Fit identity or model hash changed.")
        yield identifier, model


def diagnose(root, corpus, models):
    initial = shared.subject(root / "initial.json")
    actor = data.FrozenActor(Path(initial["path"]).read_bytes(), initial["sha256"])
    dictionary = root / "preflight/dictionary.json"
    context = corpus.context(dictionary, actor, actor)
    numeric.torch.set_num_threads(1)
    public = []
    for value, path, expected in corpus.public("VALIDATION"):
        record = corpus_owner.normalized_public(value)
        row = data.PublicRoot(record, context, actor.model, split="VALIDATION")
        public.append((row, value, path, expected))
    predictions = {}
    for name, subject in models.items():
        fitted = policy.TokenAnchoredPolicy(shared.read(shared.checked_subject(subject)))
        ready = [row for row, _, _, _ in public if row.ready]
        with numeric.torch.no_grad():
            outputs = fitted([fitted.prepare_record(row.record) for row in ready])[0] if ready else []
        predictions[name] = {
            row.record["index"]: dict(publicSha256=row.sha256, probabilities=log.exp().tolist())
            for row, log in zip(ready, outputs, strict=True)
        }
    sealed = write(root / "sealed-diagnostic-predictions.json",
                   dict(models=models, corpusSha256=corpus.subject["sha256"], predictions=predictions))
    # No validation outcome has been parsed before this durable prediction record.
    gains = {name: [] for name in predictions}
    variations = {name: [] for name in predictions}
    nonzero = disagreement = pairs = 0
    for row, value, path, expected in public:
        if not row.ready:
            continue
        record = row.record
        shared.require(shared.file_sha(path) == expected, "A held-out sidecar changed.")
        label = corpus_owner._checked_labels(shared.read(path), value, row)
        target = target_owner.WinPairedTarget.from_record(row, label, split="VALIDATION")
        differences = [(u-target.rewards[0][0], v-target.rewards[0][1])
                       for u, v in target.rewards[1:]]
        nonzero += any(delta != 0 for delta in target.deltas)
        disagreement += sum(u*v < 0 for u, v in differences)
        pairs += len(differences)
        for name, values in predictions.items():
            after = values[record["index"]]["probabilities"]
            before = record["currentProbabilities"]
            gains[name].append(math.fsum(
                (after[i]-before[i])*delta/record["inclusionProbabilities"][i]
                for i, delta in zip(record["proposedIndices"], target.deltas, strict=True)))
            variations[name].append(.5*math.fsum(abs(a-b) for a,b in zip(after,before,strict=True)))
    results = {
        name: dict(
            meanPairedGain=statistics.mean(rows) if rows else None,
            standardError=statistics.stdev(rows)/math.sqrt(len(rows)) if len(rows) > 1 else None,
            positive=sum(v > 0 for v in rows), negative=sum(v < 0 for v in rows),
            meanTotalVariation=statistics.mean(variations[name]) if rows else None,
        ) for name, rows in gains.items()
    }
    return dict(predictions=sealed, readyRoots=corpus.record["ready"], nonzeroDiagnosticRoots=nonzero,
                opposingFutureEffects=disagreement, pairedDiagnosticAlternatives=pairs, cases=results,
                terminalExecutions=corpus.record["scientificTerminalGames"], qualified=False)


def main(root):
    protocol = shared.subject(root / "protocol.json")
    plan = protocol_owner.Plan.from_record(shared.read(protocol["path"]), raw_sha256=protocol["sha256"])
    corpus = corpus_owner.Corpus(plan, admit(root, plan))
    models = dict(fit_cases(root, plan, corpus))
    report = diagnose(root, corpus, models)
    print(write(root / "diagnostic.json", report), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    main(parser.parse_args().root.resolve())
