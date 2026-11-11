"""A derived view of immutable fixed-T evidence plus explicitly added counterfactual branches.

Old outcomes are never written again. The view stores references and checksums; learning rows
are assembled in memory from original objects and native sidecars. Former validation roots
are inspected development data and cannot supply a fresh qualification claim.
"""
from dataclasses import asdict, dataclass
from pathlib import Path

from automata_experiments import read, sha, document, require
import token_order_fixed_reference as fixed
import token_order_proposals as proposals
import token_order_reference_neighbors as codec

KIND = 'token-order-reference-neighbor-augmentation-1'
VIEW_KIND = 'token-order-reference-neighbor-derived-view-1'
FIT_KIND = 'token-order-reference-neighbor-eight-tail-ridge-1'
PARENT_PROTOCOL = '5498ff1ca52de3d0d6b6af4179be177a9155170734e23a1ce968c5c4cb132c93'
PARENT_AUDIT = 'a344785271db87a035603095de1c8f66611ec8bb1c5bb6e72f292713c44d21c0'
BRANCH_PLAN = '04b01c7f155daa00d277869e7e46e9a6686bef4eb4f4b4460c253ef28aab5ecd'


def pinned(entry, base):
    require(isinstance(entry, dict) and set(entry) == {'path', 'sha256'},
            'A retained input requires its exact path and SHA-256.')
    path = (Path(base)/entry['path']).resolve()
    require(sha(path) == entry['sha256'], 'A retained input changed: '+str(path))
    return path


def streams(origin):
    return [(origin['gameSeed'] << 3) ^ replicate ^ 0x6f20a5319d for replicate in range(8)]


def effective_origin(parent, delta, parent_hash):
    """Validate ownership and construct a transient view without copying inherited files."""
    require(delta['schemaVersion'] == 1 and delta['kind'] == KIND
            and delta['parentOriginSha256'] == parent_hash
            and delta['parentSignature'] == parent['signature']
            and all(delta[key] == parent[key] for key in ('index', 'split', 'status'))
            and delta['originPolicySeed'] == parent['gameSeed']
            and delta['parentMetadataValidated'] is True,
            'An augmentation sidecar changed its exact historical root or policy stream.')
    previous = parent.get('proposedIndices', [])
    expected = (proposals.reference_neighbor_indices(parent['observation'])
                if parent['status'] == 'COLLECTED' else [])
    reused = [index for index in expected if index in previous]
    added = [index for index in expected if index not in previous]
    omitted = [index for index in previous if index not in expected]
    require(delta['parentProposedIndices'] == previous and delta['proposedIndices'] == expected
            and delta['reusedIndices'] == reused and delta['newIndices'] == added
            and delta['omittedIndices'] == omitted and len(added) <= 1
            and delta.get('referenceIndex') == parent.get('referenceIndex'),
            'The derived query set must be the exact fixed version, without selective omissions.')
    require(delta['continuationSeeds'] == (streams(parent) if previous else [])
            and delta['prefixReconstructed'] == bool(added)
            and delta['prefixMetadataExact'] == bool(added)
            and delta.get('prefixPlayerActions') == (parent['originDecisions'] if added else None),
            'Only missing branches may reconstruct the exact original prefix and eight streams.')
    new = delta['newAlternatives']
    require(isinstance(new, list) and [row['index'] for row in new] == added,
            'Sidecars must contain every missing branch once and no inherited branch outcomes.')
    if not previous:
        require(parent['status'] == 'SKIPPED' and not new,
                'Historical skips cannot receive observations or synthetic outcomes.')
        return dict(parent)
    alternatives = {row['index']: row for row in parent['alternatives']}
    require(list(alternatives) == previous, 'Original branch ordering or identity changed.')
    for branch in new:
        index = branch['index']
        require(branch['command'] == parent['observation']['actions'][index]['command']
                and len(branch['outcomes']) == 8
                and [row['continuationSeed'] for row in branch['outcomes']] == streams(parent),
                'A new branch must use its exact offered command and original paired streams.')
        alternatives[index] = branch
    # The original dictionaries are intentionally reused unchanged in this temporary object.
    return dict(parent, proposedIndices=expected,
                alternatives=[alternatives[index] for index in expected])


def parent_inputs(source_path):
    source_path = Path(source_path).resolve()
    source = read(source_path)
    require(source['schemaVersion'] == 1 and source['kind'] == KIND,
            'Require the explicit reference-neighbor augmentation source.')
    parent = (source_path.parent/source['parentDirectory']).resolve()
    audit_path = pinned(source['parentCompletionAudit'], source_path.parent)
    require(sha(audit_path) == PARENT_AUDIT, 'Only the independently audited completed37 bank is eligible.')
    audit = read(audit_path)
    require(audit['protocolSha256'] == PARENT_PROTOCOL and audit['collectedOrigins'] == 498
            and audit['prospectiveOrigins'] == 640 and audit['allArenaBanksUnopened']
            and not audit['qualificationPassed'], 'Parent audit scope or qualification changed.')
    for name, digest in audit['artifactHashes'].items():
        require(sha(parent.parent/name) == digest, 'A retained parent output changed: '+name)
    require(sha(parent/'finished.json') == source['parentFinishedSha256'],
            'The retained parent completion snapshot changed.')
    manifest = read(parent/'dataset.json')
    require(manifest['complete'] and manifest['continuationProtocol'] == fixed.PROTOCOL
            and manifest['continuationReplicates'] == 8
            and manifest['trainingRoots'] == 512 and manifest['validationRoots'] == 128
            and manifest['proposalVersion'] == proposals.ProposalProfile.ORDINARY.value
            and manifest['tokenReference']['version'] == fixed.REFERENCE,
            'Augmentation requires original fixed-T labels and the exact historical split/query set.')
    plan_path = pinned(source['branchPlan'], source_path.parent)
    require(sha(plan_path) == BRANCH_PLAN, 'The metadata-only missing-branch plan changed.')
    plan = read(plan_path)
    require(len(plan['rows']) == 640 and not plan['rewardFieldsUsed']
            and not plan['validationOutcomesUsed'], 'Missing branches must not depend on outcomes.')
    expected_replays = [row['index'] for split in ('TRAINING','VALIDATION')
                        for row in [r for r in plan['rows'] if r['split']==split and r['newIndices']][:4]]
    require(source['replayOrigins'] == expected_replays and len(expected_replays) == 8,
            'Historical verification uses the first four augmented roots per original split.')
    # Native preparation owns original-runtime class compatibility. Python independently binds
    # the original jars and the exact, reversible source relocation without path rewriting.
    for entry in source['parentRuntime'].values():
        pinned(entry, source_path.parent)
    relocation = source['parentSource']
    relocated = read(pinned(relocation['relocatedSource'], source_path.parent))
    aliases = relocation['aliases']
    resolved = {str(pinned(entry, source_path.parent)): original for original,entry in aliases.items()}
    require(len(resolved) == len(aliases), 'Historical source aliases must be one-to-one.')

    def restore(value):
        if isinstance(value, str):
            return resolved.get(value, value)
        if isinstance(value, list):
            return [restore(item) for item in value]
        if isinstance(value, dict):
            return {key:restore(item) for key,item in value.items()}
        return value

    require(restore(relocated) == read(parent/'source.json'),
            'Relocation may change only the explicitly aliased immutable model paths.')
    return source, parent, audit, plan


def verify_native(source_path, directory, *, replay_of=None):
    directory = Path(directory).resolve()
    source, parent, audit, plan = parent_inputs(source_path)
    finished = read(directory/'finished.json')
    replay = replay_of is not None
    indices = source['replayOrigins'] if replay else list(range(640))
    require(read(directory/'source.json') == source
            and finished['schemaVersion'] == 1 and finished['kind'] == KIND
            and finished['sourceSha256'] == sha(source_path)
            and finished['mode'] == ('replay' if replay else 'augment')
            and finished['proposalVersion'] == codec.PROPOSALS
            and finished['selectorVersion'] == codec.SELECTOR_VERSION
            and finished['referenceProtocol'] == fixed.REFERENCE
            and finished['validationRole'] == 'DEVELOPMENT_ONLY'
            and finished['origins'] == 640 and finished['newBranches'] == 314
            and finished['newTerminalTails'] == 2512 and finished['oldTerminalTailsRerun'] == 0
            and finished['replayOrigins'] == source['replayOrigins']
            and finished['originIndices'] == indices
            and finished['allParentFilesUnchanged']
            and finished['allReplayedNewBranchesExact'] == replay
            and not finished['productionActivation'],
            'Native augmentation/replay changed its fixed lineage, query set or bounded work.')
    require(len(finished['originSha256']) == len(indices)
            and {path.name for path in directory.glob('origin-*.json')}
                == {f'origin-{index}.json' for index in indices},
            'Every declared augmentation receipt must exist exactly once.')
    for name,digest in finished['parentFiles'].items():
        require(sha(parent/name) == digest
                and audit['artifactHashes']['collection/'+name] == digest,
                'Native parent file receipts differ from the independently audited bank.')
    counts = dict(prefixes=0, tails=0, reusedBranches=0, omittedBranches=0)
    for position,index in enumerate(indices):
        native_path = directory/f'origin-{index}.json'
        require(sha(native_path) == finished['originSha256'][position], 'A native sidecar changed.')
        original_path = parent/f'origin-{index}.json'
        original,delta = read(original_path),read(native_path)
        effective_origin(original,delta,sha(original_path))
        require(delta['newIndices'] == plan['rows'][index]['newIndices'], 'Predeclared branch requests changed.')
        counts['prefixes'] += int(delta['prefixReconstructed'])
        counts['tails'] += sum(len(arm['outcomes']) for arm in delta['newAlternatives'])
        counts['reusedBranches'] += len(delta['reusedIndices'])
        counts['omittedBranches'] += len(delta['omittedIndices'])
        if replay:
            require(sha(native_path) == sha(Path(replay_of)/native_path.name),
                    'Historical new-branch replay must reproduce exact serialized bytes.')
    require(counts['prefixes'] == finished['prefixesReconstructed'] == (8 if replay else 314)
            and counts['tails'] == finished['tailsGenerated'] == (64 if replay else 2512),
            'Native counts exceed or omit the declared missing branches and historical checks.')
    return dict(counts, finishedSha256=sha(directory/'finished.json'), origins=len(indices),
                originalOutcomesResimulated=0, qualificationPassed=False)


def assemble_view(source_path, augmentation, replay, output):
    require(not Path(output).exists(), 'Preserve an existing derived evidence view.')
    native = verify_native(source_path, augmentation)
    reproduction = verify_native(source_path, replay, replay_of=augmentation)
    source, parent, _, _ = parent_inputs(source_path)
    view = dict(schemaVersion=1, kind=VIEW_KIND,
        source=dict(path=str(Path(source_path).resolve()),sha256=sha(source_path)),
        parentDirectory=str(parent), augmentationDirectory=str(Path(augmentation).resolve()),
        augmentationFinishedSha256=native['finishedSha256'],
        replayDirectory=str(Path(replay).resolve()), replayFinishedSha256=reproduction['finishedSha256'],
        proposalVersion=codec.PROPOSALS, selectorVersion=codec.SELECTOR_VERSION,
        referenceProtocol=fixed.REFERENCE, validationPreviouslyInspected=True,
        validationPurpose='REUSED_DEVELOPMENT_SELECTION_ONLY',
        copiedParentOutcomeFiles=0, qualificationPassed=False, productionActivation=False)
    document(output, view)
    return view


@dataclass(frozen=True)
class AugmentedBindings:
    view: str
    viewSha256: str
    initializer: str
    initializerSha256: str
    initializerCanonicalSha256: str
    originalBasisIdentity: str
    originalBasisIdentitySha256: str

    def validate(self):
        for name,value in asdict(self).items():
            if name.endswith('Sha256'):
                require(isinstance(value,str) and len(value)==64
                        and all(char in '0123456789abcdef' for char in value),
                        name+' requires a lowercase SHA-256.')
            else:
                require(isinstance(value,str) and Path(value).is_absolute(),
                        name+' requires an absolute immutable input path.')
        return self


def read_effective_origin(view, index):
    parent_path = Path(view['parentDirectory'])/f'origin-{index}.json'
    return effective_origin(read(parent_path),
        read(Path(view['augmentationDirectory'])/f'origin-{index}.json'),sha(parent_path))


def load_collection(bindings):
    import token_order_ridge as ridge
    bindings.validate()
    require(sha(bindings.view) == bindings.viewSha256
            and sha(bindings.initializer) == bindings.initializerSha256
            and sha(bindings.originalBasisIdentity) == bindings.originalBasisIdentitySha256,
            'The derived view or original frozen basis changed.')
    view = read(bindings.view)
    require(view['kind'] == VIEW_KIND and view['schemaVersion'] == 1
            and view['validationPreviouslyInspected'] is True
            and view['validationPurpose'] == 'REUSED_DEVELOPMENT_SELECTION_ONLY'
            and view['proposalVersion'] == codec.PROPOSALS
            and view['selectorVersion'] == codec.SELECTOR_VERSION
            and view['referenceProtocol'] == fixed.REFERENCE
            and view['copiedParentOutcomeFiles'] == 0
            and not view['qualificationPassed'] and not view['productionActivation'],
            'Derived evidence cannot masquerade as fresh validation or inherited qualification.')
    source_path = pinned(view['source'], Path(bindings.view).parent)
    _, parent, _, _ = parent_inputs(source_path)
    require(str(parent) == view['parentDirectory'], 'The derived view changed its retained parent directory.')
    native = verify_native(source_path, view['augmentationDirectory'])
    replay = verify_native(source_path, view['replayDirectory'], replay_of=view['augmentationDirectory'])
    require(native['finishedSha256'] == view['augmentationFinishedSha256']
            and replay['finishedSha256'] == view['replayFinishedSha256'], 'Derived completion binding changed.')
    model = ridge.validate_initializer(read(bindings.initializer))
    original = read(bindings.originalBasisIdentity)
    require(original['kind'] == 'token-order-paired-fit'
            and original['initializedModelSha256'] == bindings.initializerCanonicalSha256 == ridge.b.digest(model),
            'Only the original unfitted64 basis may initialize this derived experiment.')
    rows = []
    for index in range(640):
        root = read_effective_origin(view,index)
        if root['status'] == 'COLLECTED':
            rows.append(ridge.token.validate_root(root,model,8,fixed_reference=True,
                proposal_profile=proposals.ProposalProfile.REFERENCE_NEIGHBORS))
    require(sum(row['split']=='TRAINING' for row in rows)==394
            and sum(row['split']=='VALIDATION' for row in rows)==104,
            'Every original collected root must retain its original training/development split.')
    provenance = dict(kind=VIEW_KIND,viewSha256=bindings.viewSha256,
        parentAuditSha256=PARENT_AUDIT,parentProtocolSha256=PARENT_PROTOCOL,
        sourceSha256=sha(source_path),augmentation=native,reproduction=replay,
        retainedTrainingRoots=394,reusedDevelopmentRoots=104,
        validationPreviouslyInspected=True,qualificationPassed=False)
    return model, rows, provenance
