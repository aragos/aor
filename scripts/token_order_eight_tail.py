"""Fresh eight-tail labels with the original frozen 64-feature basis and ridge grid.

This owner deliberately does not relax retained_collection's old-bank identity.
Only the initializer basis is reused. New validation selects on eight-tail means;
two/four-tail metrics are computed after selection and can never select a model.
"""
import argparse
import copy
from dataclasses import asdict, dataclass
from pathlib import Path
import sys
import time

import token_order_ridge as ridge

b, q, token = ridge.b, ridge.q, ridge.token
KIND = 'token-order-fresh-eight-tail-ridge-1'
PROTOCOL = token.metadata.EIGHT_TAIL_CONTINUATION
FIXED_REFERENCE_KIND = 'token-order-fixed-reference-eight-tail-ridge-1'


@dataclass(frozen=True)
class InputBindings:
    collection: str
    sourceSha256: str
    datasetSha256: str
    finishedSha256: str
    initializer: str
    initializerSha256: str
    initializerCanonicalSha256: str
    originalBasisIdentity: str
    originalBasisIdentitySha256: str
    validationPreviouslyInspected: bool = False

    def validate(self):
        b.require(self.validationPreviouslyInspected is False,
                  'This contract requires fresh prospective validation, used only for model selection.')
        for name, value in asdict(self).items():
            if name.endswith('Sha256'):
                b.require(isinstance(value, str) and len(value) == 64
                          and all(char in '0123456789abcdef' for char in value),
                          name + ' must be a lowercase SHA-256.')
        for name in ('collection', 'initializer', 'originalBasisIdentity'):
            b.require(isinstance(getattr(self, name), str) and Path(getattr(self, name)).is_absolute(),
                      name + ' must identify an absolute immutable artifact path.')
        return self


def fresh_collection(bindings, *, fixed_reference=False):
    bindings.validate()
    source = Path(bindings.collection)
    pins = {source/'source.json': bindings.sourceSha256,
            source/'dataset.json': bindings.datasetSha256,
            source/'finished.json': bindings.finishedSha256,
            Path(bindings.initializer): bindings.initializerSha256,
            Path(bindings.originalBasisIdentity): bindings.originalBasisIdentitySha256}
    b.require(all(b.file_digest(path) == digest for path, digest in pins.items()),
              'Fresh collection or immutable original basis provenance changed.')
    model = ridge.validate_initializer(b.read_json(bindings.initializer))
    original = b.read_json(bindings.originalBasisIdentity)
    b.require(original['kind'] == 'token-order-paired-fit'
              and original['initializedModelSha256'] == bindings.initializerCanonicalSha256 == b.digest(model),
              'Reuse the exact original zero-head initializer; never regenerate or import a fitted head.')
    loaded, rows, provenance = token.collection(source, None, supplied_initializer=model,
                                                eight_tails=True, fixed_reference=fixed_reference)
    b.require(loaded == model and provenance['signature'] != original['source']['signature'],
              'Eight-tail evidence must be a new bank, not a relabeled original collection.')
    b.require(all(b.file_digest(path) == digest for path, digest in pins.items()),
              'Fresh inputs changed while validating collection provenance.')
    return model, rows, provenance


def prefix_rows(collection, model, rows, count, *, fixed_reference=False):
    """Read-only diagnostic label prefixes after model selection; never fit another model."""
    b.require(count in (2, 4), 'Only the predeclared two/four-stream diagnostic prefixes are available.')
    result = []
    for row in rows:
        native = b.read_json(Path(collection)/f'origin-{row["origin"]}.json')
        diagnostic = copy.deepcopy(native)
        for alternative in diagnostic['alternatives']:
            alternative['outcomes'] = alternative['outcomes'][:count]
        result.append(token.validate_root(diagnostic, model, count, fixed_reference=fixed_reference))
    return result


def scaling_passes(selected):
    if not selected:
        return False
    chosen = selected['validation']
    return (chosen['observedMeanDifference'] > 0 and chosen['overrides'] >= 8
            and chosen['positiveOrigins'] >= 4 and chosen['largestPositiveFraction'] <= .5)


def fit(bindings, output, config, *, fixed_reference=False):
    config.validate()
    b.require(config.maxSeconds == 600, 'Fresh eight-tail ridge fitting keeps a ten-minute ceiling.')
    output = Path(output).resolve()
    b.require(not output.exists(), 'One fresh fitting attempt only; no resume or renewed allowance.')
    started = time.time()
    deadline = started + config.maxSeconds
    b.torch.set_num_threads(1)
    b.torch.use_deterministic_algorithms(True)
    model, rows, provenance = fresh_collection(bindings, fixed_reference=fixed_reference)
    kind = FIXED_REFERENCE_KIND if fixed_reference else KIND
    training = [row for row in rows if row['split'] == 'TRAINING']
    validation = [row for row in rows if row['split'] == 'VALIDATION']
    b.require(min(len(training), len(validation)) >= 2, 'Both prospective splits need collected roots.')
    ridge.check_deadline(deadline)
    output.mkdir(parents=True)
    identity = dict(schemaVersion=1, kind=kind, config=asdict(config), inputs=asdict(bindings), source=provenance,
        initializedModelSha256=b.digest(model), frozenBasisSha256=ridge.basis_digest(model),
        learnerSha256=b.file_digest(__file__), ridgeBackendSha256=b.file_digest(ridge.__file__),
        collectionLearnerSha256=b.file_digest(token.__file__), qBackendSha256=b.file_digest(q.__file__),
        inputBackendSha256=b.file_digest(b.__file__), pythonVersion=sys.version,
        torchVersion=b.torch.__version__, dtype='float64', threads=1, startedEpoch=started,
        deadlineEpoch=deadline, additionalRandomStreams=0, primaryContinuationReplicates=8,
        diagnosticPrefixes=[2, 4], diagnosticPrefixesAffectSelection=False,
        validationPreviouslyInspected=False, validationPurpose='MODEL_SELECTION_ONLY',
        selection='Lowest eight-tail validation paired MSE strictly below initializer; ties prefer larger penalty.',
        objective='Equal root weight then equal nonbaseline proposal weight; targets average all eight paired tails.',
        normalization='Training-only weighted RMS; no centering; exact zero columns remain zero.',
        proposalVersion=token.PROPOSALS, minimumAdvantage=token.MARGIN,
        qualificationPassed=False, productionActivation=False)
    if fixed_reference:
        identity.update(referenceProtocol=token.fixed.REFERENCE,
            referenceCodecSha256=b.file_digest(token.fixed.__file__),
            proposalReference=token.TOKEN_REFERENCE_VERSION,
            objective='Equal root weight then equal nonreference proposal weight; targets average all eight paired tails.',
            selector='MODEL_FIXED_T_RESIDUAL', modelArtifactKind=token.fixed.KIND)
    b.atomic_json(output/'identity.json', identity)
    design = ridge.training_design(model, training, deadline)
    b.atomic_json(output/'training-design.json', dict(trainingOrigins=list(design.origins),
        nonbaselineProposalCounts=list(design.proposalCounts), pairRows=len(design.targets),
        trainingRms=design.scales.tolist(), zeroColumns=[i for i, active in enumerate(design.active.tolist()) if not active],
        featuresSha256=b.digest(design.features.tolist()), targetsSha256=b.digest(design.targets.tolist()),
        weightsSha256=b.digest(design.weights.tolist()), validationUsed=False, primaryContinuationReplicates=8))
    initial_validation = token.measure(q.ActionValue(model, 'token'), validation)
    reports = []
    for index, penalty in enumerate(config.ridgePenalties):
        ridge.check_deadline(deadline)
        artifact = ridge.fit_head(model, design, penalty)
        actor = q.ActionValue(artifact, 'token')
        report = dict(index=index, ridgePenalty=penalty, training=token.measure(actor, training),
            validation=token.measure(actor, validation), frozenBasisSha256=ridge.basis_digest(artifact),
            onlyOutputWeightsChanged=True, outputBiasUnchanged=artifact['outputBias'] == .5)
        b.atomic_json(output/f'model-{index}.json', token.fixed.wrap(artifact) if fixed_reference else artifact)
        report['modelSha256'] = b.file_digest(output/f'model-{index}.json')
        reports.append(report)
        b.atomic_json(output/f'fit-{index}.json', report)
    selected = ridge.select_candidate(reports, initial_validation['pairedMse'])
    report = dict(schemaVersion=1, kind=kind, identitySha256=b.file_digest(output/'identity.json'),
        initializerValidation=initial_validation, fits=reports, selected=selected,
        scalingDiagnosticPassed=scaling_passes(selected), primaryContinuationReplicates=8,
        validationPreviouslyInspected=False, selectionOnly=True, qualificationPassed=False,
        humanStrengthVerified=False, productionActivation=False, freshWholeGameGatesRequired=True)
    if selected:
        b.atomic_json(output/'candidate.json', b.read_json(output/f'model-{selected["index"]}.json'))
        report['candidateSha256'] = b.file_digest(output/'candidate.json')
    # Freeze selection before examining any prefix metrics. They cannot affect admission.
    b.atomic_json(output/'selection.json', dict(selected=selected, scalingDiagnosticPassed=scaling_passes(selected),
        primaryContinuationReplicates=8, prefixDiagnosticsUsed=False))
    diagnostic_model = b.read_json(output/'candidate.json') if selected else model
    if fixed_reference and selected:
        diagnostic_model = token.fixed.unwrap(diagnostic_model)
    diagnostics = {}
    for count in (2, 4):
        ridge.check_deadline(deadline)
        diagnostics[str(count)] = {split:token.measure(q.ActionValue(diagnostic_model, 'token'),
            prefix_rows(bindings.collection, model, split_rows, count, fixed_reference=fixed_reference))
            for split, split_rows in (('TRAINING', training), ('VALIDATION', validation))}
    b.atomic_json(output/'prefix-diagnostics.json', dict(kind='post-selection-prefix-diagnostics-1',
        selectionSha256=b.file_digest(output/'selection.json'), modelSha256=b.digest(diagnostic_model),
        prefixes=diagnostics, selectionAffected=False, qualificationPassed=False))
    report['elapsedSeconds'] = time.time()-started
    ridge.check_deadline(deadline)
    b.atomic_json(output/'fit.json', report)
    b.atomic_json(output/'finished.json', dict(kind=kind, reportSha256=b.file_digest(output/'fit.json'),
        artifacts={path.name:b.file_digest(path) for path in sorted(output.iterdir()) if path.is_file()}))
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', type=Path, required=True)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--fixed-reference', action='store_true',
                        help='Require the explicit fixed-T eight-tail contract and export tagged residual models.')
    options = parser.parse_args()
    values = b.read_json(options.config)
    values['ridgePenalties'] = tuple(values['ridgePenalties'])
    fit(InputBindings(**b.read_json(options.inputs)), options.output, ridge.FitConfig(**values),
        fixed_reference=options.fixed_reference)
