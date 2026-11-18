"""Pure native warm evidence admission and TRAIN-only numerical projection.

Collection admission hashes opaque authority origins and label sidecars, opening public records
only. The separate fitting projection opens TRAIN labels after the complete corpus is admitted.
No held-out diagnostic labels are decoded by this owner.
"""
import math
from pathlib import Path

import expansion_training_evidence as evidence
import token_paired_campaign_plan as shared
import token_paired_storage as storage
import token_anchored_plan as protocol_owner
import token_anchor_distribution as distribution
from token_anchored_recipe import RECIPE


def normalized_public(value):
    root = value['publicRoot']
    result = dict(index=value['request']['globalIndex'], split=value['request']['split'],
        status=value['status'], requestSha256=shared.digest({key: value[key] for key in
            ('signature', 'scopeSha256', 'scienceSha256', 'round', 'request', 'streams', 'originSha256')}),
        decisionNumber=None, batchStartNumber=None, observation=None, currentProbabilities=None,
        originalProbabilities=None, sampledIndex=None, proposedIndices=None, futureSeeds=value['streams']['future'],
        anchor94Probabilities=None, referenceIndex=None, rawReferenceIndex=None,
        mandatoryIndices=None, inclusionProbabilities=None)
    if root is not None:
        result.update(decisionNumber=root['number'], batchStartNumber=root['batchStartNumber'],
            sampledIndex=root['sampledCommandIndex'], **{key: root[key] for key in
            ('observation', 'currentProbabilities', 'originalProbabilities', 'proposedIndices',
                'anchor94Probabilities', 'referenceIndex', 'rawReferenceIndex', 'mandatoryIndices',
                'inclusionProbabilities')})
    return result


def native_chunk(plan, source_path, native, round_number, chunk, host):
    """Metadata/public-only admission; exact original chunk membership is independent of write order."""
    source_path, native = Path(source_path), Path(native)
    source = shared.read(source_path); prepared = shared.read(native/'prepared.json')
    finished = shared.read(native/'finished.json'); files = storage.inventory(native)
    row = plan.record['rounds'][round_number-1]
    wanted = list(protocol_owner.indices(len(row['origins']), plan.record['chunkSize'], chunk))
    interval = dict(globalStart=wanted[0], count=len(wanted))
    shared.require(plan.owner(chunk) == host and source['round'] == round_number and
        source['globalStart'] == wanted[0] and source['count'] == len(wanted) and
        source['trainCount'] == row['trainCount'] and source['proposalBudget'] == plan.record['proposalBudget'] and
        source['origins'] == row['origins'] and source['replayIndices'] == row['replayIndices'] and
        source['opponentStyle'] == row['opponentStyle'] and source['adaptiveOpponent'] == row.get('adaptiveOpponent'),
        'Warm native admission requires the exact prospective source, global shard and original host.')
    shared.require(prepared['kind'] == 'token-anchored-paired-collect-1' and prepared['round'] == round_number and
        prepared['range'] == interval and prepared['source']['sha256'] == shared.file_sha(source_path) and
        prepared['sourceSha256'] == shared.file_sha(source_path) and prepared['trainCount'] == row['trainCount'] and
        prepared['originCount'] == len(row['origins']) and prepared['proposalBudget'] == plan.record['proposalBudget'] and
        prepared['anchor']['sha256'] == plan.record['anchorDescriptorSha256'] and
        prepared['origins'] == row['origins'] and prepared['requests'] ==
        [protocol_owner.request(index, row['trainCount']) for index in wanted] and
        prepared['replayIndices'] == row['replayIndices'] and prepared['ownedReplayIndices'] ==
        [index for index in row['replayIndices'] if index in wanted] and prepared['fits'] == 0,
        'Original warm preparation must bind every whole-bank stream, request and owned reproduction.')
    for key in ('currentActor', 'originalActor'):
        shared.require(prepared[key]['sha256'] == source[key]['sha256'] and
            prepared[key]['externalImmutable'] is True and 'snapshot' not in prepared[key],
            'Warm actor subjects remain external immutable inputs, never fictitious per-shard model copies.')
    shared.require(source['originalActor']['sha256'] == plan.record['originalActorSha256'] and
        source['anchor']['sha256'] == plan.record['anchorDescriptorSha256'] and
        prepared['dictionary']['sha256'] == plan.record['dictionarySha256'],
        'Native warm actors, dictionary and frozen anchor must match the original campaign identity.')
    shared.require(prepared['snapshots'] and all(name in files and files[name]['sha256'] == digest
        for name, digest in prepared['snapshots'].items()) and files['source.json']['sha256'] ==
        shared.file_sha(source_path) and files[prepared['dictionary']['path']]['sha256'] ==
        prepared['dictionary']['sha256'], 'Every native descriptor/source/dictionary snapshot must remain exact.')
    for key in ('signature', 'scopeSha256', 'scienceSha256'):
        shared.sha(prepared[key])
    shared.require(finished['kind'] == 'token-anchored-paired-finished-1' and finished['complete'] is True and
        finished['round'] == round_number and finished['range'] == interval and
        finished['sourceSha256'] == shared.file_sha(source_path) and finished['trainCount'] == row['trainCount'] and
        finished['originCount'] == len(row['origins']) and finished['proposalBudget'] == plan.record['proposalBudget'] and
        finished['anchor']['sha256'] == plan.record['anchorDescriptorSha256'] and
        all(finished[key] == prepared[key] for key in ('signature', 'scopeSha256', 'scienceSha256')),
        'Only the original complete warm chunk may be admitted to its numerical corpus.')
    originals = finished['origins']
    shared.require([item['globalIndex'] for item in originals] == wanted,
        'Every original warm global index must occur once in its completed receipt.')
    statuses = []; tails = 0
    for item in originals:
        index = item['globalIndex']
        for key, name in (('origin', f'origin-{index}.json.gz'), ('public', f'public-{index}.json'),
                ('labels', f'labels-{index}.json')):
            shared.require(item[key]['file'] == name and files[name]['sha256'] == item[key]['sha256'],
                'Warm origin/public/private-label raw subjects must match before semantic decoding.')
        public = shared.read(native/item['public']['file'])
        shared.require(set(public) == {'kind', 'originSha256', 'signature', 'scopeSha256', 'scienceSha256',
            'round', 'request', 'streams', 'status', 'missingReason', 'observedWindows', 'publicRoot', 'anchors'} and
            public['kind'] == 'token-anchored-paired-public-1' and public['round'] == round_number and
            public['request'] == protocol_owner.request(index, row['trainCount']) and
            public['streams'] == row['origins'][index] and public['originSha256'] == item['origin']['sha256'] and
            all(public[key] == prepared[key] for key in ('signature', 'scopeSha256', 'scienceSha256')),
            'Warm public evidence must be the exact outcome-free projection of its original authority origin.')
        status = public['status']; root = public['publicRoot']; statuses.append(status)
        shared.require(status in ('READY', 'SINGLETON', 'MISSING') and status == item['status'] and
            (root is None) == (status == 'MISSING'), 'Warm support status and captured evidence disagree.')
        expected_tails = 2*len(root['proposedIndices']) if status == 'READY' else 0
        shared.require(item['tailGames'] == expected_tails and item['anchorRows'] == len(public['anchors']),
            'Warm native tail and original-anchor counts must match the exact public proposal set.')
        shared.require(not public['anchors'] or round_number == 1 and index < row['trainCount'],
            'Only original first-round TRAIN backbones supply the fixed KL anchor bank.')
        tails += expected_tails
    scientific_names = {name for name in files if name.startswith(('origin-', 'public-', 'labels-'))}
    shared.require(scientific_names == {item[key]['file'] for item in originals for key in ('origin', 'public', 'labels')},
        'No missing, duplicate or unexpected warm origin sidecar may enter the corpus.')
    shared.require(finished['backboneGames'] == len(wanted) and finished['tailGames'] == tails and
        finished['scientificTerminalGames'] == len(wanted)+tails and
        finished['readyRoots'] == statuses.count('READY') and finished['singletonRoots'] == statuses.count('SINGLETON') and
        finished['missingRoots'] == statuses.count('MISSING') and
        finished['capturedRoots'] == len(wanted)-statuses.count('MISSING'), 'Warm completed native counts must be exact.')
    return dict(kind='token-anchored-paired-chunk-receipt-1', complete=True, host=host, round=round_number,
        chunk=chunk, range=interval, source=shared.subject(source_path), native=str(native.resolve()),
        signature=prepared['signature'], scopeSha256=prepared['scopeSha256'], scienceSha256=prepared['scienceSha256'],
        scienceIdentity=prepared['scienceIdentity'], currentActorSha256=source['currentActor']['sha256'],
        originalActorSha256=source['originalActor']['sha256'], finishedSha256=files['finished.json']['sha256'],
        origins=originals, files=files, scientificTerminalGames=len(wanted)+tails,
        semanticValidationLabelsDecoded=False)


def reproduce(receipt, index, directory):
    directory = Path(directory); path = directory/f'replay-{index}.json'; value = shared.read(path)
    original = next(item for item in receipt['origins'] if item['globalIndex'] == index)
    source = shared.read(shared.checked_subject(receipt['source']))
    shared.require(value['kind'] == 'token-anchored-paired-reproduction-1' and
        value['globalIndex'] == index and value['request'] == protocol_owner.request(index, source['trainCount']) and
        value['sourceSha256'] == receipt['source']['sha256'] and value['original']['sha256'] == original['origin']['sha256']
        and all(value[key] == receipt[key] for key in ('signature', 'scopeSha256', 'scienceSha256')) and
        value['fullBackboneEqual'] is True and value['publicRecordsEqual'] is True and value['terminalEqual'] is True and
        value['scientificTerminalGames'] == 1 and value['tailGames'] == 0,
        'A warm reproduction must exactly retain its original full backbone, public inputs and terminal.')
    for key in ('backboneTerminalSha256', 'publicRootSha256', 'anchorsSha256'):
        shared.sha(value[key])
    return dict(index=index, subject=shared.subject(path), scientificTerminalGames=1)


def assemble(plan, round_number, subjects):
    row = plan.record['rounds'][round_number-1]; total = len(row['origins'])
    receipts = [(item, shared.read(shared.checked_subject(item))) for item in subjects]
    receipts.sort(key=lambda item: item[1]['chunk'])
    shared.require([item['chunk'] for _, item in receipts] ==
        list(range((total+plan.record['chunkSize']-1)//plan.record['chunkSize'])),
        'A warm corpus requires every original chunk exactly once, independent of completion order.')
    for _, item in receipts:
        recomputed = native_chunk(plan, item['source']['path'], item['native'], round_number, item['chunk'], item['host'])
        expected = {key: value for key, value in item.items() if key != 'reproductions'}
        shared.require(recomputed == expected, 'An admitted warm native chunk must independently reproduce.')
        owned = [index for index in row['replayIndices'] if index in range(
            item['range']['globalStart'], item['range']['globalStart']+item['range']['count'])]
        shared.require([value['index'] for value in item['reproductions']] == owned and all(
            shared.file_sha(value['subject']['path']) == value['subject']['sha256'] for value in item['reproductions']),
            'Every original prescribed warm reproduction must remain retained exactly once.')
    values = [item for _, item in receipts]
    shared.require(len({item['scopeSha256'] for item in values}) ==
        len({item['scienceSha256'] for item in values}) == len({shared.digest(item['scienceIdentity']) for item in values}) ==
        len({item['currentActorSha256'] for item in values}) == 1,
        'A complete warm round must share one portable scope, full-bank science identity and current residual.')
    statuses = [(origin['globalIndex'], origin['status']) for item in values for origin in item['origins']]
    ready = {split: sum(status == 'READY' for index, status in statuses
        if (index < row['trainCount']) == (split == 'TRAIN')) for split in ('TRAIN', 'VALIDATION')}
    return dict(kind='token-anchored-paired-corpus-1', complete=True, round=round_number,
        protocolSha256=plan.sha256, scopeSha256=values[0]['scopeSha256'],
        currentActorSha256=values[0]['currentActorSha256'], chunks=[subject for subject, _ in receipts], ready=ready,
        trainCount=row['trainCount'], validationCount=row['validationCount'],
        scientificTerminalGames=sum(item['scientificTerminalGames']+len(item['reproductions']) for item in values),
        exposurePassed=ready['TRAIN'] >= math.ceil(row['trainCount']*RECIPE['minimumReadyFraction']),
        semanticValidationLabelsDecoded=False)


class Corpus:
    def __init__(self, plan, subject):
        self.plan = plan; self.subject = subject; self.record = shared.read(shared.checked_subject(subject))
        shared.require(self.record['kind'] == 'token-anchored-paired-corpus-1' and self.record['complete'] is True and
            self.record == assemble(plan, self.record['round'], self.record['chunks']),
            'Numerical projection requires its exact independently admitted complete warm corpus.')
        self.chunks = [shared.read(shared.checked_subject(item)) for item in self.record['chunks']]

    def public(self, split):
        shared.require(split in ('TRAIN', 'VALIDATION'), 'Only the original frozen split may be opened.')
        count = self.record['trainCount']
        for chunk in self.chunks:
            for origin in chunk['origins']:
                index = origin['globalIndex']
                if (index < count) == (split == 'TRAIN'):
                    path = Path(chunk['native'])/origin['public']['file']
                    shared.require(shared.file_sha(path) == origin['public']['sha256'], 'Warm public bytes changed.')
                    yield shared.read(path), Path(chunk['native'])/origin['labels']['file'], origin['labels']['sha256']

    def context(self, dictionary, original, current):
        import token_anchored_data as data
        row = self.plan.record['rounds'][self.record['round']-1]
        return data.Context(self.record['round'], row['trainCount'], row['validationCount'], self.subject['sha256'],
            self.record['scopeSha256'], shared.digest(row['origins']), shared.file_sha(dictionary),
            shared.digest(shared.read(dictionary)), original.sha256, current.sha256,
            self.plan.record['anchorDescriptorSha256'], shared.digest(distribution.RECORD))

    def training(self, context, model, original_model, original_corpus):
        import token_anchored_data as data
        import token_paired_data as labels
        roots = []; targets = []
        for value, path, expected in self.public('TRAIN'):
            root = data.PublicRoot(normalized_public(value), context, model, split='TRAIN'); roots.append(root)
            shared.require(shared.file_sha(path) == expected, 'Warm TRAIN label bytes changed at their read boundary.')
            target = normalized_labels(shared.read(path), value, root)
            targets.append(labels.WinPairedTarget.from_record(root, target) if target is not None else None)
        shared.require(original_corpus.record['round'] == 1 and
            original_corpus.record['scopeSha256'] == self.record['scopeSha256'],
            'Every warm round must retain its complete original first-round anchor corpus.')
        anchors = []
        for value, _, _ in original_corpus.public('TRAIN'):
            rows = [dict(decisionNumber=row['number'], batchStartNumber=row['batchStartNumber'],
                **{key: row[key] for key in ('observation', 'anchor94Probabilities', 'originalProbabilities')})
                for row in value['anchors']]
            anchors.append(data.AnchorWorld.from_record(dict(index=value['request']['globalIndex'],
                split='TRAIN', observations=rows), original_model, context.train_count))
        return data.Training(context, tuple(roots), tuple(targets), tuple(anchors), self.record['ready']['VALIDATION'])


def normalized_labels(value, native_public, root):
    shared.require(root.record['split'] == 'TRAIN',
        'Warm fitting cannot decode sealed diagnostic labels or any non-TRAIN split.')
    for key in ('signature', 'scopeSha256', 'scienceSha256', 'round', 'request', 'streams', 'status', 'originSha256'):
        shared.require(value[key] == native_public[key], 'Warm label sidecar changed its original public binding: '+key)
    shared.require(value['kind'] == 'token-anchored-paired-labels-1', 'Warm fitting rejects a legacy label contract.')
    record = root.record
    if not root.ready:
        shared.require(value['arms'] == [], 'Missing/singleton warm roots cannot fabricate sibling returns.')
        return None
    shared.require(len(value['arms']) == len(record['proposedIndices']), 'Every proposed warm sibling must be retained.')
    actions = []
    for index, arm in zip(record['proposedIndices'], value['arms']):
        shared.require(arm['actionIndex'] == index and arm['command'] == record['observation']['actions'][index]['command']
            and arm['inclusionProbability'] == record['inclusionProbabilities'][index] and len(arm['outcomes']) == 2,
            'Every warm action command/index/inclusion and paired future must remain exact.')
        wins = []
        for replicate, outcome in enumerate(arm['outcomes']):
            terminal = outcome['terminal']
            shared.require(outcome['replicate'] == replicate and outcome['replayVerified'] is True and
                terminal['continuationSeed'] == record['futureSeeds'][replicate],
                'Warm continuation labels require both original same-stream authority-verified futures.')
            shared.sha(outcome['canonicalSha256'])
            wins.append(evidence.terminal_win_share(dict(controlledSeat=native_public['request']['seat'],
                terminal=dict(terminal, gameDecisions=terminal['decisions']))))
        actions.append(dict(actionIndex=index, winShares=wins))
    return dict(objectiveVersion='paired-win-share-1', publicSha256=root.sha256,
        futureSeeds=record['futureSeeds'], actions=actions)
