"""One matched PPO baseline pilot; shared fresh data, two fixed updates, no selection."""
import argparse
from pathlib import Path
import os
import sys

import automata_experiments as exp
import automata_policy_search as search
import automata_purchase_continuation as legacy
import purchase_ecology as ecology
import purchase_ecology_data as data

read,sha,document,require = exp.read,exp.sha,exp.document,exp.require
KIND = 'purchase-matched-baseline-pilot-1'
ORDINARY = 'b7c42a6f347a77c9bd9ff5d29778ea74650a9affe5fdb16738a8bea8d75d17d4'
EXPANSION = 'feb80d4363a93b266c94bc46e0d75ff4b0cb1f17a55d0f6d77782715862f44ef'
CRITIC = '897d2f0106064863cb6a4a718bae68c79d4d4cc0e0139ad4e5d5705aa58959f6'
ARMS = ('recorded','external')
STAGES = ('train','screen')


def collection_source(protocol):
    return dict(schemaVersion=1,configuration=exp.CONFIGURATION,games=256,
        seed=protocol['banks']['training']['nativeOffset'],baseline=protocol['baseline'],
        opponents=protocol['opponents'],policy=protocol['purchasePolicy'],
        opponentPurchasePolicy=protocol['purchasePolicy'],sharedPurchaseBaseline=protocol['prior'],
        **legacy.transfer_source(protocol),continuationProtocol=data.CONTINUATION,
        replayIndices=data.REPLAY_INDICES,**data.bindings(protocol['expansionModel']))


def arena_source(protocol, model):
    source = exp.arena_source(protocol['baseline'],protocol['opponents'],
        protocol['banks']['screen']['nativeOffset'],512,protocol['baseline'],'SCREENING')
    return dict(source,**legacy.transfer_source(protocol),
        **data.bindings(protocol['expansionModel'],arena=True),
        frozenPurchase=dict(baseline=protocol['prior'],policy=protocol['purchasePolicy'],
            candidatePolicy=str(model),incumbentPolicy=protocol['purchasePolicy']))


def validate_inputs(protocol):
    require(protocol['kind'] == KIND and protocol['workers'] == 10
        and protocol['maximumSeconds'] == 3600 and protocol['maximumAttempts'] == 1
        and protocol['maximumTerminalGames'] == 2304 and protocol['arms'] == list(ARMS)
        and protocol['qualificationPassed'] is False and protocol['productionActivation'] is False
        and protocol['automaticNextCampaign'] is False,
        'Use only the frozen two-arm, ten-worker, one-hour diagnostic.')
    require(protocol['configuration'] == exp.CONFIGURATION
        and protocol['experimentalRulesTransfer'] == legacy.RULES_TRANSFER
        and sha(protocol['baseline']) == ORDINARY and sha(protocol['expansionModel']) == EXPANSION
        and sha(protocol['purchasePolicy']) == data.C and sha(protocol['prior']) == data.F0
        and protocol['opponents'] == [protocol['baseline']]*4
        and sha(protocol['critic']) == CRITIC,
        'The pilot requires exact ETM8/C64/F0 with indexed M8/EM8/TM8/ETM8 rivals.')
    require(sha(protocol['inferenceContract']) == '120748a9cfe6e018a6885108c0b652990d034a41cfc870600fb6a04bbe22ef45'
        and sha(protocol['stateDictionary']) == '8261e8bfa69892f6971fb6fca340860610d59afb93c26bcf264039d460bb6864',
        'Frozen fresh64 inference provenance changed.')
    audit = read(protocol['seedAudit'])
    require(audit['allIntersectionsZero'] and protocol['banks'] == audit['seedBanks']
        and set(protocol['banks']) == {'training','screen','update'},
        'Use only the prospectively audited training, shared screening and epoch streams.')
    for name,count in (('training',256),('screen',512)):
        bank = protocol['banks'][name]
        require(bank['count'] == count and bank['seeds'] == exp.arena_seeds(bank['nativeOffset'],count),
                'A prospective game bank differs from the native seed formula.')
    update = protocol['banks']['update']
    require(update['count'] == 4 and update['seeds'] == list(range(update['seeds'][0],update['seeds'][0]+4)),
            'The same four original epoch-shuffle streams are required in both arms.')
    config = read(protocol['updateConfig'])
    require(config == dict(protocol['numerical']['configDefaults'],**dict(legacy.HYPERPARAMETERS,
        valueCoefficient=0),seed=update['seeds'][0]), 'The original PPO recipe changed beyond actor-only loss.')
    exp.validate_banks({name:bank['seeds'] for name,bank in protocol['banks'].items()})


def verify_execution(protocol):
    require(str(Path(sys.executable).resolve()) == protocol['python']['executable']
        and sys.version == protocol['python']['version'] and sys.flags.ignore_environment
        and sys.flags.no_user_site and sys.dont_write_bytecode,
        'The pilot requires the frozen clean Python3.14 interpreter.')
    dynamic = {'AOR_CAMPAIGN_OUTPUT','AOR_CAMPAIGN_STAGE_ID','AOR_CAMPAIGN_DEADLINE_EPOCH',
               'AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH'}
    require({key:value for key,value in os.environ.items() if key not in dynamic}
            == protocol['environment']['variables'], 'The frozen desktop environment changed.')
    require(all(sha(path)==digest for path,digest in protocol['frozenFiles'].items()),
            'A frozen scientific, runtime, critic or environment pin changed.')


def numeric(protocol, output):
    import purchase_actor_baseline as baseline
    import purchase_policy as learner
    import purchase_ecology_environment as environment
    environment.verify(protocol['numerical'])
    path = output/'external-baselines.json'
    baseline.predict(output/'collection',protocol['purchasePolicy'],protocol['critic'],
                     protocol['inferenceContract'],protocol['stateDictionary'],path)
    document(output/'baseline-freeze.json',dict(predictionsSha256=sha(path),
        behaviorModelSha256=sha(protocol['purchasePolicy']),criticSha256=sha(protocol['critic']),
        updatesStarted=False,newCriticFits=0))
    config = learner.UpdateConfig(**read(protocol['updateConfig']))
    for arm in ARMS:
        baseline.update(output/'collection',protocol['purchasePolicy'],config,path,output/arm,arm)
        require(sha(path)==read(output/'baseline-freeze.json')['predictionsSha256'],
                'Prebatch critic predictions changed during actor optimization.')


def verify_update(protocol, directory, arm, provenance):
    model,initial = read(directory/'policy.json'),read(protocol['purchasePolicy'])
    payload = ecology.checkpoint(directory/'checkpoint.json',directory/'policy.json')
    identity,report = read(directory/'identity.json'),read(directory/'finished.json')
    expected = dict(version='purchase-actor-only-baseline-1',baseline=arm,
        frozenParameters=['valueWeights','valueBias'],optimizerInitialization='fresh-adam')
    if arm == 'external':
        path = directory.parent/'external-baselines.json'
        expected.update(externalFile=str(path),externalSha256=sha(path),predictor=read(path)['predictor'])
    require(identity['data'] == provenance and identity['modelSha256'] == data.C
        and identity['config'] == read(protocol['updateConfig'])
        and identity['optimizerFromSha256'] is None and identity['actorOnlyObjective'] == expected
        and identity['learnerSha256'] == sha(Path(__file__).with_name('purchase_policy.py'))
        and identity['entrypointSha256'] == sha(Path(__file__).with_name('purchase_actor_baseline.py'))
        and identity['torchVersion'] == protocol['numerical']['torchVersion']
        and identity['pythonVersion'] == protocol['numerical']['pythonVersion']
        and payload['identitySha256'] == legacy.digest(identity), 'Matched PPO identity or baseline changed.')
    require(report['identitySha256'] == payload['identitySha256']
        and report['inputModelSha256'] == data.C and report['outputModelSha256'] == sha(directory/'policy.json')
        and report['actorOnlyObjective'] == expected and report['history'] == payload['history']
        and report['acceptedEpochs'] == payload['epoch'] == sum(row['accepted'] for row in payload['history'])
        and report['stopReason'] in ('epochs-complete','behavior-kl-limit','time-budget'),
        'Matched PPO completion must retain its exact epoch rollback history.')
    require(all(model[name]==initial[name] for name in ('valueWeights','valueBias'))
        and len(payload['optimizer']['param_groups']) == 1
        and len(payload['optimizer']['param_groups'][0]['params']) == 5
        and len(payload['optimizer']['state']) <= 5,
        'Value-head parameters and Adam slots must remain excluded in both arms.')
    require(all(row['accepted'] == (row['behaviorKl'] <= .01) for row in payload['history']),
            'Original whole-collection KL rollback changed.')
    return report


def stage_result(protocol_path, output, stage, report, roots):
    report = dict(report,protocolSha256=sha(protocol_path),qualificationPassed=False,
                  productionActivation=False,automaticNextCampaign=False)
    path=output/(stage+'-report.json');document(path,report)
    files=[path]
    for root in roots:
        files.extend(p for p in root.rglob('*') if p.is_file())
    if stage == 'screen':
        files.extend(output.glob('screen-*-source.json'))
    return exp.stage_result(output,stage,True,'Matched baseline diagnostic; no selection or qualification.',report,files)


def train(protocol, protocol_path, output):
    directory=output/'training';directory.mkdir()
    source=directory/'collection-source.json';document(source,collection_source(protocol))
    exp.native(protocol,'purchase-collect',source,directory/'collection',extra=('--workers','10'))
    provenance,decisions=data.verify_collection(directory/'collection',expected_ordinary=ORDINARY,
                                               expected_expansion=EXPANSION)
    provenance['readerSha256']=sha(Path(__file__).with_name('purchase_actor_baseline.py'))
    replay=ecology.reproduction(protocol,directory)
    command=[protocol['numerical']['executable'],'-E','-s','-B',str(Path(__file__).resolve()),
             'numeric','--protocol',str(protocol_path),'--output',str(directory)]
    ecology.run_child(protocol,command,directory/'numeric.log')
    updates={arm:verify_update(protocol,directory/arm,arm,provenance) for arm in ARMS}
    return stage_result(protocol_path,output,'train',dict(updates=updates,decisions=decisions,
        collectionGames=256,predictionsSha256=sha(directory/'external-baselines.json'),
        reproductionSha256=sha(directory/'reproduction/reproduction.json')),[directory])


def compare(first,second):
    require(len(first)==len(second)==512,'The paired contrast requires the complete common512 worlds.')
    for a,b in zip(first,second):
        require(all(a[key]==b[key] for key in ('index','configuration','seed','seat','opponentIndices'))
            and search.same_game(a['incumbent'],b['incumbent']),
            'Both screens must reproduce the exact same qualified incumbent games.')
    return {name:exp.interval([b['candidate'][field][b['seat']]-a['candidate'][field][a['seat']]
        for a,b in zip(first,second)]) for name,field in
        (('winShareDifference','wins'),('rewardDifference','rewards'))}


def screen(protocol, protocol_path, output):
    import automata_campaign as controller
    prior=controller.validate_result(output/'results/train.json',output)
    require(prior['status']=='passed','Both fixed updates must finish before the shared screen.')
    cells,pairs_by_arm=[],{}
    for arm in ARMS:
        model=output/'training'/arm/'policy.json'
        source_path=output/f'screen-{arm}-source.json';source=arena_source(protocol,model)
        document(source_path,source)
        directory=output/'screens'/f'screen-{arm}'
        require(not directory.exists(),'Each fixed actor receives exactly one common-bank screen.')
        exp.native(protocol,'arena',source_path,directory)
        metrics=legacy.verify_arena(protocol,source_path,directory)
        data.verify_overlays(source,directory,[read(directory/'run.json'),read(directory/'evaluation.json')],
                             arena=True,expected_expansion=EXPANSION)
        require({p.name for p in directory.glob('pair-*.json')}=={f'pair-{i}.json' for i in range(512)},
                'Every common-bank paired world must complete.')
        pairs=[read(directory/f'pair-{i}.json') for i in range(512)];pairs_by_arm[arm]=pairs
        replays=ecology.arena_replays(protocol,source_path,pairs,output/'replays'/arm,
                                      expected_expansion=EXPANSION)
        cells.append(dict(arm=arm,modelSha256=sha(model),versusAnchor=metrics,replays=replays))
    report=dict(primaryExternalMinusRecorded=compare(pairs_by_arm['recorded'],pairs_by_arm['external']),
        cells=cells,sharedIncumbentParityWorlds=512,terminalGames=2304,
        inference='Paired approximate95% intervals across512 independent worlds; no candidate selection.',
        limitation='Compares baseline choice within the new matched actor-only recipe; no human-strength or qualification claim.')
    return stage_result(protocol_path,output,'screen',report,[output/'screens',output/'replays'])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=('preflight','stage','numeric'))
    parser.add_argument('--protocol',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--stage',choices=STAGES)
    options=parser.parse_args();protocol=read(options.protocol)
    validate_inputs(protocol);verify_execution(protocol)
    if options.command=='preflight':
        require(not options.output.exists(),'The actual child preflight precedes any native work.')
        import automata_campaign as controller
        own=[process for process in controller.processes.sample_group(os.getpgrp())
             if process.pid==os.getpid()]
        require(len(own)==1 and own[0].rss_bytes>0,'Actual child process sampling must work.')
        import json
        print(json.dumps(dict(passed=True,newGames=0,actualFits=0,externalPsSamplerPassed=True,
                              protocolSha256=sha(options.protocol))))
    elif options.command=='numeric':numeric(protocol,options.output)
    elif options.stage=='train':train(protocol,options.protocol,options.output)
    else:screen(protocol,options.protocol,options.output)


if __name__=='__main__':main()
