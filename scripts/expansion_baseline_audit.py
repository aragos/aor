"""Pure terminal admission for the two eight-batch expansion learning arms.

Original trajectories are streamed one world at a time. Detached predictions are
checked as retained evidence; this owner never forwards a model or refits ridge.
"""
import hashlib
import json
import math
from pathlib import Path
import random

import expansion_baseline_loop as loop
import expansion_training_evidence as e

require, sha, digest, finite = e.require, e.sha, e.digest, e.finite
VERSION = 'expansion-baseline-learning-audit-1'
TRANSACTION = 'expansion-previous-batch-update-1'
OBJECTIVE = 'expansion-previous-batch-state-baseline-1'
ESTIMATOR = dict(gamma=1., intermediateReward=0., forcedRowsIncluded=True,
    weighting='uniform-recorded-decision-mean', terminalReward='original-complete-world',
    baseline='constant-half-first-then-completed-previous-batch-state-ridge')
RIDGE_RECIPE = dict(hiddenSize=64, penalty=1., interceptPenalty=0., featureScaling='none',
    loss='sum-world-mean-row-squared-error-plus-slope-l2', rowWeight='1/rows-in-origin',
    predictionClip=[0.,1.], targetClip=False, randomInitialization=False)


def subject(path, pins):
    return dict(path=str(Path(path).resolve(strict=True)), sha256=pins.pin(path))


def inventory(root):
    root = Path(root)
    require(not any(p.is_symlink() for p in root.rglob('*')), 'Evidence cannot contain symbolic links.')
    return {p.relative_to(root).as_posix(): sha(p) for p in sorted(root.rglob('*'))
            if p.is_file() and p.name != '.DS_Store'}


def names(root):
    require(not any(p.is_symlink() for p in root.iterdir()), 'Evidence directories cannot contain symlinks.')
    return {p.name for p in root.iterdir() if p.name != '.DS_Store'}


def config(protocol, plan, batch):
    return dict(learningRate=.0003, epochs=4, batchSize=64, clip=.2, valueCoefficient=0,
        entropyCoefficient=0, maxGradientNorm=.5, maxBehaviorKl=.01, seed=plan.shuffles[batch-1],
        threads=1, maxSeconds=protocol['updateSeconds'], cardReferenceKlCoefficient=0,
        cardReferenceVersion='aor-card-physical-reference-1')


def basis_descriptor(initial, protocol):
    require(initial['schemaVersion'] == 2 and initial['encoderVersion'] == e.ENCODER and
            initial['hiddenSize'] == 64 and all(v == 0 for v in initial['actorWeights']+initial['valueWeights'])
            and initial['valueBias'] == 0, 'The baseline basis must be the original zero-head initializer.')
    return dict(kind='expansion-original-state-basis-1',
        initialModel=dict(path=protocol['actor'],sha256=protocol['actorSha256']),
        encoderVersion=e.ENCODER,hiddenSize=64,stateFeatureNamesSha256=digest(initial['stateFeatureNames']),
        stateWeightsBiasSha256=digest(dict(weights=initial['stateWeights'],bias=initial['stateBias'])))


def collection(protocol, plan, root, batch, arm, actor, pins, prepared):
    directory = root/'batches'/f'batch-{batch:02d}'/arm
    source_path = directory/'collection-source.json'
    source = pins.read(source_path)
    require(source == plan.source(protocol,batch,actor), 'Original collection source or same-arm actor differs.')
    archive = e.Archived(directory,pins)
    manifest = archive.metadata['collection/collection.json']
    done = archive.metadata['collection/finished.json']
    require(manifest['kind'] == done['kind'] == e.KIND and manifest['games'] == done['games'] == 256
        and done['complete'] is True and manifest['signature'] == done['signature']
        and manifest['seed'] == plan.offsets[batch-1]
        and manifest['seeds'] == [-1000000000+plan.offsets[batch-1]+i*8191 for i in range(256)]
        and manifest['actorVersion'] == e.ACTOR and manifest['dictionaries']['encoderVersion'] == e.ENCODER,
        'Native complete-world bank or schema identity changed.')
    fixed = ('kind','schemaVersion','configuration','rulesVersion','dataVersion','trajectoryEncoding',
        'dictionaries','policyBindings','frozenCardPurchaseBundleSha256','referenceExpansionModelSha256',
        'ecologySnapshotDirectory','actorVersion','controlledSeat','opponentIndices','reward')
    require(all(manifest[k] == prepared[k] for k in fixed), 'Native role/dictionary differs from actual preparation.')
    e.snapshots(archive,manifest,source_path,protocol,actor,pins)
    ecology = {k:manifest[k] for k in ('configuration','policyBindings',
        'frozenCardPurchaseBundleSha256','referenceExpansionModelSha256')}
    ecology['arenaSourceSha256'] = manifest['arena']['sha256']
    expected_games = {f'game-{i}.json.gz' for i in range(256)}
    require(set(done['gameSha256']) == expected_games and
        {n.removeprefix('collection/') for n in archive.files if n.startswith('collection/game-')} == expected_games,
        'Every prospective world, including empty expansion histories, must remain.')
    replay = (0,85,170,255)[(batch-1)%4]
    canonical = archive.metadata[f'collection/replay-{replay}.json']
    canonical_sha = archive.hash(f'collection/replay-{replay}.json')
    game_sha = archive.hash(f'collection/game-{replay}.json.gz')
    entries_sha = hashlib.sha256(json.dumps(canonical['entries'],ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
    require(manifest['replayIndices'] == [replay] and
        done['canonicalSha256'] == {f'replay-{replay}.json':canonical_sha} and
        canonical['kind'] == 'expansion-on-policy-canonical-1' and canonical['entriesSha256'] == entries_sha and
        canonical['signature'] == manifest['signature'] and canonical['index'] == replay and
        canonical['trajectorySha256'] == game_sha and canonical['replayVerified'] is True and
        isinstance(canonical['header'],dict) and archive.metadata['reproduction/replay.json'] == dict(
            kind='expansion-on-policy-reproduction-1',signature=manifest['signature'],index=replay,
            passed=True,trajectorySha256=game_sha,canonicalSha256=canonical_sha,
            completeTypedRecordsEqual=True,completeCanonicalEqual=True),
        'Same-factory reproduction must match the original typed and canonical world.')
    role_snapshots = {'candidate.json','incumbent.json','opponent-0.json'}
    def snapshots(value):
        if isinstance(value,dict):
            if 'snapshot' in value:
                require(Path(value['snapshot']).name == value['snapshot'], 'Snapshot must be a local owned filename.')
                role_snapshots.add(value['snapshot'])
            for item in value.values(): snapshots(item)
        elif isinstance(value,list):
            for item in value: snapshots(item)
    snapshots(manifest['policyBindings'])
    expected_members = {'collection/'+name for name in expected_games} | {
        'collection/'+name for name in ('collection.json','finished.json','started.json','source.json','arena.json','actor.json')}
    expected_members |= {'collection/ecology/'+name for name in role_snapshots}
    expected_members |= {f'collection/replay-{replay}.json','reproduction/replay.json'}
    require({name for name in archive.files if Path(name).name != '.DS_Store'} == expected_members,
            'Complete native collection/reproduction inventory differs from its exact role declarations.')
    game_hashes = [None]*256
    actor_sha = pins.pin(actor)
    origins, rewards, targets = [], [], []
    branching = cards = empty = 0
    for name, game in archive.games():
        index = game['index']
        require(type(index) is int and 0 <= index < 256 and game_hashes[index] is None and
            name == f'collection/game-{index}.json.gz', 'Unexpected or duplicate archived world.')
        game_hashes[index] = archive.hash(name)
        require(done['gameSha256'][name.removeprefix('collection/')] == game_hashes[index],
                'Original world differs from native completion.')
        reward, count, branch, card = e.trajectory(game,index,manifest,actor_sha)
        rewards.append(reward); branching += branch; cards += card; empty += count == 0
        origins.append(dict(index=index,gameSeed=game['gameSeed'],controlledSeat=index%4,decisions=count))
        targets.extend((index,row['number'],reward,len(row['observation']['actions']))
                       for row in game['expansionDecisions'])
    require(all(game_hashes), 'Missing original completed world.')
    targets.sort(key=lambda row:(row[0],row[1]))
    return dict(directory=directory,archive=archive,manifest=manifest,source=source_path,
        ecology=ecology,gameHashes=game_hashes,origins=sorted(origins,key=lambda row:row['index']),
        meanReward=math.fsum(rewards)/256,targets=[(i,n,r) for i,n,r,_ in targets],
        actionCounts=[count for _,_,_,count in targets],rows=len(targets),empty=empty,
        exposure=dict(branchingWorlds=branching,cardOfferWorlds=cards))


def accepted_minibatches(action_counts, config, accepted):
    """Count the unchanged epoch_batches/row_batches partitions without numerical imports.

    Counts retain ExpansionCollection's origin/decision order. Epochs are zero-based;
    rejected epochs contribute no steps because the owner restores the whole Adam state.
    The 16,384-action allocation cap may split even a decision batch smaller than 64.
    """
    require(all(type(count) is int and 1 <= count <= 1024 for count in action_counts),
            'Original expansion offer counts must remain within their native bound.')
    batches = 0
    for epoch in range(accepted):
        order = list(range(len(action_counts)))
        random.Random(config['seed']+epoch).shuffle(order)
        rows = actions = 0
        for index in order:
            count = action_counts[index]
            if rows and (rows == config['batchSize'] or actions+count > 16384):
                batches += 1
                rows = actions = 0
            rows += 1
            actions += count
        batches += rows > 0
    return batches


def actor_history(payload, report, action_counts, previous_steps, model, expected_config):
    history = payload['history']
    accepted = sum(row['accepted'] is True for row in history)
    require(accepted == payload['epoch'] == report['acceptedEpochs'] and
        all(row['epoch'] == i+1 and type(row['accepted']) is bool and finite(row['behaviorKl']) and
            row['accepted'] == (row['behaviorKl'] <= expected_config['maxBehaviorKl']) for i,row in enumerate(history))
        and all(row['accepted'] for row in history[:-1]), 'Whole-epoch KL acceptance history differs.')
    require((report['stopReason'] == 'epochs-complete' and accepted == 4 and len(history) == 4 and payload['stopReason'] is None)
        or (report['stopReason'] == 'behavior-kl-limit' and accepted < 4 and len(history) == accepted+1 and
            history[-1]['accepted'] is False and payload['stopReason'] == 'behavior-kl-limit'),
        'A partial or unknown numerical stop cannot admit an actor.')
    steps = e.adam_steps(payload,model,previous_steps,expected_config)
    require(steps == previous_steps+accepted_minibatches(action_counts,expected_config,accepted),
            'Adam steps must equal complete accepted epochs plus exact inherited moments.')
    return accepted,steps


def detached_targets(document, rows, objective):
    """Recheck subtraction/order from retained predictions; never recompute those predictions."""
    expected = dict(kind='expansion-detached-previous-baseline-targets-1',batch=objective['batch'],
        replica=objective['replica'],protocol=objective['protocol'],basis=objective['basis'],
        previousBaseline=objective['previousBaseline'],previousTransaction=objective['previousTransaction'],
        collectionSha256=objective['collectionSha256'],gameHashes=objective['gameHashes'])
    require(set(document) == set(expected)|{'rows'} and all(document[k] == v for k,v in expected.items())
        and len(document['rows']) == len(rows), 'Detached target source/basis/replica or original membership changed.')
    predictions, advantages = [], []
    for actual, (origin,number,reward) in zip(document['rows'],rows):
        require(set(actual) == {'originIndex','number','prediction','advantage'} and
            actual['originIndex'] == origin and actual['number'] == number and finite(actual['prediction'],0,1)
            and actual['advantage'] == reward-actual['prediction'] and
            (objective['batch'] != 1 or actual['prediction'] == .5),
            'Detached target must preserve original chronology and exact R minus previous prediction.')
        predictions.append(actual['prediction']); advantages.append(actual['advantage'])
    require(digest(predictions) == objective['predictionsSha256'] and digest(advantages) == objective['targetsSha256'],
            'The actor objective must seal these exact detached predictions and targets.')


def transaction(directory, batch, protocol_subject, replica, basis, data, objective, health, pins):
    numeric = directory/'numeric'
    done = pins.read(numeric/'finished.json'); identity = pins.read(numeric/'identity.json')
    previous = None if batch == 1 else directory.parents[1]/f'batch-{batch-1:02d}'/'TREATMENT/numeric'
    previous_transaction = None if previous is None else subject(previous/'finished.json',pins)
    previous_baseline = None if previous is None else subject(previous/'baseline.json',pins)
    target_subject = subject(numeric/'detached-targets.json',pins)
    require(objective['protocol'] == protocol_subject and objective['replicaProtocolSha256'] == protocol_subject['sha256']
        and objective['replica'] == replica and objective['batch'] == batch and objective['basis'] == basis
        and objective['previousTransaction'] == previous_transaction and objective['previousBaseline'] == previous_baseline
        and objective['detachedTargets'] == target_subject, 'Treatment must use the immediate same-replica baseline transaction.')
    expected_files = {'identity.json','detached-targets.json'}|{
        'actor/'+name+'.json' for name in ('identity','checkpoint','policy','finished','epoch-started')}
    if batch < 8: expected_files.add('baseline.json')
    actual_files = inventory(numeric)
    require(set(actual_files) == expected_files|{'finished.json'} and
        done['artifacts'] == {k:v for k,v in actual_files.items() if k != 'finished.json'},
        'Treatment transaction contains missing or undeclared numeric evidence.')
    common = dict(kind=TRANSACTION,arm='TREATMENT',batch=batch,replica=replica,protocol=protocol_subject)
    require(all(done.get(k) == v and identity.get(k) == v for k,v in common.items()) and done['complete'] is True
        and done['identity'] == subject(numeric/'identity.json',pins) and identity['basis'] == basis
        and identity['previousTransaction'] == done['previousTransaction'] == previous_transaction
        and identity['previousBaseline'] == previous_baseline and identity['detachedTargets'] == target_subject
        and identity['data'] == data and all(done[k] == v for k,v in health.items()),
        'Completed treatment transaction does not bind its actual actor and detached baseline evidence.')
    for name in ('identity','checkpoint','policy','finished'):
        require(done['actor'][name] == subject(numeric/'actor'/f'{name}.json',pins),
                'Transaction must publish its own actual actor files.')
    if batch < 8:
        baseline_path = numeric/'baseline.json'
        require(done['nextBaseline'] == subject(baseline_path,pins), 'Missing published next-batch baseline.')
        fitted = pins.read(baseline_path)
        require(fitted['kind'] == 'expansion-frozen-state-ridge-1' and fitted['recipe'] == RIDGE_RECIPE
            and fitted['basis'] == basis and isinstance(fitted['slopes'],list) and len(fitted['slopes']) == 64
            and all(finite(v) for v in fitted['slopes']) and finite(fitted['intercept'])
            and fitted['provenance'] == dict(batch=batch,replica=replica,protocol=protocol_subject,
                collectionSha256=data['collectionSha256'],gameHashes=data['gameHashes'],actorCompletion=done['actor']['finished']),
            'Next baseline must retain fixed coefficients/recipe and this completed actor/cohort provenance.')
        represented = sum(origin['decisions'] > 0 for origin in data['origins'])
        require(fitted['worlds'] == 256 and fitted['representedWorlds'] == represented
            and fitted['emptyWorlds'] == 256-represented and fitted['rows'] == sum(o['decisions'] for o in data['origins'])
            and finite(fitted['rowWeightSum']) and abs(fitted['rowWeightSum']-represented) <= 1e-9,
            'World-weighted baseline counts changed or empty worlds were invented.')
    else:
        require(done['nextBaseline'] is None, 'Final eighth update cannot fit an unused baseline.')
    return subject(numeric/'finished.json',pins)


def verify_batch(protocol, protocol_path, plan, root, batch, arm, pins, prepared, basis, previous):
    directory = root/'batches'/f'batch-{batch:02d}'/arm
    actor = loop.expected_actor(protocol,root,batch,arm).resolve(strict=True)
    c = collection(protocol,plan,root,batch,arm,actor,pins,prepared)
    require(c['rows'] > 0, 'Empty actor collection cannot publish learning progress.')
    numeric = directory/'numeric/actor'
    identity = pins.read(numeric/'identity.json'); model = pins.read(numeric/'policy.json')
    record = pins.read(numeric/'checkpoint.json'); payload = record['payload']; report = pins.read(numeric/'finished.json')
    before = pins.read(actor); actor_sha = pins.pin(actor); output_sha = pins.pin(numeric/'policy.json')
    require(record['sha256'] == digest(payload) and payload['identitySha256'] == report['identitySha256'] == digest(identity)
        and payload['model'] == model and payload['history'] == report['history'] and payload['epoch'] == report['acceptedEpochs']
        and identity['modelSha256'] == report['inputModelSha256'] == actor_sha and report['outputModelSha256'] == output_sha,
        'Numeric actor/checkpoint/history does not match the original behavior and output.')
    require(set(model) == set(before) and all(model[k] == before[k] for k in before if k not in e.PARAMETERS)
        and all(isinstance(model[k],list) and len(model[k]) == len(before[k]) and all(finite(v) for v in model[k])
            for k in e.PARAMETERS), 'Actor update changed frozen parameters, vocabulary, dimensions or finite bounds.')
    expected_config = config(protocol,plan,batch)
    require(identity['config'] == expected_config and identity['actorVersion'] == e.ACTOR and
        pins.read(numeric/'epoch-started.json') == dict(kind='expansion-epoch-started',identitySha256=digest(identity)),
        'Fixed numerical recipe or original epoch marker changed.')
    scripts = Path(protocol['runtime']).parent/'scripts'
    owner = 'expansion_mc.py' if arm == 'CONTROL' else 'expansion_previous_baseline.py'
    key = 'expansionMcObjective' if arm == 'CONTROL' else 'expansionBaselineObjective'
    owner_key = 'expansionMcOwnerSha256' if arm == 'CONTROL' else 'expansionBaselineOwnerSha256'
    require(identity['learnerSha256'] == pins.pin(scripts/'purchase_policy.py') and
        identity['entrypointSha256'] == identity[owner_key] == pins.pin(scripts/owner), 'Numerical source owner changed.')
    objective = identity[key]; data = identity['data']; archive = c['archive']; manifest = c['manifest']
    require(objective == report[key] and objective['phase'] == 'expansion' and objective['ecology'] == c['ecology']
        and objective['frozenParameters'] == ['valueWeights','valueBias'] and objective['behaviorModelSha256'] == actor_sha
        and objective['collectionSha256'] == data['collectionSha256'] == archive.hash('collection/collection.json')
        and objective['gameHashes'] == data['gameHashes'] == c['gameHashes']
        and data['completionSha256'] == archive.hash('collection/finished.json') and data['origins'] == c['origins']
        and data['games'] == report['games'] == 256 and report['decisions'] == c['rows']
        and data['actorModelSha256'] == actor_sha and data['collectionSignature'] == manifest['signature']
        and data['readerSha256'] == sha(scripts/owner) and report['strengthClaim'] is False
        and abs(data['gameMeanReward']-c['meanReward']) < 1e-12 and report['gameMeanReward'] == data['gameMeanReward'],
        'Original whole-world labels, behavior data or detached objective binding changed.')
    carry_key = 'expansionMcOptimizerContinuation' if arm == 'CONTROL' else 'expansionBaselineOptimizerContinuation'
    carry = identity.get(carry_key)
    if batch == 1:
        require(carry is None and identity['optimizerFromSha256'] is None and objective['optimizerInitialization'] == 'fresh-adam',
                'Each first actor must use fresh Adam.')
    else:
        expected_carry = dict(phase='expansion',actorAdamSteps=previous['steps'],checkpointSha256=previous['checkpointSha256'],
                             previousIdentitySha256=previous['identityDigest'])
        if arm == 'CONTROL':
            expected_carry.update(version='expansion-mc-adam-continuation-1')
            initialization = 'matching-expansion-mc-adam'
        else:
            expected_carry.update(version='expansion-previous-batch-adam-1',arm='TREATMENT',replica=protocol['replica'],
                replicaProtocolSha256=sha(protocol_path),batch=batch,previousTransaction=previous['transaction'])
            initialization = 'matching-expansion-previous-batch-adam'
        require(carry == expected_carry and identity['optimizerFromSha256'] == previous['checkpointSha256']
            and objective['optimizerInitialization'] == initialization, 'Adam carry crossed arm, replica or immediate predecessor.')
    accepted, steps = actor_history(payload,report,c['actionCounts'],previous['steps'],model,expected_config)
    health = dict(acceptedEpochs=accepted,actorChanged=any(model[k] != before[k] for k in e.PARAMETERS),
                  actorAdamSteps=steps,previousActorAdamSteps=previous['steps'])
    update = pins.read(directory/'numeric-complete.json'); receipt = pins.read(directory/'receipt.json')
    actual_transaction = None
    if arm == 'CONTROL':
        require(objective['version'] == 'expansion-mc-actor-only-1' and objective['estimator'] == e.ESTIMATOR
            and 'expansionBaselineObjective' not in identity and names(directory/'numeric') == {'actor'}
            and names(numeric) == {'identity.json','checkpoint.json','policy.json','finished.json','epoch-started.json'},
            'CONTROL must preserve the original constant-baseline actor and no extra baseline.')
    else:
        require(objective['version'] == OBJECTIVE and objective['arm'] == 'TREATMENT' and objective['estimator'] == ESTIMATOR
            and 'expansionMcObjective' not in identity, 'TREATMENT requires its separate declared baseline objective.')
        detached_targets(pins.read(directory/'numeric/detached-targets.json'),c['targets'],objective)
        actual_transaction = transaction(directory,batch,subject(protocol_path,pins),protocol['replica'],basis,data,objective,health,pins)
        transaction_identity = pins.read(directory/'numeric/identity.json')
        require(transaction_identity['config'] == expected_config and transaction_identity['behaviorModel'] == subject(actor,pins),
                'Treatment transaction changed numerical recipe or original behavior model.')
    expected_update = dict(kind='expansion-baseline-numeric-1',arm=arm,batch=batch,replica=protocol['replica'],
        protocolSha256=sha(protocol_path),actorSha256=output_sha,checkpointSha256=sha(numeric/'checkpoint.json'),
        identitySha256=sha(numeric/'identity.json'),completedSha256=sha(numeric/'finished.json'),transaction=actual_transaction,
        data=dict(data,readerSha256=sha(scripts/'expansion_on_policy_data.py')),exposure=c['exposure'],**health,qualification=False)
    require(update == expected_update, 'Numeric completion must equal actual actor, targets, exposure and progress.')
    expected_receipt = dict(kind='expansion-baseline-batch-1',arm=arm,batch=batch,complete=True,replica=protocol['replica'],
        protocolSha256=sha(protocol_path),worlds=256,reproductions=1,sourceSha256=sha(c['source']),actorSha256=output_sha,
        checkpointSha256=sha(numeric/'checkpoint.json'),identitySha256=sha(numeric/'identity.json'),
        updateSha256=sha(directory/'numeric-complete.json'),archiveSha256=archive.manifest['archiveSha256'],exposure=c['exposure'],**health)
    require(receipt == expected_receipt, 'Final arm receipt differs from original evidence.')
    require(names(directory) == {'collection-source.json','collection.log','reproduction.log','numeric.log','numeric',
        'numeric-complete.json','receipt.json','evidence.tar.gz','storage.json'}, 'Unexpected completed arm evidence tree.')
    summary = dict(batch=batch,rows=c['rows'],emptyWorlds=c['empty'],exposure=c['exposure'],**health)
    state = dict(steps=steps,checkpointSha256=sha(numeric/'checkpoint.json'),identityDigest=digest(identity),transaction=actual_transaction,
        environment={k:identity[k] for k in ('pythonVersion','torchVersion','learnerSha256')})
    return receipt,summary,state


def stopped_result(finished, rounds, plan, remaining):
    count = len(rounds); stalls = {arm:0 for arm in loop.ARMS}
    require(2 <= count <= 8 and finished['completedRounds'] == count and
        finished['completedBatches'] == {arm:count for arm in loop.ARMS} and finished['scientificWorlds'] == count*512
        and finished['historicalReproductions'] == count*2 and finished['qualification'] is False
        and finished['automaticSuccessor'] is False, 'Completed/stopped scientific counts changed.')
    for position, row in enumerate(rounds,1):
        for arm in loop.ARMS:
            stalls[arm] = 0 if loop.legacy.learning_progress(row['arms'][arm]) else stalls[arm]+1
        require(not any(v >= 4 for v in stalls.values()) or position == count, 'Run continued after its fixed four-stall stop.')
    prefix = plan.prefix_passed(rounds[:2],remaining)
    if finished['complete'] is True:
        require(count == 8 and prefix and all(v < 4 for v in stalls.values()) and finished['eligibleFinalActors'] is True
            and finished['stopReason'] == 'both-eight-complete', 'Only both completed eighth actors are eligible.')
    else:
        require(finished['complete'] is False and finished['eligibleFinalActors'] is False,
                'A stopped prefix cannot have eligible actors.')
        require((finished['stopReason'] == 'operational-prefix-gate' and count == 2 and not prefix)
            or (finished['stopReason'] == 'four-no-progress-batches' and prefix and any(v == 4 for v in stalls.values())),
            'Stopped attempt lacks its exact operational reason.')
    require(count <= 2 or prefix, 'Learning continued after failed operational admission.')


def audit(protocol, protocol_path, learning_root):
    """Verify completed evidence without importing numerical owners or executing any model."""
    plan = loop.Plan.read(protocol); root = Path(learning_root).resolve(strict=True); pins = e.Pins()
    require(pins.read(protocol_path) == protocol, 'Audit must use the exact immutable replica protocol.')
    prepared = pins.read(protocol['prepared']['path'])
    require(sha(protocol['prepared']['path']) == protocol['prepared']['sha256'], 'Prepared dictionary/role subject changed.')
    initial = pins.read(protocol['actor']); basis = basis_descriptor(initial,protocol)
    finished = pins.read(root/'finished.json'); count = finished['completedRounds']
    require(finished['kind'] == loop.VERSION and finished['replica'] == protocol['replica']
        and finished['protocolSha256'] == sha(protocol_path) and type(count) is int and 2 <= count <= 8,
        'Only a finite declared-replica learning completion may be audited.')
    require(names(root) == {'batches','finished.json','first-batch-parity.json','operational-prefix.json'} and
        names(root/'batches') == {f'batch-{i:02d}' for i in range(1,count+1)}, 'Unexpected or partial learning tree.')
    previous = {arm:dict(steps=0) for arm in loop.ARMS}; summaries = {arm:[] for arm in loop.ARMS}
    rounds = []; common_environment = None
    for batch in range(1,count+1):
        directory = root/'batches'/f'batch-{batch:02d}'
        require(names(directory) == {'CONTROL','TREATMENT','receipt.json'}, 'Unexpected matched round tree.')
        arm_receipts = {}
        for arm in loop.order(batch):
            receipt,summary,state = verify_batch(protocol,protocol_path,plan,root,batch,arm,pins,prepared,basis,previous[arm])
            if common_environment is None: common_environment = state['environment']
            require(state['environment'] == common_environment, 'Numerical source/environment changed across arms or rounds.')
            previous[arm] = state; summaries[arm].append(summary); arm_receipts[arm] = receipt
        receipt = pins.read(directory/'receipt.json')
        require(receipt['kind'] == 'expansion-baseline-round-1' and receipt['replica'] == protocol['replica']
            and receipt['protocolSha256'] == sha(protocol_path) and receipt['batch'] == batch and receipt['complete'] is True
            and receipt['arms'] == arm_receipts and receipt['executionOrder'] == list(loop.order(batch))
            and finite(receipt['elapsedSeconds'],0) and type(receipt['retainedBytes']) is int
            and receipt['retainedBytes'] == sum(p.stat().st_size for p in directory.rglob('*')
                if p.is_file() and p.name != '.DS_Store' and p != directory/'receipt.json'),
            'Original measured paired-round cost or receipt changed.')
        rounds.append(receipt)
    parity = loop.verify_first_batch_parity(root)
    require(pins.read(root/'first-batch-parity.json') == parity, 'Persisted full-world/actor/Adam equality proof changed.')
    prefix = pins.read(root/'operational-prefix.json'); remaining = prefix['remainingSeconds']
    require(prefix == dict(kind='expansion-baseline-operational-prefix-1',replica=protocol['replica'],
        protocolSha256=sha(protocol_path),completedRounds=2,passed=plan.prefix_passed(rounds[:2],remaining),
        remainingSeconds=remaining,receiptSha256={str(i):sha(root/'batches'/f'batch-{i:02d}'/'receipt.json') for i in (1,2)},
        firstBatchParitySha256=sha(root/'first-batch-parity.json'),qualityInspected=False)
        and finite(remaining,0,10800), 'Original operational-prefix clock or decision changed.')
    stopped_result(finished,rounds,plan,remaining)
    for path in root.rglob('*'):
        require(not path.is_symlink(), 'Learning evidence cannot contain symlinks.')
        if path.is_file() and path.name != '.DS_Store': pins.pin(path)
    for path in protocol['files']: pins.pin(path)
    arms = {}
    if finished['complete']:
        for arm in loop.ARMS:
            actor = loop.actor_directory(root,8,arm)
            arms[arm] = {key+suffix:value for key,path in dict(policy=actor/'policy.json',checkpoint=actor/'checkpoint.json',
                identity=actor/'identity.json',receipt=actor.parent.parent/'receipt.json').items()
                for suffix,value in (('Path',str(path)),('Sha256',sha(path)))}
    pins.unchanged()
    return dict(kind=VERSION,passed=True,complete=finished['complete'],replica=protocol['replica'],protocolSha256=sha(protocol_path),
        preparedSha256=protocol['prepared']['sha256'],completedRounds=count,completedBatches={arm:count for arm in loop.ARMS},
        scientificWorlds=count*512,historicalReproductions=count*2,stopReason=finished['stopReason'],
        firstBatchParityPassed=True,operationalPrefixPassed=prefix['passed'],eligibleFinalActors=finished['eligibleFinalActors'],
        arms=arms,receiptSha256={arm:{str(i):sha(root/'batches'/f'batch-{i:02d}'/arm/'receipt.json') for i in range(1,count+1)}
            for arm in loop.ARMS},batches=summaries,artifactPins=pins.values,newGames=0,newFits=0,inferenceCalls=0,
        predictionEvidenceRechecked=True,predictionsRecomputed=False,baselinesRefitted=False)
