"""Opt-in raw-directory evidence for current ETM8 card / F0 purchase joint behavior.

This reader binds retained native provenance, state-only sidecars and canonical histories. It
never replays an engine, changes actor inputs, resolves archive members, or chooses a policy.
"""
from enum import Enum
from pathlib import Path

import purchase_policy as policy


class JointEcology(Enum):
    CURRENT = 'current-etm8-joint-1'


VERSION = 'joint-state-chronology-1'
CONTINUATION = 'ARENA_OVERLAYS_1'
M8 = 'b7c42a6f347a77c9bd9ff5d29778ea74650a9affe5fdb16738a8bea8d75d17d4'
F0 = 'c4dd1609c737151caadfe0cb85efe406cee84f02b801877af35d65128488bcb0'
EXPANSION = 'feb80d4363a93b266c94bc46e0d75ff4b0cb1f17a55d0f6d77782715862f44ef'
DICTIONARY = 'b8e11b9d6fb4e3e66e64ecaf4ab39e139d78a8b70e6704bf6235c0e34554d883'
# Canonical JSON hashes of the complete native state+action names, independent of model weights.
PHASE_DICTIONARIES = {
    'card': 'ae8f8f386d7dad24671254ae73dda61ef647a30333c2d185ad6a9f554d429324',
    'purchase': '7594e1b48a4642a74913ebe2d33c336d0e0c64499bc91cf4bf442554fbec44c3',
}
RULES = dict(sourceRulesVersion='ah1996-three-epoch-4', targetRulesVersion='ah1996-three-epoch-5')
EXPANSION_FEATURES = ['tokens', 'progress', 'reach', 'commodity', 'income', 'risk',
                      'action.expansion', 'action.pass']
BASE_FIELDS = {'schemaVersion', 'kind', 'configuration', 'games', 'seed', 'baseline', 'bundle', 'opponents'}
FIELDS = {'jointStateChronology', 'sharedPurchaseBaseline', 'experimentalRulesTransfer',
          'continuationProtocol', 'expansionImitation', 'tokenOrder'}
WORLD_FLAGS = ('zeroCardDecisions', 'zeroPurchaseDecisions', 'anyPhysicalDeviation',
               'anyPass', 'anyLeaderOffered', 'anyLeaderSelected')
require, read, sha = policy.require, policy.read_json, policy.file_digest


class CurrentJointEvidence:
    """Intrinsic current-ecology invariants; ``pin`` is the enclosing raw reader's hash owner."""
    def __init__(self, root, source_path, source, manifest, finished, pins, pin):
        self.root, self.pins, self.pin = Path(root), pins, pin
        self.signature, self.count = manifest['signature'], manifest['games']
        require(set(source) in (BASE_FIELDS | FIELDS, BASE_FIELDS | FIELDS | {'replayIndices'})
                and source['jointStateChronology'] == manifest.get('jointStateChronology') == VERSION
                and source['continuationProtocol'] == manifest.get('continuationProtocol') == CONTINUATION
                and source['experimentalRulesTransfer'] == RULES
                and manifest.get('experimentalRulesTransfer') == dict(
                    RULES, initializationOnly=True, qualificationTransferred=False)
                and manifest.get('rulesVersion') == RULES['targetRulesVersion']
                and manifest.get('dataVersion') == 'ah1996-catalog-16',
                'CURRENT joint data requires explicit chronology, complete overlays and exact rules transfer.')
        def snapshot(value, name, expected):
            original = pin(Path(source_path).parent/value, pins)
            copied = pin(self.root/name, pins)
            require(sha(original) == sha(copied) == expected,
                    'CURRENT joint role bytes or indexed snapshot changed: '+name)
            return dict(path=str(original), sha256=expected, snapshot=name)
        ordinary = snapshot(source['baseline'], 'baseline.json', M8)
        require(manifest['baseline'] == {k: ordinary[k] for k in ('path', 'sha256')},
                'CURRENT ordinary/card prior must be exact M8.')
        prior = snapshot(source['sharedPurchaseBaseline'], 'shared-purchase-baseline.json', F0)
        require(manifest.get('sharedPurchaseBaseline') == prior,
                'CURRENT purchase prior must retain its separate exact F0 identity.')
        self.prior = Path(prior['path'])
        expansion = source['expansionImitation']
        require(set(expansion) == {'candidateModel', 'opponentModels'}
                and isinstance(expansion['opponentModels'], list)
                and len(expansion['opponentModels']) == len(source['opponents'])
                and all(isinstance(value, str) for value in expansion['opponentModels']),
                'CURRENT expansion roles must cover every indexed rival with the qualified overlay.')
        expansion_metadata = dict(version='expansion-imitation-1', decision='expand',
            featureNames=EXPANSION_FEATURES,
            candidateModel=snapshot(expansion['candidateModel'], 'expansion-imitation-candidate.json', EXPANSION),
            opponentModels=[snapshot(value, f'expansion-imitation-opponent-{index}.json', EXPANSION)
                            for index, value in enumerate(expansion['opponentModels'])])
        tokens = dict(candidateSelector='FIXED_16_TO_15',
                      opponentSelectors=['FIXED_16_TO_15']*len(source['opponents']))
        require(manifest.get('expansionImitation') == expansion_metadata
                and source['tokenOrder'] == manifest.get('tokenOrder') == tokens,
                'CURRENT expansion/token metadata must preserve every explicit qualified role.')
        indices = source.get('replayIndices', [])
        require(isinstance(indices, list) and all(type(i) is int and 0 <= i < self.count for i in indices)
                and len(indices) == len(set(indices)) and len(indices) <= 8
                and ('replayIndices' not in source or bool(indices))
                and manifest.get('replayIndices') == indices,
                'CURRENT canonical histories must retain their prospective distinct source indices.')
        self.replay_indices = indices
        self.dictionary_path = pin(self.root/'joint-state-dictionary.json', pins)
        require(sha(self.dictionary_path) == DICTIONARY, 'CURRENT complete joint dictionary changed.')
        self.dictionary = read(self.dictionary_path)
        self.sidecars = {}
        for kind, field in (('state', 'jointStateSha256'), ('label', 'jointLabelSha256'),
                            ('coverage', 'jointCoverageSha256')):
            paths = [self.root/f'joint-{kind}-{i}.json' for i in range(self.count)]
            related = {'joint-state-dictionary.json', 'joint-coverage-summary.json'}
            require({path for path in self.root.glob(f'joint-{kind}-*.json') if path.name not in related} == set(paths)
                    and finished.get(field) == [sha(pin(path, pins)) for path in paths],
                    'CURRENT completion must bind every original '+kind+' sidecar before decoding.')
            self.sidecars[kind] = paths
        replay_files = {f'replay-{index}.json': sha(pin(self.root/f'replay-{index}.json', pins))
                        for index in indices}
        require(finished.get('canonicalReplaySha256') == replay_files
                and {p.name for p in self.root.glob('replay-*.json')} == set(replay_files),
                'CURRENT canonical completion cannot omit or add a retained history.')
        summary_path = pin(self.root/'joint-coverage-summary.json', pins)
        require(finished.get('jointCoverageSummarySha256') == sha(summary_path),
                'CURRENT coverage summary completion hash changed.')
        self.summary = read(summary_path)
        require(self.summary.get('kind') == 'joint-collect' and self.summary.get('signature') == self.signature
                and self.summary.get('independentWorlds') == self.count
                and set(self.summary.get('worldCounts', {})) == set(WORLD_FLAGS),
                'CURRENT coverage summary must bind all independent origins.')
        self.world_counts = {key: 0 for key in WORLD_FLAGS}
        self.provenance = dict(version=JointEcology.CURRENT.value, chronologyVersion=VERSION,
            continuationProtocol=CONTINUATION, ordinaryPriorSha256=M8,
            phasePriorSha256=dict(card=M8, purchase=F0), expansionSha256=EXPANSION,
            tokenOrder=tokens, rulesTransfer=manifest['experimentalRulesTransfer'],
            dictionarySha256=DICTIONARY, replayIndices=indices, canonicalReplaySha256=replay_files,
            evidenceReaderSha256=sha(__file__))

    def validate_heads(self, bundles):
        """Weights may evolve; both model vocabularies retain their exact native owners."""
        for bundle in bundles:
            require(bundle['baselineSha256'] == F0, 'Every CURRENT bundle must bind the F0 purchase prior.')
            for phase in ('card', 'purchase'):
                head = bundle[phase]
                if head is not None:
                    require(policy.digest({key: head[key] for key in ('stateFeatureNames', 'actionFeatureNames')})
                            == PHASE_DICTIONARIES[phase], 'CURRENT '+phase+' state/action dictionary changed.')
        controlled = bundles[0]
        names = sorted(phase+'.'+name for phase in ('card', 'purchase')
                       for name in controlled[phase]['stateFeatureNames'])
        require(self.dictionary == dict(version=VERSION, stateFeatureNames=names,
                units='unchanged-existing-phase-encoder-units', missingValue=0.),
                'CURRENT chronology dictionary must copy the complete phase encoders without rescaling.')

    def validate_game(self, game, index, game_hash):
        provenance = dict(version=VERSION, signature=self.signature, originIndex=index,
                          sourceGameSha256=game_hash, dictionarySha256=DICTIONARY)
        sidecars = {kind: read(paths[index]) for kind, paths in self.sidecars.items()}
        require(all(all(key in value and type(value[key]) is type(expected) and value[key] == expected
                        for key, expected in provenance.items()) for value in sidecars.values()),
                'CURRENT sidecar origin/dictionary/trajectory binding changed.')
        rows = sorted([dict(number=row['number'], turn=row['turn'], phase=phase.upper(), sourceRow=i,
                            stateFeatures={phase+'.'+key: value for key, value
                                           in row['observation']['stateFeatures'].items()})
                       for phase in ('card', 'purchase') for i, row in enumerate(game[phase+'Decisions'])],
                      key=lambda row: row['number'])
        require(sidecars['state'] == dict(provenance, rows=rows)
                and sidecars['label'] == dict(provenance, terminal=game['terminal']),
                'CURRENT chronology must retain exact state-only rows and separate terminal labels.')
        coverage = sidecars['coverage']
        require(coverage.get('independentWorlds') == 1
                and coverage.get('cardDecisions') == len(game['cardDecisions'])
                and coverage.get('purchaseDecisions') == len(game['purchaseDecisions'])
                and coverage.get('observedSelectedCommandQuotes') == game.get('jointEvidence', [])
                and [(row['number'], row['turn']) for row in coverage['rows']]
                    == [(row['number'], row['turn']) for row in game['cardDecisions']]
                and set(coverage['worldCoverage']) == set(WORLD_FLAGS)
                and all(type(value) is bool for value in coverage['worldCoverage'].values()),
                'CURRENT coverage must retain original row identities, quotes and empty origins.')
        for key, value in coverage['worldCoverage'].items():
            self.world_counts[key] += value
        if index in self.replay_indices:
            replay = read(self.root/f'replay-{index}.json')
            require(replay.get('kind') == 'joint-canonical-replay-1'
                    and replay.get('signature') == self.signature and type(replay.get('index')) is int
                    and replay['index'] == index and replay.get('trajectorySha256') == game_hash
                    and replay.get('replayVerified') is True and isinstance(replay.get('header'), dict)
                    and isinstance(replay.get('entries'), list),
                    'CURRENT retained canonical history must bind the exact completed trajectory.')

    def finish(self):
        require(self.summary['worldCounts'] == self.world_counts,
                'CURRENT coverage summary must count the same complete independent origins.')
