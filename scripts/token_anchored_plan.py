"""Finite declarative warm-anchor campaign identity; no experiment-number registry or RNG.

The prospective protocol chooses bounded counts once. Every subsequent model is the sole sealed
predecessor, and each declared round/bank has fresh explicit streams. No runtime selection can add
rounds, alter the recipe, refill missing roots or request alternative candidates.
"""
from dataclasses import dataclass
import json
import math
import hashlib

import token_paired_campaign_plan as shared
import token_anchor_distribution as distribution
from token_anchored_recipe import FIT_CASES, PROXIMAL_RECIPE, RECIPE, validate_fit_case


KIND = 'token-anchored-campaign-1'
ADAPTIVE_STYLES = ('REVEALED_UNDERCUT', 'REVEALED_OVERBID',
    'REVEALED_TIE_UNDERCUT', 'REVEALED_TIE_OVERBID', 'REVEALED_BALANCED', 'QUALIFIED_REVEALED_BALANCED')
ORDINARY_STYLES = ('QUALIFIED', 'UNDERCUT', 'OVERBID', 'MIXED', 'BALANCED')
SELECTION = dict(candidate='SOLE_LAST_ROUND', ordinaryWinLower99GreaterThan=-.03,
    adaptiveWinLower99GreaterThan=0., validationSelection=False, intermediateSelection=False)


def request(index, train_count):
    shared.integer(index, 0, 8191, 'Declared global origin')
    return dict(globalIndex=index, seat=index % 4, epoch=1+(index//4) % 3,
        ordinal=1+(index//12) % 4, split='TRAIN' if index < train_count else 'VALIDATION')


def indices(total, chunk_size, chunk):
    shared.integer(total, 1, 8192, 'Declared origin/world population')
    shared.integer(chunk_size, 1, 64, 'Declared compact native shard size')
    shared.integer(chunk, 0, (total+chunk_size-1)//chunk_size-1, 'Original chunk')
    return range(chunk*chunk_size, min((chunk+1)*chunk_size, total))


def validate_replays(values, count):
    shared.require(type(values) is list and len(set(values)) == len(values) and
        values == sorted(values) and 1 <= len(values) <= 16,
        'Each declared bank requires an ordered distinct bounded reproduction set.')
    for value in values:
        shared.integer(value, 0, count-1, 'Original reproduction index')


def declared_budget(record):
    collection = sum((row['trainCount']+row['validationCount'])*25+len(row['replayIndices'])
        for row in record['rounds'])
    evaluation = sum(2*(len(row['seeds'])+len(row['replayIndices']))
        for row in record['evaluation'].values())
    return dict(maximumCollectionTerminals=collection, evaluationTerminals=evaluation,
        maximum=collection+evaluation)


@dataclass(frozen=True)
class Plan:
    wire: bytes
    raw_sha256: str = None

    @classmethod
    def from_record(cls, record, *, raw_sha256=None):
        shared.require(record['kind'] == KIND and record['schemaVersion'] == 1 and
            record['probabilityContract'] == distribution.RECORD and record['numericalRecipe'] == RECIPE,
            'The campaign must declare the exact warm policy, pure-win recipe and schema version.')
        shared.require(record['proposalBudget'] == 12,
            'Warm proposals require the prospective12-arm mandatory-neighbor plus unbiased random budget.')
        for key in ('methodSha256', 'anchorDescriptorSha256', 'originalActorSha256',
                'dictionarySha256', 'nativeJarSha256'):
            shared.sha(record[key])
        rounds = record['rounds']
        shared.require(type(rounds) is list and 1 <= len(rounds) <= 16 and
            [row['round'] for row in rounds] == list(range(1, len(rounds)+1)),
            'The complete finite round sequence is declared once; no automatic successor exists.')
        if 'comparisonNumericalRecipe' in record:
            shared.require(len(rounds) == 1 and record['comparisonNumericalRecipe'] == PROXIMAL_RECIPE,
                'Only the fixed proximal comparison may share a prospectively declared one-round corpus.')
        if 'fitCases' in record:
            shared.require(record.get('comparisonNumericalRecipe') == PROXIMAL_RECIPE and
                record['fitCases'] == FIT_CASES,
                'The shared history corpus requires all three fixed fit cases.')
            for case in record['fitCases']:
                recipe = RECIPE if case['recipeVersion'] == RECIPE['version'] else PROXIMAL_RECIPE
                validate_fit_case(case, recipe)
        streams = []
        ecology = None
        for row in rounds:
            shared.integer(row['trainCount'], 1, 4095, 'Declared TRAIN origins')
            shared.integer(row['validationCount'], 1, 4095, 'Declared diagnostic origins')
            count = row['trainCount']+row['validationCount']
            shared.require(count <= 4096 and len(row['origins']) == count and
                len(row['shuffleSeeds']) == 20, 'Every round retains all original requests and20 epoch streams.')
            validate_replays(row['replayIndices'], count)
            for origin in row['origins']:
                shared.require(set(origin) == {'gameSeed', 'proposalSeed', 'future'} and
                    type(origin['future']) is list and len(origin['future']) == 2,
                    'A paired origin requires one backbone, one proposal and exactly two future streams.')
                streams.append(shared.integer(origin['gameSeed'], -(2**31), 2**31-1, 'Backbone stream'))
                streams.extend(shared.integer(value, -(2**63), 2**63-1, 'Proposal/future stream')
                    for value in [origin['proposalSeed'], *origin['future']])
            streams.extend(shared.integer(seed, 1, 2**31-1, 'Epoch shuffle stream')
                for seed in row['shuffleSeeds'])
            shared.require(row['opponentStyle'] in ('QUALIFIED', *ADAPTIVE_STYLES),
                'Every training round must declare its exact frozen league ecology.')
            if row['opponentStyle'] in ADAPTIVE_STYLES:
                adaptive(row['adaptiveOpponent'])
            else:
                shared.require('adaptiveOpponent' not in row, 'Ordinary collection has no adaptive side channel.')
            current_ecology = (row['opponentStyle'], row.get('adaptiveOpponent'))
            shared.require(ecology is None or current_ecology == ecology,
                'Every learning round retains the exact original opponent ecology and KL anchor scope.')
            ecology = current_ecology
        shared.require(set(record['evaluation']) == {'ordinary', 'adaptive'} and
            record['selection'] == SELECTION, 'Only the sole final actor and both fixed robust win gates are admitted.')
        for name, row in record['evaluation'].items():
            shared.require(type(row['seeds']) is list and 32 <= len(row['seeds']) <= 8192,
                'Every held-out bank requires a bounded complete fresh world population.')
            validate_replays(row['replayIndices'], len(row['seeds']))
            streams.extend(shared.integer(seed, -(2**31), 2**31-1, 'Held-out world stream') for seed in row['seeds'])
            shared.require(row['opponentStyle'] in (ORDINARY_STYLES if name == 'ordinary' else ADAPTIVE_STYLES),
                'Ordinary and adaptive bank opponent styles must match their declared roles.')
            if name == 'adaptive':
                adaptive(row['adaptiveOpponent'])
        shared.require(len(streams) == len(set(streams)),
            'All backbone, proposal, future, shuffle and held-out streams must be mutually disjoint.')
        shared.require(record['maximumTerminalExecutions'] == declared_budget(record)['maximum'] and
            record['automaticRetry'] is False and record['automaticSuccessor'] is False,
            'The once-only finite execution bound must include every possible tail and reproduction.')
        shared.integer(record['chunkSize'], 1, 64, 'Compact native shard size')
        shared.integer(record['maximumScienceSeconds'], 1, 7*86400, 'Original science allowance')
        shared.integer(record['auditSeconds'], 1, 3600, 'Single original audit allowance')
        shared.require(type(record['globalDeadlineEpoch']) in (int, float) and
            math.isfinite(record['globalDeadlineEpoch']), 'The original user deadline must be finite.')
        ownership = record['ownership']
        shared.require(set(ownership) == {'desktopChunksPerCycle', 'cycle'} and
            1 <= ownership['desktopChunksPerCycle'] < ownership['cycle'] <= 32,
            'Both hosts receive prospectively fixed periodic chunk ownership.')
        shared.require(set(record['hosts']) == {'DESKTOP', 'MAC'}, 'Both original host profiles must be declared.')
        for host in record['hosts'].values():
            shared.integer(host['workers'], 1, 16, 'Native workers')
            shared.integer(host['heapMiB'], 256, 32768, 'Native heap MiB')
            for key in ('maximumRssBytes', 'minimumFreeBytes', 'maximumDataBytes'):
                shared.integer(host[key], 1, 2**63-1, 'Host resource limit')
        if raw_sha256 is not None:
            shared.sha(raw_sha256)
        return cls(shared.canonical(record), raw_sha256)

    @property
    def record(self):
        return json.loads(self.wire)

    @property
    def sha256(self):
        return self.raw_sha256 or hashlib.sha256(self.wire).hexdigest()

    def owner(self, chunk):
        table = self.record['ownership']
        return 'DESKTOP' if chunk % table['cycle'] < table['desktopChunksPerCycle'] else 'MAC'

    def numerical_recipe(self, version=None):
        record = self.record
        primary = record['numericalRecipe']
        if version is None or version == primary['version']:
            return primary
        comparison = record.get('comparisonNumericalRecipe')
        shared.require(comparison is not None and version == comparison['version'],
            'Every fitted recipe must be declared in the original campaign protocol.')
        return comparison

    def fit_case(self, identifier=None):
        cases = self.record.get('fitCases')
        if cases is None:
            shared.require(identifier is None, 'This protocol does not declare explicit fit cases.')
            return None
        for case in cases:
            if case['id'] == identifier:
                return case
        raise ValueError('Choose one explicit fit case from the original protocol.')

    def collection_source(self, round_number, chunk, profile, current):
        record = self.record; row = record['rounds'][round_number-1]
        shared.require(row['round'] == round_number, 'Only an originally declared round may be executed.')
        selected = indices(len(row['origins']), record['chunkSize'], chunk)
        source = dict(kind='token-anchored-paired-collect-1', round=round_number,
            trainCount=row['trainCount'], origins=row['origins'], replayIndices=row['replayIndices'],
            proposalBudget=record['proposalBudget'],
            globalStart=selected.start, count=len(selected), arena=profile['arena'],
            anchor=profile['anchor'], originalActor=profile['originalActor'], currentActor=current,
            opponentStyle=row['opponentStyle'], adaptiveOpponent=row.get('adaptiveOpponent'))
        return source


def adaptive(value):
    shared.require(type(value) is dict and set(value) ==
        {'kind', 'minimumObservations', 'lookbackTurns', 'targetedRivalCount'} and
        value['kind'] == 'public-token-history-counter-1', 'Unknown adaptive public-history opponent contract.')
    shared.integer(value['minimumObservations'], 1, 16, 'Minimum public observations')
    shared.integer(value['lookbackTurns'], value['minimumObservations'], 16, 'Public-history lookback')
    shared.integer(value['targetedRivalCount'], 1, 3, 'Predeclared public-history targeted rivals')
    return value
