"""Complete shared-origin joint trajectories and atomic, separately typed PPO updates.

Both phase views retain the same complete games and terminal labels, including games with an
empty phase array. A batch with an entirely empty phase rejects before either fit. The two
actors keep distinct models, critics, row-mean objectives and Adam states; no data is recollected
after fitting the first actor. Only the final bundle publishes a validated pair.
"""
from dataclasses import asdict, replace
from pathlib import Path
import math
import sys

import card_policy as card
import purchase_policy as backend
from joint_economy import terminal_metrics
from joint_current_ecology import CurrentJointEvidence, JointEcology

require, read, sha = backend.require, backend.read_json, backend.file_digest
PHASES = ('card', 'purchase')
CONFIGURATION = 'players-4/standard/no-crusades/deck-true/art-true'
CONTRACTS = {'card': card.CONTRACT, 'purchase': backend.PURCHASE_CONTRACT}
GZIP_CONTRACT = replace(backend.PURCHASE_CONTRACT, trajectoryEncoding='gzip-json-v1')


def actor_seed(game_seed, number, seat, phase):
    """The unchanged typed native sampler salt after the simulation's signed-Int seed."""
    salt = 0x3c6ef372 if phase == 'card' else 0x6a09e667
    value = game_seed ^ (number*65537 + seat*8191) ^ salt
    return (value + 2**31) % 2**32 - 2**31


def resolved(path, parent):
    value = Path(path)
    return (value if value.is_absolute() else Path(parent)/value).resolve(strict=True)


def pin(path, pins):
    path = Path(path).resolve(strict=True)
    value = sha(path)
    require(str(path) not in pins or pins[str(path)] == value, 'Joint input changed while being bound.')
    pins[str(path)] = value
    return path


def unchanged(pins):
    require(all(sha(path) == expected for path, expected in pins.items()),
            'Frozen joint inputs changed during validation or fitting.')


def load_bundle(path, configuration, baseline_sha, pins, controlled=True):
    path = pin(path, pins)
    raw = read(path)
    require(set(raw) == {'schemaVersion', 'kind', 'configuration', 'baselineSha256', 'card', 'purchase'}
            and raw['schemaVersion'] == 1 and raw['kind'] == 'joint-card-purchase-1'
            and raw['configuration'] == configuration and raw['baselineSha256'] == baseline_sha,
            'A joint bundle must bind its exact configuration and F prior.')
    descriptor = dict(path=str(path), sha256=sha(path), kind=raw['kind'],
                      configuration=configuration, baselineSha256=baseline_sha)
    models = {}
    for phase, contract in CONTRACTS.items():
        entry = raw[phase]
        if entry is None:
            require(phase == 'card' and not controlled, 'Only frozen F-card rivals may omit the card actor.')
            descriptor[phase] = None
            continue
        require(isinstance(entry, dict) and set(entry) == {'path', 'sha256'},
                'Each joint actor requires an exact model path and byte hash.')
        model_path = pin(resolved(entry['path'], path.parent), pins)
        require(sha(model_path) == entry['sha256'], 'A joint actor differs from its frozen bundle hash.')
        model = backend.validate_model(read(model_path), contract=contract)
        models[phase] = model
        descriptor[phase] = dict(path=str(model_path), sha256=sha(model_path),
            encoderVersion=contract.encoder, actorVersion=contract.actor,
            stateFeatureNames=model['stateFeatureNames'], actionFeatureNames=model['actionFeatureNames'])
    return descriptor, models


class JointCollection:
    """Hash-bound complete batch, read once before either typed fitter receives a row."""
    def __init__(self, directory, bundle_path, *, ecology=None):
        require(ecology is None or ecology is JointEcology.CURRENT,
                'Choose an explicit typed joint ecology; omission preserves the legacy reader.')
        self.directory = Path(directory).resolve(strict=True)
        self.pins = {}
        root = self.directory
        for name in ('collection.json', 'finished.json', 'state.json', 'source.json'):
            pin(root/name, self.pins)
        manifest, done, state, snapshot = (read(root/name) for name in
            ('collection.json', 'finished.json', 'state.json', 'source.json'))
        require(manifest['kind'] == done['kind'] == state['kind'] == 'joint-collect'
                and manifest['schemaVersion'] == 1 and state['phase'] == 'complete'
                and manifest['signature'] == done['signature'] == state['signature']
                and isinstance(manifest['signature'], str) and manifest['signature']
                and manifest['mode'] == 'on-policy' and manifest['trajectoryEncoding'] == 'gzip-json-v1',
                'Both phases require the same complete native joint collection.')
        require(manifest['configuration'] == CONFIGURATION,
                'The first joint learner supports only the exact qualified four-player setup.')
        source_path = pin(manifest['source'], self.pins)
        require(sha(source_path) == manifest['sourceSha256']
                and source_path.read_bytes() == (root/'source.json').read_bytes()
                and snapshot['schemaVersion'] == 1 and snapshot['kind'] == 'joint-collect',
                'Joint source bytes and native source identity must agree.')
        count = backend.integer(manifest['games'], 'Joint games', 1, 4096)
        seeds = manifest['seeds']
        require(done['games'] == snapshot['games'] == count and state['completedGames'] == count
                and isinstance(seeds, list) and len(seeds) == len(set(seeds)) == count,
                'A joint batch requires every distinct declared game.')
        for seed in seeds:
            backend.integer(seed, 'Joint game seed', -(2**31), 2**31-1)
        require(manifest['seed'] == snapshot['seed'] and manifest['configuration'] == snapshot['configuration'],
                'Joint configuration and origin schedule must match the source.')
        backend.integer(snapshot['seed'], 'Joint seed offset', 0, 100000000)
        require(seeds == [-1000000000 + snapshot['seed'] + index*8191 for index in range(count)],
                'Joint seeds must match the unchanged native arena index schedule.')
        baseline = pin(resolved(snapshot['baseline'], source_path.parent), self.pins)
        require(manifest['baseline'] == dict(path=str(baseline), sha256=sha(baseline)),
                'Joint F prior differs from its manifest.')
        self._snapshot(baseline, 'baseline.json')
        evidence = (CurrentJointEvidence(root, source_path, snapshot, manifest, done, self.pins, pin)
                    if ecology is JointEcology.CURRENT else None)
        purchase_prior = evidence.prior if evidence else baseline
        own = resolved(snapshot['bundle'], source_path.parent)
        require(own == Path(bundle_path).resolve(strict=True), 'Train only the exact collecting joint bundle.')
        self.bundle, self.models = load_bundle(own, snapshot['configuration'], sha(purchase_prior), self.pins)
        require(manifest['bundle'] == self.bundle, 'Joint phase versions, dictionaries or model identities changed.')
        self._bundle_snapshots(self.bundle, 'bundle.json', '')
        opponents = []
        require(isinstance(snapshot['opponents'], list) and 1 <= len(snapshot['opponents']) <= 16,
                'A joint collection requires a bounded frozen rival pool.')
        for index, value in enumerate(snapshot['opponents']):
            opponent, _ = load_bundle(resolved(value, source_path.parent), snapshot['configuration'],
                                      sha(purchase_prior), self.pins, controlled=False)
            opponents.append(opponent)
            self._bundle_snapshots(opponent, f'opponent-{index}-bundle.json', f'opponent-{index}-')
        require(manifest['opponents'] == opponents, 'Joint rival bundles changed.')
        if evidence:
            evidence.validate_heads([self.bundle, *opponents])
        paths = [root/f'game-{index}.json.gz' for index in range(count)]
        hashes = [sha(pin(path, self.pins)) for path in paths]
        require(done['gameSha256'] == hashes and set(root.glob('game-*')) == set(paths),
                'Bind every compressed joint game hash before decoding either phase or terminal labels.')
        self.rows = {phase: [] for phase in PHASES}
        self.origins, rewards = [], []
        actors = {phase: backend.ResidualPolicy(model, CONTRACTS[phase]) for phase, model in self.models.items()}
        for index, path in enumerate(paths):
            game = backend.read_game(path, GZIP_CONTRACT)
            expected = dict(schemaVersion=1, signature=manifest['signature'], index=index,
                configuration=manifest['configuration'], gameSeed=seeds[index], controlledSeat=index%4,
                opponentIndices=[(index//4+seat)%len(opponents) for seat in range(4)],
                bundleSha256=self.bundle['sha256'], cardModelSha256=self.bundle['card']['sha256'],
                purchaseModelSha256=self.bundle['purchase']['sha256'])
            require(all(game.get(key) == value for key, value in expected.items()),
                    'Joint game origins and both behavior model identities must match their manifest.')
            if evidence:
                evidence.validate_game(game, index, hashes[index])
            reward = terminal_metrics(game)['reward']
            rewards.append(reward)
            occupied, counts, turns = set(), {}, []
            for phase in PHASES:
                entries = game.get(phase+'Decisions')
                require(isinstance(entries, list) and len(entries) <= 12000,
                        'Retain each bounded typed decision array, including empty arrays.')
                counts[phase] = len(entries)
                previous = -1
                for row in entries:
                    number = backend.integer(row['number'], 'Joint decision number', 0, 11999)
                    require(number > previous and number not in occupied
                            and number < game['terminal']['gameDecisions'],
                            'Typed decisions must be ordered and disjoint in global simulation order.')
                    previous = number; occupied.add(number)
                    turns.append((number, backend.integer(row['turn'], 'Joint decision turn', 1, 12000)))
                    backend.integer(row['actorSeed'], 'Joint actor seed', -(2**31), 2**31-1)
                    require(row['actorSeed'] == actor_seed(game['gameSeed'], number, game['controlledSeat'], phase),
                            'Joint actor seed must preserve the exact typed native sampling stream.')
                    allowed = ('play-cards',) if phase == 'card' else ('purchase-shipping', 'purchase-advances')
                    require(row['decision'] in allowed, 'A typed view contains a decision from another phase.')
                    prepared = actors[phase].prepare(row['observation'])
                    backend.integer(row['selectedIndex'], 'Joint chosen action', 0,
                                    len(row['observation']['actions'])-1)
                    backend.number(row['behaviorLogProbability'], 'Joint behavior log probability')
                    backend.number(row['behaviorValue'], 'Joint behavior value', 0, 1)
                    self.rows[phase].append({**row, 'originIndex': index, 'return': reward, '_prepared': prepared})
            require(len(occupied) <= 12000, 'Combined typed rows exceed the native game bound.')
            ordered_turns = [turn for _, turn in sorted(turns)]
            require(all(before <= after for before, after in zip(ordered_turns, ordered_turns[1:])),
                    'Merged card and purchase decisions must preserve nondecreasing game turns.')
            self.origins.append(dict(index=index, gameSeed=seeds[index], controlledSeat=index%4,
                                     cardDecisions=counts['card'], purchaseDecisions=counts['purchase']))
        for phase in PHASES:
            require(len(self.rows[phase]) <= 200000, 'A typed joint view exceeds the bounded update size.')
            self._behavior(phase, actors[phase])
        self.common = dict(collectionSha256=sha(root/'collection.json'), collectionSignature=manifest['signature'],
            completionSha256=sha(root/'finished.json'), gameHashes=hashes, games=count,
            gameMeanReward=math.fsum(rewards)/count, bundleSha256=self.bundle['sha256'],
            originCoverage=self.origins, readerSha256=sha(__file__))
        if evidence:
            evidence.finish()
            self.common['jointEcology'] = evidence.provenance
        self.configuration, self.baseline_sha = manifest['configuration'], sha(purchase_prior)
        unchanged(self.pins)

    def _snapshot(self, original, name):
        path = pin(self.directory/name, self.pins)
        require(path.read_bytes() == Path(original).read_bytes(), 'A joint snapshot changed: '+name)

    def _bundle_snapshots(self, bundle, name, prefix):
        self._snapshot(bundle['path'], name)
        for phase in PHASES:
            if bundle[phase] is not None:
                self._snapshot(bundle[phase]['path'], prefix+phase+'-policy.json')

    def _behavior(self, phase, actor):
        with backend.torch.no_grad():
            for start in range(0, len(self.rows[phase]), 16):
                batch = self.rows[phase][start:start+16]
                logps, values = actor([row['_prepared'] for row in batch])
                for logp, value, row in zip(logps, values, batch):
                    require(abs(float(logp[row['selectedIndex']])-row['behaviorLogProbability']) <= 1e-9
                            and abs(float(value)-row['behaviorValue']) <= 1e-9,
                            'Joint behavior likelihood/value must match the frozen typed actor.')
                    uniform = backend.selection_uniform(row['actorSeed'])
                    if backend.select(logp.exp().tolist(), uniform) != row['selectedIndex']:
                        exact = backend.reference_forward(self.models[phase], row['observation'],
                                                          contract=CONTRACTS[phase])['probabilities']
                        require(backend.select(exact, uniform) == row['selectedIndex'],
                                'Joint chosen action differs from the frozen seeded selector.')

    def phase_reader(self, directory, model_path, model, *, contract):
        phase = contract.label
        require(phase in PHASES and contract == CONTRACTS[phase]
                and Path(directory).resolve() == self.directory
                and Path(model_path).resolve() == Path(self.bundle[phase]['path'])
                and model == self.models[phase], 'The fitter must use its original typed behavior view.')
        unchanged(self.pins)
        require(self.rows[phase], 'An entirely empty phase cannot be fitted; reject before publishing a pair.')
        return self.rows[phase], dict(self.common, phase=phase,
                                    behaviorModelSha256=self.bundle[phase]['sha256'])


def validate_carry(model_path, checkpoint_path, contract, config, pins):
    """Check the completed matching model/Adam lineage without performing an optimizer step."""
    model_path, checkpoint_path = pin(model_path, pins), pin(checkpoint_path, pins)
    require(checkpoint_path.name == 'checkpoint.json', 'Carry the original complete typed checkpoint.')
    identity_path = pin(checkpoint_path.parent/'identity.json', pins)
    completion_path = pin(checkpoint_path.parent/'finished.json', pins)
    identity, done = read(identity_path), read(completion_path)
    payload = backend.read_checkpoint(checkpoint_path)
    model = backend.validate_model(read(model_path), contract=contract)
    require(identity['actorVersion'] == contract.actor
            and payload['identitySha256'] == done['identitySha256'] == backend.digest(identity)
            and payload['model'] == model and isinstance(payload['optimizer'], dict)
            and done['outputModelSha256'] == sha(model_path)
            and done['inputModelSha256'] == identity['modelSha256']
            and done['history'] == payload['history'] and done['acceptedEpochs'] == payload['epoch'],
            'Joint Adam carry must belong to the exact completed typed actor and identity.')
    policy = backend.ResidualPolicy(model, contract)
    optimizer = backend.torch.optim.Adam(policy.parameters(), lr=config.learningRate, foreach=False)
    defaults = [{key: value for key, value in group.items() if key != 'params'}
                for group in backend.encode_optimizer(optimizer)['param_groups']]
    backend.restore_optimizer(optimizer, payload['optimizer'])
    require(defaults == [{key: value for key, value in group.items() if key != 'params'}
                        for group in backend.encode_optimizer(optimizer)['param_groups']],
            'Joint carry must retain the unchanged Adam hyperparameters.')
    require(len(optimizer.state) == len(list(policy.parameters())),
            'Pretrained joint Adam carry must retain every parameter state.')
    for parameter, state in optimizer.state.items():
        require(set(state) == {'step', 'exp_avg', 'exp_avg_sq'} and state['step'].numel() == 1
                and state['step'].item() >= 0
                and all(backend.torch.isfinite(value).all().item() for value in state.values())
                and (state['exp_avg_sq'] >= 0).all().item()
                and all(state[key].shape == parameter.shape and state[key].dtype == parameter.dtype
                        for key in ('exp_avg', 'exp_avg_sq')),
                'Joint Adam tensors must be finite and match their actor parameters.')
    require(backend.encode_optimizer(optimizer) == payload['optimizer'],
            'Restoring joint Adam must preserve its exact encoded values.')
    return payload


def initial_bundle(configuration, baseline_sha, models, checkpoints):
    """The first joint experiment starts only vanilla32 cards and qualified purchase32."""
    expected = {'card': '9ca29345acb4cf900d9d28861249695a69f5638174da9db4c81187ca1aacc719',
                'purchase': '3802440bc84830724e32c8f32f273648f38128fc7fc1774a9c586e5b1b1b2aa4'}
    require(set(models) == set(checkpoints) == set(PHASES)
            and all(sha(models[phase]) == expected[phase] for phase in PHASES),
            'Initialize from exact vanilla32 and qualified purchase32; no alternative card checkpoint.')
    pins = {}
    for phase in PHASES:
        validate_carry(models[phase], checkpoints[phase], CONTRACTS[phase], backend.UpdateConfig(), pins)
    unchanged(pins)
    return dict(schemaVersion=1, kind='joint-card-purchase-1', configuration=configuration,
        baselineSha256=baseline_sha,
        **{phase: dict(path=str(Path(models[phase]).resolve()), sha256=expected[phase]) for phase in PHASES})


def immutable_json(path, value):
    path = Path(path)
    if path.exists():
        require(read(path) == value, 'Preserve existing joint update evidence; identity or result changed.')
    else:
        backend.atomic_json(path, value)


def completed_phase(output, collection, phase, config, carry, purchase_reference):
    """Bind checked-prefix completion to the exact shared data and original typed actor."""
    output = Path(output)
    identity, report = read(output/'identity.json'), read(output/'finished.json')
    payload = backend.read_checkpoint(output/'checkpoint.json')
    descriptor = collection.bundle[phase]
    _, data = collection.phase_reader(collection.directory, descriptor['path'], collection.models[phase],
                                      contract=CONTRACTS[phase])
    expected = dict(schemaVersion=1, actorVersion=CONTRACTS[phase].actor,
        modelSha256=descriptor['sha256'], data=data, config=asdict(config), learnerSha256=sha(backend.__file__),
        entrypointSha256=sha(__file__), torchVersion=backend.torch.__version__, pythonVersion=sys.version,
        optimizerFromSha256=sha(carry), updateMode=backend.CHECKED_PREFIX_MODE,
        collectionReaderSource=dict(path=str(Path(__file__).resolve()), sha256=sha(__file__)))
    if purchase_reference is not None:
        expected['purchaseReferenceObjective'] = asdict(purchase_reference)
    require({key: value for key, value in identity.items() if key != 'deadlineEpochSeconds'} == expected,
            'Typed fit identity must bind this phase, old bundle, shared rows, objective and Adam.')
    summary = backend.validate_checked_prefix_checkpoint(payload, identity, collection.rows[phase])
    require(report['schemaVersion'] == 2 and report['updateMode'] == backend.CHECKED_PREFIX_MODE
            and report['identitySha256'] == backend.digest(identity)
            and report['inputModelSha256'] == descriptor['sha256']
            and report['outputModelSha256'] == sha(output/'policy.json')
            and read(output/'policy.json') == payload['model']
            and report['history'] == payload['history'] and report['acceptedEpochs'] == payload['epoch']
            and report['games'] == collection.common['games'] and report['decisions'] == len(collection.rows[phase])
            and report['gameMeanReward'] == collection.common['gameMeanReward']
            and report['stopReason'] == (payload['stopReason'] or 'epochs-complete')
            and all(report[key] == value for key, value in summary.items())
            and report.get('purchaseReferenceObjective') == expected.get('purchaseReferenceObjective'),
            'Both typed fits must finish with matching complete schema-2 evidence.')
    require(report['stopReason'] != 'time-budget',
            'An exhausted typed update cannot publish half of a new joint behavior bundle.')
    validate_carry(output/'policy.json', output/'checkpoint.json', CONTRACTS[phase], config, {})
    return dict(identitySha256=backend.digest(identity), policySha256=sha(output/'policy.json'),
        checkpointSha256=sha(output/'checkpoint.json'), completionSha256=sha(output/'finished.json'),
        decisions=len(collection.rows[phase]), **summary)


def update_pair(directory, bundle_path, configs, checkpoints, output, *,
                purchase_reference=None, resume=False):
    """Run two checked-prefix fits on one frozen batch; atomically publish only their valid pair.

    Completed first-phase artifacts may remain after interruption, but no joint bundle is
    published until both fits pass. Resume reuses those completed artifacts and their original
    per-fit deadlines. No original initializer or previously fitted sibling supplies new labels.
    """
    require(set(configs) == set(checkpoints) == set(PHASES), 'Provide both typed configs and original Adam carries.')
    for phase in PHASES:
        configs[phase].validate(CONTRACTS[phase])
        require(configs[phase].threads == 1, 'Joint numerical updates use one CPU thread.')
    backend.validate_purchase_reference(purchase_reference, backend.PURCHASE_CONTRACT, backend.CHECKED_PREFIX_MODE)
    backend.torch.set_num_threads(1)
    collection = JointCollection(directory, bundle_path)
    require(all(collection.rows[phase] for phase in PHASES),
            'Reject an entirely empty typed phase before either fit or pair publication.')
    pins = dict(collection.pins)
    for phase in PHASES:
        validate_carry(collection.bundle[phase]['path'], checkpoints[phase], CONTRACTS[phase], configs[phase], pins)
    for path in (Path(__file__), Path(backend.__file__), Path(card.__file__)):
        pin(path, pins)
    identity = dict(kind='joint-two-actor-update', schemaVersion=1, collection=collection.common,
        behaviorBundle=collection.bundle, configs={phase: asdict(configs[phase]) for phase in PHASES},
        optimizerFrom={phase: dict(path=str(Path(checkpoints[phase]).resolve()), sha256=sha(checkpoints[phase]))
                       for phase in PHASES}, purchaseReferenceObjective=asdict(purchase_reference) if purchase_reference else None,
        updateMode=backend.CHECKED_PREFIX_MODE, inputHashes=pins)
    output = Path(output).resolve()
    require(not output.exists() or resume and (output/'identity.json').exists(),
            'Use a fresh joint output or explicitly resume its original identity.')
    immutable_json(output/'identity.json', identity)
    reports, output_pins = {}, {}
    for phase in PHASES:
        target = output/phase
        objective = purchase_reference if phase == 'purchase' else None
        if not (target/'finished.json').exists():
            backend.update(collection.bundle[phase]['path'], collection.directory, configs[phase], target,
                resume=target.exists(), optimizer_from=checkpoints[phase], contract=CONTRACTS[phase],
                entrypoint=__file__, update_mode=backend.CHECKED_PREFIX_MODE,
                purchase_reference=objective, collection_reader=collection.phase_reader)
        reports[phase] = completed_phase(target, collection, phase, configs[phase], checkpoints[phase], objective)
        immutable_json(output/(phase+'-completed.json'), reports[phase])
        for path in (target/'policy.json', target/'checkpoint.json', target/'identity.json',
                     target/'finished.json', output/(phase+'-completed.json')):
            pin(path, output_pins)
    unchanged(pins)
    unchanged(output_pins)
    bundle = dict(schemaVersion=1, kind='joint-card-purchase-1', configuration=collection.configuration,
        baselineSha256=collection.baseline_sha,
        **{phase: dict(path=str(output/phase/'policy.json'), sha256=reports[phase]['policySha256']) for phase in PHASES})
    immutable_json(output/'bundle.json', bundle)
    report = dict(kind='joint-two-actor-update', schemaVersion=1, identitySha256=backend.digest(identity),
        behaviorBundleSha256=collection.bundle['sha256'], outputBundleSha256=sha(output/'bundle.json'),
        commonOrigins=collection.origins, gameHashes=collection.common['gameHashes'], phases=reports,
        outputHashes=output_pins, allInputHashesUnchanged=True, strengthClaim=False, policyPromotion=False)
    immutable_json(output/'finished.json', report)
    return report
