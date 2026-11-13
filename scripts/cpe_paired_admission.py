"""Portable atomic three-head receipts; no inference, effects or checkpoint selection."""
from pathlib import Path

import cpe_paired_campaign_plan as p
import cpe_paired_storage as storage
from cpe_paired_completed import completed as fit_receipt
from token_paired_admission import relative_subject, resolve, copy_subject

def bundle_subject(source):
    path=p.checked_subject(source);value=p.read(path)
    p.require(value['kind']=='cpe-paired-bundle-1', 'A complete raw bundle descriptor is required.')
    p.head_record(value['heads'])
    for item in value['heads'].values():
        name=storage.safe(item['path']);p.sha(item['dictionarySha256'])
        p.checked_subject(dict(path=str((path.parent/name).resolve()),sha256=item['sha256']))
    return value


def copy_bundle(source, target):
    """Copy the exact relative descriptor and its explicit components, preserving raw bytes."""
    source_path=p.checked_subject(source);bundle=bundle_subject(source);target=Path(target)
    for head,item in bundle['heads'].items():
        name=storage.safe(item['path'])
        p.require(name!='bundle.json', 'A model component cannot overwrite its descriptor.')
        copy_subject(dict(path=str((source_path.parent/name).resolve()),sha256=item['sha256']),target.parent/name)
    copy_subject(source,target)
    return target


def bindings(plan, corpus, done, seal, directory, cohorts):
    """Pure byte/receipt admission common to original publication and relocated verification."""
    identity=done['identity'];iteration=identity['iteration']
    p.require(corpus['kind']=='cpe-paired-iteration-corpus-1' and corpus['complete'] is True and
        corpus['exposurePassed'] is True and corpus['iteration']==iteration and
        corpus['protocolDigest']==plan.sha256 and corpus['independentOrigins']==1920 and
        corpus['originalBundleSha256']==identity['originalBundleSha256']==plan.record['originalBundleSha256'] and
        corpus['currentBundleSha256']==identity['currentBundleSha256'] and
        corpus['bundleScopeSha256']==identity['bundleScopeSha256']==plan.record['bundleScopeSha256'] and
        corpus['ecologySha256']==identity['ecologySha256']==plan.record['ecologySha256'],
        'All three complete cohorts and the atomic fit must bind the exact shared scientific plan.')
    p.require(seal['kind']=='cpe-paired-validation-probabilities-1' and seal['complete'] is True and
        seal['semanticLabelsDecoded'] is False and seal['iteration']==iteration and
        seal['fitFinishedSha256']==p.file_sha(directory/'finished.json') and
        seal['bundleSha256']==done['outputBundleSha256'] and
        all(seal[key]==identity[key] for key in ('originalBundleSha256','currentBundleSha256','bundleScopeSha256')),
        'The complete all-head probability seal must precede atomic publication.')
    p.head_record(cohorts);p.head_record(seal['heads'])
    for head,(subject,row) in cohorts.items():
        bank=plan.cohort(iteration,head)
        context=identity['contexts'][head];child=p.read(directory/'heads'/head/'finished.json')
        p.require(row['kind']=='cpe-paired-corpus-1' and row['head']==head and row['iteration']==iteration and
            row['complete'] is True and row['exposurePassed'] is True and row['origins']==640 and
            row['ready']['TRAIN']>=256 and row['ready']['VALIDATION']>=64 and
            row['protocolDigest']==plan.sha256 and corpus['cohorts'][head]['sha256']==subject['sha256'] and
            context['source_sha256']==subject['sha256'] and context['scope_sha256']==row['scopeSha256'] and
            all(row[key]==identity[key] for key in
                ('originalBundleSha256','currentBundleSha256','bundleScopeSha256','ecologySha256')) and
            context['scope_sha256']==plan.record['heads'][head]['scopeSha256'] and
            context['dictionary_sha256']==plan.record['heads'][head]['dictionarySha256'] and
            context['dictionary_semantic_sha256']==plan.record['heads'][head]['semanticDictionarySha256'] and
            context['original_model_sha256']==plan.record['heads'][head]['originalActorSha256'] and
            context['request_bank_sha256']==p.digest(bank['origins']) and
            identity['epochSeeds'][head]==bank['shuffleSeeds'],
            'Every child must bind its exact head corpus, complete dictionary, original actor and prospective streams.')
        item=seal['heads'][head]
        p.require(item['context']==context and item['modelSha256']==child['outputModelSha256'] and
            len(item['rows'])==128 and [r['index'] for r in item['rows']]==list(range(512,640)) and
            all(r['futureSeeds']==bank['origins'][r['index']]['future']
                for r in item['rows']) and
            item['counts']==dict(READY=row['ready']['VALIDATION'],MISSING=128-row['ready']['VALIDATION']) and
            item['counts']=={status:sum(r['status']==status for r in item['rows']) for status in ('READY','MISSING')} and
            child['identity']['publicCoverage']==dict(trainingReady=row['ready']['TRAIN'],
                validationReady=row['ready']['VALIDATION']),
            'Every head seal must retain its original128 positions and exact public exposure counts.')


def publish_iteration(protocol, original, corpus, directory, seal, portable):
    plan=p.Plan.from_file(p.checked_subject(protocol));corpus_value=p.read(p.checked_subject(corpus))
    directory=Path(directory);done=fit_receipt(directory);identity=done['identity'];iteration=identity['iteration']
    seal_value=p.read(p.checked_subject(seal))
    cohorts={head:(item,p.read(p.checked_subject(item))) for head,item in corpus_value['cohorts'].items()}
    bindings(plan,corpus_value,done,seal_value,directory,cohorts)
    portable=Path(portable);target=portable/'iterations'/f'iteration-{iteration}'
    p.require(not target.exists(), 'Each atomic publication is immutable and once-only.')
    if iteration==1:
        p.require(not portable.exists() and identity['previousFinishedSha256'] is None and
            identity['currentBundleSha256']==original['sha256']==plan.record['originalBundleSha256'],
            'First publication must start from the sole complete original bundle.')
        portable.mkdir(parents=True);copy_subject(protocol,portable/'protocol.json')
        current=copy_bundle(original,portable/'original/bundle.json');previous=None
    else:
        previous=portable/'iterations/iteration-1/admission.json';old=verify_iteration(previous)
        p.require(old['protocolSha256']==protocol['sha256'] and old['outputBundle']['sha256']==identity['currentBundleSha256'] and
            old['fitCompletion']['sha256']==identity['previousFinishedSha256'] and
            old['bundleScopeSha256']==identity['bundleScopeSha256'], 'The second bundle must consume the sole first publication.')
        current=resolve(old['outputBundle'],previous)
    target.mkdir(parents=True);copy_subject(corpus,target/'corpus.json');copy_subject(seal,target/'validation.json')
    for head,(item,_) in cohorts.items():copy_subject(item,target/'cohorts'/(head+'.json'))
    for name in ('finished.json',*done['artifacts']):copy_subject(p.subject(directory/name),target/'fit'/name)
    admission=target/'admission.json'
    result=dict(kind='cpe-paired-iteration-admission-1',iteration=iteration,
        protocolSha256=protocol['sha256'],bundleScopeSha256=identity['bundleScopeSha256'],
        ecologySha256=identity['ecologySha256'],currentBundle=relative_subject(current,admission),
        outputBundle=relative_subject(target/'fit/bundle.json',admission),
        originalBundle=relative_subject(portable/'original/bundle.json',admission),
        corpus=relative_subject(target/'corpus.json',admission),
        cohorts={head:relative_subject(target/'cohorts'/(head+'.json'),admission) for head in p.HEADS},
        fitCompletion=relative_subject(target/'fit/finished.json',admission),
        validationProbabilitySeal=relative_subject(target/'validation.json',admission),
        previousAdmission=None if previous is None else relative_subject(previous,admission),
        ready={head:row['ready'] for head,(_,row) in cohorts.items()},
        acceptedEpochs={head:p.read(directory/'heads'/head/'finished.json')['acceptedEpochs'] for head in p.HEADS},
        allHeadsChanged=True)
    storage.publish_json(admission,result);verify_iteration(admission)
    return p.subject(admission)


def verify_iteration(path):
    path=Path(path);value=p.read(path)
    p.require(value['kind']=='cpe-paired-iteration-admission-1' and value['allHeadsChanged'] is True,
        'Only the explicit changed complete three-head publication can be consumed.')
    protocol=path.parents[2]/'protocol.json';plan=p.Plan.from_file(protocol)
    p.require(p.file_sha(protocol)==value['protocolSha256'], 'Portable admission must retain its exact protocol bytes.')
    for name in ('currentBundle','outputBundle','originalBundle','corpus','fitCompletion','validationProbabilitySeal'):
        resolve(value[name],path)
    directory=resolve(value['fitCompletion'],path).parent;done=fit_receipt(directory);identity=done['identity']
    corpus=p.read(resolve(value['corpus'],path));seal=p.read(resolve(value['validationProbabilitySeal'],path))
    cohorts={head:(item,p.read(resolve(item,path))) for head,item in p.head_record(value['cohorts']).items()}
    bindings(plan,corpus,done,seal,directory,cohorts)
    current=bundle_subject(p.subject(resolve(value['currentBundle'],path)))
    original=bundle_subject(p.subject(resolve(value['originalBundle'],path)))
    for head in p.HEADS:
        context=identity['contexts'][head]
        p.require(current['heads'][head]['sha256']==context['current_model_sha256'] and
            original['heads'][head]['sha256']==context['original_model_sha256'] and
            current['heads'][head]['dictionarySha256']==original['heads'][head]['dictionarySha256']==
                context['dictionary_semantic_sha256'],
            'Every child current/original head must resolve to its actual complete bundle component bytes.')
    p.require(value['iteration']==identity['iteration'] and value['bundleScopeSha256']==identity['bundleScopeSha256'] and
        value['ecologySha256']==identity['ecologySha256'] and
        value['currentBundle']['sha256']==identity['currentBundleSha256'] and
        value['originalBundle']['sha256']==identity['originalBundleSha256'] and
        value['outputBundle']['sha256']==done['outputBundleSha256'] and
        value['ready']=={head:row['ready'] for head,(_,row) in cohorts.items()} and
        value['acceptedEpochs']=={head:p.read(directory/'heads'/head/'finished.json')['acceptedEpochs'] for head in p.HEADS},
        'Portable bundle, scope, corpus and all-head health must retain their exact atomic chain.')
    if value['iteration']==1:
        p.require(value['previousAdmission'] is None and identity['previousFinishedSha256'] is None and
            value['currentBundle']['sha256']==value['originalBundle']['sha256'], 'The first current bundle must be original.')
    else:
        previous_path=resolve(value['previousAdmission'],path)
        old=verify_iteration(previous_path)
        p.require(old['iteration']==1 and old['protocolSha256']==value['protocolSha256'] and
            old['bundleScopeSha256']==value['bundleScopeSha256'] and
            old['outputBundle']['sha256']==value['currentBundle']['sha256'] and
            old['fitCompletion']['sha256']==identity['previousFinishedSha256'],
            'The final bundle cannot switch predecessor, head or original scientific scope.')
        previous_directory=resolve(old['fitCompletion'],previous_path).parent
        for head in p.HEADS:
            previous_child=p.read(previous_directory/'heads'/head/'finished.json')
            p.require(identity['predecessors'][head]==dict(bundleFinishedSha256=old['fitCompletion']['sha256'],
                headFinishedSha256=p.file_sha(previous_directory/'heads'/head/'finished.json'),
                modelSha256=previous_child['outputModelSha256']),
                'Each final head must bind the exact previous atomic child receipt and actor.')
    return value


def publish_final(portable):
    portable=Path(portable);protocol=portable/'protocol.json';plan=p.Plan.from_file(protocol)
    paths=[portable/'iterations'/f'iteration-{i}'/'admission.json' for i in (1,2)]
    rows=[verify_iteration(path) for path in paths];target=portable/'final-admission.json'
    p.require(all(row['protocolSha256']==p.file_sha(protocol) for row in rows),
        'Both completed iterations must bind the sole original protocol.')
    result=dict(kind='cpe-paired-final-screen-admission-1',protocol=relative_subject(protocol,target),
        bundleScopeSha256=rows[1]['bundleScopeSha256'],ecologySha256=plan.record['ecologySha256'],
        iterations=[relative_subject(path,target) for path in paths],
        finalBundle=relative_subject(resolve(rows[1]['outputBundle'],paths[1]),target),
        originalBundle=relative_subject(portable/'original/bundle.json',target),
        completedIterations=2,learnerHost='DESKTOP',operationallyComplete=True,
        validationEffectsUsedForSelection=False)
    storage.publish_json(target,result);return p.subject(target)
