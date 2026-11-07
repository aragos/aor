"""Fixed two-cycle, two-context delayed sequence check; no actual games or fitting choices."""
import argparse
from pathlib import Path
import time
import torch
import card_advantage as a
import card_advantage_train as fit
import purchase_policy as common

SCHEMA={'encoderVersion':a.ENCODER,
        'stateFeatureNames':sorted(['cardWindow.normal','context','root','after-A','after-B']),
        'actionFeatureNames':['card.a','card.b']}

def observation(context,stage='root'):
    commands=('play hand-a','play hand-b') if stage=='root' else ('play hand-c','play hand-d')
    return {'encoderVersion':a.ENCODER,'stateFeatures':{'cardWindow.normal':1.,'context':2.*context-1.,stage:1.},
            'baselineIndex':0,'actions':[{'command':commands[i],'features':{'card.'+('a','b')[i]:1.}} for i in (0,1)]}

def action(policy,context,stage):
    return 0 if policy is None else policy.choose(observation(context,stage),0)['index']

def reward(context,first,second,reversed_payoff):
    target=1-context if reversed_payoff else context
    return float(first==target and second==target)

def dataset(policy,reversed_payoff,count):
    result=[]
    for index in range(count):
        context=index%2
        roots=[reward(context,first,action(policy,context,('after-A','after-B')[first]),reversed_payoff) for first in (0,1)]
        groups=[a.prepare(observation(context),0,SCHEMA,[0.,roots[1]-roots[0]],f'{index}:root')]
        for first in (0,1):
            returns=[reward(context,first,second,reversed_payoff) for second in (0,1)]
            groups.append(a.prepare(observation(context,('after-A','after-B')[first]),0,SCHEMA,
                          [0.,returns[1]-returns[0]],f'{index}:after-{first}'))
        result.append(groups)
    return result

def sequences(policy,reversed_payoff):
    result=[]
    for context in (0,1):
        first=action(policy,context,'root');second=action(policy,context,('after-A','after-B')[first])
        result.append({'context':context,'first':first,'second':second,
                       'reward':reward(context,first,second,reversed_payoff)})
    return result

def run(output):
    output=Path(output);a.require(not output.exists(),'Toy evidence requires a fresh directory.')
    output.mkdir(parents=True);torch.set_num_threads(1);torch.use_deterministic_algorithms(True)
    protocol={'kind':'two-cycle-card-advantage-correctness','config':a.asdict(a.CONFIG),'maximumSeconds':600,
              'trainingOriginsPerCycle':128,'developmentOriginsPerCycle':64,'cyclesPerDirection':2,
              'seeds':list(a.SEEDS),'schema':SCHEMA,'success':'Both visible contexts execute their correct complete sequence after two cycles, for both stationary and reversed payoffs.',
              'noActualGames':True,'freshInitializationEachCycle':True,
              'sourceHashes':{str(p.resolve()):common.file_digest(p) for p in (Path(__file__),Path(a.__file__),Path(fit.__file__))}}
    common.atomic_json(output/'protocol.json',protocol);deadline=time.time()+600;started=time.monotonic()
    policy=None;records=[];status='complete';passed=True
    try:
        for reverse in (False,True):
            for cycle in (1,2):
                training=dataset(policy,reverse,128);development=dataset(policy,reverse,64)
                if not reverse and cycle==1:
                    a.require(float(training[1][0].targets[1])==0. and float(training[1][2].targets[1])==1.,
                              'Compound sequence credit must not leak into first-action targets.')
                identity={'protocolSha256':common.file_digest(output/'protocol.json'),'reversedPayoff':reverse,'cycle':cycle}
                artifact,members=fit.fit_ensemble(SCHEMA,training,development,output/f"{'reverse' if reverse else 'stationary'}-{cycle}",identity,deadline)
                common.atomic_json(output/f"{'reverse' if reverse else 'stationary'}-{cycle}.json",artifact)
                policy=a.Ensemble(artifact);outcomes=sequences(policy,reverse)
                record={'reversedPayoff':reverse,'cycle':cycle,'members':members,'sequences':outcomes,
                    'rootPairedTargetsByContext':[float(training[i][0].targets[1]) for i in (0,1)],
                    'childPairedTargetsByContext':[[float(g.targets[1]) for g in training[i][1:]] for i in (0,1)]}
                records.append(record)
                if cycle==2:passed=passed and all(x['reward']>=.7 for x in outcomes)
                common.atomic_json(output/'progress.json',{'completedCycles':len(records),'elapsedSeconds':time.monotonic()-started})
    except ValueError as error:
        if 'deadline' not in str(error):raise
        status='time-limit';passed=False
    report={'kind':protocol['kind'],'protocolSha256':common.file_digest(output/'protocol.json'),
        'passed':passed and len(records)==4,'status':status,'cycles':records,'elapsedSeconds':time.monotonic()-started,
        'actualGames':0,'strengthClaim':False,'priorFailedPpoEvidenceUnchanged':True}
    common.atomic_json(output/'report.json',report)
    print(__import__('json').dumps(report,indent=2));return report

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();result=run(args.output)
    if not result['passed']:raise SystemExit(1)
if __name__=='__main__':main()
