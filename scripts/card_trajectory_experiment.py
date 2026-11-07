"""Bounded repeated card-PPO signal using the unchanged actor, collector and numerical backend.

An optional frozen physical-card reference profile changes training only.
One original three-hour clock covers 32 complete batches, deterministic optimizer carry,
four fixed development checkpoints and one optional fresh confirmation. Reversal failures
remain explicit. Neither parameter movement nor a passed development gate activates a policy.
"""
import argparse
from datetime import datetime
import fcntl
import json
import math
import os
from pathlib import Path
import signal
import sys
import time

sys.dont_write_bytecode = True
import card_advantage_experiment as experiment
import card_policy as card
import card_trajectory_analysis as analysis
from joint_economy import terminal_metrics

require, read, sha, document = experiment.require, experiment.read, experiment.sha, experiment.document
backend = card.backend
BASE_SHA = 'ca5a700523e29d5084bf754d6a19ff2051d618ee844ec8a617aee53701bed140'
PURCHASE_SHA = '3802440bc84830724e32c8f32f273648f38128fc7fc1774a9c586e5b1b1b2aa4'
PRIORITY_SHA = 'caf24d21d2304ead3a090a9e0dff8e61bfbf32578af171a2e451677d271acf5b'
INITIAL_SHA = '6f5703f919573dc8f4ca482c13301c9f4479ab7ecb16e367f2157d3061468ee3'
RESOURCES = dict(collectionWorkers=6, collectionHeapGiB=6, collectionActiveProcessors=6,
                 collectionGcThreads=1, evaluationWorkers=6, evaluationHeapGiBPerWorker=1,
                 evaluationActiveProcessorsPerWorker=1, fitThreads=1,
                 maximumNewDataBytes=4*1024**3, minimumDiskFreeBytes=8*1024**3)
EVALUATION = dict(batchSize=8, maximumSecondsPerStage=3600)
HELPERS = ('card_trajectory_experiment.py', 'card_trajectory_analysis.py', 'card_policy.py',
           'purchase_policy.py', 'joint_economy.py', 'card_advantage_experiment.py',
           'card_advantage_evaluation.py', 'card-advantage-run.py')


def python_runtime():
    return dict(executable=str(Path(sys.executable).absolute()), executableSha256=sha(sys.executable),
                version=sys.version, numpyVersion=analysis.np.__version__, torchVersion=backend.torch.__version__)


PROFILE = 'physical-card-reference-1'
REFERENCE_VERSION = 'aor-card-physical-reference-1'
CONTINUATION_PROFILE = 'vanilla-continuation-1'
VANILLA32_SHA = '9ca29345acb4cf900d9d28861249695a69f5638174da9db4c81187ca1aacc719'
CONTINUATION_TRAINING = dict(analysis.TRAINING, initialLifetimeUpdates=32,
    rivalSchedule='odd batches F; even batches frozen vanilla32; all purchase32')
CONTINUATION_DEVELOPMENT = dict(analysis.DEVELOPMENT, minimumGainVsVanilla=.03,
    progressRule='new accepted epoch and current policy SHA differs from vanilla32; original-prior KL is an additional guard')
CONTINUATION_CONFIRMATION = dict(analysis.CONFIRMATION, minimumGainVsVanilla=.03)
REFERENCE_COEFFICIENTS = [.1] * 16 + [0.] * 16
EXPLORATION_DEVELOPMENT = dict(analysis.DEVELOPMENT, selectionMilestones=[24, 32],
    harmStopMilestones=[24, 32], diagnosticOnlyMilestones=[8, 16])


def reference_coefficients(protocol):
    """An explicit prospective schedule; no adaptation to rewards or milestone outcomes."""
    profile = protocol.get('trainingProfile')
    if profile is None:
        require('cardReferenceCoefficients' not in protocol and 'vanillaComparator' not in protocol,
                'The original trajectory profile has no extra schedule or comparator.')
        return None
    require(profile in (PROFILE, CONTINUATION_PROFILE) and protocol.get('cardReferenceVersion') == REFERENCE_VERSION,
            'Unsupported physical-card reference training profile.')
    values = protocol.get('cardReferenceCoefficients')
    expected = [0.] * 32 if profile == CONTINUATION_PROFILE else REFERENCE_COEFFICIENTS
    require(isinstance(values, list) and len(values) == 32
            and all(type(value) in (int, float) and math.isfinite(value) and 0 <= value <= 1 for value in values)
            and values == expected,
            'Freeze exactly 32 finite coefficients matching the declared training profile.')
    return values


def update_config(index, coefficients=None):
    require(type(index) is int and 1 <= index <= 32, 'Update index must be in 1..32.')
    values = dict(analysis.PPO, seed=analysis.TRAINING['updateSeeds'][index-1])
    if coefficients is not None:
        values.update(cardReferenceKlCoefficient=coefficients[index-1], cardReferenceVersion=REFERENCE_VERSION)
    # Backend-owned defaults are explicit in the immutable update identity, including zero regularization.
    return backend.asdict(backend.UpdateConfig(**values))


def training_source(base, offset, model, opponent_model=None):
    source = dict(base, games=256, seed=offset, opponents=[base['baseline']]*4, policy=str(model))
    if opponent_model is not None:
        source['opponentCardPolicy'] = str(opponent_model)
    return source


def completed_training(directory, source, seeds, model_path, opponent_model=None):
    """Bind all compressed games before any outcomes; stream compact training provenance only."""
    directory, source, model_path = Path(directory), Path(source), Path(model_path)
    if not (directory / 'finished.json').exists(): return None
    spec, manifest, done, state = (read(path) for path in
        (source, directory / 'collection.json', directory / 'finished.json', directory / 'state.json'))
    require(manifest['kind'] == done['kind'] == state['kind'] == 'card-collect'
            and state['phase'] == 'complete' and state['completedGames'] == spec['games']
            and manifest['signature'] == done['signature'] == state['signature']
            and manifest['schemaVersion'] == 1 and manifest['trajectoryEncoding'] == 'gzip-json-v1'
            and manifest['mode'] == 'on-policy' and manifest['configuration'] == spec['configuration']
            and manifest['source'] == str(source.resolve()) and manifest['sourceSha256'] == sha(source)
            and (directory / 'source.json').read_bytes() == source.read_bytes()
            and manifest['games'] == done['games'] == spec['games'] == len(seeds)
            and manifest['seed'] == spec['seed'] and manifest['seeds'] == seeds,
            'Training must be the exact complete declared card collection.')
    def snapshot(path, name):
        path=Path(path).resolve(strict=True)
        require((directory / name).read_bytes() == path.read_bytes(), 'A frozen training snapshot changed: '+name)
        return dict(path=str(path),sha256=sha(path))
    require(manifest['baseline'] == snapshot(spec['baseline'],'baseline.json')
            and manifest['frozenPurchasePolicy'] == snapshot(spec['frozenPurchasePolicy'],'frozen-purchase-policy.json')
            and manifest['opponents'] == [snapshot(path,f'opponent-{index}.json') for index,path in enumerate(spec['opponents'])],
            'Training baseline, purchase32 or rival identities changed.')
    if opponent_model is None:
        require(spec.get('opponentCardPolicy') is None and manifest.get('opponentCardPolicy') is None,
                'F-rival batches cannot silently activate a learned opponent.')
    else:
        require(Path(spec['opponentCardPolicy']).resolve() == Path(opponent_model).resolve()
                and manifest.get('opponentCardPolicy') == snapshot(opponent_model, 'opponent-card-policy.json'),
                'The complete batch must use the exact frozen opponent actor snapshot.')
    require(Path(spec['policy']).resolve() == model_path.resolve()
            and manifest['policy'] == str(model_path.resolve())
            and manifest['modelSha256'] == snapshot(model_path,'policy.json')['sha256'],
            'Every trajectory must use the exact preceding actor bytes.')
    model=card.validate_model(read(model_path))
    require(manifest['actorVersion'] == card.ACTOR_VERSION and manifest['encoderVersion'] == card.ENCODER_VERSION
            and all(manifest[key] == model[key] for key in ('stateFeatureNames','actionFeatureNames')),
            'Card training dictionaries and actor contract must remain unchanged.')
    paths=[directory / f'game-{index}.json.gz' for index in range(len(seeds))]
    hashes=[sha(path) for path in paths]
    require(done['gameSha256'] == hashes and set(directory.glob('game-*')) == set(paths),
            'Every compressed game must match complete hashes before any trajectory is decoded.')
    metadata={name:sha(directory / name) for name in ('collection.json','finished.json','state.json')}
    rewards=[]; decisions=0
    for index,path in enumerate(paths):
        row=backend.read_game(path,card.CONTRACT)
        expected=dict(index=index,signature=manifest['signature'],configuration=spec['configuration'],
            gameSeed=seeds[index],controlledSeat=index%4,opponentIndices=[(index//4+seat)%4 for seat in range(4)],
            mode='on-policy',modelSha256=sha(model_path),encoderVersion=card.ENCODER_VERSION,
            actorVersion=card.ACTOR_VERSION,frozenPurchasePolicySha256=manifest['frozenPurchasePolicy']['sha256'])
        require(all(row.get(key)==value for key,value in expected.items()), 'Training origin/policy schedule changed.')
        terminal_metrics(row)
        rewards.append(row['terminal']['reward']);decisions+=len(row['decisions'])
    require(all(sha(directory/name)==value for name,value in metadata.items())
            and [sha(path) for path in paths] == hashes, 'Training evidence changed during its completed audit.')
    provenance=dict(collectionSha256=metadata['collection.json'],collectionSignature=manifest['signature'],
        gameHashes=hashes,games=len(seeds),gameMeanReward=sum(rewards)/len(rewards),
        frozenPurchasePolicySha256=manifest['frozenPurchasePolicy']['sha256'])
    return dict(data=provenance,decisions=decisions,completionSha256=metadata['finished.json'])


def completed_update(output, expected, collected, input_model):
    """Validate the unchanged PPO identity and exact preceding Adam/model carry chain."""
    output=Path(output)
    if not (output/'finished.json').exists():return None
    identity, report=read(output/'identity.json'),read(output/'finished.json')
    payload=backend.read_checkpoint(output/'checkpoint.json')
    require({key:value for key,value in identity.items() if key!='deadlineEpochSeconds'}==expected
            and type(identity.get('deadlineEpochSeconds')) in (int,float)
            and math.isfinite(identity['deadlineEpochSeconds']), 'Completed PPO inputs/runtime/carry identity changed.')
    require(payload['identitySha256']==backend.digest(identity)
            and payload['deadlineEpochSeconds']==identity['deadlineEpochSeconds']
            and payload['model']==read(output/'policy.json') and isinstance(payload['optimizer'],dict),
            'Completed PPO checkpoint/model/optimizer identity changed.')
    fields=dict(identitySha256=backend.digest(identity),inputModelSha256=expected['modelSha256'],
        outputModelSha256=sha(output/'policy.json'),games=collected['data']['games'],decisions=collected['decisions'],
        gameMeanReward=collected['data']['gameMeanReward'],acceptedEpochs=payload['epoch'],history=payload['history'],
        stopReason=payload['stopReason'] or 'epochs-complete',elapsedSeconds=payload['elapsedSeconds'],strengthClaim=False)
    require(all(report.get(key)==value for key,value in fields.items())
            and payload['epoch']==sum(row['accepted'] is True for row in payload['history']),
            'PPO completion disagrees with its exact checkpoint and full training batch.')
    require(payload['epoch'] > 0 or payload['model']==read(input_model),
            'A zero-accepted-epoch update must carry the identical preceding actor parameters.')
    card.validate_model(payload['model'])
    return report


def validate_continuation_checkpoint(model_path, checkpoint_path, identity_path, completion_path):
    """Validate the original update32 model, PPO identity and exact restorable Adam carry."""
    checkpoint_path, identity_path, completion_path = map(Path, (checkpoint_path, identity_path, completion_path))
    require(checkpoint_path.name == 'checkpoint.json' and identity_path == checkpoint_path.parent/'identity.json'
            and completion_path == checkpoint_path.parent/'finished.json',
            'Continuation starts from the original co-located update32 evidence.')
    model = card.validate_model(read(model_path))
    identity, completion = read(identity_path), read(completion_path)
    payload = backend.read_checkpoint(checkpoint_path)
    require(identity['schemaVersion'] == 1 and identity['actorVersion'] == card.ACTOR_VERSION
            and identity['config'] == dict(analysis.PPO, seed=74632)
            and identity['data']['games'] == 256 and identity['optimizerFromSha256'] is not None
            and payload['model'] == model and completion['outputModelSha256'] == sha(model_path),
            'The start must retain original vanilla32 PPO configuration, model and carried optimizer lineage.')
    expected = {key:value for key,value in identity.items() if key != 'deadlineEpochSeconds'}
    collected = dict(data=identity['data'], decisions=completion['decisions'])
    completed_update(checkpoint_path.parent, expected, collected, model_path)
    backend.torch.set_num_threads(1)
    policy = backend.ResidualPolicy(model, card.CONTRACT)
    optimizer = backend.torch.optim.Adam(policy.parameters(), lr=analysis.PPO['learningRate'], foreach=False)
    defaults = [{key:value for key,value in group.items() if key != 'params'}
                for group in backend.encode_optimizer(optimizer)['param_groups']]
    backend.restore_optimizer(optimizer, payload['optimizer'])
    require([{key:value for key,value in group.items() if key != 'params'}
             for group in backend.encode_optimizer(optimizer)['param_groups']] == defaults,
            'Original Adam hyperparameters must match the unchanged PPO optimizer.')
    require(len(optimizer.state) == len(list(policy.parameters())), 'Original Adam must retain every parameter state.')
    for parameter, state in optimizer.state.items():
        require(set(state) == {'step','exp_avg','exp_avg_sq'}
                and state['step'].numel() == 1 and state['step'].item() >= 0
                and all(backend.torch.isfinite(value).all().item() for value in state.values())
                and all(state[key].shape == parameter.shape and state[key].dtype == parameter.dtype
                        for key in ('exp_avg','exp_avg_sq')),
                'Original Adam tensors must be finite and match their actor parameters.')
    require(backend.encode_optimizer(optimizer) == payload['optimizer'],
            'Restoring the original Adam state must preserve its exact encoded values.')
    return payload


def continuation_progress(progress, current_model, starting_model):
    """Existing prior KL is not evidence that continuation training changed its initial actor."""
    value = dict(progress)
    changed = sha(current_model) != sha(starting_model)
    value.update(continuationUpdates=progress['completedUpdates'], lifetimeUpdates=32+progress['completedUpdates'],
        startingModelSha256=sha(starting_model), currentModelSha256=sha(current_model),
        newAcceptedEpochs=progress['totalAcceptedEpochs'], newPolicyBytesChanged=changed,
        originalPriorKlIsContinuationMovementEvidence=False,
        noProgress=progress['noProgress'] or progress['totalAcceptedEpochs'] == 0 or not changed)
    return value


def analyze_request(path):
    request=read(path)
    args=(request['seeds'],Path(request['controls']),Path(request['controlsSource']),
          Path(request['candidate']),Path(request['candidateSource']),request['base'],
          Path(request['initial']),Path(request['model']),Path(request['priority'])) if request['stage']=='development' else None
    options = dict(vanilla=request.get('vanilla'),
                   require_vanilla_superiority=request.get('requireVanillaSuperiority', False),
                   minimum_vanilla_gain=request.get('minimumGainVsVanilla', 0.))
    report=analysis.analyze_development_completed(*args, **options) if args else analysis.analyze_completed(
        'confirmation',request['seeds'],Path(request['candidate']),Path(request['candidateSource']),
        request['base'],Path(request['initial']),Path(request['model']),Path(request['priority']), **options)
    if 'checkpoint' in request:
        checkpoint = request['checkpoint']
        require(set(checkpoint) == {'continuationUpdate','lifetimeUpdate'}
                and type(checkpoint['continuationUpdate']) is int
                and checkpoint['continuationUpdate'] in analysis.MILESTONES
                and checkpoint['lifetimeUpdate'] == 32 + checkpoint['continuationUpdate'],
                'Continuation evaluation must identify its exact new and lifetime update counts.')
        report['checkpoint'] = checkpoint
    if request.get('diagnosticOnlyCheckpoint', False):
        require(request['stage'] == 'development', 'Only early development checkpoints are diagnostic-only.')
        report.update(checkpointUse='diagnostic-only exploration',
            diagnosticEligible=report['eligible'], diagnosticHarmDetected=report['harmful'],
            eligible=False, harmful=False)
    output=Path(request['output']);document(output,report)
    document(output.with_suffix('.binding.json'),dict(requestSha256=sha(path),reportSha256=sha(output)))


class TrajectoryWindow(experiment.Window):
    def __init__(self,lab,protocol_path):
        super().__init__(lab,protocol_path)
        self.preflight=read(self.lab/'preflight.json')
        require(self.protocol['kind']=='card-trajectory-window' and self.protocol['schemaVersion']==1,
                'Unsupported repeated card trajectory protocol.')
        recipe=read(self.pin(self.protocol['recipeDraft']))
        self.coefficients = reference_coefficients(self.protocol)
        self.continuation = self.protocol.get('trainingProfile') == CONTINUATION_PROFILE
        require(self.coefficients == reference_coefficients(recipe)
                and all(self.protocol.get(key) == recipe.get(key) for key in
                        ('trainingProfile', 'cardReferenceVersion', 'vanillaComparator', 'explorationEvidence',
                         'continuationStart', 'barrierDisposition')),
                'The prospective exploration schedule, comparator and evidence must match the frozen recipe.')
        self.vanilla = None
        self.vanilla_superiority = False
        self.selection_milestones = (24, 32) if self.protocol.get('trainingProfile') == PROFILE else analysis.MILESTONES
        expected=dict(training=CONTINUATION_TRAINING if self.continuation else analysis.TRAINING,
            ppo=analysis.PPO,development=(CONTINUATION_DEVELOPMENT if self.continuation else
            EXPLORATION_DEVELOPMENT if self.coefficients is not None else analysis.DEVELOPMENT),
            confirmation=CONTINUATION_CONFIRMATION if self.continuation else analysis.CONFIRMATION,bootstrap=analysis.BOOTSTRAP,resources=RESOURCES,
            evaluation=EVALUATION,originRequests={})
        require(all(self.protocol[key]==recipe[key]==value for key,value in expected.items())
                and self.protocol['configuration']==recipe['configuration']
                and self.protocol['seedReservation']==recipe['seedReservation']
                and self.protocol['pythonRuntime']==recipe['pythonRuntime']==python_runtime(),
                'Keep the exact original PPO, fixed gates/banks and numerical runtime.')
        start=datetime.fromisoformat(self.clock['startedAt'].replace('Z','+00:00')).timestamp()
        require(self.deadline-start==self.clock['originalAllowanceSeconds']==10800
                and self.clock['renewOnResume'] is False, 'Training and evaluations share one original three-hour clock.')
        reservation=read(self.lab/self.protocol['seedReservation']['file'])
        rows={row['name']:row for row in reservation['banks']}
        names={f'training-{i:02}' for i in range(1,33)}|{'development','confirmation'}
        require(set(rows)==names and len(self.banks['development'])==256 and len(self.banks['confirmation'])==1024,
                'Use exactly32 training banks and the two complete declared evaluation banks.')
        self.offsets={name:rows[name]['seedOffset'] for name in names if name.startswith('training-')}
        require(all(type(offset) is int and 0<=offset<=100000000 and self.banks[name]==[
            -1000000000+offset+i*8191 for i in range(256)] for name,offset in self.offsets.items()),
            'Training banks must match the unchanged native arena offset and index schedule.')
        seeds=[seed for bank in self.banks.values() for seed in bank]
        require(len(seeds)==len(set(seeds))==9472 and all(type(seed) is int for seed in seeds),
                'All9472 training/development/confirmation worlds must be distinct.')
        self.verify_prerequisites()

    def pin(self,entry):
        path=(self.lab/entry['file']).resolve(strict=True)
        require(sha(path)==entry['sha256']==self.preflight['pins'].get(str(path)), 'Frozen prerequisite changed: '+str(path))
        return path

    def verify_prerequisites(self):
        prior=self.protocol['prerequisites'];self.initial=self.pin(prior['initial']);self.priority=self.pin(prior['priority'])
        require(sha(self.initial)==INITIAL_SHA and sha(self.priority)==PRIORITY_SHA
                and sha(self.base['baseline'])==BASE_SHA and sha(self.base['frozenPurchasePolicy'])==PURCHASE_SHA,
                'Retain exact original initial card actor, F/purchase32 and priority64 comparator.')
        initial=card.validate_model(read(self.initial))
        require(initial['baselineProbability']==.97 and initial['actorWeights']==[0.]*64
                and initial['valueWeights']==[0.]*64 and initial['valueBias']==0.,
                'Retain the unchanged97% recommendation prior as the original initial-actor control.')
        initialization=read(self.pin(prior['initialization']));binding=read(self.pin(prior['initialCompletion']))
        require(initialization['seed']==74401 and initialization['sha256']==binding['policySha256']==INITIAL_SHA,
                'Reuse the original verified stationary-smoke initialization.')
        proof=read(self.pin(prior['runtimeProof']))
        require(proof['kind']=='card-trajectory-runtime-proof' and proof['passed'] is True
                and proof['initialSha256']==INITIAL_SHA and proof['baselineSha256']==BASE_SHA
                and proof['purchaseSha256']==PURCHASE_SHA and proof['prioritySha256']==PRIORITY_SHA
                and all(proof[key] is True for key in ('initialDictionaryMatches','legacyCardCollectionVerified',
                    'evaluationVerified','oldPurchaseParity','allPinsUnchanged')) and proof['newWorlds']==0
                and proof['runtimeJars']=={p.name:sha(p) for p in self.runtime.glob('*.jar')},
                'The new registry must preserve the unchanged collector/actor/purchase contracts on consumed inputs.')
        require(proof['sourceHashes'] and all(sha(path)==value==self.preflight['pins'].get(str(Path(path).resolve()))
                for path,value in proof['sourceHashes'].items()), 'Runtime proof inputs must remain pinned.')
        # These failures constrain interpretation; the experiment does not claim they were repaired.
        for name in ('originalToyReport','productionToyReport'):
            require(read(self.pin(prior[name]))['passed'] is False, 'Preserve both original reversal failures.')
        self.pin(prior['productionToyProtocol'])
        self.start_model = self.initial
        self.start_checkpoint = None
        if self.coefficients is not None:
            self.verify_vanilla()
            if self.continuation:
                self.verify_continuation()
            else:
                self.verify_exploration()
        self.java=self.protocol['javaRuntime']
        require(sha(self.java['executable'])==self.java['sha256']
                and str((Path(os.environ.get('JAVA_HOME',''))/'bin/java').resolve())==self.java['executable'],
                'Use the exact pinned Java21 executable for every collector/evaluator.')
        for path in [*(self.scripts/name for name in HELPERS),*self.runtime.glob('*.jar'),
                     Path(self.base['baseline']),Path(self.base['frozenPurchasePolicy']),Path(sys.executable),
                     Path(self.java['executable'])]:
            require(self.preflight['pins'].get(str(path.resolve()))==sha(path), 'Executed runtime/input not pinned: '+str(path))

    def verify_vanilla(self):
        comparator = self.protocol['vanillaComparator']
        self.vanilla = self.pin(comparator)
        evidence = read(self.pin(comparator['evidence']))
        batch = comparator['batch']
        require(type(batch) is int and 1 <= batch <= 32
                and type(comparator['superiorityRequired']) is bool
                and comparator['superiorityRequired'] == (batch == 32)
                and evidence['kind'] == 'completed-card-trajectory-comparator'
                and evidence['passed'] is True and evidence['batch'] == batch
                and evidence['modelSha256'] == sha(self.vanilla)
                and evidence['selection'] == 'highest successfully completed update after final disposition'
                and evidence['sourcePins'] and all(sha(path) == value == self.preflight['pins'].get(str(Path(path).resolve()))
                    for path, value in evidence['sourcePins'].items()),
                'Use the exact last completed vanilla update, never an outcome-selected checkpoint.')
        model = card.validate_model(read(self.vanilla))
        initial = read(self.initial)
        require(all(model[key] == initial[key] for key in ('encoderVersion', 'stateFeatureNames', 'actionFeatureNames')),
                'The frozen vanilla comparator must share the exact actor dictionaries.')
        self.vanilla_superiority = comparator['superiorityRequired']

    def verify_exploration(self):
        proof = read(self.pin(self.protocol['explorationEvidence']))
        require(proof['kind'] == 'card-reference-profile-evidence' and proof['passed'] is True
                and proof['referenceVersion'] == REFERENCE_VERSION and proof['sourcePins']
                and all(sha(path) == value == self.preflight['pins'].get(str(Path(path).resolve()))
                        for path, value in proof['sourcePins'].items()),
                'Physical-card exploration requires pinned passing backend/toy evidence.')

    def verify_continuation(self):
        comparator = self.protocol['vanillaComparator']
        evidence = read(self.pin(comparator['evidence']))
        require(comparator['batch'] == 32 and comparator['superiorityRequired'] is True
                and sha(self.vanilla) == VANILLA32_SHA,
                'Continuation must start from the exact frozen vanilla32 actor, not another checkpoint.')
        start = self.protocol['continuationStart']
        require(set(start) == {'checkpoint','identity','completion'}, 'Bind the complete original optimizer start evidence.')
        paths = {key:self.pin(entry) for key,entry in start.items()}
        require(all(evidence['sourcePins'].get(str(path.resolve())) == sha(path) for path in paths.values()),
                'Optimizer start evidence must belong to the fully audited vanilla32 lineage.')
        validate_continuation_checkpoint(self.vanilla, paths['checkpoint'], paths['identity'], paths['completion'])
        barrier = self.protocol['barrierDisposition']
        require(set(barrier) == {'result','status','preflight','protocol'}, 'Bind the complete prior barrier disposition.')
        bound = {key:self.pin(entry) for key,entry in barrier.items()}
        result,status,preflight,protocol = (read(bound[key]) for key in ('result','status','preflight','protocol'))
        failed = {'trajectory-no-probability-movement','trajectory-development-harm',
                  'trajectory-no-eligible-checkpoint','trajectory-independent-qualification-failed',
                  'trajectory-original-budget-exhausted'}
        require(result['disposition'] in failed and result['browserActivated'] is False
                and result.get('confirmationPassed') is not True and status['stage'] == 'finished'
                and all(status.get(key) == value for key,value in result.items())
                and result['preflightSha256'] == sha(bound['preflight'])
                and preflight['windowSha256'] == sha(bound['protocol'])
                and protocol['trainingProfile'] == PROFILE,
                'A completed nonqualified barrier trial is required; never overlap or replace a qualified result.')
        self.start_model = self.vanilla
        self.start_checkpoint = paths['checkpoint']



    def model(self,index):
        return getattr(self,'start_model',self.initial) if index==0 else self.lab/f'update-{index:02}/policy.json'

    def stage_clock(self,name,inputs,seconds,progressed):
        path=self.lab/(name+'-clock.json');binding=path.with_suffix('.binding.json')
        if not path.exists():
            require(not progressed and not binding.exists(), 'A missing stage clock cannot be renewed after progress.')
            now=time.time();document(path,dict(startedAt=now,deadline=min(self.deadline,now+seconds),inputs=inputs))
        clock=read(path)
        require(type(clock['startedAt']) in (int,float) and math.isfinite(clock['startedAt'])
                and 0<clock['startedAt']<=time.time()+5 and clock['deadline']==min(self.deadline,clock['startedAt']+seconds)
                and clock['inputs']==inputs and (binding.exists() or not progressed),
                'Retain the original stage deadline, input identity and binding on resume.')
        document(binding,{'sha256':sha(path)});return clock['deadline']

    def collect(self,index):
        name=f'training-{index:02}';source=self.lab/(name+'-source.json');output=self.lab/name;model=self.model(index-1)
        rival = self.vanilla if getattr(self,'continuation',False) and index % 2 == 0 else None
        document(source,training_source(self.base,self.offsets[name],model,rival))
        completed=completed_training(output,source,self.banks[name],model,rival)
        if completed is not None:return output,completed
        seconds=analysis.TRAINING['maximumCollectionSeconds']
        deadline=self.stage_clock(name,dict(sourceSha256=sha(source),modelSha256=sha(model)),seconds,
                                  output.exists() or (self.lab/(name+'.log')).exists())
        remaining=min(seconds,deadline-time.time())
        if remaining<=0:raise experiment.StageTimeout('The original card collection allowance elapsed.')
        command=['/usr/bin/env','-u','JAVA_TOOL_OPTIONS','-u','JDK_JAVA_OPTIONS','-u','_JAVA_OPTIONS',
            'AOR_TRAINING_MACHINE_CPUS='+str(os.cpu_count()),self.java['executable'],'-Xmx6g',
            '-XX:ActiveProcessorCount=6','-XX:ParallelGCThreads=1','-XX:ConcGCThreads=1',
            '-cp',str(self.runtime/'*'),'dev.aragos.aor.training.TrainingMainKt','--mode','card-collect',
            '--source',str(source),'--output',str(output),'--workers','6','--hours',str(remaining/3600)]
        self.execute(name,command,seconds,deadline)
        completed=completed_training(output,source,self.banks[name],model,rival)
        require(completed is not None,'Card collection must finish completely before PPO sees any trajectories.')
        return output,completed

    def update(self,index,collection,collected):
        name=f'update-{index:02}';output=self.lab/name;model=self.model(index-1)
        carry=self.lab/f'update-{index-1:02}/checkpoint.json' if index>1 else getattr(self,'start_checkpoint',None)
        if carry:
            checkpoint=backend.read_checkpoint(carry)
            require(checkpoint['model']==read(model) and isinstance(checkpoint['optimizer'],dict),
                    'Carry Adam only from the exact preceding model checkpoint.')
        config=update_config(index,getattr(self,'coefficients',None))
        config_path=self.lab/(name+'-config.json');document(config_path,config)
        expected=dict(schemaVersion=1,actorVersion=card.ACTOR_VERSION,modelSha256=sha(model),data=collected['data'],
            config=config,learnerSha256=sha(self.scripts/'purchase_policy.py'),entrypointSha256=sha(self.scripts/'card_policy.py'),
            torchVersion=backend.torch.__version__,pythonVersion=sys.version,
            optimizerFromSha256=sha(carry) if carry else None)
        document(self.lab/(name+'-inputs.json'),expected)
        report=completed_update(output,expected,collected,model)
        if report is None:
            deadline=self.stage_clock(name,dict(inputsSha256=sha(self.lab/(name+'-inputs.json'))),300,
                output.exists() or (self.lab/(name+'.log')).exists())
            command=[sys.executable,'-B',str(self.scripts/'card_policy.py'),'update','--source',str(collection),
                '--model',str(model),'--config',str(config_path),'--seed',str(config['seed']),'--output',str(output)]
            if carry:command+=['--optimizer-from',str(carry)]
            if output.exists():
                require((output/'identity.json').exists(),'Partial update requires its original identity to resume.')
                command.append('--resume')
            self.execute(name,command,300,deadline)
            report=completed_update(output,expected,collected,model)
        require(report is not None,'PPO must finish and bind its exact checkpoint before the next collection.')
        document(self.lab/(name+'-completed.json'),dict(inputsSha256=sha(self.lab/(name+'-inputs.json')),
            files={name:sha(output/name) for name in ('identity.json','checkpoint.json','policy.json','finished.json')}))
        return report

    def evaluate_stage(self,stage,name,model=None):
        seeds=self.banks['confirmation' if stage=='confirmation' else 'development']
        models={'initial':self.initial} if stage=='controls' else {'candidate':model} if stage=='development' else {'initial':self.initial,'candidate':model}
        source=analysis.make_source(stage,seeds,models,self.base,self.priority,getattr(self,'vanilla',None))
        return self.native('evaluate',name,source,EVALUATION['maximumSecondsPerStage'])

    def analyze(self,stage,name,output,model,controls=None,diagnostic_only=False,update_index=None):
        path=self.lab/(name+'-analysis.json');report_path=self.lab/(name+'-report.json')
        request=dict(stage=stage,seeds=self.banks[stage],candidate=str(output),candidateSource=str(self.lab/(output.name+'-source.json')),
            base=self.base,initial=str(self.initial),model=str(model),priority=str(self.priority),output=str(report_path))
        if diagnostic_only:request['diagnosticOnlyCheckpoint']=True
        if getattr(self,'vanilla',None) is not None:
            request.update(vanilla=str(self.vanilla),requireVanillaSuperiority=self.vanilla_superiority)
        if getattr(self,'continuation',False):
            require(type(update_index) is int and update_index in analysis.MILESTONES,
                    'Continuation evaluation needs its explicit checkpoint number.')
            request.update(minimumGainVsVanilla=.03,
                checkpoint=dict(continuationUpdate=update_index,lifetimeUpdate=32+update_index))
        if controls:request.update(controls=str(controls),controlsSource=str(self.lab/(controls.name+'-source.json')))
        document(path,request);binding=report_path.with_suffix('.binding.json')
        if not binding.exists():
            deadline=self.stage_clock(name+'-analysis',dict(requestSha256=sha(path)),300,
                                      report_path.exists() or (self.lab/(name+'-analysis.log')).exists())
            self.execute(name+'-analysis',[sys.executable,'-B',str(self.scripts/'card_trajectory_experiment.py'),
                '--analyze',str(path)],300,deadline)
        require(read(binding)==dict(requestSha256=sha(path),reportSha256=sha(report_path)),
                'Completed trajectory statistics changed after their exact-input audit.')
        return read(report_path)

    def run(self):
        reports={};updates=[];controls=None
        for index in range(1,33):
            collection,collected=self.collect(index);updates.append(self.update(index,collection,collected))
            progress=analysis.progress(updates)
            if getattr(self,'continuation',False):
                progress=continuation_progress(progress,self.model(index),self.start_model)
            document(self.lab/f'progress-{index:02}.json',progress)
            self.status('update-complete',batch=index,trainingGames=index*256,progress=progress)
            if index==16 and progress['noProgress']:
                return self.finish('trajectory-no-probability-movement',policyPromotion=False,completedUpdates=index)
            if index in analysis.MILESTONES:
                if controls is None:controls=self.evaluate_stage('controls','development-controls')
                output=self.evaluate_stage('development',f'development-{index:02}',self.model(index))
                allowed = index in getattr(self,'selection_milestones',analysis.MILESTONES)
                options = dict(diagnostic_only=not allowed)
                if getattr(self,'continuation',False):options['update_index']=index
                report=self.analyze('development',f'development-{index:02}',output,self.model(index),controls,**options)
                reports[index]=report
                if allowed and report['harmful']:
                    return self.finish('trajectory-development-harm',policyPromotion=False,completedUpdates=index)
        require(set(reports)==set(analysis.MILESTONES),'Selection requires all four completed fixed checkpoints.')
        selected=analysis.select({index:report for index,report in reports.items()
            if index in getattr(self,'selection_milestones',analysis.MILESTONES)})
        if selected is None:return self.finish('trajectory-no-eligible-checkpoint',policyPromotion=False)
        finalist=dict(batch=selected,model=str(self.model(selected)),modelSha256=sha(self.model(selected)),
                      developmentReportSha256=sha(self.lab/f'development-{selected:02}-report.json'))
        if getattr(self,'continuation',False):
            finalist.update(continuationUpdate=selected,lifetimeUpdate=32+selected)
        document(self.lab/'finalist.json',finalist)
        output=self.evaluate_stage('confirmation','confirmation',self.model(selected))
        options = dict(update_index=selected) if getattr(self,'continuation',False) else {}
        report=self.analyze('confirmation','confirmation',output,self.model(selected),**options)
        return self.finish('qualified-for-integration' if report['passed'] else 'trajectory-independent-qualification-failed',
            finalist=finalist,confirmationPassed=report['passed'],policyPromotion=False)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lab',type=Path);parser.add_argument('--protocol',type=Path)
    parser.add_argument('--status',action='store_true');parser.add_argument('--analyze',type=Path)
    args=parser.parse_args()
    if args.analyze:
        require(args.lab is None and args.protocol is None and not args.status,'Analysis takes only its frozen request.')
        analyze_request(args.analyze);return
    require(args.lab is not None and args.protocol is not None,'Use --lab and --protocol.')
    if args.status:print(json.dumps(read(args.lab/'window-status.json'),indent=2));return
    def canceled(*_):raise KeyboardInterrupt('Repeated card training canceled.')
    signal.signal(signal.SIGTERM,canceled)
    with (args.lab/'.window.lock').open('a+') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        window=TrajectoryWindow(args.lab,args.protocol)
        try:window.run()
        except experiment.StageTimeout:
            window.finish('trajectory-original-budget-exhausted',policyPromotion=False)


if __name__=='__main__':main()
