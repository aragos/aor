"""Pure finite C/P/E campaign audit: stream opaque worlds and retain compact world identities.

No numerical library, model forward, optimizer update, native call or process control is used.
Original TRAIN identities are recomputed one head at a time. VAL labels stay opaque unless
both complete atomic bundles and both all-head probability seals have already been admitted.
"""
from pathlib import Path

import cpe_paired_admission as admission
import cpe_paired_campaign_plan as p
import cpe_paired_completed as numeric
import cpe_paired_corpus as projection
import cpe_paired_handoff as handoff
import cpe_paired_storage as storage
from token_paired_campaign_audit import Pins,stream_archive,accepted_steps,finite


class Public:
    def __init__(self,record):self.record=record;self.sha256=p.digest(record)


def retained(path):
    """Match the archive inventory's incidental Finder-file exclusion, never a real subject."""
    return not (path.name=='.DS_Store' and path.is_file() and not path.is_symlink())


def inspected_chunk(plan,subject,pins,*,labels=False):
    path=pins.subject(subject);receipt=pins.read(path);head=p.head(receipt['head'])
    chunk=p.integer(receipt['chunk'],0,9,'Collection chunk');iteration=p.integer(receipt['iteration'],1,2,'Iteration')
    p.require(receipt['kind']=='cpe-paired-chunk-receipt-1' and receipt['host']==p.owner(chunk) and
        receipt['semanticValidationLabelsDecoded'] is False,
        'Each CPE shard must retain its original head/iteration/host owner.')
    rows=receipt['origins'];indices=list(p.indices(chunk))
    p.require([row['globalIndex'] for row in rows]==indices,'Every original shard contains64 distinct ordered world positions.')
    names={'prepared.json','source.json','finished.json','dictionary.json'}
    names.update(row['public']['file'] for row in rows)
    if labels:names.update(row['labels']['file'] for row in rows)
    names.update(f'replay-{index}/replay-{index}.json' for index in p.replays(chunk))
    members=stream_archive(path.with_name(receipt['archive']),receipt,pins,names)
    native=members['prepared.json'];done=members['finished.json']
    storage.prepared(plan,native,members['source.json'],receipt['sourceSha256'],receipt['files'],head,chunk,
        iteration,receipt['currentBundleSha256'])
    p.require(receipt['preparedSha256']==receipt['files']['prepared.json']['sha256'] and
        receipt['finishedSha256']==receipt['files']['finished.json']['sha256'] and
        done['kind']=='cpe-paired-finished-1' and done['complete'] is True and done['head']==head and
        done['iteration']==iteration and done['range']==native['range']==receipt['range'] and done['origins']==rows and
        done['signature']==receipt['signature']==native['signature'] and done['scienceSha256']==native['scienceSha256'] and
        all(receipt[key]==native[key] for key in ('bundleScopeSha256','scopeSha256','scienceSha256','scienceIdentity',
            'ecologySha256','currentBundleIdentity','originalBundleIdentity')),
        'Original native source/preparation/completion and compact receipt subjects must agree exactly.')
    compact={};tails=0;ready=0
    for row in rows:
        index=row['globalIndex']
        for key,name in (('origin',f'origin-{index}.json.gz'),('public',f'public-{index}.json'),('labels',f'labels-{index}.json')):
            p.require(row[key]['file']==name and receipt['files'][name]['sha256']==row[key]['sha256'],
                'Original opaque world/public/label bytes must remain independently pinned.')
        native_public=members[row['public']['file']];status=storage.public_record(native_public,row,native,plan,index)
        public=projection.normalized_public(native_public);typed=Public(public)
        target=projection.normalized_labels(members[row['labels']['file']],native_public,typed) if labels else None
        count=2*len(public['proposedIndices']) if status=='READY' else 0;tails+=count;ready+=status=='READY'
        p.require(row['tailGames']==count and row['anchorRows']==len(native_public['anchors']),
            'Backbone/tail/anchor counts must follow original public support, never realized outcomes.')
        anchor_sha=None;active=False
        if iteration==1 and index<512:
            anchor=projection.normalized_anchor(native_public,plan.record['heads'][head]['originalActorSha256'])
            seen=set()
            for state in anchor['observations']:
                key=(state['decisionNumber'],state['batchStartNumber'])
                p.require(key not in seen,'Original anchor world cannot repeat a decision/batch identity.');seen.add(key)
            if status=='READY':
                matching=[state for state in anchor['observations'] if
                    (state['decisionNumber'],state['batchStartNumber'])==(public['decisionNumber'],public['batchStartNumber'])]
                p.require(len(matching)==1 and all(matching[0][key]==public[key] for key in
                    ('observation','originalProbabilities')),'Iteration1 root must deduplicate its exact original backbone anchor.')
            anchor_sha=p.digest(anchor);active=bool(anchor['observations'])
        compact[index]=dict(publicSha256=typed.sha256,status=status,anchorSha256=anchor_sha,anchorActive=active,
            rewards=None if target is None else [action['rewards'] for action in target['actions']],
            validationLabel=target if index>=512 else None)
    p.require({name for name in receipt['files'] if name.startswith(('origin-','public-','labels-'))}==
        {row[key]['file'] for row in rows for key in ('origin','public','labels')},
        'No extra or missing original world sidecars may enter the admitted shard.')
    expected=dict(backboneGames=64,tailGames=tails,scientificTerminalGames=64+tails,
        readyRoots=ready,missingRoots=64-ready,capturedRoots=ready)
    p.require(all(done[key]==value for key,value in expected.items()) and all(receipt[key]==expected[key]
        for key in ('scientificTerminalGames','readyRoots','missingRoots')),
        'Native and compact counts must match every original public world.')
    p.require([row['globalIndex'] for row in receipt['replays']]==list(p.replays(chunk)) and
        receipt['historicalReproductions']==len(p.replays(chunk)),'Exactly the assigned historical reproductions are required.')
    for replay in receipt['replays']:
        index=replay['globalIndex'];name=f'replay-{index}/replay-{index}.json';value=members[name]
        p.require(replay['path']==name and replay['sha256']==receipt['files'][name]['sha256'] and
            replay['originalSha256']==rows[index-indices[0]]['origin']['sha256'] and replay['scientificTerminalGames']==1 and
            value['kind']=='cpe-paired-reproduction-1' and value['globalIndex']==index and
            value['request']==p.request(index,head) and value['sourceSha256']==receipt['sourceSha256'] and
            value['original']['sha256']==replay['originalSha256'] and value['fullBackboneEqual'] is True and
            value['publicRecordsEqual'] is True and value['terminalEqual'] is True and
            value['scientificTerminalGames']==1 and value['tailGames']==0 and
            all(value[key]==native[key] for key in ('signature','bundleScopeSha256','scopeSha256','scienceSha256')),
            'A reproduction must independently bind its complete original public/backbone/terminal equality.')
        for key in ('backboneTerminalSha256','publicRootSha256','anchorsSha256'):p.sha(value[key])
    return dict(receipt=receipt,subject=subject,worlds=compact,tails=tails)


def cohort(plan,subject,pins,*,train_labels,validation_labels):
    path=pins.subject(subject);record=pins.read(path);head=p.head(record['head']);iteration=record['iteration']
    p.require(record['kind']=='cpe-paired-corpus-1' and record['complete'] is True and
        record['protocolDigest']==plan.sha256 and len(record['chunks'])==10 and record['origins']==640 and
        record['trainOrigins']==512 and record['validationOrigins']==128 and
        record['semanticValidationLabelsDecoded'] is False,
        'A full head cohort requires all original640 worlds and ten immutable shards.')
    subjects=sorted(record['chunks'],key=lambda item:pins.read(pins.subject(item))['chunk'])
    p.require([pins.read(item['path'])['chunk'] for item in subjects]==list(range(10)),
        'Global shard completeness must precede any semantic label decoding.')
    worlds={};receipts=[];tails=0
    for chunk,item in enumerate(subjects):
        checked=inspected_chunk(plan,item,pins,labels=train_labels if chunk<8 else validation_labels)
        row=checked['receipt'];receipts.append(row);worlds.update(checked['worlds']);tails+=checked['tails']
        p.require(row['head']==head and row['iteration']==iteration,'A corpus cannot mix head or iteration shards.')
        for key in ('bundleScopeSha256','scopeSha256','scienceSha256','scienceIdentity','ecologySha256',
            'currentBundleSha256','originalBundleSha256','currentBundleIdentity','originalBundleIdentity'):
            p.require(row[key]==record[key],'A complete cohort subject changed: '+key)
    counts={split:sum(row['readyRoots'] for row in receipts if ('TRAIN' if row['chunk']<8 else 'VALIDATION')==split)
        for split in ('TRAIN','VALIDATION')}
    p.require(record['ready']==counts and record['exposurePassed']==(counts['TRAIN']>=256 and counts['VALIDATION']>=64) and
        record['scientificTerminalGames']==640+tails and record['historicalReproductions']==sum(row['historicalReproductions'] for row in receipts)==4,
        'Whole-head exposure and terminal counts must be recomputed from all original public origins.')
    return dict(record=record,subject=subject,worlds=worlds,tails=tails,receipts=receipts)


def training_identity(value,context,anchors):
    worlds=value['worlds'];iteration=value['record']['iteration']
    p.require(len(anchors)==512 and all(anchor['anchorSha256'] is not None for anchor in anchors),
        'Every original TRAIN world, including empty anchors, must retain its immutable group hash.')
    anchor_sha=p.digest([row['anchorSha256'] for row in anchors])
    expected=p.digest(dict(context=context,roots=[worlds[index]['publicSha256'] for index in range(512)],
        labels=[worlds[index]['rewards'] for index in range(512)],anchors=anchor_sha,
        validationReadyCount=value['record']['ready']['VALIDATION']))
    active=[row['anchorActive'] or (iteration==2 and worlds[index]['status']=='READY') for index,row in enumerate(anchors)]
    return expected,anchor_sha,active


def pin_transaction(directory,pins,*,complete=True):
    directory=Path(directory)
    if complete:
        checked=numeric.verify(directory)
        for path,digest in checked['artifactPins'].items():pins.pin(path,digest)
        return checked['completion'],checked['heads']
    stopped=pins.read(directory/'stopped.json');identity=pins.read(directory/'identity.json')
    p.require(stopped['kind']=='cpe-paired-bundle-stop-1' and stopped['complete'] is False and
        stopped['operationalPassed'] is False and stopped['reason']=='no-accepted-actor-change' and
        stopped['outputBundleSha256'] is None and stopped['identity']==identity and identity['recipe']==numeric.RECIPE and
        identity['sourceSha256']==numeric.source_hashes() and not (directory/'finished.json').exists() and
        not (directory/'bundle.json').exists(),'An honest unchanged-head stop cannot publish a next bundle.')
    stop=p.HEADS.index(p.head(stopped['head']));expected=list(p.HEADS[:stop+1])
    p.require(stopped['completedHeads']==expected and {path.name for path in (directory/'heads').iterdir() if retained(path)}==set(expected),
        'Only the contiguous attempted head prefix may exist at an operational stop.')
    heads={}
    for head in expected:
        child=numeric.child_completed(directory/'heads'/head)
        p.require(child['operationalPassed']==(head!=stopped['head']),
            'An operational stop follows the first and only unchanged child, never a failed or selected subset.')
        pins.pin(directory/'heads'/head/'finished.json')
        for name,digest in child['artifacts'].items():pins.pin(directory/'heads'/head/name,digest)
        heads[head]=child
    return stopped,heads


def portable(path,pins,protocol_sha):
    path=Path(path);value=admission.verify_iteration(path);pins.pin(path)
    p.require(value['protocolSha256']==protocol_sha,'Every portable admission retains the sole original protocol.')
    pins.pin(path.parents[2]/'protocol.json',protocol_sha)
    for name in ('currentBundle','outputBundle','originalBundle','corpus','fitCompletion','validationProbabilitySeal','previousAdmission'):
        if value[name] is not None:pins.pin(admission.resolve(value[name],path),value[name]['sha256'])
    for subject in value['cohorts'].values():pins.pin(admission.resolve(subject,path),subject['sha256'])
    pin_transaction(admission.resolve(value['fitCompletion'],path).parent,pins)
    return value


def publication(directory,stage,protocol_sha,pins,*,download):
    directory=Path(directory);value=pins.read(directory/'publication.json')
    p.require(value['kind']=='cpe-paired-publication-1' and value['stage']==stage and
        value['archive']=='publication.tar.gz' and value['identity']['protocolSha256']==protocol_sha,
        'Every transmitted publication must retain its exact prospective stage and protocol.')
    stream_archive(directory/value['archive'],value,pins,())
    content=directory/'content' if download else directory.parent.parent/'portable'
    actual={str(path.relative_to(content)) for path in content.rglob('*') if path.is_file() and retained(path)}
    if not download and stage=='iteration-2':
        actual={name for name in actual if not name.startswith('iterations/iteration-2/') and
            name not in ('final-admission.json','historical-admission.json')}
    p.require(actual==set(value['files']),
        'The transmitted publication inventory must equal its exact original portable closure.')
    for name,item in value['files'].items():
        path=content/storage.safe(name);p.require(path.stat().st_size==item['size'],'Portable member size changed.')
        pins.pin(path,item['sha256'])
    return value


def delivered(path,receipt,subject,pins):
    value=pins.read(path);tag=handoff.tag(receipt['iteration'],receipt['chunk'],head=receipt['head'])
    p.require(set(value)=={'kind','tag','receipt','archive'} and value['kind']=='cpe-paired-handoff-1' and
        value['tag']==tag and value['receipt']['sha256']==subject['sha256'] and
        value['archive']['sha256']==receipt['archiveSha256'] and
        Path(value['receipt']['path']).is_absolute() and Path(value['archive']['path']).is_absolute() and
        Path(value['receipt']['path']).name=='receipt.json' and Path(value['archive']['path']).name=='evidence.tar.gz' and
        Path(value['receipt']['path']).parent==Path(value['archive']['path']).parent and
        Path(value['archive']['path']).parent.name==tag,
        'Each Mac head shard must retain the receiver acceptance of its exact original bytes.')


def _desktop(plan,protocol_sha,run,terminal,pins):
    full=terminal['status']=='completed';admitted=p.integer(terminal['completedIterations'],0,2,'Admitted iterations')
    p.require((full and admitted==2 and terminal['reason']=='two-fixed-iterations-and-screen') or
        (not full and admitted<2 and terminal['reason'] in ('public-exposure-gate','no-accepted-actor-change')),
        'Desktop terminal must be full completion or one declared all-head operational stop.')
    attempts=2 if full else admitted+1
    p.require({path.name for path in (run/'iterations').iterdir() if path.is_dir() and retained(path)}==
        {f'iteration-{i}' for i in range(1,attempts+1)},'No extra or refilled iteration directory is admissible.')
    fits={};children={};aggregates={};seals={};labels={};counts=dict(backbones=0,pairedTails=0,backboneReproductions=0)
    for iteration in range(1,attempts+1):
        directory=run/'iterations'/f'iteration-{iteration}';aggregates[iteration]=pins.read(directory/'corpus.json')
        has_fit=iteration<=admitted or terminal['reason']=='no-accepted-actor-change'
        if has_fit:fits[iteration],children[iteration]=pin_transaction(directory/'fit',pins,complete=iteration<=admitted)
        else:
            p.require(not any((directory/name).exists() for name in ('fit','numeric-complete.json','validation.json')),
                'Public exposure failure must stop before every child fit or probability seal.')
        if iteration<=admitted:
            seal_path=directory/'validation.json';subject=dict(path=str(seal_path),sha256=pins.pin(seal_path))
            seals[iteration]=subject
            numeric.admitted_seal(subject)
            portable(run/'portable/iterations'/f'iteration-{iteration}'/'admission.json',pins,protocol_sha)
    if full:numeric.validate_seal_pair(seals)
    for head in p.HEADS:
        anchors=None;previous=None
        for iteration in range(1,attempts+1):
            aggregate=aggregates[iteration];directory=run/'iterations'/f'iteration-{iteration}'
            p.head_record(aggregate['cohorts']);value=cohort(plan,aggregate['cohorts'][head],pins,
                train_labels=iteration in fits,validation_labels=full)
            row=value['record'];counts['backbones']+=640;counts['pairedTails']+=value['tails'];counts['backboneReproductions']+=4
            current=plan.record['originalBundleSha256'] if iteration==1 else fits[1]['outputBundleSha256']
            p.require(row['head']==head and row['iteration']==iteration and row['currentBundleSha256']==current and
                all(row[key]==plan.record[key] for key in ('originalBundleSha256','bundleScopeSha256','ecologySha256')),
                'Each head/iteration must execute the one complete original or immediate predecessor bundle.')
            if iteration==1:anchors=[value['worlds'][index] for index in range(512)]
            if iteration in fits:
                identity=fits[iteration]['identity'];context=identity['contexts'][head];numeric.validate_context(context)
                expected,anchor_sha,active=training_identity(value,context,anchors)
                p.require(context['source_sha256']==value['subject']['sha256'] and
                    context['request_bank_sha256']==p.digest(plan.cohort(iteration,head)['origins']) and
                    context['scope_sha256']==row['scopeSha256']==plan.record['heads'][head]['scopeSha256'] and
                    context['dictionary_sha256']==plan.record['heads'][head]['dictionarySha256'] and
                    context['dictionary_semantic_sha256']==plan.record['heads'][head]['semanticDictionarySha256'] and
                    context['original_model_sha256']==plan.record['heads'][head]['originalActorSha256'] and
                    context['current_model_sha256']==row['currentBundleIdentity'][head]['sha256'] and
                    identity['trainingSha256'][head]==expected and identity['epochSeeds'][head]==plan.cohort(iteration,head)['shuffleSeeds'] and
                    all(identity[key]==row[key] for key in ('currentBundleSha256','originalBundleSha256','bundleScopeSha256','ecologySha256')) and
                    identity['originalDeadline']<=terminal['clock']['deadlineEpoch'],
                    'Recomputed TRAIN roots/paired targets/anchors and fixed head/current-bundle subjects must be exact.')
                if head in children[iteration]:
                    child=children[iteration][head]
                    p.require(child['identity']['context']==context and child['identity']['trainingSha256']==expected and
                        child['identity']['anchorSha256']==anchor_sha and child['identity']['publicCoverage']==
                        dict(trainingReady=row['ready']['TRAIN'],validationReady=row['ready']['VALIDATION']) and
                        child['adamSteps']==accepted_steps(active,identity['epochSeeds'][head],
                            pins.read(directory/'fit/heads'/head/'history.json')),
                        'Each completed child retains its exact original TRAIN identity and fresh accepted Adam counter.')
                    predecessor=child['identity']['predecessor']
                    p.require((predecessor is None) if iteration==1 else predecessor==dict(
                        bundleFinishedSha256=pins.pin(run/'iterations/iteration-1/fit/finished.json'),
                        headFinishedSha256=pins.pin(run/'iterations/iteration-1/fit/heads'/head/'finished.json'),
                        modelSha256=previous['outputModelSha256']), 'Each child consumes only its actual same-head atomic predecessor.')
                    previous=child
            if iteration<=admitted:
                seal=pins.read(seals[iteration]['path'])['heads'][head]
                p.require([row['publicSha256'] for row in seal['rows']]==[value['worlds'][i]['publicSha256'] for i in range(512,640)],
                    'All sealed VAL vectors must bind the exact original public corpus, including missing worlds.')
            if full:labels[(iteration,head)]=tuple(value['worlds'][i]['validationLabel'] for i in range(512,640))
            # Each cohort's full public observations were discarded by inspected_chunk.
    for iteration,aggregate in aggregates.items():
        rows={head:pins.read(pins.subject(subject)) for head,subject in aggregate['cohorts'].items()}
        expected=dict(kind='cpe-paired-iteration-corpus-1',iteration=iteration,protocolDigest=plan.sha256,complete=True,
            cohorts=aggregate['cohorts'],exposurePassed=all(row['exposurePassed'] for row in rows.values()),
            **{key:rows['CARD'][key] for key in ('currentBundleSha256','originalBundleSha256','bundleScopeSha256','ecologySha256')},
            independentOrigins=1920,scientificTerminalGames=sum(row['scientificTerminalGames'] for row in rows.values()),
            historicalReproductions=12,semanticValidationLabelsDecoded=False)
        p.require(aggregate==expected,'The global atomic corpus must equal all three complete independent cohorts.')
        directory=run/'iterations'/f'iteration-{iteration}'
        if iteration in fits:
            stage=pins.read(directory/'numeric-complete.json');operational=iteration<=admitted
            p.require(stage==dict(kind='cpe-paired-numeric-stage-1',iteration=iteration,complete=True,protocolSha256=protocol_sha,
                corpus=dict(path=str(directory/'corpus.json'),sha256=pins.pin(directory/'corpus.json')),
                fitCompletion=dict(path=str(directory/'fit/finished.json'),sha256=pins.pin(directory/'fit/finished.json')) if operational else None,
                stopReceipt=None if operational else dict(path=str(directory/'fit/stopped.json'),sha256=pins.pin(directory/'fit/stopped.json')),
                validationProbabilitySeal=seals.get(iteration),operationalPassed=operational,
                outputBundleSha256=fits[iteration]['outputBundleSha256']), 'Numerical stage must bind its actual complete or stopped transaction.')
        p.require(aggregate['exposurePassed']==(iteration in fits),'Only exposure-admitted cohorts may enter numerical fitting.')
    if full:
        effect=run/'iterations/validation-effect.json';pins.pin(effect)
        numeric.verify_effects(seals,effect,lambda iteration,head,subject:labels[(iteration,head)])
    else:
        p.require(not (run/'iterations/validation-effect.json').exists() and not (run/'screen-complete.json').exists() and
            not (run/'screen').exists() and not (run/'portable/final-admission.json').exists() and
            not (run/'iterations'/f'iteration-{attempts}'/'validation.json').exists(),
            'An operational stop cannot produce partial VAL effects or a candidate screen.')
    if admitted>=1:
        value=publication(run/'publications/iteration-2','iteration-2',protocol_sha,pins,download=False)
        p.require(value['identity']==dict(protocolSha256=protocol_sha,bundleSha256=fits[1]['outputBundleSha256'],
            bundleScopeSha256=plan.record['bundleScopeSha256']),'The next iteration publication cannot switch bundle.')
    if full:
        value=publication(run/'publications/screen','screen',protocol_sha,pins,download=False)
        p.require(value['identity']==dict(protocolSha256=protocol_sha,bundleSha256=fits[2]['outputBundleSha256'],
            bundleScopeSha256=plan.record['bundleScopeSha256']),'The final publication cannot switch the complete final bundle.')
    return dict(counts,completedIterations=admitted,fullTrainingRecomputed=len(fits)==attempts,
        trainingCohortsRecomputed=3*len(fits))


def _mac(plan,protocol_sha,run,terminal,pins):
    full=terminal['status']=='completed';admitted=p.integer(terminal['completedIterations'],0,2,'Admitted iterations')
    p.require((full and admitted==2 and terminal['reason']=='all-fixed-mac-shards-delivered') or
        (not full and admitted<2 and terminal['reason']=='coordinator-operational-stop'),
        'Mac stops only on the byte-exact coordinator operational stop, never as a successful failure.')
    if not full:
        path=pins.subject(terminal['peerTerminal']);peer=pins.read(path)
        p.require(path.resolve()==(run/'peer-terminal.json').resolve() and terminal['peerTerminal']['sha256']==
            terminal['originalPeerSubject']['sha256'] and Path(terminal['originalPeerSubject']['path']).is_absolute() and
            peer['kind']=='cpe-paired-terminal-1' and peer['host']=='DESKTOP' and peer['status']=='stopped' and
            peer['reason'] in ('public-exposure-gate','no-accepted-actor-change') and
            peer['completedIterations']==admitted and peer['clock']==terminal['clock'] and
            terminal['clock']['startedEpoch']<=peer['updatedEpoch']<=terminal['updatedEpoch'],
            'Mac operational stop must retain the exact original Desktop terminal and shared clock.')
    attempts=2 if full else admitted+1;current=plan.record['originalBundleSha256'];metadata={}
    if attempts==2:
        published=publication(run/'downloads/iteration-2','iteration-2',protocol_sha,pins,download=True)
        path=run/'downloads/iteration-2/content/iterations/iteration-1/admission.json'
        first=portable(path,pins,protocol_sha);current=first['outputBundle']['sha256']
        p.require(published['identity']==dict(protocolSha256=protocol_sha,bundleSha256=current,
            bundleScopeSha256=plan.record['bundleScopeSha256']),'The downloaded successor must be the exact first atomic bundle.')
        for head,subject in first['cohorts'].items():metadata[(1,head)]=pins.read(admission.resolve(subject,path))
    if full:
        published=publication(run/'downloads/screen','screen',protocol_sha,pins,download=True)
        for iteration in (1,2):
            path=run/'downloads/screen/content/iterations'/f'iteration-{iteration}'/'admission.json'
            value=portable(path,pins,protocol_sha)
            for head,subject in value['cohorts'].items():metadata[(iteration,head)]=pins.read(admission.resolve(subject,path))
        p.require(published['identity']==dict(protocolSha256=protocol_sha,bundleSha256=value['outputBundle']['sha256'],
            bundleScopeSha256=plan.record['bundleScopeSha256']),'The downloaded final screen bundle must be the exact second atomic bundle.')
    p.require({path.name for path in (run/'iterations').iterdir() if retained(path)}=={f'iteration-{i}' for i in range(1,attempts+1)},
        'Mac cannot execute an extra or refilled head iteration.')
    counts=dict(backbones=0,pairedTails=0,backboneReproductions=0)
    for iteration in range(1,attempts+1):
        base=run/'iterations'/f'iteration-{iteration}'/'cohorts'
        p.require({path.name for path in base.iterdir() if retained(path)}==set(p.HEADS),'Every prescribed head must be retained on Mac.')
        for head in p.HEADS:
            directory=base/head/'chunks'
            p.require({path.name for path in directory.iterdir() if retained(path)}=={f'chunk-{i}' for i in p.COLLECTION_CHUNKS['MAC']},
                'Mac must retain exactly its four original chunks per head and iteration.')
            for chunk in p.COLLECTION_CHUNKS['MAC']:
                path=directory/f'chunk-{chunk}'/'evidence.tar.gz.json';subject=dict(path=str(path),sha256=pins.pin(path))
                value=inspected_chunk(plan,subject,pins,labels=False);row=value['receipt']
                p.require(row['head']==head and row['iteration']==iteration and row['currentBundleSha256']==
                    (plan.record['originalBundleSha256'] if iteration==1 else current),
                    'Every local Mac shard must use the exact complete original or atomic successor bundle.')
                if (iteration,head) in metadata:
                    corpus=metadata[(iteration,head)]
                    p.require(corpus['scienceSha256']==row['scienceSha256'] and
                        any(item['sha256']==subject['sha256'] for item in corpus['chunks']),
                        'The global admitted corpus must contain this exact original Mac shard.')
                delivered(path.parent/'handoff.json',row,subject,pins)
                counts['backbones']+=64;counts['pairedTails']+=value['tails'];counts['backboneReproductions']+=len(p.replays(chunk))
    if not full:p.require(not (run/'downloads/screen').exists() and not (run/'screen').exists() and
        not (run/'screen-complete.json').exists(),'A propagated stop cannot produce a partial final screen.')
    return dict(counts,completedIterations=admitted,fullTrainingRecomputed=False,trainingCohortsRecomputed=0)


def verify(protocol_subject,run):
    pins=Pins();protocol=pins.subject(protocol_subject);pins.read(protocol);plan=p.Plan.from_file(protocol);run=Path(run).resolve()
    terminal=pins.read(run/'terminal.json');clock=pins.read(run/'clock.json')
    p.require(terminal['kind']=='cpe-paired-terminal-1' and terminal['host'] in p.HOSTS and
        terminal['status'] in ('completed','stopped','failed') and terminal['clock']==clock and
        clock['kind']=='cpe-paired-clock-1' and clock['protocolSha256']==protocol_subject['sha256'],
        'Terminal evidence must retain the original host, protocol and shared18-hour clock.')
    original=p.Clock(clock['startedEpoch'],clock['deadlineEpoch'])
    p.require(finite(terminal['updatedEpoch']) and terminal['updatedEpoch']>=original.start,'A terminal cannot precede its original attempt.')
    pins.deadline=original.audit_deadline(terminal['updatedEpoch']);pins.check_clock()
    owner=pins.read(run/'worker-owner.json')
    p.require(set(owner)=={'kind','pid','processGroup'} and owner['kind']=='cpe-paired-worker-owner-1' and
        type(owner['pid']) is int and owner['pid']>1 and type(owner['processGroup']) is int and owner['processGroup']>1,
        'Terminal audit requires typed original worker provenance, without requiring any process remain live.')
    execution=dict(newGames=0,modelForwards=0,optimizerSteps=0)
    if terminal['status']=='failed':
        pins.unchanged();return dict(kind='cpe-paired-campaign-audit-1',passed=False,complete=False,status='failed',
            host=terminal['host'],counts=None,eligibleFinalBundle=None,nominee=None,artifactPins=pins.values,execution=execution)
    p.require(terminal['updatedEpoch']<=original.deadline,'Completion or honest operational stopping must obey the original science deadline.')
    counts=(_desktop if terminal['host']=='DESKTOP' else _mac)(plan,protocol_subject['sha256'],run,terminal,pins)
    full=terminal['status']=='completed';screen=dict(worlds=0,games=0,reproductions=0,historicalControls=0,eligibleBundle=None,nominee=None)
    if full:
        import cpe_paired_screen
        screen=cpe_paired_screen.audit_completed(plan,run,terminal['host'],read_json=pins.read,check_subject=pins.subject,
            stream_archive=lambda path,receipt,names:stream_archive(path,receipt,pins,names))
        p.require(tuple(screen[key] for key in ('worlds','games','reproductions','historicalControls'))==
            ((1024,3072,12,2) if terminal['host']=='DESKTOP' else (384,1152,6,0)),
            'Every final world/role/historical control must remain in the prescribed host allocation.')
        if terminal['host']=='DESKTOP':
            report_path=pins.subject(terminal['report']);final_path=pins.subject(terminal['finalAdmission'])
            p.require(report_path.resolve()==(run/'screen-complete.json').resolve() and
                pins.read(report_path)['finalAdmission']==terminal['finalAdmission'] and
                final_path.resolve()==(run/'portable/final-admission.json').resolve(),
                'The original terminal must reference this exact completed final screen and atomic admission.')
    counts.update(origins=counts['backbones'],screenWorlds=screen['worlds'],screenGames=screen['games'],
        screenReproductions=screen['reproductions'],historicalControls=screen['historicalControls'])
    counts['scientificTerminals']=counts['backbones']+counts['pairedTails']+counts['screenGames']
    counts['terminalExecutions']=counts['scientificTerminals']+counts['backboneReproductions']+counts['screenReproductions']+counts['historicalControls']
    p.require(counts['terminalExecutions']<=plan.record['maximumTerminalExecutions'],'Terminal execution count exceeds the original finite cap.')
    pins.unchanged()
    return dict(kind='cpe-paired-campaign-audit-1',passed=True,complete=full,status=terminal['status'],host=terminal['host'],
        counts=counts,eligibleFinalBundle=screen['eligibleBundle'],nominee=screen['nominee'],artifactPins=pins.values,execution=execution)
