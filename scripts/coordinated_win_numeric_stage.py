"""Win-continuation stage binding over the existing immutable numerical subprocess owner."""
from pathlib import Path
import sys

import coordinated_es_continuation as c
import coordinated_es_numeric_stage as base
import coordinated_es_storage as storage
import coordinated_win_handoff as mailbox
import coordinated_win_plan as p
import token_paired_process as process

VERBS=('materialize','update','center','diagnose')
NUMERIC_MODULE='coordinated_win_numeric.py'
same_subject=base.same_subject
copy_file=base.copy_file
copy_tree=base.copy_tree
bank_subject=base.bank_subject
publish_numeric=base.publish_numeric


def request(profile,verb,fields):return base.request(profile,verb,fields,prefix=p.PREFIX,verbs=VERBS)
def verify_complete(root,path,verb):return base.verify_complete(root,path,verb,prefix=p.PREFIX)
def run_child(profile,command,log):return process.run_child(profile,command,log,mailbox=mailbox)


def verify_stop(root,path,verb):
    p.require(verb=='update','An inherited calibration cannot be retried or recalibrated during continuation.')
    return base.verify_stop(root,path,verb,prefix=p.PREFIX)


def invoke(profile,verb,directory,fields):
    return base.invoke(profile,verb,directory,fields,contract=sys.modules[__name__])


def population_publication(materialization,descriptor,calibration,output):
    """Copy only the small inherited seal subtree; preparation/projection/16directions stay shared."""
    output=Path(output);materialization=Path(materialization)
    p.require(not output.exists(),'An executable continuation population is published once.')
    value=p.read(p.checked_subject(descriptor))
    p.require(value['kind']==c.POPULATION_KIND and len(value['members'])==32,
        'Only a complete typed continuation population may execute.')
    inherited=p.checked_subject(calibration)
    p.require(inherited.name=='inherited-calibration.json' and
        value['inheritedCalibrationSha256']==calibration['sha256'],
        'The publication retains its exact original inherited seal envelope.')
    expected={'inherited-calibration.json','origin/calibration.json','origin/protocol.json',
        'origin/gap-EXPANSION.json','origin/gap-TOKEN.json'}
    p.require(set(storage.inventory(inherited.parent))==expected,
        'The portable calibration subtree contains only its five original sealed byte subjects.')
    copy_file(descriptor,output/'population.json')
    copy_tree(materialization/'members',output/'members')
    copy_tree(inherited.parent,output/'calibration')
    for member in value['members']:
        path=output/storage.safe(member['path'])
        p.require(path.is_file() and p.file_sha(path)==member['bundleSha256'],
            'All32 portable members retain their exact raw descriptor bytes.')
    return p.subject(output/'population.json')
