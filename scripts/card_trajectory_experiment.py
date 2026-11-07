"""Bounded repeated card-PPO signal using the unchanged actor, collector and numerical backend.

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


def training_source(base, offset, model):
    return dict(base, games=256, seed=offset, opponents=[base['baseline']]*4, policy=str(model))


def completed_training(directory, source, seeds, model_path):
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
            and manifest['seed'] == spec['seed'] and manifest['seeds'] == seeds
            and manifest.get('opponentCardPolicy') is None,
            'Training must be the exact complete declared F-rival card collection.')
    def snapshot(path, name):
        path=Path(path).resolve(strict=True)
        require((directory / name).read_bytes() == path.read_bytes(), 'A frozen training snapshot changed: '+name)
        return dict(path=str(path),sha256=sha(path))
    require(manifest['baseline'] == snapshot(spec['baseline'],'baseline.json')
            and manifest['frozenPurchasePolicy'] == snapshot(spec['frozenPurchasePolicy'],'frozen-purchase-policy.json')
            and manifest['opponents'] == [snapshot(path,f'opponent-{index}.json') for index,path in enumerate(spec['opponents'])],
            'Training baseline, purchase32 or rival identities changed.')
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


def analyze_request(path):
    request=read(path)
    args=(request['seeds'],Path(request['controls']),Path(request['controlsSource']),
          Path(request['candidate']),Path(request['candidateSource']),request['base'],
          Path(request['initial']),Path(request['model']),Path(request['priority'])) if request['stage']=='development' else None
    report=analysis.analyze_development_completed(*args) if args else analysis.analyze_completed(
        'confirmation',request['seeds'],Path(request['candidate']),Path(request['candidateSource']),
        request['base'],Path(request['initial']),Path(request['model']),Path(request['priority']))
    output=Path(request['output']);document(output,report)
    document(output.with_suffix('.binding.json'),dict(requestSha256=sha(path),reportSha256=sha(output)))


class TrajectoryWindow(experiment.Window):
    def __init__(self,lab,protocol_path):
        super().__init__(lab,protocol_path)
        self.preflight=read(self.lab/'preflight.json')
        require(self.protocol['kind']=='card-trajectory-window' and self.protocol['schemaVersion']==1,
                'Unsupported repeated card trajectory protocol.')
        recipe=read(self.pin(self.protocol['recipeDraft']))
        expected=dict(training=analysis.TRAINING,ppo=analysis.PPO,development=analysis.DEVELOPMENT,
            confirmation=analysis.CONFIRMATION,bootstrap=analysis.BOOTSTRAP,resources=RESOURCES,
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
                'Start from the unchanged97% recommendation prior, without optimizer warm start.')
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
        self.java=self.protocol['javaRuntime']
        require(sha(self.java['executable'])==self.java['sha256']
                and str((Path(os.environ.get('JAVA_HOME',''))/'bin/java').resolve())==self.java['executable'],
                'Use the exact pinned Java21 executable for every collector/evaluator.')
        for path in [*(self.scripts/name for name in HELPERS),*self.runtime.glob('*.jar'),
                     Path(self.base['baseline']),Path(self.base['frozenPurchasePolicy']),Path(sys.executable),
                     Path(self.java['executable'])]:
            require(self.preflight['pins'].get(str(path.resolve()))==sha(path), 'Executed runtime/input not pinned: '+str(path))

    def model(self,index):
        return self.initial if index==0 else self.lab/f'update-{index:02}/policy.json'

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
        document(source,training_source(self.base,self.offsets[name],model))
        completed=completed_training(output,source,self.banks[name],model)
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
        completed=completed_training(output,source,self.banks[name],model)
        require(completed is not None,'Card collection must finish completely before PPO sees any trajectories.')
        return output,completed

    def update(self,index,collection,collected):
        name=f'update-{index:02}';output=self.lab/name;model=self.model(index-1)
        carry=self.lab/f'update-{index-1:02}/checkpoint.json' if index>1 else None
        if carry:
            checkpoint=backend.read_checkpoint(carry)
            require(checkpoint['model']==read(model) and isinstance(checkpoint['optimizer'],dict),
                    'Carry Adam only from the exact preceding model checkpoint.')
        config=dict(analysis.PPO,seed=analysis.TRAINING['updateSeeds'][index-1])
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
        source=analysis.make_source(stage,seeds,models,self.base,self.priority)
        return self.native('evaluate',name,source,EVALUATION['maximumSecondsPerStage'])

    def analyze(self,stage,name,output,model,controls=None):
        path=self.lab/(name+'-analysis.json');report_path=self.lab/(name+'-report.json')
        request=dict(stage=stage,seeds=self.banks[stage],candidate=str(output),candidateSource=str(self.lab/(output.name+'-source.json')),
            base=self.base,initial=str(self.initial),model=str(model),priority=str(self.priority),output=str(report_path))
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
            document(self.lab/f'progress-{index:02}.json',progress)
            self.status('update-complete',batch=index,trainingGames=index*256,progress=progress)
            if index==16 and progress['noProgress']:
                return self.finish('trajectory-no-probability-movement',policyPromotion=False,completedUpdates=index)
            if index in analysis.MILESTONES:
                if controls is None:controls=self.evaluate_stage('controls','development-controls')
                output=self.evaluate_stage('development',f'development-{index:02}',self.model(index))
                report=self.analyze('development',f'development-{index:02}',output,self.model(index),controls)
                reports[index]=report
                if report['harmful']:
                    return self.finish('trajectory-development-harm',policyPromotion=False,completedUpdates=index)
        require(set(reports)==set(analysis.MILESTONES),'Selection requires all four completed fixed checkpoints.')
        selected=analysis.select(reports)
        if selected is None:return self.finish('trajectory-no-eligible-checkpoint',policyPromotion=False)
        finalist=dict(batch=selected,model=str(self.model(selected)),modelSha256=sha(self.model(selected)),
                      developmentReportSha256=sha(self.lab/f'development-{selected:02}-report.json'))
        document(self.lab/'finalist.json',finalist)
        output=self.evaluate_stage('confirmation','confirmation',self.model(selected))
        report=self.analyze('confirmation','confirmation',output,self.model(selected))
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
