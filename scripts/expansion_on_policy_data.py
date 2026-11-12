"""Strict completed expansion-only episodes; existing two-head readers cannot consume these."""
import gzip
import json
import math
from pathlib import Path

import expansion_mc as mc
import joint_trajectory as joint
import purchase_policy as policy
from joint_economy import terminal_metrics
from territory_trajectory import bot_seed, signed


KIND = 'expansion-on-policy-collect-1'
TRAJECTORY = 'expansion-on-policy-trajectory-1'
ACTOR_SALT = 0x9b05688c


def read(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            policy.require(key not in result, 'Duplicate expansion episode field: '+key)
            result[key] = value
        return result
    opener = gzip.open if str(path).endswith('.gz') else open
    with opener(path, 'rt', encoding='utf-8') as stream:
        return json.load(stream, object_pairs_hook=unique,
            parse_constant=lambda value: policy.require(False, 'Nonfinite expansion JSON: '+value))


class ExpansionCollection:
    """One complete native batch, all original rows, no proposal truncation or omitted worlds."""
    def __init__(self, directory, actor_path):
        self.directory = Path(directory).resolve(strict=True); self.pins = {}
        root = self.directory
        manifest = read(self.pin(root/'collection.json')); done = read(self.pin(root/'finished.json'))
        policy.require(manifest['kind'] == done['kind'] == KIND and manifest['schemaVersion'] == 1 and
            done['complete'] is True and done['signature'] == manifest['signature'],
            'Expansion MC requires its complete new native collection contract.')
        policy.require(manifest['trajectoryEncoding'] == 'gzip-json-v1' and
            manifest['actorVersion'] == mc.CONTRACT.actor and manifest['configuration'] ==
            'players-4/standard/no-crusades/deck-true/art-true', 'Expansion setup/actor/encoding differs.')
        source = self.subject(manifest['source'], root); self.source = read(source)
        arena_path = self.subject(manifest['arena'], root); arena = read(arena_path)
        self.actor_path = self.subject(manifest['actor'], root)
        policy.require(self.pin(source.parent/self.source['arena']) == arena_path and
            self.pin(source.parent/self.source['actor']['path']) == self.actor_path,
            'Source arena and actor paths must resolve to their exact manifest subjects.')
        policy.require(self.actor_path == Path(actor_path).resolve(strict=True) and
            self.source['kind'] == KIND and self.source['actor']['sha256'] == manifest['actor']['sha256'],
            'The exact source and behavior expansion actor must agree.')
        self.actor_sha = policy.file_digest(self.actor_path)
        self.model = policy.validate_model(read(self.actor_path), contract=mc.CONTRACT)
        dictionary = manifest['dictionaries']
        policy.require(dictionary['encoderVersion'] == mc.CONTRACT.encoder and all(
            dictionary[k] == self.model[k] for k in ('stateFeatureNames','actionFeatureNames')),
            'Expansion actor requires the full setup dictionary, not observed or selected columns.')
        policy.require(manifest['ecologySnapshotDirectory'] == 'ecology', 'Unexpected ecology snapshot owner.')
        bindings = manifest['policyBindings']; self.bindings(bindings, root/'ecology')
        self.ecology = dict(configuration=manifest['configuration'], policyBindings=bindings,
            arenaSourceSha256=manifest['arena']['sha256'],
            frozenCardPurchaseBundleSha256=manifest['frozenCardPurchaseBundleSha256'],
            referenceExpansionModelSha256=manifest['referenceExpansionModelSha256'])
        joint = bindings['jointPolicy']; expansion = bindings['expansionImitation']; token = bindings['tokenOrder']
        policy.require(all(joint[role]['sha256']==self.ecology['frozenCardPurchaseBundleSha256'] and
            joint[role]['card'] is not None and joint[role]['purchase'] is not None
            for role in ('sharedBundle','candidateBundle','incumbentBundle')) and
            expansion['candidateModel']['sha256']==expansion['incumbentModel']['sha256']==
                self.ecology['referenceExpansionModelSha256'] and
            [v['sha256'] for v in expansion['opponentModels']]==[self.ecology['referenceExpansionModelSha256']] and
            token['candidateSelector']==token['incumbentSelector']=='FIXED_16_TO_15' and
            token['opponentSelectors']==['FIXED_16_TO_15'],
            'Every role must retain the same frozen learned C/P, E reference and fixed T.')
        self.roles(arena_path, arena, manifest, bindings)
        ordinary = []
        for role, name in [('candidate',arena['candidate']),('incumbent',arena['incumbent']),
                *[(f'opponent-{i}',p) for i,p in enumerate(arena['opponents'])]]:
            original = read(self.pin(arena_path.parent/name)); snapshot = read(self.pin(root/'ecology'/f'{role}.json'))
            policy.require(original == snapshot, 'An ordinary-model snapshot differs from the exact arena input.')
            ordinary.append(original)
        policy.require(len(ordinary)==3 and ordinary[0]==ordinary[1]==ordinary[2],
            'Expansion collection requires identical ordinary behavior in every role.')
        count = policy.integer(manifest['games'], 'Expansion complete games', 1, 4096)
        offset = policy.integer(manifest['seed'], 'Expansion world offset', 0, 100000000)
        seeds = [-1000000000+offset+i*8191 for i in range(count)]
        policy.require(done['games'] == self.source['games'] == count and self.source['seed'] == offset and
            manifest['seeds'] == seeds and manifest['replayIndices'] == self.source['replayIndices'],
            'Expansion worlds, seat rotation and declared replays must match the source.')
        files = done['gameSha256']; expected = {f'game-{i}.json.gz' for i in range(count)}
        policy.require(set(files) == expected and {p.name for p in root.glob('game-*.json.gz')} == expected,
            'Every original expansion world must be complete exactly once.')
        policy.require(set(done['canonicalSha256']) == {f'replay-{i}.json' for i in manifest['replayIndices']},
            'Canonical completion must cover exactly the predeclared reproduction indices.')
        policy.require(not (root/'failed.json').exists(), 'A failed native batch cannot become training data.')
        for name, digest in done['canonicalSha256'].items():
            policy.require(Path(name).name == name and policy.file_digest(self.pin(root/name)) == digest,
                'Original canonical evidence must retain its completed byte hash.')
        actor = policy.ResidualPolicy(self.model, mc.CONTRACT)
        self.rows = []; self.origins = []; hashes = []; rewards = []
        for index in range(count):
            path = self.pin(root/f'game-{index}.json.gz'); digest = policy.file_digest(path)
            policy.require(digest == files[path.name], 'An expansion game differs from its completion receipt.')
            hashes.append(digest); game = read(path)
            expected_identity = dict(schemaVersion=1, kind=TRAJECTORY, signature=manifest['signature'],
                index=index, configuration=manifest['configuration'], gameSeed=seeds[index], controlledSeat=index%4,
                actorModelSha256=self.actor_sha, arenaSourceSha256=manifest['arena']['sha256'],
                frozenCardPurchaseBundleSha256=self.ecology['frozenCardPurchaseBundleSha256'],
                referenceExpansionModelSha256=self.ecology['referenceExpansionModelSha256'])
            policy.require(all(game.get(k) == v for k,v in expected_identity.items()),
                'Expansion trajectory identity differs from the completed source/role binding.')
            policy.require(game['opponentIndices']==[0]*4, 'Expansion opponents must retain their explicit homogeneous roles.')
            reward = terminal_metrics(game)['reward']; rewards.append(reward)
            rows = game['expansionDecisions']; policy.require(isinstance(rows,list) and len(rows)<=12000,
                'Retain each bounded expansion decision array, including empty worlds.')
            previous = -1; previous_turn = 0
            for row in rows:
                number = policy.integer(row['number'], 'Absolute expansion decision', 0, 11999)
                turn = policy.integer(row['turn'], 'Expansion turn', 1, 12000)
                policy.require(previous < number < game['terminal']['gameDecisions'] and
                    row['batchStartNumber'] == number and turn >= previous_turn and row['decision'] == 'expand',
                    'Ordinary expansion is sequential and retains ordered absolute decisions/turns.')
                previous = number; previous_turn = turn
                expected_bot = bot_seed(seeds[index], number, index%4)
                policy.require(row['botSeed'] == expected_bot and row['actorSeed'] == signed(expected_bot ^ ACTOR_SALT),
                    'Expansion sampler must retain its exact original policy seed and phase salt.')
                prepared = actor.prepare(row['observation'])
                policy.integer(row['selectedIndex'], 'Expansion selected index', 0, prepared.action_count-1)
                policy.number(row['behaviorLogProbability'], 'Expansion behavior log probability')
                policy.number(row['behaviorValue'], 'Expansion behavior value', 0, 1)
                self.rows.append(dict(row, originIndex=index, **{'return':reward}, _prepared=prepared))
            self.origins.append(dict(index=index, gameSeed=seeds[index], controlledSeat=index%4, decisions=len(rows)))
        policy.require(len(self.rows) <= 200000, 'Expansion batch exceeds its bounded recorded decision count.')
        with policy.torch.no_grad():
            for rows in policy.row_batches(self.rows, 16):
                logps, values = actor([r['_prepared'] for r in rows])
                for row, logp, value in zip(rows, logps, values):
                    policy.require(abs(float(logp[row['selectedIndex']])-row['behaviorLogProbability']) <= 1e-9 and
                        abs(float(value)-row['behaviorValue']) <= 1e-9, 'Expansion behavior likelihood/value differs.')
                    uniform = policy.selection_uniform(row['actorSeed'])
                    if policy.select(logp.exp().tolist(), uniform) != row['selectedIndex']:
                        exact = policy.reference_forward(self.model, row['observation'], contract=mc.CONTRACT)
                        policy.require(policy.select(exact['probabilities'], uniform) == row['selectedIndex'],
                            'Original seeded expansion choice differs from its complete offered distribution.')
        self.provenance = dict(collectionSha256=policy.file_digest(root/'collection.json'),
            completionSha256=policy.file_digest(root/'finished.json'), collectionSignature=manifest['signature'],
            gameHashes=hashes, games=count, gameMeanReward=math.fsum(rewards)/count,
            actorModelSha256=self.actor_sha, origins=self.origins, readerSha256=policy.file_digest(__file__))
        self.unchanged()

    def pin(self, path):
        path = Path(path).resolve(strict=True); digest = policy.file_digest(path)
        policy.require(str(path) not in self.pins or self.pins[str(path)] == digest,
            'Expansion input changed while being read.')
        self.pins[str(path)] = digest
        return path

    def subject(self, value, root):
        policy.require(isinstance(value,dict) and {'path','sha256','snapshot'} <= set(value),
            'Every expansion role requires its complete typed path/hash/snapshot subject.')
        policy.require(Path(value['snapshot']).name == value['snapshot'], 'A snapshot must be a local owned filename.')
        path = self.pin(value['path']); snapshot = self.pin(root/value['snapshot'])
        policy.require(policy.file_digest(path) == value['sha256'] == policy.file_digest(snapshot),
            'Expansion subject/snapshot bytes differ from their declared hash.')
        return path

    def bindings(self, value, root):
        if isinstance(value,dict):
            if {'path','sha256','snapshot'} & set(value): self.subject(value, root)
            for child in value.values(): self.bindings(child, root)
        elif isinstance(value,list):
            for child in value: self.bindings(child, root)

    def roles(self, arena_path, arena, manifest, bindings):
        """Reuse the existing two-head decoder, but never adapt its records or objective."""
        root = self.directory/'ecology'; metadata = bindings['jointPolicy']; source = arena['jointPolicy']
        policy.require(self.subject(metadata['source'],root)==arena_path,
            'The joint ecology must retain the actual arena source.')
        baseline = self.subject(metadata['baseline'],root)
        policy.require(self.pin(arena_path.parent/source['baseline']) == baseline,
            'The actual F0 baseline declaration differs from its snapshot.')
        def without_snapshots(value):
            if isinstance(value,dict):return {k:without_snapshots(v) for k,v in value.items() if k!='snapshot'}
            if isinstance(value,list):return [without_snapshots(v) for v in value]
            return value
        for role in ('sharedBundle','candidateBundle','incumbentBundle'):
            path = self.subject(metadata[role],root)
            policy.require(self.pin(arena_path.parent/source[role]) == path,
                'The actual frozen C/P declaration differs from its typed subject.')
            descriptor,_ = joint.load_bundle(path,manifest['configuration'],policy.file_digest(baseline),self.pins)
            policy.require(without_snapshots(metadata[role]) == descriptor,
                'C/P role metadata must equal its original typed bundle, components and F0 prior.')
            for phase in ('card','purchase'):self.subject(metadata[role][phase],root)
        expansion = bindings['expansionImitation']; declared = arena['expansionImitation']
        for role in ('candidateModel','incumbentModel'):
            policy.require(self.pin(arena_path.parent/declared[role]) == self.subject(expansion[role],root),
                'The actual E reference declaration differs from its typed subject.')
        policy.require(len(declared['opponentModels']) == 1 and
            self.pin(arena_path.parent/declared['opponentModels'][0]) == self.subject(expansion['opponentModels'][0],root),
            'The actual rival E declaration differs from its typed subject.')
        policy.require(all(arena['tokenOrder'][key] == bindings['tokenOrder'][key]
            for key in ('candidateSelector','incumbentSelector','opponentSelectors')),
            'The actual fixed-T source declaration differs from its role metadata.')

    def unchanged(self):
        policy.require(all(policy.file_digest(path)==digest for path,digest in self.pins.items()),
            'A frozen expansion input changed during validation or fitting.')

    def phase_reader(self, directory, model_path, model, *, contract):
        policy.require(contract == mc.CONTRACT and Path(directory).resolve() == self.directory and
            Path(model_path).resolve() == self.actor_path and model == self.model,
            'Expansion fitting requires its exact original typed behavior view.')
        self.unchanged()
        policy.require(self.rows, 'An entirely empty expansion batch cannot publish a fitted actor.')
        return self.rows, self.provenance
