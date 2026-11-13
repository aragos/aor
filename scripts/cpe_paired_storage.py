"""Public-only C/P/E shard admission over the unchanged lossless archive owner.

Labels and full origins are hashed, never parsed here. All three independent head
cohorts must complete under one current bundle before any child optimizer starts.
"""
from pathlib import Path

import cpe_paired_campaign_plan as p
from token_paired_storage import (safe, publish_json, inventory, portable,
    archive_collection, verify_archive, member_json, public_members)


def prepared(plan, native, source, source_sha, files, phase, chunk, iteration, current_sha):
    original=plan.record['heads'][phase]
    p.require(native['kind']=='cpe-paired-collect-1' and native['head']==phase and
        native['iteration']==iteration and native['range']==dict(globalStart=chunk*64,count=64) and
        native['source']['sha256']==source_sha and native['source']['snapshot']=='source.json',
        'Prepared head/iteration/range/source must match the exact original request.')
    p.require(native['origins']==plan.cohort(iteration,phase)['origins'] and
        native['requests']==[p.request(index,phase) for index in p.indices(chunk)] and
        native['replayIndices']==list(p.COLLECTION_REPLAYS) and
        native['ownedReplayIndices']==list(p.replays(chunk)),
        'Prepared streams, global requests and once-only replay ownership changed.')
    p.require(native['currentBundle']['sha256']==current_sha and
        native['originalBundle']['sha256']==plan.record['originalBundleSha256'] and
        native['bundleScopeSha256']==plan.record['bundleScopeSha256'] and
        native['scopeSha256']==original['scopeSha256'] and
        native['ecologySha256']==plan.record['ecologySha256'] and
        native['dictionary']['sha256']==original['dictionarySha256'] and
        native['dictionarySha256']==original['semanticDictionarySha256'],
        'Prepared complete bundle, head dictionary or immutable ecological scope differs.')
    dictionaries=p.head_record(native['dictionaries'])
    p.require(all(p.digest(dictionaries[head])==plan.record['heads'][head]['semanticDictionarySha256']
        for head in p.HEADS), 'All three complete native dictionaries must remain exact.')
    for key in ('arena','currentBundle','originalBundle'):
        p.require(native[key]['sha256']==source[key]['sha256'], 'Source and prepared subject disagree: '+key)
    for prefix,key in (('current','currentBundleIdentity'),('original','originalBundleIdentity')):
        identity=p.head_record(native[key])
        for head,row in identity.items():
            p.require(set(row)=={'sha256','dictionarySha256'} and
                row['dictionarySha256']==plan.record['heads'][head]['semanticDictionarySha256'] and
                files[f'{prefix}/{head.lower()}.json']['sha256']==p.sha(row['sha256']),
                'Every semantic bundle component must bind its exact dictionary and model snapshot.')
            if prefix=='original':
                p.require(row['sha256']==plan.record['heads'][head]['originalActorSha256'],
                    'The complete original G3 C/P and untrained E actor subjects are immutable.')
        p.require(files[f'{prefix}/bundle.json']['sha256']==native[prefix+'Bundle']['sha256'],
            'The exact complete bundle descriptor snapshot must remain bound.')
    snapshots=native['snapshots']
    p.require(snapshots and all(safe(name) in files and files[name]['sha256']==digest
        for name,digest in snapshots.items()), 'Every original model/source/ecology snapshot must remain exact.')
    p.require(files['source.json']['sha256']==source_sha and
        files[safe(native['dictionary']['path'])]['sha256']==native['dictionary']['sha256'],
        'Raw source and dictionary snapshots must match their original bytes.')
    for key in ('signature','bundleScopeSha256','scopeSha256','scienceSha256'):p.sha(native[key])


def public_record(value, receipt, native, plan, index):
    p.require(set(value)=={'kind','signature','bundleScopeSha256','scopeSha256','scienceSha256',
        'iteration','request','streams','status','originSha256','missingReason','observedWindows',
        'branchingWindows','selectedOrdinal','publicRoot','anchors'},
        'C/P/E public evidence must exclude private history, terminal labels and all outcomes.')
    phase=native['head']
    p.require(value['kind']=='cpe-paired-public-1' and
        all(value[key]==native[key] for key in ('signature','bundleScopeSha256','scopeSha256',
            'scienceSha256','iteration')) and
        value['request']==p.request(index,phase) and
        value['streams']==plan.cohort(native['iteration'],phase)['origins'][index] and
        value['originSha256']==receipt['origin']['sha256'] and value['status']==receipt['status'],
        'Public evidence differs from its exact complete-origin/head/request subject.')
    status=value['status'];p.require(status in ('READY','MISSING'), 'Only branching READY or MISSING roots exist.')
    observed=p.integer(value['observedWindows'],0,2**31-1,'Observed assigned-epoch windows')
    branching=p.integer(value['branchingWindows'],0,observed,'Branching assigned-epoch windows')
    root=value['publicRoot']
    p.require((root is None)==(status=='MISSING') and (branching==0)==(status=='MISSING'),
        'A uniform branching reservoir is missing exactly when no branching window exists.')
    if status=='MISSING':
        p.require(value['selectedOrdinal'] is None and
            value['missingReason']=='NO_BRANCHING_WINDOW_IN_ASSIGNED_EPOCH',
            'Missing reservoir roots require their exact reason and no selected ordinal.')
    else:
        p.integer(value['selectedOrdinal'],1,branching,'Selected branching reservoir ordinal')
        p.require(value['missingReason'] is None, 'A retained branching root cannot have a missing reason.')
        actions=root['observation']['actions'];count=len(actions)
        p.integer(count,2,1024,'Full offered action count')
        prior=p.integer(root['observation']['baselineIndex'],0,count-1,'Network prior index')
        sampled=p.integer(root['sampledCommandIndex'],0,count-1,'Current sampled command index')
        p.require(root['referenceIndex']==sampled,
            'The paired reference must be the actual current sampled command, not the network prior.')
        # prior is deliberately not equated with sampled/reference: qualified C/P heads are nonzero.
        p.require(actions[prior]['command'] is not None, 'The original network prior must remain an offered command.')
        proposed=root['proposedIndices'];size=min(8,count-1)
        p.require(len(proposed)==size+1 and len(set(proposed))==len(proposed) and
            proposed[0]==sampled and all(type(i) is int and 0<=i<count for i in proposed) and
            proposed[1:]==sorted(proposed[1:]) and root['inclusionProbability']==size/(count-1),
            'Original uniform-subset order, full offered indices and inclusion probability changed.')
        for key in ('currentProbabilities','originalProbabilities'):
            probabilities=root[key]
            p.require(len(probabilities)==count and all(type(x) in (float,int) and 0<x<=1
                for x in probabilities) and abs(sum(probabilities)-1)<=1e-9,
                'Every offered original/current policy probability must be finite and normalized.')
    p.require(isinstance(value['anchors'],list) and (not value['anchors'] or
        (native['iteration']==1 and index<512)), 'Only original TRAIN backbones may supply fixed anchors.')
    for anchor in value['anchors']:
        p.require(anchor['head']==phase and anchor['iteration']==1 and anchor['globalIndex']==index,
            'An original-policy anchor cannot cross head/world/iteration boundaries.')
    return status


def verify_collection(plan, root, source, phase, chunk, iteration, current_sha, replay_verifier):
    root=Path(root);files=inventory(root);native=p.read(root/'prepared.json');done=p.read(root/'finished.json')
    source_sha=p.file_sha(source)
    prepared(plan,native,p.read(source),source_sha,files,phase,chunk,iteration,current_sha)
    p.require(done['kind']=='cpe-paired-finished-1' and done['complete'] is True and
        done['head']==phase and done['iteration']==iteration and done['range']==native['range'] and
        done['signature']==native['signature'] and done['scienceSha256']==native['scienceSha256'],
        'Only the complete original head shard may enter learning or archive admission.')
    rows=done['origins'];p.require([r['globalIndex'] for r in rows]==list(p.indices(chunk)),
        'Every prescribed independent world must occur once in canonical order.')
    statuses=[];tails=0
    for row in rows:
        index=row['globalIndex']
        for key,name in (('origin',f'origin-{index}.json.gz'),('public',f'public-{index}.json'),
            ('labels',f'labels-{index}.json')):
            p.require(row[key]['file']==name and files[name]['sha256']==row[key]['sha256'],
                'Complete origin/public/label raw bytes must match before public decoding.')
        value=p.read(root/row['public']['file']);status=public_record(value,row,native,plan,index)
        statuses.append(status);expected=2*len(value['publicRoot']['proposedIndices']) if status=='READY' else 0
        p.require(row['tailGames']==expected and row['anchorRows']==len(value['anchors']),
            'Original backbone/paired-tail/anchor execution counts changed.')
        tails+=expected
    p.require({name for name in files if name.startswith(('origin-','public-','labels-'))}==
        {row[key]['file'] for row in rows for key in ('origin','public','labels')},
        'No extra, missing or duplicate world sidecars may enter the cohort.')
    p.require(done['backboneGames']==64 and done['tailGames']==tails and
        done['scientificTerminalGames']==64+tails and done['readyRoots']==statuses.count('READY') and
        done['missingRoots']==statuses.count('MISSING') and done['capturedRoots']==statuses.count('READY'),
        'Native complete origin/tail/branching exposure counts must be exact.')
    replays=[replay_verifier(root,i,native,rows[i-chunk*64]) for i in p.replays(chunk)]
    return dict(kind='cpe-paired-chunk-receipt-1',head=phase,iteration=iteration,chunk=chunk,
        host=p.owner(chunk),range=native['range'],signature=native['signature'],
        bundleScopeSha256=native['bundleScopeSha256'],scopeSha256=native['scopeSha256'],
        scienceSha256=native['scienceSha256'],scienceIdentity=native['scienceIdentity'],
        ecologySha256=native['ecologySha256'],sourceSha256=source_sha,
        preparedSha256=files['prepared.json']['sha256'],finishedSha256=files['finished.json']['sha256'],
        currentBundleSha256=current_sha,originalBundleSha256=plan.record['originalBundleSha256'],
        currentBundleIdentity=native['currentBundleIdentity'],originalBundleIdentity=native['originalBundleIdentity'],
        origins=rows,readyRoots=done['readyRoots'],missingRoots=done['missingRoots'],
        scientificTerminalGames=64+tails,historicalReproductions=len(replays),replays=replays,
        files=files,semanticValidationLabelsDecoded=False)


def cohort_corpus(plan, iteration, phase, chunk_subjects):
    p.integer(iteration,1,2,'Iteration');p.head(phase)
    p.require(len(chunk_subjects)==10, 'Every head corpus requires all ten original fixed shards.')
    rows=[]
    for item in chunk_subjects:
        path=p.checked_subject(item);row=p.read(path);verify_archive(path.with_name(row['archive']),row)
        rows.append(row)
    rows.sort(key=lambda row:row['chunk'])
    p.require([row['chunk'] for row in rows]==list(range(10)) and all(
        row['kind']=='cpe-paired-chunk-receipt-1' and row['iteration']==iteration and row['head']==phase and
        row['host']==p.owner(row['chunk']) for row in rows), 'A cohort must contain exactly its prescribed host/head shards.')
    for key in ('bundleScopeSha256','scopeSha256','scienceSha256','scienceIdentity','ecologySha256',
        'currentBundleSha256','originalBundleSha256','currentBundleIdentity','originalBundleIdentity'):
        p.require(len({p.digest(row[key]) for row in rows})==1,
            'Host-local signatures may differ; complete portable cohort identity must match: '+key)
    p.require(rows[0]['originalBundleSha256']==plan.record['originalBundleSha256'] and
        rows[0]['bundleScopeSha256']==plan.record['bundleScopeSha256'] and
        rows[0]['scopeSha256']==plan.record['heads'][phase]['scopeSha256'] and
        rows[0]['ecologySha256']==plan.record['ecologySha256'], 'Cohort identity must bind the frozen plan.')
    counts={split:sum(row['readyRoots'] for row in rows if
        ('TRAIN' if row['chunk']<8 else 'VALIDATION')==split) for split in ('TRAIN','VALIDATION')}
    p.require(sum(row['historicalReproductions'] for row in rows)==4,
        'The four prescribed reproductions must execute once globally per head cohort.')
    return dict(kind='cpe-paired-corpus-1',head=phase,iteration=iteration,protocolDigest=plan.sha256,
        complete=True,origins=640,trainOrigins=512,validationOrigins=128,
        **{key:rows[0][key] for key in ('bundleScopeSha256','scopeSha256','scienceSha256','scienceIdentity',
            'ecologySha256','currentBundleSha256','originalBundleSha256','currentBundleIdentity','originalBundleIdentity')},
        chunks=chunk_subjects,ready=counts,exposurePassed=counts['TRAIN']>=256 and counts['VALIDATION']>=64,
        scientificTerminalGames=sum(row['scientificTerminalGames'] for row in rows),
        historicalReproductions=4,semanticValidationLabelsDecoded=False)


def iteration_corpus(plan, iteration, subjects):
    """Atomic exposure admission: no fit can start until all three full cohorts are known."""
    p.head_record(subjects);rows={head:p.read(p.checked_subject(item)) for head,item in subjects.items()}
    for head,row in rows.items():
        p.require(row==cohort_corpus(plan,iteration,head,row['chunks']),
            'Each head corpus must independently reproduce its exact complete admission.')
    for key in ('currentBundleSha256','originalBundleSha256','bundleScopeSha256','ecologySha256'):
        p.require(len({row[key] for row in rows.values()})==1,
            'All three cohorts must share one immutable complete bundle and ecological scope.')
    return dict(kind='cpe-paired-iteration-corpus-1',iteration=iteration,protocolDigest=plan.sha256,
        complete=True,cohorts=subjects,exposurePassed=all(row['exposurePassed'] for row in rows.values()),
        **{key:rows['CARD'][key] for key in
            ('currentBundleSha256','originalBundleSha256','bundleScopeSha256','ecologySha256')},
        independentOrigins=1920,scientificTerminalGames=sum(row['scientificTerminalGames'] for row in rows.values()),
        historicalReproductions=12,semanticValidationLabelsDecoded=False)
