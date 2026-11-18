"""Native win conservation and completed-only host admission over the original finite audit loop.

The independent numerical owner recomputes updates. Native games remain one archive at a time;
no policy tensor, diagnostic forward, random stream or scientific process is executed here.
"""
from pathlib import Path
import sys

import coordinated_es_audit as base
import coordinated_es_continuation as c
import coordinated_es_storage as storage
import coordinated_win_handoff as handoff
import coordinated_win_plan as p
import coordinated_win_storage as wins
from token_paired_campaign_audit import Pins,stream_archive

SCREEN_MODULE='coordinated_win_screen'
CONTROL_FIELD='supportedControls'
METRIC_CHECK_FIELD='semanticGameWinsChecked'
STOP_REASONS=handoff.STOP_REASONS
receipt_path=base.receipt_path
file_subject=base.file_subject


def panel_digest(plan,protocol_sha,host,number):return None


def verify_fitness(fitness,population,returns):
    p.require(fitness['kind']==c.FITNESS_KIND and fitness['objective']==c.OBJECTIVE and
        fitness['protocolSha256']==population['protocolSha256'] and
        fitness['populationSha256']==p.digest(population) and
        all(fitness['members'][member]['winShares'][index]==value for (index,member),value in returns.items()),
        'Every continuation fitness value equals its original authoritative controlled-seat win share.')


def chunk(plan,item,pins,*,population=None,decode_games=True):
    p.require(decode_games is True,'Inherited-calibration continuation has no partial calibration admission.')
    path=pins.subject(item);receipt=pins.read(path)
    phase=receipt['phase'];number=receipt['generation'];ordinal=receipt['chunk']
    p.require(receipt['kind']==p.PREFIX+'-chunk-receipt-1' and receipt['host']==p.owner(ordinal),
        'Every retained continuation shard preserves its original fixed host.')
    names={'source.json','prepared.json','finished.json',*receipt['games']}
    names.update(receipt['panels'])
    for replay in receipt['replays']:
        names.add(replay['file'])
        names.update(name for name in receipt['files'] if name.startswith(str(Path(replay['file']).parent)+'/'))
        names.add(wins.game_name(replay['globalIndex'],2*int(replay['role'].split('-')[1])+
            int(replay['role'].startswith('MINUS'))))
    data=stream_archive(path.with_name(receipt['archive']),receipt,pins,names)
    expected=wins.generation_record(plan,data['source.json'],receipt['sourceSha256'],data['prepared.json'],
        data['finished.json'],receipt['files'],number,ordinal,phase,data)
    p.require(receipt==dict(expected,archive=receipt['archive'],archiveSha256=receipt['archiveSha256']),
        'Archived continuation metadata reproduces its entire original native receipt.')
    if phase=='population':
        p.require(population is not None and population['kind']==c.POPULATION_KIND and
            p.digest(population)==receipt['subjects']['population']['sha256'] and population['step']==number,
            'Population evidence requires the exact inherited-calibration32-member descriptor.')
    result={}
    for index in p.indices(ordinal):
        for member in ([None] if phase=='controls' else range(32)):
            result[index,member]=wins.game_win(data[wins.game_name(index,member)],receipt,plan,index,
                'CONTROL' if member is None else storage.role(member),
                None if member is None else population['members'][member]['bundleSha256'])
    return receipt,result,[]


def training(protocol_subject,root,*,host='DESKTOP',terminal=None,pins=None):
    if terminal is not None and terminal['status']=='stopped' and host=='DESKTOP':
        p.require(terminal['reason'] in STOP_REASONS,'A continuation stop cannot introduce recalibration or selection.')
    return base.training(protocol_subject,root,host=host,terminal=terminal,pins=pins,contract=sys.modules[__name__])


def verify_numerical(protocol_subject,root,pins):
    import coordinated_win_generation_audit as numerical
    owner=pins.read(Path(root)/'worker-owner.json')
    p.require(owner['kind']==p.PREFIX+'-worker-owner-1','The original worker must retain its typed profile subject.')
    profile=owner['profile'];pins.subject(profile)
    return numerical.verify(protocol_subject,root,profile_subject=profile,pins=pins)


def verify(protocol_subject,root):
    return base.verify(protocol_subject,root,contract=sys.modules[__name__])
