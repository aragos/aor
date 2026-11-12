"""Bind separately frozen public support to detached full-prefix TRAIN/VALIDATION labels.

No archive extraction, policy choice, fit or TEST mode. The native prefix export must
finish before the separately admitted label export; their exact bytes are inputs here.
"""
import hashlib
import json
from pathlib import Path

from purchase_macro_value import FEATURE_NAMES, MacroOrigin, MacroOutcome, MacroPlan, Split, require

VERSION = 'purchase-macro-public-1'
SUPPORT = {'SUPPORTED', 'INFORMATION_BOUNDARY', 'INTERVENING_CHOICE', 'CHANGED_OFFER',
           'MISSING_CLOSING_PASS', 'EXTRA_COMMAND_AFTER_FINISH', 'INVALID_PREVIEW'}


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def origin(public, labels, public_sha):
    # Reject TEST and unknown split before examining detached label objects.
    index = public['originIndex']
    require(type(index) is int and 0 <= index < 448, 'Only original TRAIN/VALIDATION origins are admitted.')
    split = Split.TRAIN if index < 384 else Split.VALIDATION
    require(public['split'] == split.value and public['version'] == VERSION,
            'Original public projection/split version changed.')
    require(labels['labelKind'] == 'COMPLETE_PREFIX' and labels['originIndex'] == index
            and labels['split'] == split.value and labels['sourceSha256'] == public['sourceSha256']
            and labels['publicFactsSha256'] == public_sha, 'Detached labels must bind the exact prior public support.')
    if public['status'] == 'MISSING':
        require(labels['plans'] == [], 'A missing recorded root cannot acquire synthetic returns.')
        return MacroOrigin(index, split, public['sourceSha256'], ())
    require(public['status'] == 'CAPTURED' and public['sampledPassVerified'] is True,
            'Captured support requires exact original sampled G2 PASS parity.')
    facts = public['facts'];plans = facts['plans']
    require(facts['version'] == VERSION and facts['featureNames'] == list(FEATURE_NAMES),
            'Native and numerical fixed169-column dictionaries differ.')
    require(1 <= len(plans) <= 8 and [p['planIndex'] for p in plans] == list(range(len(plans)))
            and [p['commands'] for p in plans] == [p['commands'] for p in facts['proposals']['plans']],
            'Retain all prospective teacher plans in their original order, including unsupported plans.')
    streams = public['continuationSeeds']
    require(len(streams) == 2 and len(set(streams)) == 2 and all(type(s) is int for s in streams),
            'Original two common future streams must be explicit.')
    expected = [p['planIndex'] for p in plans if p['support'] == 'SUPPORTED']
    require([p['planIndex'] for p in labels['plans']] == expected,
            'Only all supported plans may have detached full-prefix labels.')
    by_index = {p['planIndex']: p for p in labels['plans']}
    result = []
    for plan in plans:
        support = plan['support'];require(support in SUPPORT, 'Unknown public support diagnostic.')
        require(type(plan['naturalFinish']) is bool, 'Natural owner/terminal finish must be explicit.')
        if support != 'SUPPORTED':
            require(plan['features'] is None, 'Unsupported plans retain no usable feature row.')
            result.append(MacroPlan(plan['planIndex'], tuple(plan['commands']), None, False, support, None))
            continue
        label = by_index[plan['planIndex']]
        require(label['commands'] == plan['commands'] and label['continuationSeeds'] == streams
                and len(label['returns']) == 2, 'Labels must execute the identical complete plan under both original futures.')
        result.append(MacroPlan(plan['planIndex'], tuple(plan['commands']), tuple(plan['features']),
            True, None, tuple(MacroOutcome(**row) for row in label['returns'])))
    return MacroOrigin(index, split, public['sourceSha256'], tuple(result))


def load(public_pin, label_pin):
    """One prepared public file plus one separate label file; paths/hashes are caller-frozen."""
    for pin in (public_pin, label_pin):
        require(set(pin) == {'path', 'sha256'} and not Path(pin['path']).is_symlink()
                and sha(pin['path']) == pin['sha256'], 'Frozen feature/label export bytes changed.')
    public = [json.loads(line) for line in Path(public_pin['path']).read_text().splitlines()]
    require([row['originIndex'] for row in public] == list(range(448)),
            'Public export must retain every original TRAIN/VALIDATION origin; no TEST input.')
    labels = [json.loads(line) for line in Path(label_pin['path']).read_text().splitlines()]
    require([row['originIndex'] for row in labels] == list(range(448)),
            'Detached labels must retain the same448 whole origins.')
    rows = tuple(origin(a, b, public_pin['sha256']) for a, b in zip(public, labels))
    require(sha(public_pin['path']) == public_pin['sha256'] and sha(label_pin['path']) == label_pin['sha256'],
            'Prepared export changed while being consumed.')
    return rows[:384], rows[384:]
