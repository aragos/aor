"""Completed78 three-role evidence and lossless chunks; no inference or fitting in verification."""
import copy
import gzip
import hashlib
import json
import math
from pathlib import Path, PurePosixPath
import shutil
import tarfile

import automata_experiments as exp
import automata_policy_search as arena
import purchase_storage as storage

read, sha, require, document = exp.read, exp.sha, exp.require, exp.document
KIND = 'expansion-baseline-screen-1'
ROLES = ('TREATMENT','CONTROL','RAW_G3')
REPLAYS = (0,341,682,1023)

def safe(name):
    p=PurePosixPath(name)
    require(isinstance(name,str) and not p.is_absolute() and '..' not in p.parts and str(p)==name,
            'Unsafe native evidence member.')
    return name

def inventory(root):
    root=Path(root); result={}
    for path in sorted(root.rglob('*')):
        require(not path.is_symlink(),'Native evidence cannot contain symlinks.')
        if path.is_file() and path.name!='.DS_Store':
            result[safe(path.relative_to(root).as_posix())]=dict(sha256=sha(path),size=path.stat().st_size)
    return result

def finished(value, files, stage, signature):
    worlds,reproductions=(0,2) if stage=='historical' else (512,6)
    require(value['kind']==KIND and value['signature']==signature and value['stage']==stage
            and value['complete'] is True and value['worlds']==worlds
            and value['reproductions']==reproductions and value['newFits']==0
            and value['qualification'] is False
            and value['files']=={name:row['sha256'] for name,row in files.items() if name!='finished.json'},
            'Original complete native stage membership/counts changed.')
    if stage=='historical': expected={f'historical-{i}.json' for i in range(2)}
    else:
        chunk=int(stage[-1]);indices=range(chunk*512,(chunk+1)*512)
        expected={f'world-{i}.json.gz' for i in indices}
        expected|={f'{kind}-{i}-{role}.json' for i in REPLAYS if i in indices for role in ROLES for kind in ('canonical','replay')}
        require({n for n in files if n.startswith('world-')}=={f'world-{i}.json.gz' for i in indices},
                'Every exact prospective world is required once.')
        require({n for n in files if n.startswith(('canonical-','replay-'))}==
                {n for n in expected if n.startswith(('canonical-','replay-'))},
                'Only the twelve prescribed role reproductions may appear across both chunks.')
    require(expected<=set(files),'A declared historical/role replay is missing.')

def archive_chunk(directory, target, chunk, signature):
    """Verify original native completion, fsync and verify every member, then remove exact originals."""
    directory,target=Path(directory),Path(target)
    require(chunk in (0,1) and not target.exists() and not Path(str(target)+'.json').exists(),'One fresh archive per fixed chunk.')
    files=inventory(directory); finished(read(directory/'finished.json'),files,f'chunk-{chunk}',signature)
    target.parent.mkdir(parents=True,exist_ok=True)
    def write(stream):
        with tarfile.open(fileobj=stream,mode='w:gz',compresslevel=6) as out:
            for name in files:out.add(directory/name,arcname=name,recursive=False)
    storage.publish(target,write)
    record=dict(kind='expansion-baseline-chunk-storage-1',chunk=chunk,originalDirectory=str(directory),
                archive=target.name,archiveSha256=sha(target),files=files)
    storage.verify_members(target,files,record['archiveSha256'])
    document(Path(str(target)+'.json'),record)
    require(inventory(directory)==files,'Original native chunk changed before pruning.')
    for name in files:(directory/name).unlink()
    # Only ignored incidental Finder files may remain; never delete an unexpected file.
    for path in sorted(directory.rglob('*'),key=lambda p:len(p.parts),reverse=True):
        if path.is_dir():path.rmdir()
        else:
            require(path.name=='.DS_Store' and not path.is_symlink(),'Unexpected post-archive native file.')
            path.unlink()
    directory.rmdir();storage.sync_directory(directory.parent)
    return record

def canonical_digest(value):
    return hashlib.sha256(json.dumps({k:value[k] for k in ('header','entries')},sort_keys=True,
                                    separators=(',',':'),ensure_ascii=False,allow_nan=False).encode()).hexdigest()

def snapshots(protocol,files):
    expected={name:digest for name,digest in protocol['nativeSnapshots'].items()
              if name not in ('manifest.json','started.json','finished.json')}
    require(expected and all(name in files and files[name]['sha256']==digest for name,digest in expected.items()),
            'Every original native model/source snapshot, including incumbent E, must remain byte-identical.')

def verify_chunk(protocol, archive, chunk):
    archive=Path(archive);record=read(Path(str(archive)+'.json'))
    require(record['kind']=='expansion-baseline-chunk-storage-1' and record['chunk']==chunk
            and record['archive']==archive.name,'Archived chunk identity changed.')
    for name in record['files']:safe(name)
    storage.verify_members(archive,record['files'],record['archiveSha256'])
    prepared=read(protocol['prepared']['path']); signature=prepared['signature']
    metadata={};worlds={};canonical={};replays={}
    with tarfile.open(archive,'r:gz') as source:
        for member in source:
            name=member.name
            if name.startswith('world-'):
                with source.extractfile(member) as stream:world=json.loads(gzip.decompress(stream.read()))
                index=world['index'];require(index in range(chunk*512,(chunk+1)*512) and index not in worlds,'Unique global world index required.')
                require(world['kind']==KIND and world['signature']==signature and world['replica']==protocol['replica'] and set(world['roles'])==set(ROLES),'Fixed three-role world required.')
                for control in ('RAW_G3','CONTROL'):
                    pair=dict(world,candidate=world['roles']['TREATMENT'],incumbent=world['roles'][control])
                    arena.validate_pair(pair,index,protocol['banks']['screen']['seeds'][index],1)
                worlds[index]=world
            elif name.startswith('canonical-') or name.startswith('replay-'):
                with source.extractfile(member) as stream:value=json.load(stream)
                if name.startswith('canonical-'):canonical[name]=canonical_digest(value)
                else:replays[name]={**{key:value.get(key) for key in ('replayVerified','index','seed','seat','configuration','opponentIndices','side','jointPolicy','expansionActor','game')},'canonicalDigest':canonical_digest(value)}
            elif name in ('manifest.json','finished.json','historical-admission.json'):
                with source.extractfile(member) as stream:metadata[name]=json.load(stream)
    require(sorted(worlds)==list(range(chunk*512,(chunk+1)*512)),'All512 independent worlds required.')
    require(metadata['manifest.json']==prepared,'Chunk source, complete roles or dictionary subjects changed.')
    finished(metadata['finished.json'],record['files'],f'chunk-{chunk}',signature)
    snapshots(protocol,record['files'])
    require(metadata['historical-admission.json']['sha256']==protocol['_historicalFinishedSha256'],
            'Fresh worlds require the same completed historical controls.')
    bindings=prepared['policyBindings']
    for index in REPLAYS:
        if index not in worlds:continue
        for role in ROLES:
            replay=replays[f'replay-{index}-{role}.json'];world=worlds[index]
            require(replay['replayVerified'] is True and replay['index']==index and replay['seed']==world['seed']
                    and replay['seat']==world['seat'] and replay['configuration']==world['configuration']
                    and replay['opponentIndices']==world['opponentIndices']
                    and replay['side']==('CANDIDATE' if role=='TREATMENT' else 'INCUMBENT')
                    and replay['jointPolicy']==bindings['jointPolicy']
                    and replay.get('expansionActor')==(None if role=='RAW_G3' else bindings['expansionActor'])
                    and arena.same_game(replay['game'],world['roles'][role])
                    and replay['canonicalDigest']==canonical[f'canonical-{index}-{role}.json'],
                    'Every role reproduction must match its exact original terminal, canonical history and actor role.')
    return [worlds[i] for i in sorted(worlds)]

def summarize(worlds):
    require([row['index'] for row in worlds]==list(range(1024)),
            'One completed bank only; no partial strength interpretation.')
    replicas={row['replica'] for row in worlds}
    require(len(replicas)==1 and replicas<=set(('PRIMARY_DESKTOP','MAC_REPLICATION')),
            'A completed screen must retain its single declared replica.')
    def contrast(first,second):
        def value(world,role,metric):
            game=world['roles'][role];seat=world['seat']
            if metric=='chaos':return int(game['scores'][seat]['total'] is None)
            result=game[metric][seat]
            require(type(result) in (int,float) and 0<=result<=1,
                    'Original normalized terminal metrics are required.')
            return result
        return {metric:exp.interval([value(w,first,metric)-value(w,second,metric) for w in worlds])
                for metric in ('wins','rewards','ranks','chaos')}
    primary=contrast('TREATMENT','CONTROL');raw=contrast('TREATMENT','RAW_G3')
    mechanism=primary['rewards']['lower95']>0
    return dict(kind='expansion-baseline-screen-report-1',replica=next(iter(replicas)),worlds=1024,
                treatmentMinusControl=primary,treatmentMinusG3=raw,controlMinusG3=contrast('CONTROL','RAW_G3'),
                primaryMechanismPassed=mechanism,
                developmentGatePassed=mechanism and raw['wins']['lower95']>0 and raw['rewards']['lower95']>0,
                intervalMethod='Normal95% across independent paired worlds; each role stored once',
                combinedReplicaAdmissionRequired=True,qualification=False,activated=False,automaticSuccessor=False)

def verify(protocol,run):
    run=Path(run)
    require(sha(protocol['prepared']['path'])==protocol['prepared']['sha256'],
            'The exact prepared native screen manifest must remain unchanged.')
    prepared=read(protocol['prepared']['path'])
    require({p.name for p in (run/'archives').iterdir()}==
            {f'chunk-{i}.tar.gz{suffix}' for i in (0,1) for suffix in ('','.json')},
            'Only the two fixed completed screen archives may exist.')
    historical=run/'historical';files=inventory(historical)
    finished(read(historical/'finished.json'),files,'historical',prepared['signature'])
    snapshots(protocol,files)
    require(read(historical/'manifest.json')==prepared,'Historical stage must bind the same completed eighth-actor study.')
    for ordinal,subject in enumerate(prepared['historical']):
        expected=read(subject['replay']['path']);actual=read(historical/f'historical-{ordinal}.json')
        pair=read(subject['pair']['path'])
        require(sha(subject['replay']['path'])==subject['replay']['sha256'] and actual['replayVerified'] is True
                and sha(subject['pair']['path'])==subject['pair']['sha256']
                and actual['historicalPairVerified'] is True and canonical_digest(actual)==canonical_digest(expected)
                and arena.same_game(actual['game'],pair[subject['side']])
                and arena.same_game(actual['game'],expected['game']),
                'Original historical controlled replay binding changed.')
        for key in ('index','configuration','seed','seat','side','opponentIndices','jointPolicy','expansionActor'):
            require(expected.get(key)==actual.get(key),'Historical replay role/seed changed: '+key)
        commands=lambda v:[(x['snapshotEntryCount'],x['commandEntryIndex'],x['chosen']['command']) for x in v['decisions']]
        require(commands(expected)==commands(actual),'Historical full controlled command trace changed.')
    local=dict(protocol,_historicalFinishedSha256=sha(historical/'finished.json'))
    worlds=[row for chunk in (0,1) for row in verify_chunk(local,run/'archives'/f'chunk-{chunk}.tar.gz',chunk)]
    result=summarize(worlds)
    return dict(passed=True,report=result,scientificWorlds=1024,scientificTerminalGames=3072,
                canonicalReproductions=12,historicalControls=2,terminalExecutions=3086,
                artifacts={str(path):sha(path) for folder in ('historical','archives') for path in (run/folder).rglob('*') if path.is_file() and path.name!='.DS_Store'},
                newAuditGames=0,newAuditFits=0,inferenceCalls=0)


def subject(path, expected=None):
    path=Path(path).resolve(strict=True)
    require(path.is_file() and not path.is_symlink(),'A regular immutable subject is required.')
    digest=sha(path)
    require(expected is None or digest==expected,'A declared subject changed: '+str(path))
    return dict(path=str(path),sha256=digest,originalPath=str(path))


def arena_source(protocol, admission):
    """Resolve only the existing arena's typed path fields; never rewrite model contents."""
    original=Path(protocol['arena'])
    require(sha(original)==protocol['arenaSha256'],'The original learning ecology changed.')
    value=copy.deepcopy(read(original))
    def absolute(name):
        require(isinstance(name,str),'Every fixed ecology role requires an explicit model path.')
        path=(original.parent/name).resolve(strict=True)
        require(protocol['files'].get(str(path))==sha(path),'Every arena role must be an original frozen input.')
        return str(path)
    require('expansionActor' not in value,'The learning ecology must retain its original E reference.')
    for name in ('candidate','incumbent'):value[name]=absolute(value[name])
    value['opponents']=[absolute(name) for name in value['opponents']]
    value['jointPolicy']={key:absolute(name) for key,name in value['jointPolicy'].items()}
    for owner in ('expansionImitation','tokenOrder'):
        for key,name in value[owner].items():
            if key in ('candidateModel','incumbentModel'):value[owner][key]=absolute(name)
            elif key=='opponentModels':value[owner][key]=[absolute(item) for item in name]
    value.update(pairsPerConfiguration=1024,seed=protocol['screenBank']['nativeOffset'],
        expansionActor=dict(version='expansion-actor-arena-2',
            candidate={k:admission['subjects']['treatmentPolicy'][k] for k in ('path','sha256')},
            incumbent={k:admission['subjects']['controlPolicy'][k] for k in ('path','sha256')}))
    return value


def admission(protocol, protocol_path, learning, audit_path):
    """No final actor is opened until both eighth actors have completed their pure audit."""
    audit=read(audit_path)
    require(audit['kind']=='expansion-baseline-learning-audit-1' and audit['passed'] is True and
        audit['complete'] is True and audit['eligibleFinalActors'] is True and
        audit['replica']==protocol['replica'] and audit['completedRounds']==8 and
        audit['completedBatches']==dict(CONTROL=8,TREATMENT=8) and
        audit['scientificWorlds']==4096 and audit['historicalReproductions']==16 and
        audit['firstBatchParityPassed'] is True and audit['operationalPrefixPassed'] is True and
        audit['protocolSha256']==sha(protocol_path) and
        audit['preparedSha256']==protocol['prepared']['sha256'],
        'Only the complete two-arm eighth-actor audit can open the development screen.')
    subjects=dict(learningAudit=subject(audit_path),learningProtocol=subject(protocol_path),
        prepared=subject(protocol['prepared']['path'],protocol['prepared']['sha256']),
        initialPolicy=subject(protocol['actor'],protocol['actorSha256']),
        originalArena=subject(protocol['arena'],protocol['arenaSha256']))
    original=read(protocol['arena'])
    ordinary=(Path(protocol['arena']).parent/original['candidate']).resolve(strict=True)
    subjects['ordinary']=subject(ordinary,protocol['files'][str(ordinary)])
    for arm in ('CONTROL','TREATMENT'):
        directory=Path(learning)/'batches/batch-08'/arm
        for name in ('policy','checkpoint','identity','receipt'):
            path=directory/'receipt.json' if name=='receipt' else directory/'numeric/actor'/f'{name}.json'
            path=path.resolve(strict=True)
            require(audit['arms'][arm][name+'Path']==str(path) and
                audit['artifactPins'][str(path)]==audit['arms'][arm][name+'Sha256'],
                'Final eighth-actor paths must be exactly the audited original subjects.')
            subjects[arm.lower()+name.title()]=subject(path,audit['arms'][arm][name+'Sha256'])
    return dict(kind='expansion-baseline-screen-admission-1',subjects=subjects)


def native_command(protocol, mode, source, output, extra=()):
    import expansion_on_policy_loop as legacy
    require(mode in ('prepare','historical','chunk'),'Only the declared fixed native screen stages are executable.')
    command=legacy.native_command(protocol,'expansion-baseline-screen-'+mode,source,output,extra)
    if mode=='prepare':
        index=command.index('--seconds')
        del command[index:index+2]
    return command


def run(protocol, protocol_path, output):
    """One same-clock learning audit, historical gate, then two losslessly archived chunks."""
    import expansion_baseline_audit as learning_audit
    import expansion_baseline_loop as loop
    import purchase_ecology as execution
    loop.Plan.read(protocol)
    output=Path(output).resolve(strict=True)
    audit_path=output/'learning-audit.json'
    document(audit_path,learning_audit.audit(protocol,protocol_path,output/'learning'))
    admitted=admission(protocol,protocol_path,output/'learning',audit_path)
    root=output/'screen'
    require(not root.exists(),'The final screen has exactly one attempt after completed learning.')
    root.mkdir()
    document(root/'admission.json',admitted)
    document(root/'arena.json',arena_source(protocol,admitted))
    document(root/'source.json',dict(kind=KIND,arena=subject(root/'arena.json'),
        admission=subject(root/'admission.json'),historical=protocol['historical']))
    source=root/'source.json'
    execution.run_child(protocol,native_command(protocol,'prepare',source,root/'prepared'),root/'prepare.log')
    prepared=read(root/'prepared/manifest.json')
    seeds=[-1000000000+protocol['screenBank']['nativeOffset']+i*8191 for i in range(1024)]
    require(prepared['replica']==protocol['replica'] and prepared['seeds']==seeds and
            prepared['worlds']==1024 and prepared['replayIndices']==list(REPLAYS) and
            prepared['roles']==list(ROLES),'Native preparation must preserve the exact fixed bank and roles.')
    context=dict(kind='expansion-baseline-screen-evidence-1',replica=protocol['replica'],
        learningProtocol=subject(protocol_path),learningAudit=subject(audit_path),
        prepared=subject(root/'prepared/manifest.json'),
        nativeSnapshots={name:item['sha256'] for name,item in inventory(root/'prepared').items()},
        banks=dict(screen=dict(protocol['screenBank'],seeds=seeds)))
    document(root/'evidence.json',context)
    execution.run_child(protocol,native_command(protocol,'historical',source,root/'historical'),root/'historical.log')
    for chunk in (0,1):
        directory=root/f'chunk-{chunk}'
        execution.run_child(protocol,native_command(protocol,'chunk',source,directory,
            ('--workers',str(protocol['workers']),'--chunk',str(chunk),'--historical',str(root/'historical'))),
            root/f'chunk-{chunk}.log')
        archive_chunk(directory,root/'archives'/f'chunk-{chunk}.tar.gz',chunk,prepared['signature'])
    result=verify(context,root)
    document(root/'report.json',result['report'])
    document(root/'verification.json',dict(result,reportSha256=sha(root/'report.json'),
        contextSha256=sha(root/'evidence.json')))
    document(output/'finished.json',dict(kind='expansion-baseline-replica-1',replica=protocol['replica'],
        complete=True,protocolSha256=sha(protocol_path),learningAuditSha256=sha(audit_path),
        reportSha256=sha(root/'report.json'),screenVerificationSha256=sha(root/'verification.json'),
        scientificTerminalGames=7168,historicalReproductions=30,terminalExecutions=7198,
        combinedReplicaAdmissionRequired=True,qualification=False,activated=False))


def streams(protocol):
    return ({-1000000000+x+i*8191 for x in protocol['offsets'] for i in range(256)} |
        {x+i for x in protocol['shuffleSeeds'] for i in range(4)} |
        {-1000000000+protocol['screenBank']['nativeOffset']+i*8191 for i in range(1024)})


def science_identity(protocol, prepared, original):
    """Compare behavior/math across hosts while excluding paths, seed banks and resource limits.

    Raw bundle JSON can carry relocated component paths. Its immutable card/purchase bytes,
    configuration, prior and complete encoder contracts determine the shared behavior instead.
    Native/runtime and numerical source bytes remain exact; machine libraries stay host subjects.
    """
    import expansion_baseline_loop as loop
    def portable(value):
        if isinstance(value,list):return [portable(item) for item in value]
        if isinstance(value,dict):return {k:portable(v) for k,v in value.items() if k not in ('path','snapshot')}
        return value
    bindings=prepared['policyBindings'];joint=bindings['jointPolicy']
    roles={}
    for role in ('sharedBundle','candidateBundle','incumbentBundle'):
        value=joint[role]
        require(value['card'] is not None and value['purchase'] is not None,
                'Every fixed G3 role must retain both learned heads.')
        roles[role]=portable({k:v for k,v in value.items() if k not in ('sha256','path','snapshot')})
    original_directory=Path(protocol['arena']).parent
    ordinary=[]
    for name in (original['candidate'],original['incumbent'],*original['opponents']):
        path=(original_directory/name).as_posix()
        require(path in protocol['files'],'Every original ordinary role must be frozen.')
        ordinary.append(protocol['files'][path])
    sources=protocol['scientificSources']
    require(set(sources)==set(loop.SCIENCE_SOURCES) and all(
        protocol['files'].get(item['path'])==item['sha256'] for item in sources.values()),
        'Every learning and likelihood source must have an exact original pin.')
    runtime=Path(protocol['runtime'])/'lib'
    jars={Path(path).name:digest for path,digest in protocol['files'].items()
          if Path(path).parent==runtime and path.endswith('.jar')}
    require(jars,'The exact native/runtime JAR set is required for replica equivalence.')
    return dict(kind='expansion-baseline-fixed-science-1',
        configuration=prepared['configuration'],rulesVersion=prepared['rulesVersion'],dataVersion=prepared['dataVersion'],
        actorVersion=prepared['actorVersion'],dictionaries=prepared['dictionaries'],
        controlledSeat=prepared['controlledSeat'],opponentIndices=prepared['opponentIndices'],reward=prepared['reward'],
        initialActorSha256=protocol['actorSha256'],ordinaryModelSha256=ordinary,
        jointPolicy=dict(version=joint['version'],baseline=portable(joint['baseline']),roles=roles),
        expansionImitation=portable(bindings['expansionImitation']),tokenOrder=portable(bindings['tokenOrder']),
        updateRecipe=loop.UPDATE_RECIPE,baselineRecipe=protocol['baselineRecipe'],
        numericalSourceSha256={name:item['sha256'] for name,item in sources.items()},runtimeJarSha256=jars)


def combine(replicas):
    """Interpret once both independent terminal audits admit complete fixed-role banks."""
    require(set(replicas)=={'PRIMARY_DESKTOP','MAC_REPLICATION'},'Both exact replicas are required; no runner-up.')
    reports,protocols,learning,science,pins={},{},{},{},{}
    for replica,subjects in replicas.items():
        require(set(subjects)=={'protocol','terminalAudit','report','learningAudit','prepared','arena'},
                'Bind each replica protocol, terminal/report/learning audits and original prepared ecology.')
        values={}
        for name,item in subjects.items():
            verified=subject(item['path'],item['sha256'])
            pins[verified['path']]=verified['sha256'];values[name]=read(verified['path'])
        protocol,terminal,report,audit=(values[k] for k in ('protocol','terminalAudit','report','learningAudit'))
        require(protocol['replica']==terminal['replica']==report['replica']==audit['replica']==replica and
            terminal['kind']=='expansion-baseline-terminal-audit-1' and terminal['passed'] is True and
            terminal['status']=='completed' and terminal['allOwnedProcessesExited'] is True and
            terminal['terminalExecutions']==7198 and
            terminal['protocolSha256']==subjects['protocol']['sha256'] and
            terminal['reportSha256']==subjects['report']['sha256'] and
            terminal['learningAuditSha256']==subjects['learningAudit']['sha256'] and
            audit['complete'] is True and audit['eligibleFinalActors'] is True and
            audit['protocolSha256']==subjects['protocol']['sha256'] and
            audit['preparedSha256']==subjects['prepared']['sha256']==protocol['prepared']['sha256'] and
            subjects['arena']['sha256']==protocol['arenaSha256'] and
            report['kind']=='expansion-baseline-screen-report-1' and report['worlds']==1024 and
            report['qualification'] is False and report['activated'] is False,
            'Both replicas must be complete, independently audited and cleaned before interpretation.')
        for name in ('treatmentMinusControl','treatmentMinusG3','controlMinusG3'):
            for metric in ('wins','rewards','ranks','chaos'):
                row=report[name][metric]
                require(row['count']==1024 and all(type(row[k]) in (float,int) and math.isfinite(row[k])
                    for k in ('mean','lower95','upper95'))
                    and -1<=row['mean']<=1 and row['lower95']<=row['mean']<=row['upper95'],
                    'Each effect requires a complete finite paired-world interval.')
        reports[replica],protocols[replica],learning[replica]=report,protocol,audit
        science[replica]=science_identity(protocol,values['prepared'],values['arena'])
    first,second=(protocols[k] for k in ('PRIMARY_DESKTOP','MAC_REPLICATION'))
    require(science['PRIMARY_DESKTOP']==science['MAC_REPLICATION'] and
        streams(first).isdisjoint(streams(second)),
        'Independent replicas must use identical fixed science and disjoint prospective streams.')
    mechanism={key:value['treatmentMinusControl']['rewards']['lower95']>0 for key,value in reports.items()}
    primary=reports['PRIMARY_DESKTOP']['treatmentMinusG3']
    passed=all(mechanism.values()) and primary['wins']['lower95']>0 and primary['rewards']['lower95']>0
    actor=learning['PRIMARY_DESKTOP']['arms']['TREATMENT']
    return dict(kind='expansion-baseline-combined-report-1',bothReplicasComplete=True,
        mechanismPassed=mechanism,nominationPassed=passed,
        nominee=dict(replica='PRIMARY_DESKTOP',arm='TREATMENT',batch=8,
            path=actor['policyPath'],sha256=actor['policySha256']) if passed else None,
        reports=reports,fixedScience=science['PRIMARY_DESKTOP'],inputPins=pins,terminalExecutions=14396,qualification=False,
        activated=False,automaticSuccessor=False,noMacFallback=True)
