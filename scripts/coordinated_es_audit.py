"""Pure native/global conservation audit for the single finite coordinated ES attempt.

Only compact terminal records are decoded, at most one fixed shard in memory. Original
model inputs remain opaque hashes. The separate numerical owner recomputes ES arithmetic;
neither auditor performs inference, RNG generation, fitting or process execution.
"""
import hashlib
import importlib
from pathlib import Path
import sys

import coordinated_es_plan as p
import coordinated_es_storage as storage
import coordinated_es_generation_audit as numerical
from token_paired_campaign_audit import Pins, stream_archive

SCREEN_MODULE='coordinated_es_screen'
CONTROL_FIELD='rawControls'
METRIC_CHECK_FIELD='semanticGameRewardsChecked'
STOP_REASONS=('calibration-failed','zero-gradient','nonfinite-arithmetic','prefix-resource-gate')


def owner(contract):return sys.modules[__name__] if contract is None else contract


def receipt_path(root,number,phase,chunk,host):
    root=Path(root)
    if host=='DESKTOP' and p.owner(chunk)=='MAC':
        return root/'incoming'/f'{phase}-{number}-chunk-{chunk}'/'receipt.json'
    return root/'generations'/f'generation-{number}'/phase/f'chunk-{chunk}'/'evidence.tar.gz.json'


def file_subject(pins,path):
    path=Path(path);digest=pins.pin(path)
    resolved=path.resolve(strict=True)
    if resolved!=path.absolute():pins.pin(resolved,digest)
    return dict(path=str(resolved),sha256=digest)


class PublicPanelDigest:
    """Rebuild canonical no-LF panel bytes one admitted native world at a time."""
    def __init__(self,plan,protocol_sha):
        header=dict(kind=p.PREFIX+'-public-panel-1',protocolSha256=protocol_sha,
            originalBundleSha256=plan.record['original']['bundleSha256'])
        self.hasher=hashlib.sha256(p.canonical(header)[:-1]+b',"worlds":[')
        self.count=0

    def append(self,world):
        p.require(world['index']==self.count and self.count<256,
            'The calibration panel must retain every native world once in global order.')
        if self.count:self.hasher.update(b',')
        self.hasher.update(p.canonical(world));self.count+=1

    def digest(self):
        p.require(self.count==256,'The calibration panel requires all256 original public worlds.')
        complete=self.hasher.copy();complete.update(b']}')
        return complete.hexdigest()


def bind_public_panel(pins,directory,controls,panel_digest):
    path=Path(directory)/'public-panel.json'
    pins.pin(path,panel_digest.digest());panel=file_subject(pins,path)
    binding=pins.read(Path(directory)/'public-panel-binding.json')
    p.require(binding==dict(panel=panel,controls=controls),
        'The calibration panel binding must name its exact ordered native control receipts.')
    started=pins.read(Path(directory)/'calibrate/started.json')
    request=pins.read(pins.subject(started['request']))
    p.require(request['publicPanel']==panel,
        'Calibration must consume the exact reconstructed native public panel subject.')


def bind_generation(pins,directory,number,completed,controls,populations,population,fitness):
    if number<=completed:
        row=pins.read(Path(directory)/'receipt.json')
        p.require(row['generation']==number and row['controls']==controls and row['populations']==populations and
            row['population']==population and row['fitness']==fitness,
            'The numerical generation must consume these exact native shards, population and fitness bytes.')
    else:
        stop=pins.read(Path(directory)/'update/stopped.json')
        request=pins.read(pins.subject(stop['request']))
        p.require(request['population']==population and request['fitness']==fitness,
            'A stopped update must consume the exact retained native population and fitness subjects.')


def panel_digest(plan,protocol_sha,host,number):
    return PublicPanelDigest(plan,protocol_sha) if host=='DESKTOP' and number==1 else None


def verify_fitness(fitness,population,returns):
    p.require(fitness['kind']==p.PREFIX+'-fitness-1' and
        fitness['populationSha256']==p.digest(population) and
        all(fitness['members'][member]['rewards'][index]==value for (index,member),value in returns.items()),
        'Every numerical fitness value must equal its original authoritative native game return.')


def chunk(plan,item,pins,*,population=None,decode_games=True):
    path=pins.subject(item);receipt=pins.read(path);phase=receipt['phase'];number=receipt['generation'];ordinal=receipt['chunk']
    p.require(receipt['kind']==p.PREFIX+'-chunk-receipt-1' and receipt['host']==p.owner(ordinal),
        'Every retained native shard must preserve its original fixed host.')
    names={'source.json','prepared.json','finished.json'}
    names.update(receipt['panels'])
    if decode_games:names.update(receipt['games'])
    for replay in receipt['replays']:
        names.add(replay['file'])
        names.update(name for name in receipt['files'] if name.startswith(str(Path(replay['file']).parent)+'/'))
        names.add(storage.game_name(replay['globalIndex'],2*int(replay['role'].split('-')[1])+
            int(replay['role'].startswith('MINUS'))))
    data=stream_archive(path.with_name(receipt['archive']),receipt,pins,names)
    expected=storage.generation_record(plan,data['source.json'],receipt['sourceSha256'],data['prepared.json'],
        data['finished.json'],receipt['files'],number,ordinal,phase,data)
    p.require(receipt==dict(expected,archive=receipt['archive'],archiveSha256=receipt['archiveSha256']),
        'The archived native metadata must reproduce its entire original compact receipt.')
    rewards={};panels=[]
    if phase=='population':
        p.require(population is not None and p.digest(population)==receipt['subjects']['population']['sha256'] and
            population['generation']==number,'Population game evidence requires the exact executable32-member descriptor.')
    for index in p.indices(ordinal):
        if phase=='controls':
            panel=data[f'panel-{index}.json']
            if number==1:panels.append(storage.panel_world(panel,receipt,plan,index))
            else:p.require(panel['generation']==number and all(not values for values in panel['heads'].values()),
                'Only generation one may contribute a calibration public panel.')
        if not decode_games:continue
        for member in ([None] if phase=='controls' else range(32)):
            name=storage.game_name(index,member)
            rewards[index,member]=storage.game_reward(data[name],receipt,plan,index,
                'RAW_G3' if member is None else storage.role(member),
                None if member is None else population['members'][member]['bundleSha256'])
    return receipt,rewards,panels


def training(protocol_subject,root,*,host='DESKTOP',terminal=None,pins=None,contract=None):
    """Complete16 admission or exact honest native prefix; no final-screen effects here."""
    current=owner(contract);p=current.p
    pins=pins if pins is not None else Pins();plan=p.Plan.from_file(pins.subject(protocol_subject));root=Path(root)
    p.require(host in p.HOSTS,'Only the two original hosts can own retained native evidence.')
    completed=16 if terminal is None else terminal['completedGenerations']
    stopped=terminal is not None and terminal['status']=='stopped'
    reason=terminal.get('reason') if terminal else None
    if host=='MAC' and stopped:
        peer=pins.read(pins.subject(terminal['peerTerminal']));reason=peer['reason']
        p.require(terminal['reason']=='coordinator-operational-stop' and
            terminal['originalPeerSubject']['sha256']==terminal['peerTerminal']['sha256'] and
            peer['kind']==p.PREFIX+'-terminal-1' and peer['host']=='DESKTOP' and peer['status']=='stopped' and
            peer['clock']==terminal['clock'] and peer['completedGenerations']==completed and
            reason in current.STOP_REASONS,
            'An honest Mac stop must retain its exact original Desktop stop and shared clock.')
    count=completed+(1 if stopped and reason!='prefix-resource-gate' else 0)
    p.integer(count,1,16,'Retained native generation count')
    decode_games=reason!='calibration-failed';rows=[];controls=mutants=reproductions=0
    chunks=range(8) if host=='DESKTOP' else p.GENERATION_CHUNKS['MAC']
    for number in range(1,count+1):
        directory=root/'generations'/f'generation-{number}'
        row=dict(generation=number,controls=0,mutants=0,reproductions=0)
        has_population=not (stopped and reason=='calibration-failed' and number==1)
        population=None;fitness=None;population_subject=None;fitness_subject=None
        retained={phase:[] for phase in ('controls','population')}
        panel=current.panel_digest(plan,protocol_subject['sha256'],host,number)
        if has_population:
            pop_path=(root/'generations'/f'generation-{number}'/'portable/population.json' if host=='DESKTOP'
                else root/'downloads'/f'population-{number}'/'content/population.json')
            population_subject=file_subject(pins,pop_path);population=pins.read(pop_path)
            if host=='DESKTOP':
                fitness_subject=file_subject(pins,directory/'fitness.json');fitness=pins.read(fitness_subject['path'])
        for phase in ('controls','population') if has_population else ('controls',):
            sciences=set()
            for ordinal in chunks:
                path=receipt_path(root,number,phase,ordinal,host)
                item=file_subject(pins,path);retained[phase].append(item)
                receipt,rewards,panels=current.chunk(plan,item,pins,population=population,decode_games=decode_games)
                p.require(receipt['generation']==number and receipt['chunk']==ordinal and receipt['phase']==phase and
                    receipt['protocolSha256']==protocol_subject['sha256'],
                    'Native archives cannot replace a generation, stage or fixed world range.')
                sciences.add(receipt['scienceSha256'])
                row['controls' if phase=='controls' else 'mutants']+=receipt['completedGames']
                row['reproductions']+=receipt['historicalReproductions']
                if panel is not None and phase=='controls':
                    for world in panels:panel.append(world)
                if phase=='population' and fitness is not None:
                    current.verify_fitness(fitness,population,rewards)
            p.require(len(sciences)==1,'Both hosts must share one path-independent stage science identity.')
        if panel is not None:bind_public_panel(pins,directory,retained['controls'],panel)
        if host=='DESKTOP' and has_population:
            bind_generation(pins,directory,number,completed,retained['controls'],retained['population'],
                population_subject,fitness_subject)
        controls+=row['controls'];mutants+=row['mutants'];reproductions+=row['reproductions'];rows.append(row)
    complete=not stopped and completed==16
    if complete and host=='DESKTOP':
        p.require((controls,mutants,reproductions)==(4096,131072,32),
            'Only every original population/control world and32 reproductions admits the final mean.')
    pins.unchanged()
    return dict(kind=p.PREFIX+'-training-audit-1',passed=True,complete=complete,host=host,
        protocolSha256=protocol_subject['sha256'],completedGenerations=completed,generations=rows,
        mutants=mutants,reproductions=reproductions,artifactPins=dict(pins.values),
        **{current.CONTROL_FIELD:controls,current.METRIC_CHECK_FIELD:decode_games})


def verify_numerical(protocol_subject,root,pins):
    return numerical.verify(protocol_subject,root,pins=pins)


def verify(protocol_subject,root,*,contract=None):
    """Original bounded host terminal audit; release of process groups belongs to its watcher."""
    current=owner(contract);p=current.p;screen=importlib.import_module(current.SCREEN_MODULE)
    pins=Pins();root=Path(root);terminal=pins.read(root/'terminal.json');clock_wire=pins.read(root/'clock.json')
    p.require(terminal['kind']==p.PREFIX+'-terminal-1' and terminal['host'] in p.HOSTS and
        terminal['status'] in ('completed','stopped') and terminal['clock']==clock_wire and
        clock_wire['kind']==p.PREFIX+'-clock-1' and clock_wire['protocolSha256']==protocol_subject['sha256'],
        'Only the original completed or honest-stopped worker may enter pure terminal admission.')
    clock=p.Clock(clock_wire['startedEpoch'],clock_wire['deadlineEpoch'])
    pins.deadline=clock.audit_deadline(terminal['updatedEpoch']);pins.check_clock()
    p.require(clock.start<=terminal['updatedEpoch']<=clock.deadline,'The shared original science deadline cannot be renewed.')
    numeric=current.verify_numerical(protocol_subject,root,pins) if terminal['host']=='DESKTOP' else None
    native=current.training(protocol_subject,root,host=terminal['host'],terminal=terminal,pins=pins)
    screen.audit_historical(protocol_subject,root,terminal['host'],pins)
    if terminal['status']=='completed':
        result=screen.audit_completed(protocol_subject,root,terminal['host'],pins)
        if numeric is not None:
            plan=p.Plan.from_file(pins.subject(protocol_subject))
            subjects,_=screen.final_bindings(plan,protocol_subject['sha256'],terminal['finalAdmission'],pins)
            p.require(numeric['eligibleFinalCenter']['sha256']==subjects['center']['sha256'],
                'The screened portable center must equal the exact independently recomputed final mean16.')
    else:
        p.require(not (root/'screen').exists() and not (root/'screen-complete.json').exists(),
            'An operational stop cannot retain a shortened or replacement final screen.')
        result=dict(games=0,reproductions=0,historicalControls=2 if terminal['host']=='DESKTOP' else 0)
    total=native[current.CONTROL_FIELD]+native['mutants']+native['reproductions']+result['games']+result['reproductions']+result['historicalControls']
    p.require(total<=p.MAXIMUM_TERMINALS,'Actual retained executions cannot exceed the single predeclared global cap.')
    if terminal['status']=='completed' and terminal['host']=='DESKTOP':
        p.require(total==p.MAXIMUM_TERMINALS,'Complete Desktop evidence includes the exact global count, not a sum of host subsets.')
    pins.unchanged()
    return dict(kind=p.PREFIX+'-terminal-evidence-1',passed=True,complete=terminal['status']=='completed',
        status=terminal['status'],host=terminal['host'],protocolSha256=protocol_subject['sha256'],
        completedGenerations=terminal['completedGenerations'],scientificTerminalExecutions=total,
        eligibleFinalBundle=terminal.get('finalBundle') if terminal['status']=='completed' else None,
        nativeCounts={key:native[key] for key in (current.CONTROL_FIELD,'mutants','reproductions')},
        numericalRecomputed=numeric is not None,screenCounts=result,artifactPins=dict(pins.values),
        execution=dict(newGames=0,actualFits=0,optimizerSteps=0),interpretationAllowed=False)
