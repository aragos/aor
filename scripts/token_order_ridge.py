#!/usr/bin/env python3
"""Fit only a frozen token network's affine output head with root-weighted ridge.

The original initializer supplies every hidden feature and its unchanged .5 bias.
Training-only RMS scales apply to paired hidden differences, without centering.
The exact original objective averages nonbaseline proposals within each root, then
weights roots equally. Reused validation selects a penalty; it is not qualification.
"""
from __future__ import annotations

import argparse
import copy
from dataclasses import asdict, dataclass
from pathlib import Path
import sys
import time

import purchase_policy as b
import territory_value as q
import token_order_policy as token


KIND = "token-order-frozen-head-ridge-1"
PENALTIES = (.0001, .001, .01, .1, 1.)


@dataclass(frozen=True)
class FitConfig:
    ridgePenalties: tuple[float, ...] = PENALTIES
    maxSeconds: float = 600.

    def validate(self):
        for value in self.ridgePenalties:
            b.number(value, "Normalized ridge penalty", .0001, 1.)
        b.require(self.ridgePenalties == PENALTIES,
                  "The five normalized ridge penalties are fixed before fitting.")
        b.number(self.maxSeconds, "Original ridge allowance", 1, 7200)
        return self


@dataclass(frozen=True)
class InputBindings:
    collection: str
    datasetSha256: str
    finishedSha256: str
    initializer: str
    initializerSha256: str
    initializerCanonicalSha256: str
    priorFitIdentity: str
    priorFitIdentitySha256: str
    validationPreviouslyInspected: bool = True

    def validate(self):
        b.require(self.validationPreviouslyInspected is True,
                  "This opt-in contract explicitly reuses previously inspected validation.")
        for name in ("collection", "initializer", "priorFitIdentity"):
            value = getattr(self, name)
            b.require(isinstance(value, str) and Path(value).is_absolute(),
                      name + " must identify an absolute retained artifact path.")
        for name, value in asdict(self).items():
            if name.endswith("Sha256"):
                b.require(isinstance(value, str) and len(value) == 64
                          and all(char in "0123456789abcdef" for char in value),
                          name + " must be an exact lowercase SHA-256.")
        return self


def validate_initializer(model):
    q.validate_model(model, "token")
    b.require(model["encoderVersion"] == q.TOKEN_ENCODER_2
              and model["outputBias"] == .5 and model["outputWeights"] == [0.]*64,
              "Use the original token2 initializer with zero output weights and unchanged .5 bias.")
    return model


def basis_digest(model):
    """Canonical identity of every frozen field, including the untrained output bias."""
    return b.digest({key: value for key, value in model.items() if key != "outputWeights"})


def check_deadline(deadline):
    b.require(time.time() < deadline, "Original ridge fitting deadline expired; no fit may resume.")


def retained_collection(bindings):
    bindings.validate()
    source = Path(bindings.collection)
    pins = {source/"dataset.json": bindings.datasetSha256,
            source/"finished.json": bindings.finishedSha256,
            Path(bindings.initializer): bindings.initializerSha256,
            Path(bindings.priorFitIdentity): bindings.priorFitIdentitySha256}
    b.require(all(b.file_digest(path) == digest for path, digest in pins.items()),
              "A retained collection, original initializer or prior fit identity changed.")
    model = validate_initializer(b.read_json(bindings.initializer))
    prior = b.read_json(bindings.priorFitIdentity)
    b.require(b.digest(model) == bindings.initializerCanonicalSha256
              == prior["initializedModelSha256"] and prior["kind"] == "token-order-paired-fit",
              "The supplied Q initializer must be the exact basis from the previous fit.")
    loaded, rows, provenance = token.collection(source, seed=None, supplied_initializer=model)
    b.require(loaded == model and prior["source"]["signature"] == provenance["signature"]
              and all(prior["source"]["files"].get(name) == digest
                      for name, digest in provenance["files"].items()),
              "Ridge fitting must reuse the exact previously validated collection and basis.")
    b.require(all(b.file_digest(path) == digest for path, digest in pins.items()),
              "Retained inputs changed while validating the original bank.")
    return model, rows, provenance


def frozen_actor(model):
    actor = q.ActionValue(validate_initializer(model), "token")
    for name, parameter in actor.named_parameters():
        parameter.requires_grad_(name == "outputWeights")
    return actor


def paired_features(actor, row):
    """Read hidden differences through the existing affine head's exact derivative.

    Only the head requires gradients, and nothing updates any parameter. This avoids
    reimplementing ActionValue's sparse encoder, activations or feature ordering.
    """
    baseline = token.fixed.reference_index(row)
    indices = [index for index in row["indices"] if index != baseline]
    b.require(indices and row["targets"][baseline] == 0.,
              "Paired features require alternatives and the zero declared-reference target.")
    values = actor([row["observation"]])[0]
    features = b.torch.stack([
        b.torch.autograd.grad(values[index]-values[baseline], actor.outputWeights,
                              retain_graph=position+1 < len(indices))[0].detach()
        for position, index in enumerate(indices)])
    b.require(features.dtype == b.torch.float64 and bool(b.torch.isfinite(features).all()),
              "Frozen paired hidden features must be finite Float64 values.")
    return features, indices


@dataclass(frozen=True)
class TrainingDesign:
    features: object
    targets: object
    weights: object
    scales: object
    active: object
    origins: tuple[int, ...]
    proposalCounts: tuple[int, ...]


def training_design(initializer, rows, deadline):
    b.require(rows and all(row["split"] == "TRAINING" for row in rows)
              and len({row["origin"] for row in rows}) == len(rows),
              "Ridge features and RMS may use each training root exactly once, never validation.")
    actor = frozen_actor(initializer)
    features, targets, weights, counts = [], [], [], []
    for row in rows:
        check_deadline(deadline)
        paired, indices = paired_features(actor, row)
        features.append(paired)
        targets.extend(row["targets"][index] for index in indices)
        weights.extend([1./(len(rows)*len(indices))]*len(indices))
        counts.append(len(indices))
    x = b.torch.cat(features)
    y = b.torch.tensor(targets, dtype=b.torch.float64)
    w = b.torch.tensor(weights, dtype=b.torch.float64)
    active = b.torch.any(x != 0, dim=0)
    scales = b.torch.sqrt(b.torch.sum(w[:, None]*x.square(), dim=0))
    b.require(bool(b.torch.isfinite(y).all()) and bool(b.torch.isfinite(scales).all())
              and bool((scales[active] > 0).all()),
              "Training-only RMS must be finite and positive on every nonzero feature column.")
    b.require(actor.artifact() == initializer, "Feature extraction changed the frozen initializer.")
    check_deadline(deadline)
    return TrainingDesign(x, y, w, scales, active, tuple(row["origin"] for row in rows), tuple(counts))


def fit_head(initializer, design, penalty):
    """Minimize weighted paired MSE + penalty * squared normalized-head norm.

    Every root has weight 1/N, split evenly across its nonbaseline proposals.
    No centering/intercept is fitted. Exact zero columns export a zero coefficient.
    """
    validate_initializer(initializer)
    b.number(penalty, "Normalized ridge penalty", .0001, 1.)
    b.require(penalty in PENALTIES, "Use one of the five predeclared normalized penalties.")
    weights = b.torch.zeros(64, dtype=b.torch.float64)
    if bool(design.active.any()):
        scales = design.scales[design.active]
        z = design.features[:, design.active]/scales
        gram = z.T @ (design.weights[:, None]*z)
        right = z.T @ (design.weights*design.targets)
        normalized = b.torch.linalg.solve(gram + penalty*b.torch.eye(len(scales), dtype=b.torch.float64), right)
        weights[design.active] = normalized/scales
    b.require(bool(b.torch.isfinite(weights).all()), "Ridge solve produced a nonfinite output head.")
    result = copy.deepcopy(initializer)
    result["outputWeights"] = weights.tolist()
    q.validate_model(result, "token")
    b.require(basis_digest(result) == basis_digest(initializer),
              "Only the64 output weights may change; every other model field must remain exact.")
    return result


def select_candidate(reports, initializer_mse):
    eligible = [row for row in reports if row["validation"]["pairedMse"] < initializer_mse]
    return min(eligible, key=lambda row: (row["validation"]["pairedMse"], -row["ridgePenalty"])) if eligible else None


def fit(bindings, output, config):
    config.validate()
    output = Path(output).resolve()
    b.require(not output.exists(), "Use a fresh ridge directory; incomplete fits never renew their allowance.")
    started = time.time()
    deadline = started + config.maxSeconds
    b.torch.set_num_threads(1)
    b.torch.use_deterministic_algorithms(True)
    model, rows, provenance = retained_collection(bindings)
    training = [row for row in rows if row["split"] == "TRAINING"]
    validation = [row for row in rows if row["split"] == "VALIDATION"]
    b.require(len(training) >= 2 and len(validation) >= 2,
              "Ridge selection requires at least two retained roots in each original split.")
    check_deadline(deadline)
    output.mkdir(parents=True)
    identity = dict(schemaVersion=1, kind=KIND, config=asdict(config), inputs=asdict(bindings), source=provenance,
        initializedModelSha256=b.digest(model), frozenBasisSha256=basis_digest(model),
        learnerSha256=b.file_digest(__file__), collectionLearnerSha256=b.file_digest(token.__file__),
        qBackendSha256=b.file_digest(q.__file__), inputBackendSha256=b.file_digest(b.__file__),
        pythonVersion=sys.version, torchVersion=b.torch.__version__, dtype="float64", threads=1,
        startedEpoch=started, deadlineEpoch=deadline, additionalRandomStreams=0,
        objective="Exact original mean across roots of mean squared paired errors over NONBASELINE proposals.",
        normalization="Training-only weighted RMS of paired hidden differences; no centering; zero columns export zero.",
        penalty="Mean weighted squared error plus lambda times squared normalized output coefficients.",
        selection="Lowest reused-validation paired MSE strictly below initializer; exact ties prefer larger penalty.",
        proposalVersion=token.PROPOSALS,
        proposalLimitation="The unchanged proposal set generally omits14 around baseline16; this learner cannot reproduce a minus-two control there.",
        validationPreviouslyInspected=True, validationPurpose="MODEL_SELECTION_ONLY", minimumAdvantage=token.MARGIN,
        selectionOnly=True, qualificationPassed=False, productionActivation=False)
    b.atomic_json(output/"identity.json", identity)
    design = training_design(model, training, deadline)
    b.atomic_json(output/"training-design.json", dict(trainingOrigins=list(design.origins),
        nonbaselineProposalCounts=list(design.proposalCounts), pairRows=len(design.targets),
        trainingRms=design.scales.tolist(), zeroColumns=[index for index, active in enumerate(design.active.tolist()) if not active],
        featuresSha256=b.digest(design.features.tolist()), targetsSha256=b.digest(design.targets.tolist()),
        weightsSha256=b.digest(design.weights.tolist()), validationUsed=False))
    initial_validation = token.measure(q.ActionValue(model, "token"), validation)
    reports = []
    for index, penalty in enumerate(config.ridgePenalties):
        check_deadline(deadline)
        artifact = fit_head(model, design, penalty)
        actor = q.ActionValue(artifact, "token")
        report = dict(index=index, ridgePenalty=penalty, training=token.measure(actor, training),
                      validation=token.measure(actor, validation), frozenBasisSha256=basis_digest(artifact),
                      onlyOutputWeightsChanged=True, outputBiasUnchanged=artifact["outputBias"] == .5)
        b.atomic_json(output/f"model-{index}.json", artifact)
        report["modelSha256"] = b.file_digest(output/f"model-{index}.json")
        reports.append(report)
        b.atomic_json(output/f"fit-{index}.json", report)
        print(f"Completed fixed ridge penalty {index+1}/{len(config.ridgePenalties)}.", flush=True)
    selected = select_candidate(reports, initial_validation["pairedMse"])
    chosen = selected["validation"] if selected else initial_validation
    scaling = bool(selected and chosen["observedMeanDifference"] > 0 and chosen["overrides"] >= 8
                   and chosen["positiveOrigins"] >= 4 and chosen["largestPositiveFraction"] <= .5)
    report = dict(schemaVersion=1, kind=KIND, identitySha256=b.file_digest(output/"identity.json"),
        initializerValidation=initial_validation, fits=reports, selected=selected, scalingDiagnosticPassed=scaling,
        validationPreviouslyInspected=True, selectionOnly=True, qualificationPassed=False, humanStrengthVerified=False,
        productionActivation=False, freshWholeGameGatesRequired=True, elapsedSeconds=time.time()-started)
    if selected:
        b.atomic_json(output/"candidate.json", b.read_json(output/f"model-{selected['index']}.json"))
        report["candidateSha256"] = b.file_digest(output/"candidate.json")
    check_deadline(deadline)
    b.atomic_json(output/"fit.json", report)
    b.atomic_json(output/"finished.json", dict(kind=KIND, reportSha256=b.file_digest(output/"fit.json"),
        artifacts={path.name:b.file_digest(path) for path in sorted(output.iterdir()) if path.is_file()}))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inputs", type=Path, required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    options = parser.parse_args()
    values = b.read_json(options.config)
    if "ridgePenalties" in values: values["ridgePenalties"] = tuple(values["ridgePenalties"])
    fit(InputBindings(**b.read_json(options.inputs)), options.output, FitConfig(**values))


if __name__ == "__main__": main()
