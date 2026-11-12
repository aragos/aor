"""Bind separately admitted Windmill public prefixes and detached paired terminal labels.

No fit, archive extraction, CLI or TEST mode. Public export must precede label export.
The caller supplies hash-pinned original members; sources and streams never enter features.
"""
import hashlib
import json
from pathlib import Path

from windmill_value import Action, FEATURE_NAMES, Origin, Outcome, Split, require

VERSION = 'windmill-value-public-1'
LABEL_KIND = 'windmill-four-paired-labels-1'


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def identity(row):
    chunk, index = row['chunk'], row['index']
    require(type(chunk) is int and 1 <= chunk <= 8 and type(index) is int and 0 <= index < 224,
            'Only original TRAIN/VALIDATION members may be opened; TEST is forbidden.')
    split = Split.TRAIN if index < 192 else Split.VALIDATION
    require(row['split'] == split.value, 'Original whole-origin split changed.')
    return (chunk - 1) * 256 + index, split


def detached_labels(subject, public, public_line_sha256):
    """Explicit label-stage operation, only after its prior public line has been frozen.

    This function is never called by native public export. It projects terminal metrics only;
    original canonical suffixes and later offer-card actor inputs are not exported or fitted.
    """
    index, split = identity(subject)  # before any origin file access
    require(identity(public) == (index, split) and public['version'] == VERSION
            and public['globalIndex'] == index, 'Exact prior public origin required.')
    path = Path(subject['path'])
    require(sha(path) == subject['sha256'] == public['sourceSha256'],
            'Original result and prior public source bytes must remain unchanged.')
    raw = json.loads(path.read_bytes())
    require(raw['kind'] in ('windmill-origin-1', 'windmill-origin-2')
            and raw['request']['index'] == subject['index']
            and raw['status'] == public['status'] == subject['status']
            and raw['signature'] == public['signature'] == subject['signature']
            and raw['gameSeed'] == subject['gameSeed'], 'Original source/root identity changed.')
    streams = raw['chanceSeeds']
    require(streams == public['chanceSeeds'] == subject['chanceSeeds']
            and len(streams) == len(set(streams)) == 4 and all(type(v) is int for v in streams),
            'Keep exactly the original four shared continuation streams.')
    result = {key: public[key] for key in ('chunk', 'index', 'globalIndex', 'split',
              'sourceSha256', 'signature', 'chanceSeeds', 'status')}
    result.update(labelKind=LABEL_KIND, publicLineSha256=public_line_sha256, actions={})
    if public['status'] == 'MISSING':
        require(raw['arms'] == [] and public['features'] is None
                and public['incumbentCommand'] is None, 'Missing roots cannot acquire labels.')
        return result
    require(public['status'] == 'COLLECTED' and len(raw['arms']) == 2,
            'Captured roots retain both original offered actions.')
    actor = raw['provenance']['actor']
    require(raw['provenance']['incumbentCommand'] == public['incumbentCommand']
            and raw['provenance']['exactPrefixAndIncumbentVerified'] is True,
            'Preserve the verified normal action solely as an evaluation reference.')
    for arm in raw['arms']:
        command = arm['command']
        require(command in ('windmill', 'pass') and command not in result['actions'],
                'Both distinct offered commands must survive exactly once.')
        require([row['replicate'] for row in arm['outcomes']] == list(range(4)),
                'Every original replicate must remain in fixed order.')
        rows = []
        for replicate, row in enumerate(arm['outcomes']):
            terminal = row['terminal']
            require(row['replayVerified'] is True and terminal['continuationSeed'] == streams[replicate],
                    'Detached labels require the original canonically verified paired stream.')
            scores = [score for score in terminal['scores'] if score['player'] == actor]
            require(len(scores) == 1, 'The terminal public score must identify exactly one actor.')
            metrics = {key: terminal[key] for key in ('reward', 'winShare', 'rankReward')}
            metrics['chaos'] = int(scores[0]['misery'] is None)
            Outcome(**metrics)
            rows.append(metrics)
        result['actions'][command] = rows
    return result


def bind(public, labels, public_line_sha256):
    index, split = identity(public)  # reject TEST before accessing any label
    require(public['version'] == VERSION and public['globalIndex'] == index,
            'Use the fixed public encoder and original global identity.')
    require(labels['labelKind'] == LABEL_KIND and labels['publicLineSha256'] == public_line_sha256,
            'Detached labels must bind the exact prior immutable public line.')
    for key in ('chunk', 'index', 'globalIndex', 'split', 'sourceSha256', 'signature',
                'chanceSeeds', 'status'):
        require(labels[key] == public[key], f'Detached {key} differs from the public export.')
    streams = public['chanceSeeds']
    require(len(streams) == len(set(streams)) == 4 and all(type(v) is int for v in streams),
            'Exactly four paired original streams are required.')
    if public['status'] == 'MISSING':
        require(labels['actions'] == {} and public['features'] is None
                and public['incumbentCommand'] is None, 'Missing roots retain no usable row.')
        return Origin(index, split, public['sourceSha256'], None, None, None, None, 'NO_ROOT')
    require(public['status'] == 'COLLECTED' and public['featureNames'] == list(FEATURE_NAMES)
            and set(labels['actions']) == {'windmill', 'pass'}, 'Fixed public columns/actions required.')
    incumbent = {'pass': Action.PASS, 'windmill': Action.ATTEMPT}[public['incumbentCommand']]
    return Origin(index, split, public['sourceSha256'], tuple(public['features']),
                  tuple(Outcome(**row) for row in labels['actions']['pass']),
                  tuple(Outcome(**row) for row in labels['actions']['windmill']), incumbent)


def load(public_subject, label_subject):
    """Validate all prospective requests and line hashes; no TEST member can enter either file."""
    require(sha(public_subject['path']) == public_subject['sha256']
            and sha(label_subject['path']) == label_subject['sha256'], 'Frozen export hashes changed.')
    public_lines = Path(public_subject['path']).read_bytes().splitlines(keepends=True)
    label_lines = Path(label_subject['path']).read_bytes().splitlines()
    expected = [(chunk - 1) * 256 + i for chunk in range(1, 9) for i in range(224)]
    require(len(public_lines) == len(label_lines) == len(expected),
            'Retain all1,792 prospective TRAIN/VALIDATION requests, including missing.')
    rows = [bind_lines(p, l) for p, l in zip(public_lines, label_lines)]
    require([row.originIndex for row in rows] == expected, 'Original request ordering changed.')
    return tuple(rows)


def bind_lines(public_line, label_line):
    public = json.loads(public_line)
    identity(public)  # Reject TEST before even decoding its detached label document.
    return bind(public, json.loads(label_line), hashlib.sha256(public_line).hexdigest())
