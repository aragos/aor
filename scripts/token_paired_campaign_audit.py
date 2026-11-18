"""Pure, bounded completed-evidence audit for the fixed two-iteration TOKEN2 experiment.

No numerical library, model forward, optimizer, native process or network call is imported.
Every archive is streamed once; only declared public sidecars and admitted labels are decoded.
"""
import hashlib
import gzip
import json
import math
from pathlib import Path
import random
from statistics import NormalDist
import tarfile
import time

import token_paired_campaign_plan as p
import token_paired_storage as storage
from token_paired_identity import ACTOR_PARAMETERS, actor_parameters_sha256


MODEL_PARAMETERS = ACTOR_PARAMETERS + ('valueWeights', 'valueBias')
FIT_RECIPE = dict(version='token-paired-expected-improvement-1', epochs=20, batchWorlds=64,
    learningRate=.0003, gradientNorm=.5, originalKlCoefficient=.1,
    maximumMeanOriginalKl=.02, maximumStateOriginalKl=.10,
    gainWeighting='equal-ready-root-horvitz-thompson-sum',
    anchorWeighting='equal-represented-world-equal-deduplicated-state',
    klOrientation='current-to-original', optimizer='fresh-adam-each-iteration',
    frozenParameters=['valueWeights', 'valueBias'])
FIT_FILES = ('identity.json', 'policy.json', 'checkpoint.json', 'history.json')


def digest(value):
    """The numerical owner's exact JSON boundary, including its default ASCII escaping."""
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'),
        allow_nan=False).encode()).hexdigest()


def decode(raw):
    def fields(items):
        value = {}
        for key, item in items:
            p.require(key not in value, 'Duplicate evidence field: '+key)
            value[key] = item
        return value
    return json.loads(raw, object_pairs_hook=fields,
        parse_constant=lambda value: p.require(False, 'Nonfinite evidence JSON: '+value))


def finite(value):
    return type(value) in (int, float) and math.isfinite(value)


def _stat(path):
    value = Path(path).stat()
    return value.st_dev,value.st_ino,value.st_size,value.st_mtime_ns,value.st_ctime_ns


class Pins:
    def __init__(self, deadline=None):
        self.values = {}; self.archives = {}; self.deadline = deadline

    def check_clock(self):
        p.require(self.deadline is None or time.time() < self.deadline,
            'The original read-only terminal audit deadline expired.')

    def pin(self, path, expected=None):
        self.check_clock(); path = Path(path).absolute()
        p.require(path.is_file() and not path.is_symlink(), 'Evidence must be an original regular file.')
        actual = p.file_sha(path)
        p.require((expected is None or actual == expected) and
            (str(path) not in self.values or self.values[str(path)] == actual),
            'An admitted evidence subject changed: '+str(path))
        self.values[str(path)] = actual
        return actual

    def subject(self, value):
        p.require(isinstance(value, dict) and set(value) == {'path', 'sha256'} and
            Path(value['path']).is_absolute(), 'Audit subjects require exact absolute paths and hashes.')
        self.pin(value['path'], p.sha(value['sha256']))
        return Path(value['path'])

    def read(self, path):
        self.pin(path)
        return decode(Path(path).read_bytes())

    def unchanged(self):
        for path, expected in self.values.items():
            self.check_clock()
            if path in self.archives:
                p.require(_stat(path) == self.archives[path], 'A streamed archive changed after audit.')
            else:
                p.require(p.file_sha(path) == expected, 'A compact audited subject changed.')


class _Hashed:
    def __init__(self, source):
        self.source = source; self.hasher = hashlib.sha256()

    def read(self, size=-1):
        value = self.source.read(size); self.hasher.update(value); return value


def stream_archive(path, receipt, pins, names):
    """Verify all bytes/member types once; decode only a prospective explicit name set."""
    path = Path(path).absolute(); pins.check_clock()
    p.require(path.name == receipt['archive'] and path.is_file() and not path.is_symlink(),
        'A chunk archive must retain its original basename and regular-file identity.')
    expected = receipt['files']; requested = set(names)
    p.require(requested <= set(expected), 'Every requested JSON member must be in the frozen inventory.')
    for name in expected: storage.safe(name)
    p.require(str(path) not in pins.archives, 'A terminal audit must stream each archive only once.')
    before = _stat(path); seen = set(); decoded = {}
    with path.open('rb') as file:
        stream = _Hashed(file)
        with tarfile.open(fileobj=stream, mode='r|gz') as archive:
            for member in archive:
                pins.check_clock()
                p.require(member.isfile() and member.name in expected and member.name not in seen,
                    'Archive contains a duplicate, undeclared or non-file member.')
                seen.add(member.name); hasher = hashlib.sha256(); size = 0; parts = []
                with archive.extractfile(member) as source:
                    for block in iter(lambda: source.read(1024*1024), b''):
                        hasher.update(block); size += len(block)
                        if member.name in requested: parts.append(block)
                row = expected[member.name]
                p.require(size == row['size'] and hasher.hexdigest() == row['sha256'],
                    'Archive member bytes changed before semantic decoding: '+member.name)
                if member.name in requested:
                    raw = b''.join(parts)
                    decoded[member.name] = decode(gzip.decompress(raw) if member.name.endswith('.json.gz') else raw)
        for _ in iter(lambda: stream.read(1024*1024), b''): pass
    p.require(seen == set(expected) and stream.hasher.hexdigest() == receipt['archiveSha256'] and
        _stat(path) == before, 'The complete original archive must survive its single verification pass.')
    pins.values[str(path)] = receipt['archiveSha256']; pins.archives[str(path)] = before
    return decoded


def _numbers(values, shape, label, minimum=None):
    if not shape:
        p.require(finite(values), label+' requires finite numerical values.')
        p.require(minimum is None or values >= minimum, label+' requires nonnegative second moments.')
        return
    p.require(isinstance(values, list) and len(values) == shape[0], label+' has an incorrect tensor shape.')
    for value in values: _numbers(value, shape[1:], label, minimum)


def model_metadata(model):
    p.require(model['schemaVersion'] == 3 and model['encoderVersion'] == 'aor-token-observation-2' and
        model['hiddenSize'] == 64 and model['baselineProbability'] == .97,
        'Only the explicit64-wide TOKEN2 residual model may complete this study.')
    for key, maximum in (('stateFeatureNames',8192), ('actionFeatureNames',512)):
        names = model[key]
        p.require(isinstance(names,list) and 1 <= len(names) <= maximum and names == sorted(set(names)) and
            all(isinstance(name,str) and 1 <= len(name) <= 160 for name in names),
            'A complete canonical model dictionary is required.')
    shapes = dict(stateWeights=(64,len(model['stateFeatureNames'])),stateBias=(64,),
        actionWeights=(64,len(model['actionFeatureNames'])),actionBias=(64,),actorWeights=(64,))
    for name in MODEL_PARAMETERS:
        shape = () if name == 'valueBias' else (64,) if name == 'valueWeights' else shapes[name]
        _numbers(model[name], () if not shape else (math.prod(shape),), 'Model '+name)
    fixed = {key:value for key,value in model.items() if key not in ACTOR_PARAMETERS}
    serialized = dict(model,**{name:[float(value) for value in model[name]] for name in MODEL_PARAMETERS[:-1]},
        valueBias=float(model['valueBias']))
    return dict(semanticSha256=digest(model), frozenSha256=digest(fixed), shapes=shapes,
        actorParametersSha256=actor_parameters_sha256(model), initialArtifactSha256=digest(serialized),
        dictionarySha256=digest({key:model[key] for key in
            ('encoderVersion','stateFeatureNames','actionFeatureNames')}))


def accepted_steps(active, seeds, history):
    p.require((active is None or len(active) == 512) and len(seeds) == 20,
        'Step audit requires all512 slots and20 epoch seeds when the full corpus is local.')
    result = 0
    for row in history[1:]:
        if active is None:
            count = p.integer(row['attemptedAdamSteps'],0,8,'Portable recorded minibatch count')
        else:
            slots = list(range(512)); random.Random(seeds[row['epoch']-1]).shuffle(slots)
            count = sum(any(active[index] for index in slots[start:start+64]) for start in range(0,512,64))
        p.require(row['attemptedAdamSteps'] == count,
            'Recorded minibatch steps must equal the original nonempty requested-world partition.')
        if row['accepted']: result += count
    return result


def optimizer(encoded, shapes, steps):
    p.require(set(encoded) == {'state','param_groups'} and len(encoded['param_groups']) == 1,
        'Each iteration has one fresh five-array Adam parameter group.')
    group = encoded['param_groups'][0]
    p.require(group['params'] == list(range(5)) and group['lr'] == .0003 and
        group['betas'] == [.9,.999] and group['eps'] == 1e-8 and group['weight_decay'] == 0 and
        group['amsgrad'] is False and group['maximize'] is False and
        not any(group.get(name,False) for name in ('capturable','differentiable','decoupled_weight_decay')) and
        all(group.get(name) is None for name in ('foreach','fused')),
        'The fixed fresh Adam recipe cannot inherit alternate optimizer semantics.')
    state = encoded['state']
    p.require(set(state) == (set(map(str,range(5))) if steps else set()),
        'Only the five actor arrays may have Adam slots; value parameters stay outside the optimizer.')
    for index, name in enumerate(ACTOR_PARAMETERS):
        if not steps: break
        slot = state[str(index)]
        p.require(set(slot) == {'step','exp_avg','exp_avg_sq'} and
            slot['step']['dtype'] == 'torch.float32' and slot['step']['tensor'] == steps,
            'Adam counters must restart at zero each iteration and exclude rolled-back epochs.')
        for field in ('exp_avg','exp_avg_sq'):
            value = slot[field]
            p.require(set(value) == {'tensor','dtype'} and value['dtype'] == 'torch.float64',
                'Actor Adam moment arrays must retain Float64 precision.')
            _numbers(value['tensor'],shapes[name],name+' '+field,0. if field=='exp_avg_sq' else None)


def transaction(directory, pins, context, input_model, original_model, coverage,
                anchor_sha, training_sha, seeds, active, previous, deadline=None):
    """Validate exact fixed fit metadata/Adam scope without optimizer arithmetic or inference."""
    directory = Path(directory); done = pins.read(directory/'finished.json')
    p.require(done['kind'] == 'token-paired-fit-complete-1' and done['complete'] is True and
        set(done['artifacts']) == set(FIT_FILES), 'Fit completion requires its exact four immutable artifacts.')
    for name,digest_value in done['artifacts'].items(): pins.pin(directory/name,digest_value)
    identity = pins.read(directory/'identity.json')
    p.require(identity['kind'] == 'token-paired-fit-identity-1' and identity == done['identity'] and
        identity['recipe'] == FIT_RECIPE and
        identity['context'] == context and identity['publicCoverage'] == coverage and
        identity['trainingSha256'] == training_sha and identity['anchorSha256'] == anchor_sha and
        identity['epochSeeds'] == seeds and identity['predecessor'] == previous and
        identity['inputModelCanonicalSha256'] == input_model['semanticSha256'] and
        identity['inputActorParametersSha256'] == input_model['actorParametersSha256'] and
        identity['frozenModelFieldsSha256'] == original_model['frozenSha256'],
        'The fit must consume the exact complete TRAIN labels/public anchors and fixed model chain.')
    p.require(deadline is None or identity['originalDeadline'] == deadline,
        'Each numerical transaction must retain the original shared science deadline.')
    p.require(set(identity['sourceSha256']) == {'token_paired_fit.py','token_paired_data.py',
        'token_paired_policy.py','token_paired_identity.py','purchase_policy.py'},
        'Only the original numerical source closure is admitted.')
    for name,expected in identity['sourceSha256'].items(): pins.pin(Path(__file__).with_name(name),expected)
    history = pins.read(directory/'history.json')
    attempted = p.integer(done['attemptedEpochs'],1,20,'Attempted epochs')
    accepted = p.integer(done['acceptedEpochs'],0,attempted,'Accepted epochs')
    p.require(len(history) == attempted+1 and [row['epoch'] for row in history] == list(range(attempted+1)) and
        history[0]['accepted'] is True and history[0]['actorSha256'] == input_model['initialArtifactSha256'] and
        all(row['accepted'] is True for row in history[1:accepted+1]) and
        ((accepted == attempted == 20 and done['stopReason'] == 'epoch-limit') or
         (accepted == attempted-1 and history[-1]['accepted'] is False and done['stopReason'] == 'original-kl-limit')),
        'Only the fixed20 epochs or the first whole-epoch KL rollback can complete a fit.')
    for row in history[1:]:
        mean, maximum = row['anchor']['mean'], row['anchor']['maximum']
        p.require(finite(mean) and finite(maximum) and row['accepted'] == (mean <= .02 and maximum <= .10),
            'Original-policy KL checks must use the exact fixed inequalities after each epoch.')
        p.sha(row['actorSha256'])
    steps = accepted_steps(active,seeds,history)
    output = model_metadata(pins.read(directory/'policy.json'))
    p.require(done['outputModelSha256'] == pins.pin(directory/'policy.json') and
        history[-1]['actorSha256'] == output['semanticSha256'] and
        history[accepted]['actorSha256'] == output['semanticSha256'] and
        output['frozenSha256'] == original_model['frozenSha256'] and output['shapes'] == input_model['shapes'] and
        done['actorChanged'] == (output['actorParametersSha256'] != input_model['actorParametersSha256']) and
        done['operationalPassed'] == (accepted > 0 and done['actorChanged']) and done['adamSteps'] == steps,
        'Published last-accepted actor, frozen value fields and fresh step counters must agree.')
    checkpoint = pins.read(directory/'checkpoint.json')
    p.require(checkpoint['kind'] == 'token-paired-adam-1' and
        digest(checkpoint['model']) == output['semanticSha256'] and
        checkpoint['acceptedEpochs'] == accepted and checkpoint['adamSteps'] == steps,
        'The checkpoint must bind the exact last accepted model and iteration-local optimizer state.')
    optimizer(checkpoint['optimizer'],output['shapes'],steps)
    return dict(done=done, metadata=output, completionSha256=pins.pin(directory/'finished.json'))


class _Public:
    """Minimal pure projection accepted by the campaign's label normalization owner."""
    def __init__(self, record):
        self.record = record; self.sha256 = digest(record)


def inspect_chunk(plan, subject, pins, *, labels=False, original_model=False):
    from token_paired_corpus import normalized_public, normalized_anchor, normalized_labels
    path = pins.subject(subject); receipt = pins.read(path)
    p.require(receipt['kind'] == 'token-paired-chunk-receipt-1' and
        receipt['host'] == p.owner(receipt['chunk']), 'A corpus shard must have its exact original owner.')
    chunk = receipt['chunk']; iteration = receipt['iteration']; p.integer(iteration,1,2,'Chunk iteration')
    indices = list(p.indices(chunk)); rows = receipt['origins']
    p.require([row['globalIndex'] for row in rows] == indices,
        'A chunk must retain all64 original global origin indices exactly once.')
    names = {'prepared.json','finished.json','source.json','dictionary.json'}
    names.update(row['public']['file'] for row in rows)
    if labels: names.update(row['labels']['file'] for row in rows)
    if original_model: names.add('original-actor.json')
    members = stream_archive(path.with_name(receipt['archive']),receipt,pins,names)
    native = members['prepared.json']; finished = members['finished.json']; source = members['source.json']
    storage.prepared(plan,native,source,receipt['sourceSha256'],receipt['files'],
        chunk,iteration,receipt['currentActorSha256'])
    p.require(receipt['preparedSha256'] == receipt['files']['prepared.json']['sha256'] and
        receipt['finishedSha256'] == receipt['files']['finished.json']['sha256'] and
        receipt['signature'] == native['signature'] == finished['signature'] and
        receipt['scopeSha256'] == native['scopeSha256'] == finished['scopeSha256'] and
        receipt['scienceSha256'] == native['scienceSha256'] == finished['scienceSha256'] and
        receipt['scienceIdentity'] == native['scienceIdentity'] and
        finished['kind'] == 'token-paired-finished-1' and finished['complete'] is True and
        finished['iteration'] == iteration and finished['range'] == native['range'] == receipt['range'] and
        finished['origins'] == rows, 'Compact chunk receipts must bind the exact native prepared/completed subjects.')
    public = {}; targets = {}; anchors = {}; statuses = []; tails = 0
    for row in rows:
        index = row['globalIndex']
        for key,name in (('origin',f'origin-{index}.json.gz'),('public',f'public-{index}.json'),
                         ('labels',f'labels-{index}.json')):
            p.require(row[key]['file'] == name and receipt['files'][name]['sha256'] == row[key]['sha256'],
                'Complete original/public/label subjects must retain their original member bytes.')
        value = members[row['public']['file']]
        status = storage.public_record(value,row,native,plan,index); statuses.append(status)
        normalized = normalized_public(value); public[index] = normalized
        count = 2*len(normalized['proposedIndices']) if status == 'READY' else 0
        p.require(row['tailGames'] == count and row['anchorRows'] == len(value['anchors']),
            'Original tail/anchor counts cannot change during archive retention.')
        tails += count
        if iteration == 1 and index < 512: anchors[index] = normalized_anchor(value)
        if labels:
            targets[index] = normalized_labels(members[row['labels']['file']],value,_Public(normalized))
    expected = dict(backboneGames=64,tailGames=tails,scientificTerminalGames=64+tails,
        readyRoots=statuses.count('READY'),singletonRoots=statuses.count('SINGLETON'),
        missingRoots=statuses.count('MISSING'),capturedRoots=64-statuses.count('MISSING'))
    p.require(all(finished[key] == value for key,value in expected.items()) and
        all(receipt[key] == expected[key] for key in
            ('scientificTerminalGames','readyRoots','singletonRoots','missingRoots')),
        'Native and compact counts must independently match all original public origins.')
    p.require([row['globalIndex'] for row in receipt['replays']] == list(p.replays(chunk)) and
        receipt['historicalReproductions'] == len(p.replays(chunk)),
        'Only the prescribed whole-backbone reproductions may be charged.')
    for replay in receipt['replays']:
        index = replay['globalIndex']
        p.require(replay['path'] == f'replay-{index}/replay-{index}.json' and
            replay['sha256'] == receipt['files'][replay['path']]['sha256'] and
            replay['originalSha256'] == rows[index-indices[0]]['origin']['sha256'] and
            replay['scientificTerminalGames'] == 1,
            'Every reproduction must retain its native-verified receipt and original origin subject.')
    return dict(receipt=receipt,subject=subject,public=public,labels=targets,anchors=anchors,
        dictionary=members['dictionary.json'],originalModel=members.get('original-actor.json'),tails=tails)


def corpus(plan, path, pins, *, labels, train_labels=True, original_model=False):
    record = pins.read(path)
    p.require(record['kind'] == 'token-paired-corpus-1' and record['complete'] is True and
        record['protocolDigest'] == plan.sha256 and record['origins'] == 640 and
        record['trainOrigins'] == 512 and record['validationOrigins'] == 128 and
        len(record['chunks']) == 10, 'A corpus requires all640 original worlds and ten immutable shards.')
    p.integer(record['iteration'],1,2,'Corpus iteration')
    # Order source subjects by their compact receipts, never by arrival order.
    compact = [(value,pins.read(pins.subject(value))) for value in record['chunks']]
    compact.sort(key=lambda item:item[1]['chunk'])
    p.require([row['chunk'] for _,row in compact] == list(range(10)) and
        all(row['iteration'] == record['iteration'] for _,row in compact),
        'Exact global chunk coverage must precede any TRAIN or validation label decoding.')
    subjects = [subject for subject,_ in compact]
    chunks = [inspect_chunk(plan,subject,pins,labels=labels or (train_labels and index<8),
        original_model=original_model and index==0) for index,subject in enumerate(subjects)]
    rows = [chunk['receipt'] for chunk in chunks]
    p.require([row['chunk'] for row in rows] == list(range(10)) and
        all(row['iteration'] == record['iteration'] for row in rows),
        'Each global shard must belong to this same complete iteration.')
    for key in ('scienceSha256','scopeSha256','scienceIdentity','currentActorSha256','originalActorSha256'):
        p.require(all(row[key] == record[key] for row in rows), 'Cross-host corpus subject changed: '+key)
    counts = {split:sum(row['readyRoots'] for row in rows if
        ('TRAIN' if row['chunk']<8 else 'VALIDATION') == split) for split in ('TRAIN','VALIDATION')}
    p.require(record['ready'] == counts and record['exposurePassed'] ==
        (counts['TRAIN'] >= 256 and counts['VALIDATION'] >= 64) and
        record['scientificTerminalGames'] == sum(row['scientificTerminalGames'] for row in rows) and
        record['historicalReproductions'] == sum(row['historicalReproductions'] for row in rows) == 4,
        'Complete corpus coverage/counts must be derived from all independent public origins.')
    return dict(record=record,subject=dict(path=str(Path(path).absolute()),sha256=pins.pin(path)),chunks=chunks,
        public={i:value for chunk in chunks for i,value in chunk['public'].items()},
        labels={i:value for chunk in chunks for i,value in chunk['labels'].items()},
        anchors={i:value for chunk in chunks for i,value in chunk['anchors'].items()},
        dictionary=chunks[0]['dictionary'],originalModel=chunks[0]['originalModel'])


def sealed_probabilities(path, done, completion_sha, pins):
    seal = pins.read(path); context = done['identity']['context']
    p.require(seal['kind'] == 'token-paired-validation-probabilities-1' and seal['complete'] is True and
        seal['semanticLabelsDecoded'] is False and seal['context'] == context and
        seal['fitFinishedSha256'] == completion_sha and seal['modelSha256'] == done['outputModelSha256'] and
        seal['originalModelSha256'] == context['original_model_sha256'] and
        seal['currentModelSha256'] == context['current_model_sha256'] and len(seal['rows']) == 128 and
        [row['index'] for row in seal['rows']] == list(range(512,640)) and
        seal['counts']['READY'] == done['identity']['publicCoverage']['validationReady'] >= 64,
        'A complete public probability seal must bind its sole completed actor and all128 original worlds.')
    p.require(seal['counts'] == {status:sum(row['status'] == status for row in seal['rows'])
        for status in ('READY','SINGLETON','MISSING')}, 'Sealed public support counts must be exact.')
    return seal


def _preflight_fits(run, count, pins):
    """Only compact transaction subjects; establishes the VAL barrier before archive labels."""
    import token_paired_admission as admission
    result = []
    for iteration in range(1,count+1):
        directory = Path(run)/'iterations'/f'iteration-{iteration}'
        done = admission.fit_receipt(directory/'fit')
        for name in (*FIT_FILES,'finished.json'): pins.pin(directory/'fit'/name)
        p.require(done['identity']['context']['iteration'] == iteration and
            done['identity']['recipe'] == FIT_RECIPE,
            'Both probability seals require their exact fixed iteration identities.')
        if iteration == 1:
            p.require(done['identity']['predecessor'] is None, 'Iteration1 cannot inherit another fitted actor.')
        else:
            old = result[-1]
            p.require(done['identity']['predecessor'] == dict(finishedSha256=old['sha'],
                modelSha256=old['done']['outputModelSha256']) and
                done['identity']['context']['current_model_sha256'] == old['done']['outputModelSha256'] and
                all(done['identity']['context'][key] == old['done']['identity']['context'][key] for key in
                    ('scope_sha256','original_model_sha256','dictionary_sha256','dictionary_semantic_sha256')) and
                done['identity']['anchorSha256'] == old['done']['identity']['anchorSha256'],
                'The sole iteration2 actor must consume the exact completed iteration1 output.')
        completion_sha = pins.pin(directory/'fit/finished.json')
        seal = sealed_probabilities(directory/'validation.json',done,completion_sha,pins)
        result.append(dict(done=done,sha=completion_sha,seal=seal))
    return result


def _context(plan, value, original, current_sha):
    record = value['record']; iteration = record['iteration']
    return dict(iteration=iteration,source_sha256=value['subject']['sha256'],
        scope_sha256=record['scopeSha256'],
        request_bank_sha256=p.digest(plan.record['iterations'][iteration-1]['origins']),
        dictionary_sha256=plan.record['dictionarySha256'],
        dictionary_semantic_sha256=original['dictionarySha256'],
        original_model_sha256=plan.record['originalActorSha256'],current_model_sha256=current_sha)


def _training_subjects(value, original_anchors):
    anchors = [original_anchors[index] for index in range(512)]
    anchor_sha = digest([digest(anchor) for anchor in anchors])
    roots = [value['public'][index] for index in range(512)]
    labels = [value['labels'][index] for index in range(512)]
    active = []
    for index, root in enumerate(roots):
        group = anchors[index]['observations']
        if value['record']['iteration'] == 1 and root['status'] != 'MISSING':
            matches = [row for row in group if (row['decisionNumber'],row['batchStartNumber']) ==
                (root['decisionNumber'],root['batchStartNumber'])]
            p.require(len(matches) == 1 and all(matches[0][key] == root[key] for key in
                ('observation','originalProbabilities')), 'The original root must deduplicate its exact backbone anchor.')
        active.append(bool(group) or (value['record']['iteration'] == 2 and root['status'] != 'MISSING'))
    return anchor_sha, roots, labels, active


def _effect(path, seal_path, seal, value, pins):
    report = pins.read(path); rows = []
    for item in seal['rows']:
        index = item['index']; public = value['public'][index]; label = value['labels'][index]
        p.require(item['publicSha256'] == digest(public) and item['requestSha256'] == public['requestSha256'] and
            all(item[key] == public[key] for key in ('status','futureSeeds','proposedIndices',
                'inclusionProbability','currentProbabilities')),
            'Every validation result must retain its original sealed public input and proposal streams.')
        if public['status'] != 'READY':
            p.require(label is None, 'Missing/singleton validation rows have no fabricated effects.')
            rows.append(dict(index=index,status=public['status'],gain=None)); continue
        probabilities = item['probabilities']; previous = public['currentProbabilities']
        p.require(len(probabilities) == len(previous) and all(finite(x) and 0 < x <= 1 for x in probabilities) and
            abs(math.fsum(probabilities)-1) <= 2e-12, 'Sealed probabilities retain full offered support.')
        rewards = [action['rewards'] for action in label['actions']]
        estimates = [math.fsum((probabilities[action]-previous[action])*(reward[stream]-rewards[0][stream])/
            public['inclusionProbability'] for action,reward in zip(public['proposedIndices'][1:],rewards[1:]))
            for stream in range(2)]
        rows.append(dict(index=index,status='READY',pairedFutureGains=estimates,gain=math.fsum(estimates)/2))
    values = [row['gain'] for row in rows if row['gain'] is not None]
    mean = math.fsum(values)/len(values)
    variance = math.fsum((value-mean)**2 for value in values)/(len(values)-1)
    margin = NormalDist().inv_cdf(.975)*math.sqrt(variance/len(values))
    expected = dict(kind='token-paired-validation-effect-1',
        probabilitySeal=dict(path=str(Path(seal_path).resolve()),sha256=pins.pin(seal_path)),
        context=seal['context'],modelSha256=seal['modelSha256'],counts=seal['counts'],
        estimate=dict(mean=mean,lower=mean-margin,upper=mean+margin,origins=len(values),
            interval='two-sided-normal-95-independent-origin'),perOrigin=rows,
        optimizerSteps=0,modelForwards=0,selectedCheckpoint=False)
    p.require(report == expected, 'The fixed validation statistic must recompute exactly from every original paired sibling.')
    return dict(path=str(Path(path).absolute()),sha256=pins.pin(path))


def _numeric_stage(path, fit_result, value, seal, protocol_sha, pins):
    stage = pins.read(path); done = fit_result['done']
    expected = dict(kind='token-paired-numeric-stage-1',iteration=value['record']['iteration'],complete=True,
        protocolSha256=protocol_sha,corpus=value['subject'],
        fitCompletion=dict(path=str((Path(path).parent/'fit/finished.json').absolute()),sha256=fit_result['completionSha256']),
        validationProbabilitySeal=seal,operationalPassed=done['operationalPassed'],
        acceptedEpochs=done['acceptedEpochs'],actorChanged=done['actorChanged'],outputModelSha256=done['outputModelSha256'])
    p.require(stage == expected, 'The numeric-stage receipt must retain exact fit/corpus/seal subjects and health.')


def _desktop(plan, protocol_sha, run, terminal, pins):
    import token_paired_admission as admission
    admitted = p.integer(terminal['completedIterations'],0,2,'Admitted changed iterations')
    full = terminal['status'] == 'completed'
    p.require((full and admitted == 2 and terminal['reason'] == 'two-fixed-iterations-and-screen') or
        (not full and admitted < 2 and terminal['reason'] in
            ('public-exposure-gate','no-accepted-actor-change')), 'Only the two prescribed scientific stops are ordinary stops.')
    barriers = _preflight_fits(run,admitted,pins)
    iterations = 2 if full else admitted+1
    original = None; current = None; current_sha = plan.record['originalActorSha256']; original_anchors = None
    previous = None; backbones = tails = reproductions = 0
    for iteration in range(1,iterations+1):
        directory = run/'iterations'/f'iteration-{iteration}'
        has_fit = iteration <= admitted or terminal['reason'] == 'no-accepted-actor-change'
        value = corpus(plan,directory/'corpus.json',pins,labels=full,train_labels=has_fit,original_model=iteration==1)
        record = value['record']
        p.require(record['iteration'] == iteration and record['currentActorSha256'] == current_sha,
            'A complete iteration cannot switch its frozen current-policy continuation actor.')
        backbones += 640; tails += record['scientificTerminalGames']-640; reproductions += 4
        if original is None:
            original = model_metadata(value['originalModel']); current = original; original_anchors = value['anchors']
        p.require(digest(value['dictionary']) == original['dictionarySha256'], 'Native and numerical dictionaries must have exact meaning.')
        if not has_fit:
            p.require(not record['exposurePassed'] and not (directory/'fit').exists() and
                not (directory/'numeric-complete.json').exists(), 'Exposure stops must occur before any actor fit.')
            break
        p.require(record['exposurePassed'], 'A numerical fit requires its prior public coverage gate.')
        context = _context(plan,value,original,current_sha)
        anchor_sha, roots, labels, active = _training_subjects(value,original_anchors)
        training_sha = digest(dict(context=context,roots=[digest(root) for root in roots],
            labels=[None if label is None else [action['rewards'] for action in label['actions']] for label in labels],
            anchors=anchor_sha,validationReadyCount=record['ready']['VALIDATION']))
        checked = transaction(directory/'fit',pins,context,current,original,
            dict(trainingReady=record['ready']['TRAIN'],validationReady=record['ready']['VALIDATION']),
            anchor_sha,training_sha,plan.record['iterations'][iteration-1]['shuffleSeeds'],active,previous,
            terminal['clock']['deadlineEpoch'])
        done = checked['done']; seal_subject = None
        if iteration <= admitted:
            p.require(done['operationalPassed'], 'Each admitted iteration must have accepted actor movement.')
            seal = barriers[iteration-1]['seal']; seal_subject = dict(path=str((directory/'validation.json').absolute()),
                sha256=pins.pin(directory/'validation.json'))
            portable = run/'portable/iterations'/f'iteration-{iteration}'/'admission.json'
            admitted_value = portable_iteration(portable,pins,protocol_sha)
            p.require(admitted_value['outputActor']['sha256'] == done['outputModelSha256'] and
                admitted_value['fitCompletion']['sha256'] == checked['completionSha256'],
                'Portable publication must preserve the sole completed iteration actor.')
            if full: _effect(directory/'validation-effect.json',directory/'validation.json',seal,value,pins)
        else:
            p.require(not done['operationalPassed'] and terminal['reason'] == 'no-accepted-actor-change' and
                not (directory/'validation.json').exists(), 'A no-change stop cannot publish validation probabilities.')
        _numeric_stage(directory/'numeric-complete.json',checked,value,seal_subject,protocol_sha,pins)
        current, current_sha = checked['metadata'], done['outputModelSha256']
        previous = dict(finishedSha256=checked['completionSha256'],modelSha256=current_sha)
    if not full:
        p.require(not (run/'screen-complete.json').exists() and not (run/'portable/final-admission.json').exists() and
            not any((run/'iterations'/f'iteration-{i}'/'validation-effect.json').exists() for i in (1,2)) and
            not (run/'iterations'/f'iteration-{iterations+1}').exists(),
            'An operational stop permits no effects, shortened candidate, screen or later iteration.')
    return dict(backbones=backbones,pairedTails=tails,backboneReproductions=reproductions,
        completedIterations=admitted,fullTrainingRecomputed=True)


def portable_iteration(path, pins, protocol_sha):
    import token_paired_admission as admission
    path = Path(path); value = admission.verify_iteration(path); pins.pin(path)
    p.require(value['protocolSha256'] == protocol_sha,
        'Portable admission must bind this exact original protocol.')
    pins.pin(path.parents[2]/'protocol.json',protocol_sha)
    for name in ('currentActor','outputActor','originalActor','corpus','fitCompletion',
                 'validationProbabilitySeal','previousAdmission'):
        if value[name] is not None:
            pins.pin(admission.resolve(value[name],path),value[name]['sha256'])
    directory = admission.resolve(value['fitCompletion'],path).parent
    for name in (*FIT_FILES,'finished.json'): pins.pin(directory/name)
    return value


def publication(directory, stage, protocol_sha, pins):
    """Copied publication subjects stay local; embedded remote paths are provenance only."""
    directory = Path(directory); record = pins.read(directory/'publication.json')
    p.require(record['kind'] == 'token-paired-publication-1' and record['stage'] == stage and
        record['archive'] == 'publication.tar.gz' and
        record['identity']['protocolSha256'] == protocol_sha,
        'The Mac worker must consume the exact fixed portable stage publication.')
    stream_archive(directory/record['archive'],record,pins,())
    content = directory/'content'
    actual = {str(path.relative_to(content)) for path in content.rglob('*') if path.is_file()}
    p.require(actual == set(record['files']), 'Portable extraction must preserve the exact complete inventory.')
    for name, subject in record['files'].items():
        path = content/name
        p.require(path.stat().st_size == subject['size'], 'Portable file size changed.')
        pins.pin(path,subject['sha256'])
    pins.pin(content/'protocol.json',protocol_sha)
    return content, record


def handoff_receipt(path, chunk, iteration, local_subject, archive_sha, pins, *, screen=False):
    """Remote absolute paths are metadata; verify the exact original transferred byte subjects."""
    import token_paired_handoff as handoff
    value = pins.read(path)
    p.require(set(value) == {'kind','tag','receipt','archive'} and
        value['kind'] == 'token-paired-handoff-1' and
        value['tag'] == handoff.tag(iteration,chunk,screen) and
        value['receipt']['sha256'] == local_subject['sha256'] and
        value['archive']['sha256'] == archive_sha and
        Path(value['receipt']['path']).is_absolute() and Path(value['archive']['path']).is_absolute() and
        Path(value['receipt']['path']).name == 'receipt.json' and
        Path(value['archive']['path']).name == 'evidence.tar.gz' and
        Path(value['receipt']['path']).parent == Path(value['archive']['path']).parent and
        Path(value['archive']['path']).parent.name == value['tag'],
        'Every Mac-owned shard requires the exact receiver acceptance of its original bytes.')


def peer_stop(run, terminal, pins):
    path = pins.subject(terminal['peerTerminal'])
    p.require(path.resolve() == (run/'peer-terminal.json').resolve() and
        terminal['peerTerminal']['sha256'] == terminal['originalPeerSubject']['sha256'] and
        Path(terminal['originalPeerSubject']['path']).is_absolute(),
        'The local peer-stop copy must preserve the exact original Desktop terminal subject.')
    peer = pins.read(path)
    p.require(peer['kind'] == 'token-paired-terminal-1' and peer['host'] == 'DESKTOP' and
        peer['status'] == 'stopped' and peer['reason'] in
            ('public-exposure-gate','no-accepted-actor-change') and
        peer['completedIterations'] == terminal['completedIterations'] in (0,1) and
        peer['clock'] == terminal['clock'] and
        peer['clock']['startedEpoch'] <= peer['updatedEpoch'] <= terminal['updatedEpoch'],
        'Only an original-clock ordinary coordinator stop can propagate to the Mac worker.')


def _mac(plan, protocol_sha, run, terminal, pins):
    full = terminal['status'] == 'completed'
    admitted = p.integer(terminal['completedIterations'],0,2,'Portable completed iterations')
    p.require((full and admitted == 2 and terminal['reason'] == 'all-fixed-mac-shards-delivered') or
        (not full and admitted < 2 and terminal['reason'] == 'coordinator-operational-stop'),
        'A Mac terminal must be full delivery or the exact propagated coordinator stop.')
    if not full: peer_stop(run,terminal,pins)
    iterations = 2 if full else admitted+1
    copied = {}; subjects = {}; original = None; current = None; previous = None
    first_publication = None
    if iterations == 2:
        first_publication, first_record = publication(run/'downloads/iteration-2','iteration-2',protocol_sha,pins)
    if full:
        directory, record = publication(run/'downloads/screen','screen',protocol_sha,pins)
    elif admitted:
        directory = first_publication
    if admitted:
        original_path = directory/'original-policy.json'
        pins.pin(original_path,plan.record['originalActorSha256'])
        original = model_metadata(pins.read(original_path)); current = original
        for iteration in range(1,admitted+1):
            base = directory/'iterations'/f'iteration-{iteration}'
            value = portable_iteration(base/'admission.json',pins,protocol_sha)
            copied[iteration] = value
            metadata = pins.read(base/'corpus.json'); done = pins.read(base/'fit/finished.json')
            context = _context(plan,dict(record=metadata,subject=dict(sha256=pins.pin(base/'corpus.json'))),
                original,plan.record['originalActorSha256'] if iteration==1 else copied[iteration-1]['outputActor']['sha256'])
            identity = done['identity']
            checked = transaction(base/'fit',pins,context,current,original,
                dict(trainingReady=metadata['ready']['TRAIN'],validationReady=metadata['ready']['VALIDATION']),
                identity['anchorSha256'],identity['trainingSha256'],
                plan.record['iterations'][iteration-1]['shuffleSeeds'],None,previous,terminal['clock']['deadlineEpoch'])
            p.sha(identity['anchorSha256']); p.sha(identity['trainingSha256'])
            p.require(done['operationalPassed'] and metadata['complete'] is True and metadata['exposurePassed'] is True and
                metadata['protocolDigest'] == plan.sha256 and metadata['origins'] == 640 and
                len(metadata['chunks']) == 10, 'The portable chain needs the complete independently audited global corpus.')
            sealed_probabilities(base/'validation.json',done,checked['completionSha256'],pins)
            current = checked['metadata']
            previous = dict(finishedSha256=checked['completionSha256'],modelSha256=done['outputModelSha256'])
            subjects[iteration] = dict(context=context,metadata=metadata)
        p.require(first_record['identity'] == dict(protocolSha256=protocol_sha,
            actorSha256=copied[1]['outputActor']['sha256'],scopeSha256=copied[1]['scopeSha256']),
            'The original next-iteration publication must bind the first completed actor and scope.')
        if full:
            first = portable_iteration(first_publication/'iterations/iteration-1/admission.json',pins,protocol_sha)
            p.require(first == copied[1] and record['identity'] == dict(protocolSha256=protocol_sha,
                actorSha256=copied[2]['outputActor']['sha256'],scopeSha256=copied[2]['scopeSha256']),
                'The final portable chain must preserve the same first actor actually used by Mac.')
    backbones = tails = reproductions = 0; scope = None
    for iteration in range(1,iterations+1):
        root = run/'iterations'/f'iteration-{iteration}'
        p.require(not (root/'fit').exists() and not (root/'validation-effect.json').exists(),
            'Mac owns no numerical update or aggregate validation effect analysis.')
        expected_actor = plan.record['originalActorSha256'] if iteration==1 else copied[1]['outputActor']['sha256']
        p.require({path.name for path in (root/'chunks').iterdir()} ==
            {f'chunk-{chunk}' for chunk in p.COLLECTION_CHUNKS['MAC']},
            'Mac must retain exactly its four original assigned collection chunks.')
        for chunk in p.COLLECTION_CHUNKS['MAC']:
            path = root/'chunks'/f'chunk-{chunk}'/'evidence.tar.gz.json'
            subject = dict(path=str(path),sha256=pins.pin(path))
            value = inspect_chunk(plan,subject,pins,labels=False)
            receipt = value['receipt']
            if scope is None: scope = receipt['scopeSha256']
            p.require(receipt['iteration'] == iteration and receipt['chunk'] == chunk and
                receipt['currentActorSha256'] == expected_actor and receipt['scopeSha256'] == scope,
                'Mac collection must preserve its exact original/current actor and stable shared scope.')
            if iteration in subjects:
                metadata = subjects[iteration]['metadata']
                p.require(receipt['scienceSha256'] == metadata['scienceSha256'] and
                    receipt['scopeSha256'] == metadata['scopeSha256'] and
                    any(item['sha256'] == subject['sha256'] for item in metadata['chunks']),
                    'The admitted global corpus must include each exact locally produced Mac shard.')
            handoff_receipt(path.parent/'handoff.json',chunk,iteration,subject,receipt['archiveSha256'],pins)
            backbones += 64; tails += value['tails']; reproductions += len(p.replays(chunk))
    if not full:
        p.require(not (run/'downloads/screen').exists() and not (run/'screen-complete.json').exists() and
            not (run/'iterations'/f'iteration-{iterations+1}').exists(),
            'A propagated operational stop must never become a partial candidate or screen.')
    return dict(backbones=backbones,pairedTails=tails,backboneReproductions=reproductions,
        completedIterations=admitted,fullTrainingRecomputed=False)


def verify(protocol_subject, run):
    """Audit one original host terminal; reports subjects/counts, never partial scientific effects."""
    pins = Pins(); protocol_path = pins.subject(protocol_subject)
    plan = p.Plan.from_record(pins.read(protocol_path)); run = Path(run).resolve()
    terminal = pins.read(run/'terminal.json'); clock = pins.read(run/'clock.json')
    p.require(terminal['kind'] == 'token-paired-terminal-1' and terminal['host'] in p.HOSTS and
        terminal['status'] in ('completed','stopped','failed') and terminal['clock'] == clock and
        clock['kind'] == 'token-paired-clock-1' and clock['protocolSha256'] == protocol_subject['sha256'],
        'Terminal evidence must bind the original host, shared protocol and unchanged science clock.')
    original = p.Clock(clock['startedEpoch'],clock['deadlineEpoch'])
    p.require(finite(terminal['updatedEpoch']) and terminal['updatedEpoch'] >= original.start,
        'The terminal cannot precede the original attempt.')
    pins.deadline = original.audit_deadline(terminal['updatedEpoch']); pins.check_clock()
    owner = pins.read(run/'worker-owner.json')
    p.require(set(owner) == {'kind','pid','processGroup'} and
        owner['kind'] == 'token-paired-worker-owner-1' and
        type(owner['pid']) is int and owner['pid'] > 1 and
        type(owner['processGroup']) is int and owner['processGroup'] > 1,
        'Terminal provenance requires the original typed worker PID and process group.')
    execution = dict(newGames=0,modelForwards=0,optimizerSteps=0)
    if terminal['status'] == 'failed':
        pins.unchanged()
        return dict(kind='token-paired-campaign-audit-1',passed=False,complete=False,status='failed',
            host=terminal['host'],counts=None,eligibleFinalActor=None,nominee=None,
            artifactPins=pins.values,execution=execution)
    p.require(terminal['updatedEpoch'] <= original.deadline,
        'Ordinary completion or stopping must remain within the original science clock.')
    counts = (_desktop if terminal['host']=='DESKTOP' else _mac)(
        plan,protocol_subject['sha256'],run,terminal,pins)
    complete = terminal['status'] == 'completed'
    screen = dict(worlds=0,games=0,reproductions=0,historicalControls=0,eligibleActor=None,nominee=None)
    if complete:
        import token_paired_screen
        screen = token_paired_screen.audit_completed(plan,run,terminal['host'],
            read_json=pins.read,check_subject=pins.subject,
            stream_archive=lambda path,receipt,names:stream_archive(path,receipt,pins,names))
        expected = (1024,3072,12,2) if terminal['host']=='DESKTOP' else (384,1152,6,0)
        p.require(tuple(screen[key] for key in ('worlds','games','reproductions','historicalControls')) == expected,
            'The final screen must preserve every fixed original world, role and replay.')
    counts.update(origins=counts['backbones'],screenWorlds=screen['worlds'],screenGames=screen['games'],
        screenReproductions=screen['reproductions'],historicalControls=screen['historicalControls'])
    counts['scientificTerminals'] = counts['backbones']+counts['pairedTails']+counts['screenGames']
    counts['terminalExecutions'] = counts['scientificTerminals']+counts['backboneReproductions']+\
        counts['screenReproductions']+counts['historicalControls']
    p.require(counts['terminalExecutions'] <= plan.record['maximumTerminalExecutions'],
        'No terminal audit can authorize executions beyond the original finite budget.')
    pins.unchanged()
    return dict(kind='token-paired-campaign-audit-1',passed=True,complete=complete,status=terminal['status'],
        host=terminal['host'],counts=counts,eligibleFinalActor=screen['eligibleActor'],nominee=screen['nominee'],
        artifactPins=pins.values,execution=execution)
