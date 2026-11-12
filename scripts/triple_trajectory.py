"""Raw-only completed three-head behavior evidence; no virtual legacy collections or critic rows."""
import math
import hashlib
import json
from pathlib import Path, PurePosixPath

import expansion_on_policy_data as expansion
import joint_current_ecology as current
import joint_trajectory as joint
import purchase_policy as policy
import triple_mc as mc
from joint_economy import terminal_metrics
from territory_trajectory import bot_seed, signed


KIND = 'triple-on-policy-collect-1'
TRAJECTORY = 'triple-on-policy-trajectory-1'
read, require = expansion.read, policy.require


class TripleCollection:
    """Read and verify one original complete batch before any of its three actors can update."""
    def __init__(self, directory):
        self.directory = Path(directory).resolve(strict=True); self.pins = {}
        root = self.directory
        manifest = read(self.pin(root/'collection.json')); done = read(self.pin(root/'finished.json'))
        require(manifest['kind'] == done['kind'] == KIND and manifest['schemaVersion'] == 1
            and manifest['trajectoryEncoding'] == 'gzip-json-v1' and done['complete'] is True
            and isinstance(manifest['signature'], str) and manifest['signature']
            and done['signature'] == manifest['signature'] and not (root/'failed.json').exists(),
            'Triple MC requires its own complete raw native collection, never a legacy phase alias.')
        self.manifest = manifest; self.snapshot_subjects = {}
        for name, subject in manifest['snapshots'].items():
            relative = PurePosixPath(name)
            require(isinstance(name, str) and str(relative) == name and not relative.is_absolute()
                and '..' not in relative.parts and name not in ('', '.'), 'Unsafe triple snapshot path.')
            require(set(subject) == {'path','sha256'}, 'Every triple snapshot has an exact original subject.')
            original = self.pin(subject['path']); copied = self.pin(root/name)
            require(joint.sha(original) == joint.sha(copied) == subject['sha256'],
                'A triple original or snapshot changed: '+name)
            self.snapshot_subjects[name] = dict(path=str(original), sha256=subject['sha256'])
        source_path = self.subject(manifest['source'], 'source.json')
        joint_path = self.subject(manifest['jointSource'], 'joint/source.json')
        source = read(source_path); source_joint = read(joint_path)
        require(set(source) == {'kind','jointSource','expansion'} and source['kind'] == KIND,
            'Triple collection requires its explicit new source grammar.')
        for key, path in (('jointSource',joint_path),('expansion',self.subject(manifest['expansion'], 'expansion.json'))):
            declared = source[key]
            require(set(declared) == {'path','sha256'} and
                joint.resolved(declared['path'], source_path.parent) == path and joint.sha(path) == declared['sha256'],
                'Triple source subjects differ from the actual manifest.')
        jm = manifest['joint']; self.configuration = jm['configuration']
        require(jm['kind'] == 'joint-collect' and jm['schemaVersion'] == 1 and jm['mode'] == 'on-policy'
            and jm['signature'] == manifest['signature'] and jm['trajectoryEncoding'] == 'gzip-json-v1'
            and jm['source'] == str(joint_path) and jm['sourceSha256'] == joint.sha(joint_path)
            and self.configuration == source_joint['configuration'] == joint.CONFIGURATION,
            'The intrinsic CURRENT joint subject must remain unchanged inside the triple manifest.')
        self.ecology = self.current_ecology(joint_path, source_joint, jm)
        own = joint.resolved(source_joint['bundle'], joint_path.parent)
        self.bundle, self.models = joint.load_bundle(own, self.configuration, self.baseline_sha, self.pins)
        require(jm['bundle'] == self.bundle, 'Original C/P bundle, dictionaries or priors changed.')
        expected_snapshots = {'source.json','expansion.json','joint/source.json','joint/baseline.json',
            'joint/shared-purchase-baseline.json','joint/expansion-imitation-candidate.json',
            'joint/expansion-imitation-opponent-0.json'}
        expected_snapshots |= self.bundle_snapshots(self.bundle, '')
        require(isinstance(source_joint['opponents'], list) and len(source_joint['opponents']) == 1,
            'Triple behavior requires one fixed explicit G3 rival bundle.')
        rival, _ = joint.load_bundle(joint.resolved(source_joint['opponents'][0], joint_path.parent),
            self.configuration, self.baseline_sha, self.pins)
        expected_snapshots |= self.bundle_snapshots(rival, 'opponent-0-')
        require(jm['opponents'] == [rival] and set(self.snapshot_subjects) == expected_snapshots,
            'Triple snapshots must include exactly all original source, prior and controlled/rival actors.')
        self.ecology['rivalBundle'] = rival
        for bundle in (self.bundle, rival):
            for phase in ('card','purchase'):
                require(policy.digest({k:bundle[phase][k] for k in ('stateFeatureNames','actionFeatureNames')})
                    == current.PHASE_DICTIONARIES[phase], 'Triple '+phase+' dictionary changed.')
        e = manifest['expansion']; actor_path = self.subject(e, 'expansion.json')
        self.models['expansion'] = policy.validate_model(read(actor_path), contract=mc.CONTRACTS['expansion'])
        require(e['modelSchemaVersion'] == 2 and e['actorVersion'] == mc.CONTRACTS['expansion'].actor
            and e['encoderVersion'] == mc.CONTRACTS['expansion'].encoder and all(
                e[key] == self.models['expansion'][key] for key in ('stateFeatureNames','actionFeatureNames')),
            'Triple expansion must retain its complete schema2 full-offer dictionary.')
        self.heads = dict(expansion=dict(path=str(actor_path),sha256=joint.sha(actor_path)),
            **{p:self.bundle[p] for p in ('card','purchase')})
        require(manifest['controlledBehavior'] == {p:self.heads[p]['sha256'] for p in mc.PHASES},
            'All three actual behavior models must match the native source.')
        self.ecology['dictionaries'] = {p:{k:self.models[p][k] for k in
            ('encoderVersion','stateFeatureNames','actionFeatureNames')} for p in mc.PHASES}
        count = policy.integer(jm['games'], 'Triple games', 1, 4096)
        offset = policy.integer(jm['seed'], 'Triple world offset', 0, 100000000)
        seeds = [-1000000000+offset+i*8191 for i in range(count)]
        require(done['games'] == source_joint['games'] == count and source_joint['seed'] == offset
            and jm['seeds'] == seeds and jm['controlledSeat'] == 'index % playerCount'
            and jm['opponentRotation'] == '(index / playerCount + seat) % opponentCount'
            and jm['reward'] == '.8 * winShare + .2 * rankReward', 'Triple original world schedule changed.')
        indices = manifest['replayIndices']
        require(indices == source_joint.get('replayIndices', []) == jm.get('replayIndices', [])
            and isinstance(indices, list) and len(indices) == len(set(indices)) <= 8
            and all(type(i) is int and 0 <= i < count for i in indices), 'Triple canonical schedule changed.')
        expected_games = {f'game-{i}.json.gz' for i in range(count)}
        require(set(done['gameSha256']) == expected_games
            and {p.name for p in root.glob('game-*')} == expected_games
            and set(done['canonicalSha256']) == {f'replay-{i}.json' for i in indices}
            and {p.name for p in root.glob('replay-*.json')} == set(done['canonicalSha256']),
            'Every original triple world and prescribed canonical replay must exist exactly once.')
        hashes = [joint.sha(self.pin(root/f'game-{i}.json.gz')) for i in range(count)]
        require(done['gameSha256'] == {f'game-{i}.json.gz':h for i,h in enumerate(hashes)},
            'Every original triple game hash must bind before row or label decoding.')
        self.rows = {p:[] for p in mc.PHASES}; self.origins = []; rewards = []
        actors = {p:policy.ResidualPolicy(self.models[p], mc.CONTRACTS[p]) for p in mc.PHASES}
        for index in range(count):
            game = read(root/f'game-{index}.json.gz')
            expected = dict(kind=TRAJECTORY, schemaVersion=1, signature=manifest['signature'], index=index,
                configuration=self.configuration, gameSeed=seeds[index], controlledSeat=index%4, opponentIndices=[0]*4,
                bundleSha256=self.bundle['sha256'], **{p+'ModelSha256':self.heads[p]['sha256'] for p in mc.PHASES})
            require(set(game) == set(expected) | {'terminal','jointEvidence',*[p+'Decisions' for p in mc.PHASES]}
                and all(game[k] == v for k,v in expected.items()) and isinstance(game['jointEvidence'], list),
                'A triple trajectory must retain its exact complete source/role identity and typed fields.')
            reward = terminal_metrics(game)['reward']; rewards.append(reward)
            self.validate_rows(game, index, reward, actors)
            self.origins.append(dict(index=index, gameSeed=seeds[index], controlledSeat=index%4,
                **{p+'Decisions':len(game[p+'Decisions']) for p in mc.PHASES}))
            if index in indices:self.canonical(root/f'replay-{index}.json', index, hashes[index], done)
        for phase in mc.PHASES:
            require(len(self.rows[phase]) <= 200000, 'Triple typed phase exceeds the bounded update size.')
            self.behavior(phase, actors[phase])
        self._row_seals = {phase:tuple((self.row_digest(row),row['_prepared'],self.prepared_digest(row['_prepared']))
            for row in self.rows[phase]) for phase in mc.PHASES}
        self.common = dict(collectionSha256=joint.sha(root/'collection.json'), completionSha256=joint.sha(root/'finished.json'),
            collectionSignature=manifest['signature'], gameHashes=hashes, games=count,
            gameMeanReward=math.fsum(rewards)/count, originCoverage=self.origins,
            behaviorModels=manifest['controlledBehavior'], collectionReaderSha256=joint.sha(__file__),
            readerSha256=joint.sha(__file__))
        for module in (joint,current,expansion,policy,mc):self.pin(module.__file__)
        self.pin(__file__)
        self._collection_seal = self.collection_digest()
        self.unchanged()

    def pin(self, path):
        path = Path(path)
        require(not path.is_symlink(), 'Triple evidence subjects cannot be symlinks.')
        return joint.pin(path, self.pins)

    def subject(self, value, name):
        require({'path','sha256','snapshot'} <= set(value) and value['snapshot'] == name
            and self.snapshot_subjects.get(name) == {k:value[k] for k in ('path','sha256')},
            'Triple subject must equal its exact owned original snapshot: '+name)
        return Path(value['path'])

    def bundle_snapshots(self, bundle, prefix):
        subjects = {f'joint/{prefix}bundle.json':bundle,
            **{f'joint/{prefix}{p}-policy.json':bundle[p] for p in ('card','purchase')}}
        require(all(self.snapshot_subjects.get(name) == {k:value[k] for k in ('path','sha256')}
            for name,value in subjects.items()), 'Every controlled/rival head snapshot must match its typed bundle.')
        return set(subjects)

    def current_ecology(self, path, source, manifest):
        require(set(source) in (current.BASE_FIELDS | current.FIELDS, current.BASE_FIELDS | current.FIELDS | {'replayIndices'})
            and source['schemaVersion'] == 1 and source['kind'] == 'joint-collect'
            and source['jointStateChronology'] == manifest['jointStateChronology'] == current.VERSION
            and source['continuationProtocol'] == manifest['continuationProtocol'] == current.CONTINUATION
            and source['experimentalRulesTransfer'] == current.RULES
            and manifest['experimentalRulesTransfer'] == dict(current.RULES,initializationOnly=True,qualificationTransferred=False)
            and manifest['rulesVersion'] == self.manifest['rulesVersion'] == current.RULES['targetRulesVersion']
            and manifest['dataVersion'] == self.manifest['dataVersion'] == 'ah1996-catalog-16',
            'Triple current ecology must retain the exact original rules, public encoders and overlays.')
        def subject(value, name, expected):
            original = self.pin(joint.resolved(value, path.parent))
            require(joint.sha(original) == expected and self.snapshot_subjects.get('joint/'+name) ==
                dict(path=str(original),sha256=expected), 'Triple original prior/overlay changed: '+name)
            return dict(path=str(original),sha256=expected,snapshot=name)
        ordinary = subject(source['baseline'], 'baseline.json', current.M8)
        prior = subject(source['sharedPurchaseBaseline'], 'shared-purchase-baseline.json', current.F0)
        references = source['expansionImitation']
        require(set(references) == {'candidateModel','opponentModels'} and len(references['opponentModels']) == 1,
            'Triple E reference requires all indexed roles.')
        overlay = dict(version='expansion-imitation-1',decision='expand',featureNames=current.EXPANSION_FEATURES,
            candidateModel=subject(references['candidateModel'],'expansion-imitation-candidate.json',current.EXPANSION),
            opponentModels=[subject(references['opponentModels'][0],'expansion-imitation-opponent-0.json',current.EXPANSION)])
        tokens = dict(candidateSelector='FIXED_16_TO_15',opponentSelectors=['FIXED_16_TO_15'])
        require(manifest['baseline'] == {k:ordinary[k] for k in ('path','sha256')}
            and manifest['sharedPurchaseBaseline'] == prior and manifest['expansionImitation'] == overlay
            and source['tokenOrder'] == manifest['tokenOrder'] == tokens, 'Triple phase prior/E/T metadata changed.')
        self.baseline_sha = current.F0
        return dict(configuration=self.configuration,ordinary=ordinary,purchasePrior=prior,
            expansionReference=overlay,tokenOrder=tokens,rules=current.RULES)

    def validate_rows(self, game, index, reward, actors):
        occupied = set(); turns = []
        for phase in mc.PHASES:
            rows = game[phase+'Decisions']; previous = -1
            require(isinstance(rows,list) and len(rows) <= 12000, 'Retain bounded phase arrays, including empty worlds.')
            for row in rows:
                fields = {'number','turn','decision','observation','selectedIndex',
                    'behaviorLogProbability','behaviorValue','actorSeed'}
                if phase == 'expansion':fields |= {'batchStartNumber','botSeed'}
                require(set(row) == fields, 'Triple phase rows cannot add private or detached learner fields.')
                number = policy.integer(row['number'], 'Absolute triple decision', 0, 11999)
                require(previous < number < game['terminal']['gameDecisions'] and number not in occupied,
                    'Triple decisions must be ordered, disjoint and inside their original world.')
                previous = number; occupied.add(number)
                turns.append((number,policy.integer(row['turn'],'Triple turn',1,12000)))
                if phase == 'expansion':
                    seed = bot_seed(game['gameSeed'],number,game['controlledSeat'])
                    require(row['decision'] == 'expand' and row['batchStartNumber'] == number
                        and row['botSeed'] == seed and row['actorSeed'] == signed(seed ^ expansion.ACTOR_SALT),
                        'Expansion retains the original absolute batch seed and full-offer phase salt.')
                else:
                    require(row['decision'] in (('play-cards',) if phase == 'card' else ('purchase-shipping','purchase-advances'))
                        and row['actorSeed'] == joint.actor_seed(game['gameSeed'],number,game['controlledSeat'],phase),
                        'Card/purchase rows retain their original phase and absolute sampler seed.')
                prepared = actors[phase].prepare(row['observation'])
                policy.integer(row['selectedIndex'],'Triple chosen action',0,prepared.action_count-1)
                policy.number(row['behaviorLogProbability'],'Triple behavior log probability')
                policy.number(row['behaviorValue'],'Triple behavior value',0,1)
                self.rows[phase].append(dict(row,originIndex=index,**{'return':reward},_prepared=prepared))
        ordered = [turn for _,turn in sorted(turns)]
        require(len(occupied) <= 12000 and all(a <= b for a,b in zip(ordered,ordered[1:])),
            'Merged triple decisions must retain original world order and nondecreasing turns.')

    def behavior(self, phase, actor):
        with policy.torch.no_grad():
            for rows in policy.row_batches(self.rows[phase], 16):
                logps, values = actor([row['_prepared'] for row in rows])
                for row, logp, value in zip(rows, logps, values):
                    require(abs(float(logp[row['selectedIndex']])-row['behaviorLogProbability']) <= 1e-9
                        and abs(float(value)-row['behaviorValue']) <= 1e-9,
                        'Triple behavior likelihood/value must match the original typed actor.')
                    uniform = policy.selection_uniform(row['actorSeed'])
                    if policy.select(logp.exp().tolist(), uniform) != row['selectedIndex']:
                        exact = policy.reference_forward(self.models[phase],row['observation'],contract=mc.CONTRACTS[phase])
                        require(policy.select(exact['probabilities'],uniform) == row['selectedIndex'],
                            'Triple original seeded choice differs from its full offered distribution.')

    def canonical(self, path, index, game_hash, finished):
        require(joint.sha(self.pin(path)) == finished['canonicalSha256'][path.name], 'Triple canonical bytes changed.')
        value = read(path)
        require(value['kind'] == 'triple-on-policy-canonical-1' and value['signature'] == self.manifest['signature']
            and value['index'] == index and value['trajectorySha256'] == game_hash and value['replayVerified'] is True
            and isinstance(value['header'],dict) and isinstance(value['entries'],list)
            and all(isinstance(entry,str) for entry in value['entries'])
            and value['entriesSha256'] == hashlib.sha256(json.dumps(value['entries'],ensure_ascii=False,
                separators=(',',':'),allow_nan=False).encode()).hexdigest(),
            'Original triple canonical replay must bind the completed source and trajectory.')

    def unchanged(self):
        joint.unchanged(self.pins)
        require(self.collection_digest() == self._collection_seal,
            'Original in-memory triple models, roles or collection provenance changed.')
        require(set(self.rows) == set(mc.PHASES), 'Triple phase views changed after validation.')
        for phase in mc.PHASES:
            rows, seals = self.rows[phase], self._row_seals[phase]
            require(len(rows) == len(seals) and all(self.row_digest(row) == digest
                and row.get('_prepared') is prepared and self.prepared_digest(prepared) == prepared_digest
                for row,(digest,prepared,prepared_digest) in zip(rows,seals)),
                'Original in-memory triple observations, behavior, returns or prepared inputs changed.')

    def collection_digest(self):
        return policy.digest(dict(common=self.common,ecology=self.ecology,heads=self.heads,
            models=self.models,bundle=self.bundle,origins=self.origins,
            configuration=self.configuration,baselineSha256=self.baseline_sha))

    @staticmethod
    def row_digest(row):
        return policy.digest({k:v for k,v in row.items() if k != '_prepared'})

    @staticmethod
    def prepared_digest(value):
        return policy.digest(dict(dictionary=value.dictionary,action_count=value.action_count,
            baseline_index=value.baseline_index,**{name:getattr(value,name).tolist() for name in
                ('state_indices','state_values','action_indices','action_values')}))

    def phase_reader(self, directory, model_path, model, *, contract):
        phase = contract.label
        require(phase in mc.PHASES and contract == mc.CONTRACTS[phase]
            and Path(directory).resolve() == self.directory and Path(model_path).resolve() == Path(self.heads[phase]['path'])
            and model == self.models[phase], 'Triple fitting requires its exact original typed behavior view.')
        self.unchanged()
        require(self.rows[phase], 'An entirely empty phase cannot fit or publish a triple.')
        return self.rows[phase], dict(self.common,phase=phase,behaviorModelSha256=self.heads[phase]['sha256'])
