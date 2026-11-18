"""A single local TOKEN learning step, with no model-selection or qualification capability."""
import argparse
import hashlib
import importlib
import json
import math
from pathlib import Path
import time

import coordinated_es_math as es
import mac_policy_search as search
import mac_qualification_campaign as local
import robust_token_campaign as c
import token_focus_contract as contract


KINDS=dict(prepare='token-focus-step-preparation-1',population='token-focus-step-population-receipt-1',
    update='token-focus-step-update-1',final='token-focus-step-final-mean-1')


def portable(owner,item):
    relative=Path(item['path'])
    c.require(not relative.is_absolute() and relative.parts and '..' not in relative.parts,
        'A numerical artifact must stay inside its original immutable owner directory.')
    path=Path(owner)/relative
    current=Path(owner)
    for part in relative.parts:
        current=current/part
        c.require(not current.is_symlink(),'An original numerical artifact cannot traverse a symbolic link.')
    c.require(c.sha(path)==item['sha256'],'An original numerical artifact changed.')
    return dict(path=str(path),sha256=item['sha256'])


def numerical_receipt(output,mode):
    folder=Path(output)/'numeric'/mode;owner=folder/'artifacts';path=owner/'receipt.json'
    value=c.read(path)
    plan=c.read(Path(output)/'protocol-copy.json')
    c.require(value['kind']==KINDS[mode] and value['complete'] is True and
        value['recipe']==plan['numericRecipe'] and
        value['inputPins'].get(str(folder/'request.json'))==c.sha(folder/'request.json'),
        'Every numerical phase must retain the original exact request and complete typed receipt.')
    c.require((plan['study']==96 and 'stageSpec' not in value) or
        (plan['study']==97 and value.get('stageSpec')==plan['numericStageSpec']),
        'Every numerical receipt must retain its exact source-registered stage identity.')
    for name,digest in value['inputPins'].items():
        c.require(c.sha(name)==digest,'An original numerical input changed.')
    for name,digest in value['files'].items():portable(owner,dict(path=name,sha256=digest))
    c.require({str(item.relative_to(owner)) for item in owner.rglob('*') if item.is_file()}==
        {*value['files'],'receipt.json'},'All and only original numerical artifacts must remain retained.')
    return dict(path=str(path),sha256=c.sha(path)),value


def numerical_artifact(output,mode,name):
    item,record=numerical_receipt(output,mode)
    return portable(Path(item['path']).parent,record[name])


def members(output):
    item=numerical_artifact(output,'population','population');value=search.subject(item)
    c.require(len(value['members'])==32,'The one TOKEN update requires all32 antithetic members.')
    result=[]
    for index,row in enumerate(value['members']):
        c.require(row['index']==index and row['direction']==index//2 and
            row['sign']==(1 if index%2==0 else -1),'The original antithetic member order cannot change.')
        result.append(dict(id=contract.CASE_IDS[index],bundle=portable(Path(item['path']).parent,row['bundle'])))
    return value,result


def ordered(rows,count):
    c.validate_chunk_indices(rows,0,count)
    return sorted(rows,key=lambda row:row['index'])


def same_controls(cohorts):
    """Compare full terminals/diagnostics and every canonical hash available in both cases."""
    reference=cohorts[0]
    for rows in cohorts[1:]:
        c.require(len(rows)==len(reference),'Repeated controls require the same complete original world bank.')
        for first,second in zip(reference,rows):
            a,b=first['canonicalSha256']['CONTROL'],second['canonicalSha256']['CONTROL']
            c.require(first['index']==second['index'] and first['seed']==second['seed'] and
                first['roles']['CONTROL']==second['roles']['CONTROL'] and
                first['diagnostics']['CONTROL']==second['diagnostics']['CONTROL'] and
                (a is None or b is None or a==b),
                'All repeated qualified94 controls, diagnostics and available decision histories must match exactly.')


def outcomes(population,cases,cohorts):
    same_controls(cohorts)
    return dict(kind='token-focus-step-outcomes-1',complete=True,verified=True,
        generationBankSha256=population['generationBankSha256'],worldSeeds=population['worldSeeds'],
        members=[dict(index=index,direction=index//2,sign=1 if index%2==0 else -1,
            bundleSha256=case['bundle']['sha256'],winShares=[c.metric(row,'CANDIDATE','wins') for row in rows])
            for index,(case,rows) in enumerate(zip(cases,cohorts))])


def holdout(start,final):
    same_controls([start,final])
    learned=c.interval([c.metric(b,'CANDIDATE','wins')-c.metric(a,'CANDIDATE','wins')
        for a,b in zip(start,final)],contract.CONFIDENCE)
    gain=c.interval([c.metric(row,'CANDIDATE','wins')-c.metric(row,'CONTROL','wins') for row in final],contract.CONFIDENCE)
    return dict(kind='token-focus-step-holdout-1',complete=True,worlds=len(final),
        finalMinusStart={('lower95' if key=='lower99' else 'upper95' if key=='upper99' else key):value
            for key,value in learned.items()},
        finalMinus94={('lower95' if key=='lower99' else 'upper95' if key=='upper99' else key):value
            for key,value in gain.items()},researchSignal=learned['lower99']>0 and gain['lower99']>0,
        qualified=False,activated=False)


def independent_step(directions,outcome):
    """Audit only: independently recompute the declared64-coordinate update without RNG."""
    vectors=directions['vectors'];rows=outcome['members']
    c.require(len(vectors)==16 and all(len(row)==256 and all(value==0 for value in row[:192]) for row in vectors),
        'Only final64 TOKEN coordinates may influence the sole update.')
    c.require(len(rows)==32 and all(len(row['winShares'])==16 and
        all(type(value) in (int,float) and math.isfinite(value) and 0<=value<=1 for value in row['winShares'])
        for row in rows),'The sole gradient requires all32 complete16-world bounded terminal win-share vectors.')
    means=[math.fsum(row['winShares'])/16 for row in rows]
    differences=[means[2*index]-means[2*index+1] for index in range(16)]
    gradient=[math.fsum(differences[index]*vectors[index][192+coordinate] for index in range(16))/32
        for coordinate in range(64)]
    norm=es.rms(gradient)
    c.require(math.isfinite(norm) and norm>1e-12,'A zero/nonfinite TOKEN gradient cannot supply a replacement candidate.')
    return differences,gradient,[0.]*192+[value/norm for value in gradient]


def immutable_heads(profile,cases):
    original=search.subject(profile['candidateBundle'])
    original_token=original['heads']['TOKEN']
    original_path=Path(profile['candidateBundle']['path']).parent/original_token['path']
    c.require(c.sha(original_path)==original_token['sha256'],'The original TOKEN weights must remain physically frozen.')
    original_model=c.read(original_path)
    actor_blocks={'stateWeights','stateBias','actionWeights','actionBias','actorWeights'}
    metadata={key:value for key,value in original_model.items() if key not in actor_blocks}
    for case in cases:
        bundle=search.subject(case['bundle']);owner=Path(case['bundle']['path']).parent
        for head in ('CARD','PURCHASE','EXPANSION'):
            item=bundle['heads'][head]
            c.require(item['sha256']==original['heads'][head]['sha256'] and
                c.sha(owner/item['path'])==item['sha256'],
                'Every population/final member must preserve the three immutable original head bytes.')
        token=bundle['heads']['TOKEN'];path=owner/token['path']
        c.require(c.sha(path)==token['sha256'] and
            {key:value for key,value in c.read(path).items() if key not in actor_blocks}==metadata,
            'TOKEN dictionaries, priors, values and all non-actor metadata must remain exactly original.')


def audit(protocol,profile_path,output,terminal,policy=contract,*,now=time.time):
    plan=policy.validate(c.read(protocol));profile=search.verify_profile(profile_path,plan);output=Path(output)
    clock=c.read(output/'clock.json')
    c.require(clock==c.science_clock(plan,clock['startedEpoch'],c.sha(protocol),policy.PREFIX) and
        clock['startedEpoch']>=plan['preparedEpoch'] and clock['auditDeadlineEpoch']<=plan['globalDeadlineEpoch'] and
        plan['globalDeadlineEpoch']-clock['startedEpoch']>=7800 and now()<=clock['auditDeadlineEpoch'] and
        terminal['complete'] is True and terminal['status']=='completed' and terminal['qualified'] is False and
        terminal['protocolSha256']==c.sha(protocol) and (output/'protocol-copy.json').read_bytes()==Path(protocol).read_bytes(),
        'Only the original complete research attempt may receive its unchanged bounded audit.')
    population,train_cases=members(output)
    c.require({path.name for path in (output/'chunks').iterdir()}=={'training','holdout'} and
        {path.name for path in (output/'numeric').iterdir()}==set(KINDS),
        'No extra native bank or unreported numerical phase may disappear from research accounting.')
    c.require(population['worldSeeds']==plan['banks']['training']['seeds'] and
        population['generationBankSha256']==hashlib.sha256(c.canonical(plan['banks']['training'])[:-1]).hexdigest(),
        'The population must retain the exact original16 native training worlds.')
    final_case=dict(id='FINAL',bundle=numerical_artifact(output,'final','bundle'))
    hold_cases=[dict(id='START',bundle=profile['candidateBundle']),final_case]
    immutable_heads(profile,[*train_cases,final_case]);all_rows={};executions=0
    for bank,cases in (('training',train_cases),('holdout',hold_cases)):
        base=output/'chunks'/bank
        c.require({path.name for path in base.iterdir()}=={case['id'] for case in cases},
            'Every attempted TOKEN case must belong to the full fixed population or two final holdout cases.')
        cohorts=[]
        for case in cases:
            folder=base/case['id']/'chunk-0'
            c.require({path.name for path in folder.parent.iterdir()}=={'chunk-0'},'TOKEN cases have exactly one original native chunk.')
            source=policy.native_source(plan,profile,bank,case,0,c.sha(protocol))
            c.require(c.read(folder/'source.json')==source,'An original TOKEN native source changed.')
            rows,counts,_=policy.native_chunk(source,folder/'native',bank,source_path=folder/'source.json')
            cohorts.append(ordered(rows,plan['banks'][bank]['count']));executions+=counts['terminalExecutions']
            receipt=c.read(folder/'receipt.json')
            c.require(receipt['complete'] is True and receipt['sourceSha256']==c.sha(folder/'source.json') and
                receipt['finishedSha256']==c.sha(folder/'native/finished.json') and
                receipt['terminalExecutions']==counts['terminalExecutions'],
                'Every original TOKEN native completion must preserve its exact source, output and execution count.')
        same_controls(cohorts);all_rows[bank]=cohorts
    expected=outcomes(population,train_cases,all_rows['training'])
    pop_item=numerical_artifact(output,'population','population');expected['populationSha256']=pop_item['sha256']
    c.require(c.read(output/'training-outcomes.json')==expected,'The complete original32x16 win-only training matrix must independently reproduce.')
    prepare,prep=numerical_receipt(output,'prepare');pop,pop_rec=numerical_receipt(output,'population')
    update,upd=numerical_receipt(output,'update');final,fin=numerical_receipt(output,'final')
    c.require(all(record['scopeSha256']==prep['scopeSha256'] for record in (pop_rec,upd,fin)) and
        population['scopeSha256']==prep['scopeSha256'],
        'All original numerical phases and population must preserve the one prospectively bound scope.')
    requests=dict(prepare=dict(protocol=contract.declaration(protocol),seedSchedule=plan['seedSchedule'],
        originBundle=profile['candidateBundle'],inferenceAsset=profile['inferenceAsset']),
        population=dict(preparation=prepare,center=portable(Path(prepare['path']).parent,prep['center'])),
        update=dict(preparation=prepare,population=pop,outcomes=contract.declaration(output/'training-outcomes.json')),
        final=dict(preparation=prepare,update=update))
    c.require(all(c.read(output/'numeric'/mode/'request.json')==
        dict(kind='token-focus-step-numeric-'+mode+'-1',**body) for mode,body in requests.items()),
        'Every original numerical request must retain the sole prospective origin, center, population and update.')
    c.require(pop_rec['preparation']==upd['preparation']==fin['preparation']==prepare and
        upd['population']==pop and upd['outcomes']==contract.declaration(output/'training-outcomes.json') and fin['update']==update,
        'Final materialization must descend from the sole complete original numerical update.')
    direction_item=portable(Path(prepare['path']).parent,prep['directions'])
    draw=search.subject(portable(Path(prepare['path']).parent,prep['directionDraw']))
    c.require(draw['seed']==plan['reservedSchedule']['directionSeed'] and draw['drawShape']==[16,64] and
        draw['expandedShape']==[16,256] and draw['zeroPrefix']==192 and draw['renormalized'] is False and
        draw['directionsSha256']==direction_item['sha256'],
        'The original declared direction stream must draw only16x64 Gaussian values and preserve the zero prefix.')
    differences,gradient,coordinates=independent_step(search.subject(direction_item),expected)
    center=search.subject(portable(Path(update['path']).parent,upd['center']))
    c.require(upd['antitheticWinDifferences']==differences and upd['step']['gradient']==gradient and
        center['coordinates']==coordinates and fin['center']==portable(Path(update['path']).parent,upd['center']),
        'The final mean must equal the declared active64 RMS1 gradient step, without alternative centers.')
    report=holdout(*all_rows['holdout'])
    c.require(report==c.read(output/'holdout.json') and terminal['researchSignal'] is report['researchSignal'] and
        terminal['candidateBundle']==final_case['bundle'] and executions==1560==terminal['knownCompletedTerminalExecutions']==
        terminal['reservedTerminalUpperBound'],'All original research effects and1560 executions must reproduce without qualification.')
    pins={str(path):c.sha(path) for path in output.rglob('*') if path.is_file()}
    return dict(kind=policy.PREFIX+'-original-audit-1',passed=True,complete=True,qualified=False,
        researchSignal=report['researchSignal'],protocolSha256=c.sha(protocol),profileSha256=c.sha(profile_path),
        clockSha256=c.sha(output/'clock.json'),terminalExecutions=executions,artifactPins=pins,
        originalReproductionsVerified=True,active64UpdateIndependentlyRecomputed=True,
        allWin95StatisticsRecomputed=True,newGames=0,newFits=0,modelForwards=0)


class Campaign(search.Campaign):
    def numeric(self,mode,body):
        folder=self.output/'numeric'/mode;folder.mkdir(parents=True,exist_ok=False)
        request=folder/'request.json';c.write(request,dict(kind='token-focus-step-numeric-'+mode+'-1',**body))
        source=search.subject(self.plan['numericSourceReadiness'])
        command=[source['python']['path'],'-E','-s','-B',str(Path(source['sourceRoot'])/'token_focus_numeric.py'),
            '--mode',mode,'--request',str(request),'--output',str(folder/'artifacts')]
        if self.plan['study']!=96:command.extend(['--stage',str(self.plan['study'])])
        local.bounded_process(command,folder,self.profile,self.clock,self.cancelled,
            lambda process:setattr(self,'process',process))
        return numerical_receipt(self.output,mode)[0]

    def cohort(self,bank,cases):
        cohorts=[]
        for case in cases:
            source=self.contract.native_source(self.plan,self.profile,bank,case,0,c.sha(self.protocol))
            self.reserved+=local.expected_executions(source)
            c.require(self.reserved<=1560,'The one TOKEN learning attempt cannot exceed1560 terminal executions.')
            rows=local.execute(self.profile,source,self.output/'chunks'/bank/case['id']/'chunk-0',self.clock,
                self.cancelled,lambda process:setattr(self,'process',process),
                validator=lambda source,native:self.contract.native_chunk(source,native,bank)[0])
            cohorts.append(ordered(rows,source['count']));self.known+=local.expected_executions(source)
            self.status(status='running',stage=bank,case=case['id'])
        same_controls(cohorts);return cohorts

    def run_work(self):
        prep=self.numeric('prepare',dict(protocol=contract.declaration(self.protocol),
            seedSchedule=self.plan['seedSchedule'],originBundle=self.profile['candidateBundle'],
            inferenceAsset=self.profile['inferenceAsset']))
        center=numerical_artifact(self.output,'prepare','center')
        pop=self.numeric('population',dict(preparation=prep,center=center))
        population,cases=members(self.output);immutable_heads(self.profile,cases)
        result=outcomes(population,cases,self.cohort('training',cases))
        result['populationSha256']=numerical_artifact(self.output,'population','population')['sha256']
        c.write(self.output/'training-outcomes.json',result)
        update=self.numeric('update',dict(preparation=prep,population=pop,
            outcomes=contract.declaration(self.output/'training-outcomes.json')))
        self.numeric('final',dict(preparation=prep,update=update))
        final=dict(id='FINAL',bundle=numerical_artifact(self.output,'final','bundle'));immutable_heads(self.profile,[final])
        rows=self.cohort('holdout',[dict(id='START',bundle=self.profile['candidateBundle']),final])
        report=holdout(*rows);c.write(self.output/'holdout.json',report)
        return dict(kind=self.contract.PREFIX+'-terminal-1',study=self.plan['study'],complete=True,status='completed',qualified=False,
            researchSignal=report['researchSignal'],candidateBundle=final['bundle'],originPolicy=self.plan['originPolicy'],
            protocolSha256=c.sha(self.protocol),knownCompletedTerminalExecutions=self.known,
            reservedTerminalUpperBound=self.reserved,finishedEpoch=time.time(),activated=False)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--contract',choices=('token_focus_contract','token_focus_second_contract'),required=True)
    for name in ('protocol','profile','approval','output'):parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--approval-sha256',required=True);parser.add_argument('--launch',action='store_true')
    parser.add_argument('--owner-record',type=Path);args=parser.parse_args();policy=importlib.import_module(args.contract)
    if args.launch:return search.launch(args,policy,entrypoint=Path(__file__))
    result=Campaign(args.protocol,args.profile,args.approval,args.approval_sha256,args.output,policy).run(auditor=audit)
    print(json.dumps(result,sort_keys=True))


if __name__=='__main__':main()
