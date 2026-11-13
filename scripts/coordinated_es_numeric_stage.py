"""Fixed numerical subprocess requests and byte-preserving generation publication.

All equations, projection and calibration live with the numerical owner. This adapter
only binds subjects, one original deadline, output closure and portable relative paths.
"""
from pathlib import Path
import shutil

import coordinated_es_plan as p
import coordinated_es_process as execution
import coordinated_es_storage as storage
import purchase_storage as atomic

VERBS=('materialize','calibrate','update','center','diagnose')
CALIBRATION_STOPS=('calibration-insufficient-worlds','calibration-positive-gap-mass',
    'calibration-small-signed-quantile','calibration-scale-bounds','calibration-movement-bounds')


def same_subject(value,path):
    return p.checked_subject(value).resolve()==Path(path).resolve() and value['sha256']==p.file_sha(path)


def request(profile,verb,fields):
    p.require(verb in VERBS,'Only the fixed scientific numerical stages may execute here.')
    p.require(profile['numericalSourceOwners'] and all(p.file_sha(path)==digest
        for path,digest in profile['numericalSourceOwners'].items()),
        'Every executing numerical owner must match the frozen source closure.')
    p.checked_subject(profile['numerical'])
    return dict(kind=p.PREFIX+'-'+verb+'-request-1',
        sourceOwners=profile['numericalSourceOwners'],numerical=profile['numerical'],
        deadlineEpoch=profile['_clock'].deadline,**fields)


def verify_complete(root,request_path,verb):
    root=Path(root);value=p.read(root/'finished.json');wire=p.read(request_path)
    files=storage.inventory(root)
    p.require(value['kind']==p.PREFIX+'-numeric-complete-1' and value['passed'] is True and
        value['complete'] is True and value['verb']==verb and same_subject(value['request'],request_path) and
        value['protocolSha256']==wire['protocol']['sha256'] and
        value['sourceOwners']==wire['sourceOwners'],
        'The numerical child must finish exactly its one original immutable request.')
    p.require(value['artifacts'] and set(files)==set(value['artifacts'])|{'finished.json'} and
        all(files[storage.safe(name)]['sha256']==digest for name,digest in value['artifacts'].items()),
        'The atomic numerical receipt must bind its entire output, including original startup evidence.')
    p.require(value['inputPins'] and all(p.file_sha(path)==digest for path,digest in value['inputPins'].items()),
        'Every numerical input remains byte-exact at completion.')
    p.require(all(storage.safe(name) in value['artifacts'] for name in value['outputs'].values()),
        'Every consumable numerical output must belong to the completed artifact inventory.')
    return value


def invoke(profile,verb,directory,fields):
    directory=Path(directory)
    p.require(not directory.exists(),'Every prescribed numerical operation executes once with a fresh output.')
    directory.parent.mkdir(parents=True,exist_ok=True)
    path=directory.with_name(directory.name+'-request.json')
    storage.publish_json(path,request(profile,verb,fields))
    command=[profile['python']['executable'],'-E','-s','-B',
        str(Path(__file__).with_name('coordinated_es_numeric.py')),verb,
        '--request',str(path),'--output',str(directory)]
    execution.run_child(profile,command,directory.with_name(directory.name+'.log'))
    p.require(not ((directory/'finished.json').exists() and (directory/'stopped.json').exists()),
        'A numerical operation cannot be both stopped and completed.')
    if (directory/'stopped.json').exists():
        p.require(verb in ('calibrate','update') and not (directory/'center.json').exists(),
            'Only the original calibration or zero-gradient boundary can stop without a successor.')
        row=verify_stop(directory,path,verb)
        return dict(complete=False,directory=directory,receipt=p.subject(directory/'stopped.json'),reason=row['reason'])
    verify_complete(directory,path,verb)
    return dict(complete=True,directory=directory,receipt=p.subject(directory/'finished.json'))


def verify_stop(root,request_path,verb):
    root=Path(root);row=p.read(root/'stopped.json');files=storage.inventory(root)
    reason=row['reason']
    admitted=(verb=='calibrate' and reason in CALIBRATION_STOPS) or (
        verb=='update' and (reason=='zero-gradient' or reason.startswith('nonfinite-')))
    p.require(row['kind']==p.PREFIX+'-numeric-stop-1' and row['complete'] is False and
        row['passed'] is False and row['verb']==verb and same_subject(row['request'],request_path) and
        admitted and set(files)==set(row['artifacts'])|{'stopped.json'} and
        all(files[storage.safe(name)]['sha256']==digest for name,digest in row['artifacts'].items()),
        'Only a complete original calibration/arithmetic stop may end without another candidate.')
    request_value=p.read(request_path)
    p.require(row['deadlineEpoch']==request_value['deadlineEpoch'] and
        row['stoppedEpoch']<=row['deadlineEpoch'] and row['inputPins'] and
        all(p.file_sha(path)==digest for path,digest in row['inputPins'].items()),
        'An expired clock or changed input is failure, never an honest operational stop.')
    return row


def copy_file(source,target):
    path=p.checked_subject(source);target=Path(target)
    p.require(not target.exists(),'Portable scientific publication cannot overwrite a prior member.')
    target.parent.mkdir(parents=True,exist_ok=True)
    with path.open('rb') as stream:
        atomic.publish(target,lambda output:shutil.copyfileobj(stream,output),source['sha256'])
    return p.subject(target)


def copy_tree(source,target):
    source,target=Path(source),Path(target)
    p.require(not target.exists(),'A portable immutable subtree is created once.')
    files=storage.inventory(source)
    for name,row in files.items():copy_file(dict(path=str(source/name),sha256=row['sha256']),target/name)
    p.require(storage.inventory(target)==files,'Every portable scientific byte must remain unchanged.')
    return files


def population_publication(materialization,descriptor,calibration,output):
    """Assemble once; G1 calibration points to the same unmodified member-relative paths."""
    materialization,output=Path(materialization),Path(output)
    p.require(not output.exists(),'An executable generation is published once, after calibration.')
    source=p.checked_subject(descriptor);value=p.read(source)
    p.require(value['kind']==p.PREFIX+'-population-1' and len(value['members'])==32,
        'Only the complete admitted32-member population can become executable.')
    copy_file(descriptor,output/'population.json')
    copy_tree(materialization/'members',output/'members')
    copy_tree(calibration,output/'calibration')
    for member in value['members']:
        name=storage.safe(member['path']);path=output/name
        p.require(path.is_file() and p.file_sha(path)==member['bundleSha256'],
            'Executable population members must resolve to their exact copied relative bundles.')
    return p.subject(output/'population.json')


def bank_subject(plan,number,directory):
    path=Path(directory)/'bank.json';path.parent.mkdir(parents=True,exist_ok=True)
    wire=p.canonical(plan.bank(number)['worldBank'])
    atomic.publish(path,lambda output:output.write(wire))
    p.require(p.file_sha(path)==p.digest(plan.bank(number)['worldBank']),
        'The native inline bank and numerical raw bank share the same plain canonical digest.')
    return p.subject(path)


def publish_numeric(path,value):
    """Numerical owner records have no LF; raw SHA must equal their semantic digest."""
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    atomic.publish(path,lambda output:output.write(p.canonical(value)))
    return p.subject(path)
