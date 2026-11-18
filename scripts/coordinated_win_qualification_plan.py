"""Prospective win-only qualification of one explicitly nominated inference asset."""
import coordinated_qualification_plan as legacy
from coordinated_qualification_plan import (
    APPLICATION_CONFIGURATION, AUDIT_SECONDS, BANKS, CHUNKS, CONFIGURATION, DATA, HOSTS,
    MAXIMUM_TERMINALS, MULTIPLIER, REFERENCE_FIELDS, REPLAYS, ROLES, RULES, SCIENCE_SECONDS,
    SUPPORT, Clock, bank_name, canonical, checked_subject, digest, file_sha, host_limits,
    indices, integer, owner, read, replays, require, sha, subject,
)

PREFIX = 'coordinated-win-qualification'
PROTOCOL_KIND = PREFIX+'-protocol-1'
PROFILE = 'aor-coordinated-win-support-1'
INTERVAL = dict(confidence=.99, multiplier=MULTIPLIER,
    requiredMetrics=['wins'], lowerBoundStrictlyPositive=True)
INPUT_BINDINGS = ('inferenceAsset', 'nomination')


class Plan(legacy.Plan):
    """Canonical bytes bind the sole nominee; no family model SHA or training runtime is inferred."""

    def __post_init__(self):
        value = self.record
        require(type(self.wire) is bytes and self.wire == canonical(value)+b'\n',
            'The nominated qualification protocol must retain exact canonical bytes plus one LF.')
        require(set(value) == {'kind', 'profile', 'supportProfile', 'inferenceAssetSha256',
            'nominationSha256', 'candidateBundleSha256', 'originalBundleSha256', 'calibrationSha256',
            'rulesVersion', 'dataVersion', 'configuration', 'references', 'banks', 'maximumSeconds',
            'auditSeconds', 'maximumTerminalExecutions', 'interval'},
            'The generic qualification protocol has exactly its nominated inference and fixed test fields.')
        require(value['kind'] == PROTOCOL_KIND and value['profile'] == PROFILE and
            value['supportProfile'] == SUPPORT and value['rulesVersion'] == RULES and
            value['dataVersion'] == DATA and value['configuration'] == CONFIGURATION and
            value['maximumSeconds'] == SCIENCE_SECONDS and value['auditSeconds'] == AUDIT_SECONDS and
            value['maximumTerminalExecutions'] == MAXIMUM_TERMINALS and value['interval'] == INTERVAL,
            'Only the explicit two-bank99 win-only method is admitted; legacy metrics cannot be reinterpreted.')
        for key in ('inferenceAssetSha256', 'nominationSha256', 'candidateBundleSha256',
                'originalBundleSha256', 'calibrationSha256'):
            sha(value[key])
        require(set(value['references']) == set(REFERENCE_FIELDS) and
            value['references']['tokenControlVersion'] == 'FIXED_16_TO_15',
            'All four frozen reference components and the exact token version are required.')
        for key in REFERENCE_FIELDS[:-1]:
            sha(value['references'][key])
        legacy.validate_banks(value['banks'])

    def source(self, bank, chunk, subjects):
        bank_name(bank)
        host = owner(chunk)
        require(set(subjects) == {'protocol', 'inferenceAsset', 'nomination', 'arena', 'confirmation'},
            'The native request binds only the protocol, nominated inference asset, arena and mixed confirmation.')
        for key, item in subjects.items():
            if item is None:
                require(key == 'confirmation' and bank == 'mixed',
                    'Only mixed qualification may omit its prior confirmation.')
            else:
                require(type(item) is dict and set(item) == {'path', 'sha256'} and
                    isinstance(item['path'], str) and item['path'],
                    'Native subjects have exactly a nonempty path and raw SHA256.')
                sha(item['sha256'])
        require((subjects['confirmation'] is None) == (bank == 'mixed') and
            subjects['protocol']['sha256'] == self.sha256 and
            all(subjects[key]['sha256'] == self.record[key+'Sha256'] for key in INPUT_BINDINGS),
            'Every native request consumes the exact prospectively nominated bytes and original mixed gate.')
        return dict(kind=PREFIX+'-input-1', host=host, **subjects, bankName=bank,
            globalStart=chunk*128, count=128)
