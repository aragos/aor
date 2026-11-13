"""All-head probability barriers and equal-phase paired estimates; no checkpoint selection."""
from dataclasses import asdict
import math
from pathlib import Path
from statistics import NormalDist

import purchase_policy as b
import cpe_paired_data as data
import cpe_paired_fit as fit
import cpe_paired_policy as policy


def seal_validation(fit_directory, original, current, dictionaries, output, load_public):
    """Open public VAL only after atomic completion; publish one seal for all three heads."""
    directory=Path(fit_directory);done=fit.completed(directory)
    b.require(isinstance(dictionaries,dict) and set(dictionaries)==set(policy.HEADS),
        'All three raw dictionaries must be explicitly supplied.')
    output=Path(output);b.require(not output.exists(),'All-head VAL probability publication is once-only.')
    finished_sha=b.file_digest(directory/'finished.json');selected=fit.load_completed_bundle(directory)
    heads={}
    for head in policy.HEADS:
        context=data.Context.from_record(done['identity']['contexts'][head.value])
        context.bind(original,current,dictionaries[head])
        roots=tuple(load_public(head))
        b.require(len(roots)==128 and all(isinstance(root,data.PublicRoot) and root.context==context and
            root.record['split']=='VALIDATION' and root.record['index']==512+index for index,root in enumerate(roots)),
            'Each head must retain every original128 VAL public positions in order.')
        child=fit.child_completed(directory/'heads'/head.value)
        b.require(sum(root.ready for root in roots)==child['identity']['publicCoverage']['validationReady']>=64,
            'Sealed VAL support must equal the outcome-blind pre-fit admission.')
        actor=policy.CpePolicy(selected.actor(head).model,head)
        behavior=policy.CpePolicy(current.actor(head).model,head)
        anchor=policy.CpePolicy(original.actor(head).model,head)
        entries=[]
        with b.torch.no_grad():
            for root in roots:
                row=root.record;values=None
                if row['status']!='MISSING':
                    view=row['observation'];old,_=behavior([view]);initial,_=anchor([view]);new,_=actor([view])
                    for logs,expected in ((old,row['currentProbabilities']),(initial,row['originalProbabilities'])):
                        b.require(max(abs(left-right) for left,right in zip(logs[0].exp().tolist(),expected))<=2e-12,
                            'VAL behavior/original full probabilities must match their exact head actors.')
                    values=new[0].exp().tolist();data.probabilities(values,len(view['actions']),'Sealed VAL probabilities')
                entries.append(dict(index=row['index'],status=row['status'],publicSha256=root.sha256,
                    requestSha256=row['requestSha256'],futureSeeds=row['futureSeeds'],referenceIndex=row['referenceIndex'],
                    proposedIndices=row['proposedIndices'],inclusionProbability=row['inclusionProbability'],
                    currentProbabilities=row['currentProbabilities'],probabilities=values))
        heads[head.value]=dict(context=asdict(context),modelSha256=selected.actor(head).sha256,rows=entries,
            counts={status:sum(row['status']==status for row in entries) for status in ('READY','MISSING')})
    b.require(fit.completed(directory)==done and b.file_digest(directory/'finished.json')==finished_sha,
        'The same complete three-head transaction must survive all public probability generation.')
    receipt=dict(kind='cpe-paired-validation-probabilities-1',complete=True,iteration=done['identity']['iteration'],
        fitDirectory=str(directory.resolve()),fitFinishedSha256=finished_sha,bundleSha256=selected.sha256,
        originalBundleSha256=original.sha256,currentBundleSha256=current.sha256,
        bundleScopeSha256=done['identity']['bundleScopeSha256'],heads=heads,semanticLabelsDecoded=False)
    b.atomic_json(output,receipt)
    return dict(path=str(output.resolve()),sha256=b.file_digest(output))


def admitted_seal(subject):
    data.exact(subject,('path','sha256'),'All-head probability seal subject')
    path=Path(subject['path']);data.sha(subject['sha256'],'Probability seal')
    b.require(b.file_digest(path)==subject['sha256'],'The persisted probability seal changed.')
    seal=b.read_json(path)
    b.require(seal['kind']=='cpe-paired-validation-probabilities-1' and seal['complete'] is True and
        seal['semanticLabelsDecoded'] is False and set(seal['heads'])=={head.value for head in policy.HEADS},
        'Only a complete public all-head seal can admit labels.')
    directory=Path(seal['fitDirectory']);done=fit.completed(directory)
    b.require(b.file_digest(directory/'finished.json')==seal['fitFinishedSha256'] and
        done['outputBundleSha256']==seal['bundleSha256'] and done['identity']['iteration']==seal['iteration'] and
        all(done['identity'][key]==seal[key] for key in
            ('originalBundleSha256','currentBundleSha256','bundleScopeSha256')),
        'The exact completed bundle and scientific identity must precede VAL labels.')
    for head in policy.HEADS:
        item=seal['heads'][head.value];child=fit.child_completed(directory/'heads'/head.value)
        b.require(item['context']==child['identity']['context'] and item['modelSha256']==child['outputModelSha256'] and
            len(item['rows'])==128 and item['counts']=={status:sum(row['status']==status for row in item['rows'])
                for status in ('READY','MISSING')} and
            item['counts']['READY']==child['identity']['publicCoverage']['validationReady']>=64,
            'Every sealed head requires its exact completed actor and original support counts.')
        for index,row in enumerate(item['rows'],512):
            b.require(row['index']==index and row['status'] in ('READY','MISSING'),
                'All original VAL world positions and support flags must remain exact.')
            data.sha(row['publicSha256'],'Sealed public input');data.sha(row['requestSha256'],'Sealed request')
            if row['status']=='MISSING':
                b.require(all(row[key] is None for key in ('referenceIndex','proposedIndices','inclusionProbability',
                    'currentProbabilities','probabilities')),'Missing VAL roots cannot invent distributions or decisions.')
                continue
            count=len(row['probabilities']);data.probabilities(row['probabilities'],count,'Sealed probabilities')
            data.probabilities(row['currentProbabilities'],count,'Sealed behavior probabilities')
            size=min(8,count-1);indices=row['proposedIndices']
            b.require(count>1 and row['status']=='READY' and len(indices)==size+1==len(set(indices)) and
                indices[0]==row['referenceIndex'] and row['inclusionProbability']==(size/(count-1) if count>1 else 0.),
                'Sealed sampled reference, support and uniform inclusion matrix must remain exact.')
            for action in indices:b.integer(action,'Sealed offered index',0,count-1)
    return seal,done


def estimate(values):
    b.require(len(values)>=2,'An origin interval requires at least two independent worlds.')
    mean=math.fsum(values)/len(values)
    variance=math.fsum((value-mean)**2 for value in values)/(len(values)-1)
    error=math.sqrt(variance/len(values));margin=NormalDist().inv_cdf(.975)*error
    return dict(mean=mean,variance=variance,standardError=error,lower=mean-margin,upper=mean+margin,
        origins=len(values),interval='two-sided-normal-95-independent-world')


def equal_phase(estimates):
    b.require(set(estimates)=={head.value for head in policy.HEADS},'Equal-phase inference requires all three phases.')
    mean=math.fsum(value['mean'] for value in estimates.values())/3
    variance=math.fsum(value['variance']/value['origins'] for value in estimates.values())/9
    error=math.sqrt(variance);margin=NormalDist().inv_cdf(.975)*error
    return dict(mean=mean,standardError=error,lower=mean-margin,upper=mean+margin,
        interval='two-sided-normal-95-equal-phase-independent-worlds',phaseWeights={head.value:1/3 for head in policy.HEADS})


def _effects(item, labels):
    b.require(len(labels)==128,'Each head retains all128 original label/missing positions.')
    results=[]
    for row,label in zip(item['rows'],labels):
        if row['status']!='READY':
            b.require(label is None,'Missing/singleton roots have no fabricated paired effects.')
            results.append(dict(index=row['index'],status=row['status'],gain=None));continue
        data.exact(label,('publicSha256','futureSeeds','actions'),'Detached VAL labels')
        b.require(label['publicSha256']==row['publicSha256'] and label['futureSeeds']==row['futureSeeds'] and
            len(label['actions'])==len(row['proposedIndices']),
            'VAL labels must bind the sealed public root and complete original paired matrix.')
        rewards=[]
        for index,action in zip(row['proposedIndices'],label['actions']):
            data.exact(action,('actionIndex','rewards'),'VAL sibling outcome')
            b.require(action['actionIndex']==index and isinstance(action['rewards'],list) and len(action['rewards'])==2,
                'Every original proposed sibling requires both ordered future returns.')
            rewards.append(tuple(b.number(value,'Normalized paired reward',0.,1.) for value in action['rewards']))
        values=tuple(math.fsum((row['probabilities'][index]-row['currentProbabilities'][index])*
            (reward[stream]-rewards[0][stream])/row['inclusionProbability']
            for index,reward in zip(row['proposedIndices'][1:],rewards[1:])) for stream in range(2))
        results.append(dict(index=row['index'],status='READY',pairedFutureGains=values,gain=math.fsum(values)/2))
    return dict(counts=item['counts'],estimate=estimate([row['gain'] for row in results if row['gain'] is not None]),perOrigin=results)


def validate_seal_pair(sealed_subjects):
    """Shared intrinsic barrier; callers must not substitute a weaker receipt-shape check."""
    b.require(isinstance(sealed_subjects,dict) and set(sealed_subjects)=={1,2},
        'Both fixed iteration probability seals must be supplied before any labels.')
    seals={};completed={}
    for iteration in (1,2):
        seals[iteration],completed[iteration]=admitted_seal(sealed_subjects[iteration])
        b.require(seals[iteration]['iteration']==iteration,'Iteration seals cannot be swapped or substituted.')
    first=seals[1];second=seals[2]
    b.require(second['currentBundleSha256']==first['bundleSha256'] and
        all(second[key]==first[key] for key in ('originalBundleSha256','bundleScopeSha256')) and
        completed[2]['identity']['previousFinishedSha256']==first['fitFinishedSha256'],
        'VAL analysis requires the sole original-to-first-to-second atomic bundle chain.')
    return seals,completed


def evaluate_validation(sealed_subjects, output, load_labels):
    """Require both completed iterations AND both all-head seals before any label callback.

    load_labels(iteration, Head, exact_subject) returns128 detached label positions.
    This owner runs no model forward and never selects an epoch, head or iteration.
    """
    seals,completed=validate_seal_pair(sealed_subjects)
    second=seals[2]
    output=Path(output);b.require(not output.exists(),'All-head VAL effects are evaluated once-only after both barriers.')
    reports={}
    for iteration in (1,2):
        heads={head.value:_effects(seals[iteration]['heads'][head.value],
            tuple(load_labels(iteration,head,dict(sealed_subjects[iteration])))) for head in policy.HEADS}
        reports[str(iteration)]=dict(heads=heads,equalPhase=equal_phase({key:value['estimate'] for key,value in heads.items()}))
    b.require(all(b.file_digest(subject['path'])==subject['sha256'] for subject in sealed_subjects.values()),
        'Both original probability barriers must survive every semantic label pass.')
    for iteration in (1,2):
        b.require(fit.completed(seals[iteration]['fitDirectory'])==completed[iteration],
            'Both exact complete atomic transactions must survive VAL evaluation.')
    report=dict(kind='cpe-paired-validation-effect-1',probabilitySeals={str(key):value for key,value in sealed_subjects.items()},
        iterations=reports,finalIteration=2,finalBundleSha256=second['bundleSha256'],
        mechanismPassed=reports['2']['equalPhase']['lower']>0,optimizerSteps=0,modelForwards=0,
        selectedCheckpoint=False,individualPhasesDescriptiveOnly=True)
    b.atomic_json(output,report);return report
