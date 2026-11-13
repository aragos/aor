"""Lossless ES artifacts and explicit per-generation byte accounting.

The original storage owner verifies every archive member before pruning expanded output.
This adapter adds only the finite ES identities and resource census; no fitness is decoded
by archiving or byte accounting.
"""
from pathlib import Path
import re
import tarfile

import coordinated_es_plan as p
from token_paired_storage import (
    archive_collection, inventory, member_json, publish_json, safe, verify_archive,
)


def role(index):
    item=p.mutant(index)
    return ('PLUS' if item['sign']==1 else 'MINUS')+f'-{item["direction"]:02d}'


def game_name(index,mutant=None):
    p.integer(index,0,255,'Global native world index')
    return f'game-{role(mutant) if mutant is not None else "RAW_G3"}-{index}.json'


def generation_record(plan,request,source_sha,prepared,finished,files,number,chunk,phase,replay_values):
    """Pure native metadata admission, shared before pruning and after lossless retention."""
    p.require(phase in ('controls','population'),'Unknown original native execution phase.')
    subjects={key:request[key] for key in ('protocol','arena','originalBundle','projection',
        'center','directions','population','calibration','previousUpdate','historical')}
    p.require(request==plan.generation_source(number,chunk,phase.upper(),subjects),
        'Native collection must use the exact prescribed source, global bank and shard.')
    p.require(prepared['kind']==p.PREFIX+'-prepared-1' and
        prepared['stage']==phase.upper() and prepared['generation']==number and
        prepared['globalStart']==chunk*32 and prepared['count']==32 and
        prepared['bank']==plan.bank(number)['worldBank'] and set(prepared['subjects'])==set(subjects),
        'Native preparation must bind every exact generation subject and full global bank.')
    for name,declared in subjects.items():
        actual=prepared['subjects'][name]
        if declared is None:
            p.require(actual is None,'A null stage subject cannot acquire an executable binding.')
        else:
            p.require(set(actual)=={'path','sha256','originalPath'} and
                actual['sha256']==declared['sha256'] and
                actual['originalPath']==declared.get('originalPath',declared['path']) and
                prepared['inputPins'].get(actual['path'])==actual['sha256'],
                'Native resolved subjects must preserve exact declared provenance and raw input bytes.')
    original=plan.record['original']
    p.require(prepared['source']['sha256']==source_sha and
        prepared['scopeSha256']==original['scopeSha256'] and
        prepared['ecologySha256']==original['ecologySha256'] and
        prepared['originalBundle']['sha256']==original['bundleSha256'],
        'Native preparation changed its raw source, original bundle or complete fixed ecology.')
    p.require(files['source.json']['sha256']==source_sha and
        files['arena.json']['sha256']==subjects['arena']['sha256'] and
        prepared['arena']['sha256']==subjects['arena']['sha256'],
        'Original raw source and arena snapshot bytes must remain explicitly bound.')
    p.require(set(prepared['dictionaries'])==set(p.HEADS) and all(
        p.digest(prepared['dictionaries'][head])==original['heads'][head]['dictionarySha256']
        for head in p.HEADS),'Every full native head dictionary must preserve its canonical identity.')
    p.require(prepared['inputPins'] and all(isinstance(path,str) and Path(path).is_absolute() and p.sha(digest)
        for path,digest in prepared['inputPins'].items()),
        'Every native input retains its explicit original path and immutable raw hash.')
    for field in ('signature','scopeSha256','scienceSha256'):p.sha(prepared[field])
    expected=32 if phase=='controls' else 1024
    replays=[] if phase=='controls' else p.replays(number,chunk)
    p.require(finished['kind']==p.PREFIX+'-complete-1' and
        all(finished[key]==prepared[key] for key in
            ('signature','scopeSha256','scienceSha256','stage','generation','globalStart','count')) and
        finished['attemptedGames']==finished['completedGames']==finished['expectedGames']==expected and
        finished['canonicalGames']==len(replays),
        'Only the complete once-attempted fixed native shard can be admitted.')
    native_files=finished['files']
    p.require(native_files and all(safe(name) in files and files[name]['sha256']==digest
        for name,digest in native_files.items()),'Every original native completion file must match.')
    games=[game_name(index,mutant) for index in p.indices(chunk)
        for mutant in ([None] if phase=='controls' else range(32))]
    panels=[f'panel-{index}.json' for index in p.indices(chunk)] if phase=='controls' else []
    p.require(all(name in native_files for name in games+panels),
        'Every original world/member and required public panel must be present once.')
    p.require({name for name in native_files if name.startswith('game-')}==set(games) and
        {name for name in native_files if name.startswith('panel-')}==set(panels),
        'No extra or substituted native game/panel belongs to this shard.')
    replay_receipts=[];replay_files=set()
    for item in replays:
        index=item['globalIndex'];name=role(item['mutant']['index'])
        directory=f'replay-{name}-{index}';filename=directory+'/'+directory+'.json'
        value=replay_values[filename]
        p.require(value['kind']==p.PREFIX+'-reproduction-1' and
            value['signature']==prepared['signature'] and
            value['scienceSha256']==prepared['scienceSha256'] and
            value['sourceSha256']==source_sha and value['role']==name and value['globalIndex']==index and
            value['original']['sha256']==files[game_name(index,item['mutant']['index'])]['sha256'] and
            all(value[key] is True for key in ('terminalEqual','canonicalEqual','replayVerified')) and
            value['scientificTerminalExecutions']==value['attemptedGames']==value['completedGames']==1,
            'A prescribed reproduction must retain exact original game and full canonical equality.')
        actual_name=directory+'/'+safe(value['actual']['file'])
        p.require(files[actual_name]['sha256']==value['actual']['sha256'],
            'A reproduction must retain the exact actual compact game bytes it verified.')
        original_game=replay_values[game_name(index,item['mutant']['index'])]
        actual_game=replay_values[actual_name]
        def semantic(game):
            return dict(game,terminal={key:item for key,item in game['terminal'].items() if key!='elapsedMillis'})
        p.require(original_game['canonical'] is not None and
            original_game['canonical']['replayVerified'] is True and
            actual_game['canonical']['replayVerified'] is True and semantic(original_game)==semantic(actual_game),
            'Actual reproduction must preserve the complete original canonical and semantic game.')
        actual_files={key for key in files if key.startswith(directory+'/')}
        p.require(actual_files=={filename,actual_name},
            'A prescribed reproduction retains exactly its actual game and equality receipt.')
        replay_receipts.append(dict(globalIndex=index,role=name,file=filename,sha256=files[filename]['sha256']))
        replay_files|=actual_files
    p.require(set(files)==set(native_files)|{'finished.json'}|replay_files,
        'The completed shard inventory cannot omit generated evidence or unexpected files.')
    return dict(kind=p.PREFIX+'-chunk-receipt-1',phase=phase,generation=number,chunk=chunk,
        host=p.owner(chunk),globalStart=chunk*32,count=32,signature=prepared['signature'],
        scopeSha256=prepared['scopeSha256'],scienceSha256=prepared['scienceSha256'],
        sourceSha256=source_sha,protocolSha256=subjects['protocol']['sha256'],subjects=subjects,
        preparedSha256=files['prepared.json']['sha256'],finishedSha256=files['finished.json']['sha256'],
        inputPins=prepared['inputPins'],attemptedGames=expected,completedGames=expected,
        historicalReproductions=len(replays),scientificTerminalExecutions=expected+len(replays),
        games=games,panels=panels,replays=replay_receipts,files=files,fitnessDecoded=False)


def verify_generation(plan,root,source,number,chunk,phase):
    """Admit native bytes without decoding game outcomes or public observations."""
    root=Path(root);files=inventory(root);prepared=p.read(root/'prepared.json')
    p.require(all(p.file_sha(path)==digest for path,digest in prepared['inputPins'].items()),
        'Every admitted native/model/reference input must still match its original bytes.')
    values={}
    if phase=='population':
        for item in p.replays(number,chunk):
            name=f'replay-{role(item["mutant"]["index"])}-{item["globalIndex"]}'
            values[name+'/'+name+'.json']=p.read(root/name/(name+'.json'))
            original_name=game_name(item['globalIndex'],item['mutant']['index'])
            values[original_name]=p.read(root/original_name)
            actual_name=name+'/'+values[name+'/'+name+'.json']['actual']['file']
            values[actual_name]=p.read(root/actual_name)
    return generation_record(plan,p.read(source),p.file_sha(source),prepared,p.read(root/'finished.json'),
        files,number,chunk,phase,values)


def members(receipt_subject,names):
    """One archive/member at a time; explicit filenames are the semantic-read capability."""
    path=p.checked_subject(receipt_subject);receipt=p.read(path)
    archive=path.with_name(receipt['archive']);verify_archive(archive,receipt)
    allowed=set(names);p.require(allowed<=set(receipt['files']),'Requested semantic evidence is absent.')
    found=set()
    with tarfile.open(archive,'r|gz') as source:
        for member in source:
            if member.name in allowed:
                found.add(member.name);yield member.name,member_json(source,member,receipt)
    p.require(found==allowed,'Every requested semantic member must occur exactly once.')


def panel_world(value,receipt,plan,index):
    p.require(set(value)=={'kind','signature','scopeSha256','scienceSha256','sourceSha256',
        'originalBundleSha256','generation','globalIndex','seed','seat','assignedEpoch','heads',
        'windows','game'},'Calibration panels must contain only the exact own/public typed fields.')
    epoch=1+(index//4)%3
    p.require(value['kind']==p.PREFIX+'-panel-1' and value['generation']==1 and
        value['globalIndex']==index and value['seed']==plan.bank(1)['worldBank']['seeds'][index] and
        value['seat']==index%4 and value['assignedEpoch']==epoch and
        value['originalBundleSha256']==plan.record['original']['bundleSha256'] and
        all(value[key]==receipt[key] for key in ('signature','scopeSha256','scienceSha256','sourceSha256')),
        'Calibration must preserve each first-generation raw-control public identity.')
    name=game_name(index)
    p.require(value['game']==dict(file=name,sha256=receipt['files'][name]['sha256']),
        'The public panel must bind its untouched opaque complete game bytes.')
    p.require(set(value['heads'])==set(value['windows'])=={'EXPANSION','TOKEN'},
        'Only E/T have a prescribed public sensitivity panel.')
    for head,states in value['heads'].items():
        counts=value['windows'][head]
        p.require(set(counts)=={'observed','branching'} and
            type(counts['observed']) is int and type(counts['branching']) is int and
            0<=counts['branching']<=counts['observed']<=12000 and
            len(states)==min(2,counts['branching']),
            'Public first/last coverage must preserve every missing and singleton window count.')
        previous=-1
        for state in states:
            p.require(set(state)=={'number','batchStartNumber','epoch','referenceIndex','observation'} and
                type(state['number']) is int and type(state['batchStartNumber']) is int and
                0<=state['batchStartNumber']<=state['number']<=12000 and state['number']>previous and
                state['epoch']==epoch and state['referenceIndex']==state['observation']['baselineIndex'] and
                len(state['observation']['actions'])>1,
                'Only distinct chronological branching public observations in the assigned epoch qualify.')
            previous=state['number']
    return dict(index=index,assignedEpoch=epoch,heads=value['heads'])


def public_panel(plan,protocol_sha,receipts):
    p.require(len(receipts)==8,'The one calibration needs all eight raw-control shards.')
    worlds={};sciences=set();pins=[]
    for item in receipts:
        receipt=p.read(p.checked_subject(item))
        p.require(receipt['kind']==p.PREFIX+'-chunk-receipt-1' and receipt['phase']=='controls' and
            receipt['generation']==1 and receipt['protocolSha256']==protocol_sha,
            'Only complete generation-one raw-control public files may calibrate E/T.')
        sciences.add(receipt['scienceSha256']);pins.append(item)
        for name,value in members(item,receipt['panels']):
            index=value['globalIndex']
            p.require(index in p.indices(receipt['chunk']) and index not in worlds and
                name==f'panel-{index}.json','The public panel has no duplicate/reassigned world.')
            worlds[index]=panel_world(value,receipt,plan,index)
    p.require(set(worlds)==set(range(256)) and len(sciences)==1,
        'The complete panel requires one shared science identity and every original world.')
    return dict(kind=p.PREFIX+'-public-panel-1',protocolSha256=protocol_sha,
        originalBundleSha256=plan.record['original']['bundleSha256'],
        worlds=[worlds[index] for index in range(256)]),pins


def game_reward(value,receipt,plan,index,expected_role,bundle_sha):
    """Authoritative score-derived normalized reward, never a substitute for unfinished play."""
    import expansion_training_evidence as evidence
    number=receipt['generation'];p.generation(number)
    p.require(value['kind']==p.PREFIX+'-game-1' and value['generation']==number and
        value['globalIndex']==index and value['seed']==plan.bank(number)['worldBank']['seeds'][index] and
        value['seat']==index%4 and value['role']==expected_role and value['bundleSha256']==bundle_sha and
        value['originalBundleSha256']==plan.record['original']['bundleSha256'] and
        all(value[key]==receipt[key] for key in ('signature','scopeSha256','scienceSha256','sourceSha256')),
        'A fitness row must retain its exact original world, role, actor and executable factory binding.')
    game=value['terminal'];seat=value['seat']
    p.require(all(len(game[key])==4 for key in ('wins','rewards','ranks','scores')) and
        type(game['decisions']) is int and 0<game['decisions']<=12000,
        'Only an original complete bounded four-seat game supplies fitness.')
    for own in range(4):
        evidence.terminal_reward(dict(controlledSeat=own,terminal=dict(scores=game['scores'],
            winShare=game['wins'][own],rankReward=game['ranks'][own],reward=game['rewards'][own],
            gameDecisions=game['decisions'])))
    return game['rewards'][seat]


def fitness(plan,protocol_sha,population_subject,calibration_subject,receipts):
    """The first semantic mutant-return boundary follows the one immutable calibration."""
    calibration=p.read(p.checked_subject(calibration_subject))
    p.require(calibration['kind']==p.PREFIX+'-calibration-1' and calibration['passed'] is True and
        calibration['complete'] is True and calibration['protocolSha256']==protocol_sha and
        calibration['semanticFitnessDecoded'] is False and calibration['calibrationAttempts']==1,
        'Fitness is closed until the exact single public-only calibration is sealed.')
    population=p.read(p.checked_subject(population_subject));number=population['generation']
    p.require(population['kind']==p.PREFIX+'-population-1' and
        population['protocolSha256']==protocol_sha and
        population['calibrationSha256']==calibration_subject['sha256'] and
        population['worldBankSha256']==p.digest(plan.bank(number)['worldBank']) and
        population['worldIds']==list(range(256)) and len(population['members'])==32 and
        p.digest(population)==population_subject['sha256'],
        'The complete executable population and native bank must share exact calibrated identity.')
    p.require(len(receipts)==8,'A gradient requires both hosts and all eight immutable population shards.')
    rows=[[None]*256 for _ in range(32)];seen=set();sciences=set()
    for item in receipts:
        receipt=p.read(p.checked_subject(item));chunk=receipt['chunk']
        p.require(receipt['kind']==p.PREFIX+'-chunk-receipt-1' and receipt['phase']=='population' and
            receipt['generation']==number and receipt['protocolSha256']==protocol_sha and
            receipt['subjects']['population']['sha256']==population_subject['sha256'] and
            receipt['subjects']['calibration']['sha256']==calibration_subject['sha256'] and chunk not in seen,
            'Every fitness shard must bind the same original calibrated population once.')
        seen.add(chunk);sciences.add(receipt['scienceSha256'])
        expected={game_name(index,member):(index,member) for index in p.indices(chunk) for member in range(32)}
        p.require(set(receipt['games'])==set(expected),'A shard must retain all32 mutants for each owned world.')
        for name,value in members(item,receipt['games']):
            index,member=expected[name]
            p.require(rows[member][index] is None,'A native mutant/world cannot contribute more than once.')
            rows[member][index]=game_reward(value,receipt,plan,index,role(member),
                population['members'][member]['bundleSha256'])
    p.require(seen==set(range(8)) and len(sciences)==1 and all(value is not None for row in rows for value in row),
        'Incomplete or mixed-identity populations cannot produce a gradient or a replacement world.')
    return dict(kind=p.PREFIX+'-fitness-1',populationSha256=population_subject['sha256'],
        worldBankSha256=population['worldBankSha256'],worldIds=list(range(256)),members=[
            dict(direction=member//2,sign=1 if member%2==0 else -1,
                bundleSha256=population['members'][member]['bundleSha256'],rewards=rows[member])
            for member in range(32)])


def generation_path(name):
    """Attribute every owned data copy once, including transfer archives and temporary files."""
    parts=Path(name).parts
    if len(parts)<2:return None
    if parts[0]=='generations':pattern=r'generation-([1-9]|1[0-6])'
    elif parts[0] in ('publications','downloads'):
        pattern=r'(?:controls|population)-([1-9]|1[0-6])'
    elif parts[0]=='incoming':pattern=r'(?:controls|population)-([1-9]|1[0-6])-chunk-[0-7]'
    elif parts[0]=='peer-retention':pattern=r'generation-([12])\.json'
    else:return None
    match=re.fullmatch(pattern,parts[1])
    p.require(match is not None or parts[1].startswith('screen'),
        'Every generation data directory must use its fixed attribution name.')
    return None if match is None else int(match[1])


def byte_census(root, base_directories):
    """Count every regular byte, rejecting links/overlap; environmental inputs are separate.

    Base directories are explicit immutable scientific package payloads: original models,
    projection/directions, dictionaries and historical subjects. Runtime/JDK/venv source
    closure is pinned separately and is not derived campaign data. Each generated copy in
    the worker root counts independently even if its bytes equal another copy.
    """
    p.require(all(not Path(item).is_symlink() for item in [root,*base_directories]),
        'Resource roots cannot themselves be symbolic links.')
    root=Path(root).resolve();bases=[Path(item).resolve() for item in base_directories]
    p.require(len(bases)==len(set(bases)) and all(path!=root and not path.is_relative_to(root)
        and not root.is_relative_to(path) for path in bases),
        'Scientific base payloads and the complete worker root must be disjoint.')
    p.require(all(not a.is_relative_to(b) and not b.is_relative_to(a)
        for i,a in enumerate(bases) for b in bases[i+1:]),
        'Shared input payloads may be counted once only, without overlapping directories.')
    shared=0;generations={str(number):0 for number in range(1,17)};files=0
    for directory in [*bases,root]:
        p.require(directory.is_dir() and not directory.is_symlink(),
            'Resource accounting requires real scientific data directories.')
        for path in directory.rglob('*'):
            p.require(not path.is_symlink(),'Resource accounting cannot bypass a directory through links.')
            if not path.is_file() or path.name=='.DS_Store':continue
            size=path.stat().st_size;files+=1
            number=generation_path(path.relative_to(root)) if directory==root else None
            if number is None:shared+=size
            else:generations[str(number)]+=size
    return dict(sharedBaseBytes=shared,generationBytes=generations,
        totalBytes=shared+sum(generations.values()),regularFiles=files)


def retention_record(root, base_directories, host, number, protocol_sha):
    """Seal the first two Mac censuses after their last shard transfer, before waiting.

    The receipt's own bytes are included by a bounded decimal-length fixed point. No
    subsequent local acknowledgement file is written, so the measurement remains exact.
    """
    p.require(host in p.HOSTS and number in (1,2),'Only the two original prefix censuses exist.')
    p.sha(protocol_sha);root=Path(root)
    target=root/'generations'/f'generation-{number}'/'retention.json'
    p.require(not target.exists(),'A completed-generation census is published once.')
    census=byte_census(root,base_directories)
    value=dict(kind=p.PREFIX+'-retention-1',host=host,generation=number,
        protocolSha256=protocol_sha,**census)
    original=census['generationBytes'][str(number)];size=0
    for _ in range(8):
        value['generationBytes'][str(number)]=original+size
        value['totalBytes']=census['totalBytes']+size
        value['regularFiles']=census['regularFiles']+1
        new_size=len(p.canonical(value))+1
        if new_size==size:break
        size=new_size
    else:raise ValueError('The bounded resource receipt byte framing did not stabilize.')
    publish_json(target,value)
    p.require(byte_census(root,base_directories)=={key:value[key] for key in census},
        'Scientific data changed while sealing the exact retained-byte census.')
    return value


def prefix_retention(record, protocol_sha, host, number):
    p.require(set(record)=={'kind','host','generation','protocolSha256','sharedBaseBytes',
        'generationBytes','totalBytes','regularFiles'} and
        record['kind']==p.PREFIX+'-retention-1' and record['host']==host and
        record['generation']==number and record['protocolSha256']==protocol_sha,
        'The prefix census must bind its exact original host, generation and protocol.')
    p.integer(record['regularFiles'],1,10**7,'Retained regular file count')
    p.require(set(record['generationBytes'])=={str(g) for g in range(1,17)},
        'All finite generation byte counters must be explicit.')
    values=[p.integer(value,0,24*1024**3,'Retained generation bytes')
        for value in record['generationBytes'].values()]
    base=p.integer(record['sharedBaseBytes'],0,24*1024**3,'Shared scientific payload bytes')
    p.require(record['totalBytes']==base+sum(values) and
        all(record['generationBytes'][str(g)]==0 for g in range(number+1,17)),
        'A prefix census cannot omit bytes or include unstarted future generation data.')
    return dict(sharedBaseBytes=base,
        generationDeltaBytes=[record['generationBytes'][str(g)] for g in (1,2)])
