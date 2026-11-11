"""Matched fresh-Adam GAE learning with old versus refreshed frozen state critics.

Both arms start at the same G2 actor and retain fixed G2 rivals. Only predeclared terminal arm
actors are compared; a four-zero-update arm stop is part of the fixed learning recipe.
"""
import argparse
import json
import os
from pathlib import Path
import platform

import automata_campaign as controller
import automata_experiments as exp
import automata_policy_search as search
import automata_purchase_continuation as legacy
import purchase_baseline_pilot as matched
import purchase_critic_refresh_data as critics
import purchase_ecology as ecology
import purchase_ecology_data as data
import purchase_gae_learning_curve as curve
import purchase_gae_pilot as pilot
import purchase_storage as storage
import purchase_strong_curriculum as curriculum
import purchase_strong_data as strong

read, sha, document, require = exp.read, exp.sha, exp.document, exp.require
KIND = 'purchase-matched-critic-refresh-1'
ARMS = ('old', 'refreshed')
BATCHES = 16
COMPARISONS = ('old-anchor', 'refreshed-anchor', 'refreshed-old')
REPLAYS = (0, 341, 682, 1023)
NATIVE = '1b1b1c6cf2bc3c94258a4078d9d3bfeaa5361ca1'


def arm_protocol(protocol, arm):
    require(arm in ARMS, 'Use only the two predeclared frozen-critic arms.')
    binding = protocol['critics'][arm]
    return dict(protocol, critic=binding['checkpoint'], inferenceContract=binding['inferenceContract'])


def batch_directory(output, index, arm):
    require(arm in ARMS and type(index) is int and 1 <= index <= BATCHES,
            'An arm update needs one predeclared batch index.')
    return output/'arms'/arm/'batches'/f'batch-{index}'/'gae'


def batch_source(protocol, index, model):
    require(type(index) is int and 1 <= index <= BATCHES, 'Only sixteen common on-policy banks exist.')
    return curriculum.batch_source(protocol, index, model)


def arena_source(protocol, models, name):
    require(name in COMPARISONS, 'Only the three terminal actor comparisons are permitted.')
    candidate, incumbent = name.split('-')
    source = exp.arena_source(protocol['baseline'], protocol['opponents'],
        protocol['banks']['screen']['nativeOffset'], 1024, protocol['baseline'], 'SCREENING')
    return dict(source, **legacy.transfer_source(protocol),
        **data.bindings(protocol['expansionModel'], arena=True,
                        rival_ecology=data.ArenaRivalEcology.HOMOGENEOUS),
        frozenPurchase=dict(baseline=protocol['prior'], policy=protocol['initialActor'],
            candidatePolicy=str(models[candidate]), incumbentPolicy=str(
                protocol['initialActor'] if incumbent == 'anchor' else models[incumbent])))


def validate_inputs(protocol):
    exact = dict(kind=KIND, workers=10, maximumSeconds=10800, maximumAttempts=1,
        batchesPerArm=16, maximumScientificTerminalGames=14336,
        maximumHistoricalTerminalReproductions=152, maximumTerminalExecutions=14488,
        maximumActorUpdates=32, maximumCriticFits=0, maximumConsecutiveZeroAcceptedBatches=4,
        finalCheckpointOnly=True, qualificationPassed=False, productionActivation=False,
        automaticNextCampaign=False, checkpointSelection=False, automaticResume=False,
        intermediateLooks=False, nativeSourceCommit=NATIVE)
    require(all(type(protocol.get(key)) is type(value) and protocol[key] == value for key, value in exact.items())
            and protocol['arms'] == list(ARMS) and protocol['comparisons'] == list(COMPARISONS)
            and protocol['replayIndices'] == list(REPLAYS) and protocol['advantageEstimator'] == pilot.ESTIMATOR
            and 'initialOptimizer' not in protocol,
            'Use the fixed two-arm fresh-Adam sixteen-batch recipe and three terminal comparisons only.')
    require(protocol['configuration'] == exp.CONFIGURATION
            and protocol['experimentalRulesTransfer'] == legacy.RULES_TRANSFER
            and sha(protocol['baseline']) == matched.ORDINARY and sha(protocol['expansionModel']) == matched.EXPANSION
            and protocol['purchasePolicy'] == protocol['initialActor'] and sha(protocol['initialActor']) == strong.G2
            and sha(protocol['prior']) == data.F0 and protocol['opponents'] == [protocol['baseline']]*4
            and protocol['collectionEcology'] == strong.ECOLOGY.value,
            'Both arms and every rival retain the exact initial G2/E/T/M8/F0 roles.')
    require(sha(protocol['baseInferenceContract']) == critics.BASE_CONTRACT
            and sha(protocol['stateDictionary']) == critics.FEATURES
            and set(protocol['critics']) == set(ARMS), 'The original state-value math/units and dictionary stay fixed.')
    for arm in ARMS:
        binding = protocol['critics'][arm]
        require(set(binding) == {'checkpoint', 'inferenceContract'}, 'Declare each critic checkpoint and actual descriptor.')
        critics.predictor_identity(critics.CriticRole(arm), binding['checkpoint'], binding['inferenceContract'],
                                   protocol['stateDictionary'], protocol['numerical']['torchVersion'])
    audit = read(protocol['refreshAudit'])
    original = '/home/aragos/aor-worker/purchase-strong-value-20261111/run/value/fits/warm-best.pt'
    require(sha(protocol['refreshAudit']) == critics.REFRESH_AUDIT and audit['passed'] is True
            and audit['status'] == 'completed' and audit['allOwnedProcessesExited'] is True
            and audit['result']['selectedArm'] == 'warm'
            and audit['result']['selectedSha256'] == critics.CriticRole.REFRESHED.checkpoint_sha256
            and audit['artifacts'][original] == critics.CriticRole.REFRESHED.checkpoint_sha256,
            'The refreshed critic must be the exact independently audited selected49 checkpoint.')
    banks, audit = protocol['banks'], read(protocol['seedAudit'])
    require(audit['allIntersectionsZero'] is True and audit['seedBanks'] == banks
            and audit['liveMetadataScans'] == 0 and audit['humanInputsRead'] == 0
            and set(banks) == {*[f'training-{i}' for i in range(1,17)], 'screen', 'updates'},
            'Use only frozen ancestry and the declared common banks, without human data or live scans.')
    updates = banks['updates']['seeds']
    require(len(updates) == 64 and updates == list(range(updates[0], updates[0]+64)),
            'Both arms share exactly four original epoch shuffle streams per batch.')
    for name, bank in banks.items():
        if name == 'updates':
            continue
        count = 1024 if name == 'screen' else 256
        require(bank['count'] == count and bank['seeds'] == exp.arena_seeds(bank['nativeOffset'], count),
                'Each training or terminal bank must preserve its native world formula.')
    exp.validate_banks({name: bank['seeds'] for name, bank in banks.items()})
    for index in range(1,17):
        require(pilot.update_config(protocol,index) == dict(protocol['numerical']['configDefaults'],
            **dict(legacy.HYPERPARAMETERS,valueCoefficient=0),seed=updates[4*(index-1)]),
            'Both GAE arms retain the original identical PPO recipe and matched epoch streams.')


def numeric(protocol, directory, index, arm):
    """Existing updater owns math and strict continuation; fresh Adam is mandatory in batch one."""
    require(arm in ARMS and type(index) is int and 1 <= index <= BATCHES,
            'Only the declared arm/batch can update.')
    expected = ('arms', arm, 'batches', f'batch-{index}', 'gae')
    require(tuple(directory.parts[-5:]) == expected, 'Numeric output must belong to this exact arm lineage.')
    pilot.numeric(arm_protocol(protocol,arm), directory, index, 'gae',
                  collection_adapter=critics.FrozenCriticAdapter(critics.CriticRole(arm)))


def verify_update(protocol, directory, index, arm, model, provenance):
    report = pilot.verify_update(arm_protocol(protocol,arm), directory, index, 'gae', model, provenance)
    predictions = read(directory/'external-baselines.json')
    binding = protocol['critics'][arm]
    expected = critics.predictor_identity(critics.CriticRole(arm), binding['checkpoint'],
        binding['inferenceContract'], protocol['stateDictionary'], protocol['numerical']['torchVersion'])
    require(predictions['predictor'] == expected
            and predictions['predictionAdapter'] == dict(version='purchase-critic-refresh-adapter-1',
                sha256=sha(critics.__file__), role=arm)
            and predictions['collectionReader'] == dict(version='purchase-strong-collection-reader-1',
                sha256=sha(strong.__file__), rivalActorSha256=strong.G2, ecology=strong.ECOLOGY.value),
            'Exact frozen state predictor, adapter and independent collection provenance must agree.')
    return report


def train_batch(protocol, path, output, index, arm, model):
    directory = batch_directory(output,index,arm)
    require(not directory.exists(), 'Each on-policy arm/batch has one attempt; preserve partial failures.')
    source = directory/'collection-source.json'; document(source,batch_source(protocol,index,model))
    exp.native(protocol,'purchase-collect',source,directory/'collection',extra=('--workers','10'))
    provenance, decisions = strong.verify_collection(directory/'collection')
    provenance['readerSha256'] = sha(strong.__file__)
    ecology.reproduction(protocol,directory)
    command = [protocol['numerical']['executable'],'-E','-s','-B',str(Path(__file__).resolve()),
        'numeric','--protocol',str(path),'--output',str(directory),'--index',str(index),'--arm',arm]
    ecology.run_child(protocol,command,directory/'numeric.log')
    update = verify_update(protocol,directory,index,arm,model,provenance)
    previous = pilot.previous_optimizer(directory,index,'gae')
    model = directory/'update/policy.json'
    receipt = dict(batch=index,arm=arm,modelSha256=sha(model),
        previousCheckpointSha256=sha(previous) if previous else None, update=update,decisions=decisions,
        provenance=provenance,storage=storage.archive(directory),criticMetrics=read(directory/'critic-metrics.json'),
        predictor=read(directory/'external-baselines.json')['predictor'],
        reproductionSha256=sha(directory/'reproduction/reproduction.json'))
    document(directory/'receipt.json',receipt)
    return model,receipt


def next_arm_state(previous, accepted):
    require(previous['stopReason'] is None and previous['completedBatches'] < 16,
            'Stopped or complete arms cannot consume another batch.')
    streak = curve.zero_streak(previous['consecutiveZeroAcceptedBatches'],accepted)
    completed = previous['completedBatches']+1
    return dict(completedBatches=completed,
        acceptedEpochs=previous['acceptedEpochs']+accepted, consecutiveZeroAcceptedBatches=streak,
        stopReason='four-consecutive-zero-accepted-batches' if streak==4 else
                   'all16-batches-complete' if completed==16 else None)


def compare_final_parity(pairs):
    require(set(pairs) == set(COMPARISONS) and all(len(rows)==1024 for rows in pairs.values()),
            'All three complete terminal comparisons are required.')
    for old,new,direct in zip(pairs['old-anchor'],pairs['refreshed-anchor'],pairs['refreshed-old']):
        require(all(old[key]==new[key]==direct[key] for key in
                    ('index','configuration','seed','seat','opponentIndices'))
                and search.same_game(old['incumbent'],new['incumbent'])
                and search.same_game(old['candidate'],direct['incumbent'])
                and search.same_game(new['candidate'],direct['candidate']),
                'Common final worlds must reproduce each terminal arm and the unchanged anchor exactly.')


def final_screens(protocol,path,output,models):
    pairs_by_name, cells = {}, []
    for name in COMPARISONS:
        source_path = output/f'{name}-source.json'; directory = output/'screens'/name
        require(not source_path.exists() and not directory.exists(), 'Final comparisons permit one attempt, no top-ups.')
        source = arena_source(protocol,models,name); document(source_path,source)
        exp.native(protocol,'arena',source_path,directory)
        metrics = legacy.verify_arena(protocol,source_path,directory)
        data.verify_overlays(source,directory,[read(directory/'run.json'),read(directory/'evaluation.json')],
            arena=True,expected_expansion=matched.EXPANSION,rival_ecology=data.ArenaRivalEcology.HOMOGENEOUS)
        require({p.name for p in directory.glob('pair-*.json')}=={f'pair-{i}.json' for i in range(1024)},
                'Each terminal contrast must retain every predeclared pair.')
        pairs = [read(directory/f'pair-{i}.json') for i in range(1024)]; pairs_by_name[name]=pairs
        replays = ecology.arena_replays(protocol,source_path,pairs,output/'replays'/name,
            expected_expansion=matched.EXPANSION,rival_ecology=data.ArenaRivalEcology.HOMOGENEOUS,indices=REPLAYS)
        cells.append(dict(comparison=name,candidateSha256=sha(source['frozenPurchase']['candidatePolicy']),
            incumbentSha256=sha(source['frozenPurchase']['incumbentPolicy']),metrics=metrics,replays=replays))
    compare_final_parity(pairs_by_name)
    return cells


def progress(path,output,states,cells):
    count = sum(state['completedBatches'] for state in states.values())
    report = dict(protocolSha256=sha(path),arms=states,completedUpdates=count,
        completedComparisons=[cell['comparison'] for cell in cells],
        scientificTerminalGames=count*256+len(cells)*2048,
        historicalReproductions=count*4+len(cells)*8,
        totalTerminalExecutions=count*260+len(cells)*2056,
        qualificationPassed=False,productionActivation=False,automaticNextCampaign=False,
        selectionPerformed=False,actualCriticFits=0)
    document(output/'progress.json',report,immutable=False)
    return report


def train(protocol,path,output):
    require(not (output/'progress.json').exists() and not (output/'arms').exists()
            and not (output/'screens').exists(), 'No automatic resume, retry or renewed deadline.')
    models = {arm:Path(protocol['initialActor']) for arm in ARMS}
    states = {arm:dict(completedBatches=0,acceptedEpochs=0,consecutiveZeroAcceptedBatches=0,stopReason=None)
              for arm in ARMS}
    batches, cells = [], []
    for index in range(1,17):
        for arm in ARMS:
            if states[arm]['stopReason'] is not None:
                continue
            exp.remaining_seconds()
            models[arm], receipt = train_batch(protocol,path,output,index,arm,models[arm])
            batches.append(receipt); states[arm]=next_arm_state(states[arm],receipt['update']['acceptedEpochs'])
            progress(path,output,states,cells)
            print(f'Completed critic arm={arm} batch={index}/16; no intermediate arena.',flush=True)
        if all(state['stopReason'] is not None for state in states.values()):
            break
    require(all(state['stopReason'] is not None for state in states.values()),
            'Only actors terminal under the fixed learning/stall recipe enter final comparison.')
    cells = final_screens(protocol,path,output,models)
    report = dict(progress(path,output,states,cells),batches=batches,comparisons=cells,
        terminalActors={arm:dict(path=str(model),sha256=sha(model)) for arm,model in models.items()},
        primaryRefreshedMinusOld=cells[-1]['metrics'],sharedFinalWorlds=1024,
        inference='Matched fixed-budget GAE recipes; paired95% diagnostic only, no qualification or actor selection.')
    document(output/'critic-refresh-report.json',report)
    artifacts = [output/'progress.json',output/'critic-refresh-report.json',*output.glob('*-source.json')]
    for root in (output/'arms',output/'screens',output/'replays'):
        artifacts.extend(p for p in root.rglob('*') if p.is_file())
    return exp.stage_result(output,'learn',True,'Bounded matched-critic diagnostic completed; no qualification.',report,artifacts)


def campaign_spec(root,protocol,files):
    return curve.campaign_spec(root,protocol,files,maximum_seconds=10800,
                               script_name='purchase_critic_refresh.py')


def numerical_preflight(protocol):
    """Validate frozen tensor schemas and first-batch lineage without predictions or updates."""
    import torch
    import purchase_actor_baseline as baseline
    import purchase_ecology_environment as environment
    import purchase_policy as learner
    environment.verify(protocol['numerical'])
    learner.validate_model(read(protocol['initialActor']))
    checked = {}
    for arm in ARMS:
        binding = protocol['critics'][arm]
        identity = critics.predictor_identity(critics.CriticRole(arm),binding['checkpoint'],
            binding['inferenceContract'],protocol['stateDictionary'],torch.__version__)
        critic = baseline.benchmark.Critic((64,),0)
        critic.load_state_dict(torch.load(binding['checkpoint'],map_location='cpu',weights_only=True),strict=True)
        require(pilot.previous_optimizer(Path('/unused')/'arms'/arm/'batches/batch-1/gae',1,'gae') is None,
                'Both first actor updates require fresh Adam and no inherited checkpoint.')
        checked[arm] = identity
    return dict(passed=True,predictors=checked,initialActorSha256=sha(protocol['initialActor']),
                optimizerInitialization='fresh-adam-both-arms',newGames=0,actualFits=0,predictions=0)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=('preflight','numerical-preflight','stage','numeric'))
    parser.add_argument('--protocol',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--index',type=int);parser.add_argument('--arm',choices=ARMS)
    args=parser.parse_args();protocol=read(args.protocol)
    validate_inputs(protocol);matched.verify_execution(protocol)
    expected=protocol['environment']
    require(platform.platform()==expected['platform'] and platform.machine()==expected['machine']
            and os.cpu_count()==expected['logicalCpus'],'The frozen execution host changed.')
    if args.command=='preflight':
        require(not args.output.exists(),'Preflight precedes games or fitting.')
        own=[row for row in controller.processes.sample_group(os.getpgrp()) if row.pid==os.getpid()]
        require(len(own)==1 and own[0].rss_bytes>0,'The exact child requires external process sampling.')
        print(json.dumps(dict(passed=True,newGames=0,actualFits=0,externalPsSamplerPassed=True,
                              protocolSha256=sha(args.protocol))))
    elif args.command=='numerical-preflight':print(json.dumps(numerical_preflight(protocol)))
    elif args.command=='numeric':numeric(protocol,args.output,args.index,args.arm)
    else:train(protocol,args.protocol,args.output)


if __name__=='__main__':main()
