"""Typed nominated-inference joins over the unchanged finite two-bank qualification owner.

The root-approved protocol pins the nomination's truth. This owner verifies its byte closure;
it does not replay training, decode actor tensors, nominate an alternative or activate a policy.
"""
from functools import partial
from pathlib import Path, PurePosixPath
import sys

import coordinated_qualification as legacy
import coordinated_win_qualification_plan as p
from token_paired_campaign_audit import Pins

PREFIX = p.PREFIX
KIND = PREFIX+'-screen-1'
Clock = p.Clock
STAGES = legacy.STAGES
MAXIMUM_PUBLICATION_BYTES = legacy.MAXIMUM_PUBLICATION_BYTES
CONFIRMATION_KIND = PREFIX+'-confirmation-1'
CERTIFICATE_BANK_GATES = dict(win99Passed=True)
storage = legacy.storage
resolve, relative, copied = legacy.resolve, legacy.relative, legacy.copied
HEADS = ('CARD', 'PURCHASE', 'EXPANSION', 'TOKEN')


def contract():
    return sys.modules[__name__]


class PeerStopped(legacy.PeerStopped):
    def __init__(self, response):
        super().__init__(response, owner=contract())


def identity(plan):
    return {key: plan.record[key] for key in
        ('candidateBundleSha256', 'inferenceAssetSha256', 'nominationSha256')}


def snapshots(bank):
    result = dict(source='study-source.json', protocol='protocol.json',
        inferenceAsset='inference-asset.json', nomination='nomination.json', arena='arena-source.json')
    if bank == 'homogeneous':
        result['confirmation'] = 'confirmation.json'
    return result


def source_snapshot_fields():
    return {name: name for name in ('protocol', 'inferenceAsset', 'nomination', 'arena')}


def finished_bindings(plan, manifest):
    result = report_bindings(plan, None)
    p.require(all(manifest[key]['sha256'] == result[key+'Sha256'] for key in p.INPUT_BINDINGS),
        'Native completion binds the exact nominated inference asset and nomination bytes.')
    return result


def report_bindings(plan, completion):
    return {key+'Sha256': plan.record[key+'Sha256'] for key in p.INPUT_BINDINGS}


def certificate_fields(plan):
    value = plan.record
    return dict(kind=PREFIX+'-1', schemaVersion=1, passed=True, complete=True,
        profile=p.PROFILE, supportProfile=p.SUPPORT, configuration=p.APPLICATION_CONFIGURATION,
        **{key: value[key] for key in ('rulesVersion', 'dataVersion',
            'inferenceAssetSha256', 'nominationSha256')})


def asset_record(plan, asset, nomination):
    """Pure manifest identity, also used on original archive snapshots without following paths."""
    p.require(set(asset) == {'kind', 'schemaVersion', 'profile', 'supportProfile', 'selectorVersion',
        'rulesVersion', 'dataVersion', 'configuration', 'currentBundle', 'originalBundle',
        'calibration', 'references'} and asset['kind'] == 'coordinated-inference-asset-1' and
        type(asset['schemaVersion']) is int and asset['schemaVersion'] == 1 and asset['profile'] == p.PROFILE and
        asset['supportProfile'] == p.SUPPORT and asset['selectorVersion'] == 'incumbent-residual-margin-1' and
        asset['rulesVersion'] == p.RULES and asset['dataVersion'] == p.DATA and
        asset['configuration'] == p.APPLICATION_CONFIGURATION,
        'The inference asset retains the exact supported selector, rules, data and complete configuration.')
    for name, key in (('currentBundle', 'candidateBundleSha256'), ('originalBundle', 'originalBundleSha256'),
            ('calibration', 'calibrationSha256')):
        p.require(asset[name]['sha256'] == plan.record[key],
            'The root-pinned asset cannot replace a current/original bundle or calibration seal.')
    names = ('qualifiedOrdinary', 'cardM8', 'purchaseF0', 'expansionE')
    p.require(set(asset['references']) == {*names, 'tokenControlVersion'} and
        asset['references']['tokenControlVersion'] == plan.record['references']['tokenControlVersion'] and
        all(asset['references'][name]['sha256'] == plan.record['references'][name+'Sha256'] for name in names),
        'Packed and separate reference identities must match the prospective protocol exactly.')
    p.require(set(nomination) == {'kind', 'schemaVersion', 'passed', 'complete', 'assetSha256', 'profile',
        'completionAudit', 'developmentReport', 'learningWin95Passed', 'controlWin95Passed',
        'nomination', 'candidateAlternatives'} and nomination['kind'] == 'coordinated-policy-nomination-1' and
        type(nomination['schemaVersion']) is int and nomination['schemaVersion'] == 1 and
        nomination['passed'] is True and nomination['complete'] is True and
        nomination['profile'] == p.PROFILE and nomination['assetSha256'] == plan.record['inferenceAssetSha256'] and
        nomination['learningWin95Passed'] is True and nomination['controlWin95Passed'] is True and
        nomination['nomination'] == 'SOLE_FINAL_MEAN' and type(nomination['candidateAlternatives']) is int and
        nomination['candidateAlternatives'] == 0,
        'Only the exact root-pinned completed sole-final-mean nomination may enter independent qualification.')


def asset_subject(item, owner, pins):
    """Opaque bytes stay confined to the portable owner; no symlink or normalized-path aliases."""
    p.require(type(item) is dict and set(item) == {'path', 'sha256'} and isinstance(item['path'], str),
        'Every inference or nomination subject has exactly a relative path and raw SHA256.')
    relative_path = PurePosixPath(item['path'])
    p.require(not relative_path.is_absolute() and item['path'] == relative_path.as_posix() and
        relative_path.parts and all(part not in ('.', '..') for part in relative_path.parts) and
        '\\' not in item['path'], 'Portable inference subjects must be normalized confined relative paths.')
    # Keep declared host paths for input-pin joins; macOS /var and /private/var may alias.
    base = Path(owner).absolute().parent
    path = base
    for part in relative_path.parts:
        path = path/part
        p.require(not path.is_symlink(), 'Inference subject paths cannot traverse symbolic links.')
    p.require(path.is_file() and path.resolve().is_relative_to(base.resolve()),
        'Every declared inference subject must be a retained ordinary file inside its owner directory.')
    pins.pin(path, p.sha(item['sha256']))
    return path


def profile_admission(plan, profile, pins=None):
    pins = pins if pins is not None else Pins()
    p.require(set(profile['arenas']) == set(p.BANKS) and
        all(profile[key]['sha256'] == plan.record[key+'Sha256'] for key in p.INPUT_BINDINGS),
        'The actual host consumes the exact nominated asset and both fixed evaluation arenas.')
    asset_path = pins.subject(profile['inferenceAsset']); nomination_path = pins.subject(profile['nomination'])
    asset = pins.read(asset_path); nomination = pins.read(nomination_path)
    asset_record(plan, asset, nomination)
    closure = {}
    def pin(item, owner):
        path = asset_subject(item, owner, pins)
        closure[str(path)] = item['sha256']
        return path
    for key in ('currentBundle', 'originalBundle'):
        bundle_path = pin(asset[key], asset_path); bundle = pins.read(bundle_path)
        p.require(set(bundle) == {'kind', 'heads'} and bundle['kind'] == 'coordinated-es-bundle-1' and
            set(bundle['heads']) == set(HEADS), 'Both inference bundles retain exactly four named heads.')
        for row in bundle['heads'].values():
            p.require(set(row) == {'path', 'sha256', 'dictionarySha256'},
                'Each opaque actor subject binds its complete semantic dictionary identity.')
            p.sha(row['dictionarySha256'])
            pin({key: row[key] for key in ('path', 'sha256')}, bundle_path)
    calibration_path = pin(asset['calibration'], asset_path)
    p.require(pins.read(calibration_path)['kind'] == 'coordinated-es-calibration-2',
        'Inference asset version1 retains an exact V2 calibration seal; legacy V1 is a different contract.')
    for name in ('qualifiedOrdinary', 'cardM8', 'purchaseF0', 'expansionE'):
        pin(asset['references'][name], asset_path)
    for name in ('completionAudit', 'developmentReport'):
        pin(nomination[name], nomination_path)
    for item in (*profile['arenas'].values(), profile['inferenceAsset'], profile['nomination']):
        path = pins.subject(item); closure[str(path)] = item['sha256']
    p.require(all(profile['files'].get(path) == digest for path, digest in closure.items()),
        'All executing inference, reference and opaque nomination evidence bytes must belong to frozen host input pins.')


def validate_identity(plan, bank, value, manifest, values):
    p.require(set(value) == {'kind', 'protocolSha256', 'inferenceAssetSha256', 'nominationSha256',
        'ecologySha256', 'ecology', 'bankName', 'profile', 'supportProfile', 'candidateBundleSha256',
        'originalBundleSha256', 'calibrationSha256', 'seeds', 'roles', 'replayIndices'} and
        value['kind'] == KIND and value['protocolSha256'] == plan.sha256 and value['bankName'] == bank and
        value['profile'] == p.PROFILE and value['supportProfile'] == p.SUPPORT and
        all(value[key] == plan.record[key] for key in (*identity(plan), 'originalBundleSha256', 'calibrationSha256')) and
        value['seeds'] == plan.record['banks'][bank]['seeds'] and value['roles'] == list(p.ROLES) and
        value['replayIndices'] == list(p.REPLAYS) and p.digest(value) == manifest['scienceSha256'],
        'Every shard retains the same nominated behavior, complete independent bank and exact ecology identity.')
    p.sha(value['ecologySha256'])
    asset_record(plan, values['inference-asset.json'], values['nomination.json'])


def wanted(files):
    return legacy.wanted(files) | ({'inference-asset.json', 'nomination.json'} & set(files))


# Explicit bound entrypoints share all finite execution, archive and reporting mechanics.
audit_profile_admission = profile_admission
tag = partial(legacy.tag, owner=contract())
allowed_tag = partial(legacy.allowed_tag, owner=contract())
receipt_tag = partial(legacy.receipt_tag, owner=contract())
terminal = partial(legacy.terminal, owner=contract())
health = partial(legacy.health, owner=contract())
worker_alive = partial(legacy.worker_alive, owner=contract())
notify_failure = partial(legacy.notify_failure, owner=contract())
stage = partial(legacy.stage, owner=contract())
reserve = partial(legacy.reserve, owner=contract())
accept = partial(legacy.accept, owner=contract())
report_gate = partial(legacy.report_gate, owner=contract())
verify_report = partial(legacy.verify_report, owner=contract())
confirmation = partial(legacy.confirmation, owner=contract())
validate_world = partial(legacy.validate_world, owner=contract())
validate_policy_bindings = partial(legacy.validate_policy_bindings, owner=contract())
chunk_evidence = partial(legacy.chunk_evidence, owner=contract())
archived = partial(legacy.archived, owner=contract())
check_profile = partial(legacy.check_profile, owner=contract())
live = partial(legacy.live, owner=contract())
native_run = partial(legacy.native_run, owner=contract())
collect = partial(legacy.collect, owner=contract())
wait_receipts = partial(legacy.wait_receipts, owner=contract())
complete_bank = partial(legacy.complete_bank, owner=contract())
publish_confirmation = partial(legacy.publish_confirmation, owner=contract())
qualification = partial(legacy.qualification, owner=contract())
worker = partial(legacy.worker, owner=contract())


if __name__ == '__main__':
    legacy.main(owner=contract())
