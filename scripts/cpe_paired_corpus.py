"""Strict head-cohort projection; labels cross only the existing all-head seal barrier."""
from pathlib import Path
import tarfile

import expansion_training_evidence as evidence
import cpe_paired_campaign_plan as p
import cpe_paired_storage as storage


def normalized_public(value):
    request=value['request'];root=value['publicRoot']
    result=dict(head=request['head'],index=request['globalIndex'],split=request['split'],status=value['status'],
        requestSha256=p.digest({key:value[key] for key in ('signature','bundleScopeSha256','scopeSha256',
            'scienceSha256','iteration','request','streams','originSha256')}),
        decisionNumber=None,batchStartNumber=None,observation=None,currentProbabilities=None,
        originalProbabilities=None,sampledIndex=None,referenceIndex=None,proposedIndices=None,
        inclusionProbability=None,futureSeeds=value['streams']['future'])
    if root is not None:
        result.update(decisionNumber=root['number'],batchStartNumber=root['batchStartNumber'],
            observation=root['observation'],currentProbabilities=root['currentProbabilities'],
            originalProbabilities=root['originalProbabilities'],sampledIndex=root['sampledCommandIndex'],
            referenceIndex=root['referenceIndex'],proposedIndices=root['proposedIndices'],
            inclusionProbability=root['inclusionProbability'])
    return result


def normalized_anchor(value, original_sha):
    index=value['request']['globalIndex'];head=value['request']['head']
    p.require(value['iteration']==1 and index<512, 'Only original TRAIN backbones own the fixed anchor bank.')
    observations=[]
    for row in value['anchors']:
        p.require(row['iteration']==1 and row['globalIndex']==index and row['head']==head,
            'An original anchor must retain its assigned head/world/iteration.')
        observations.append(dict(decisionNumber=row['number'],batchStartNumber=row['batchStartNumber'],
            observation=row['observation'],originalProbabilities=row['originalProbabilities']))
    return dict(head=head,originalModelSha256=original_sha,index=index,split='TRAIN',observations=observations)


def normalized_labels(value, native_public, typed_public):
    for key in ('signature','bundleScopeSha256','scopeSha256','scienceSha256','iteration',
        'request','streams','status','originSha256'):
        p.require(value[key]==native_public[key], 'Labels differ from their exact original public subject: '+key)
    p.require(value['kind']=='cpe-paired-labels-1', 'Only the explicit C/P/E detached label wire is admitted.')
    public=typed_public.record
    if public['status']!='READY':
        p.require(value['arms']==[], 'Missing reservoir roots cannot invent intervention outcomes.')
        return None
    arms=value['arms'];p.require(len(arms)==len(public['proposedIndices']), 'Every original proposed sibling is required.')
    actions=[]
    for action_index,arm in zip(public['proposedIndices'],arms):
        p.require(arm['actionIndex']==action_index and
            arm['command']==public['observation']['actions'][action_index]['command'] and
            arm['inclusionProbability']==(1. if action_index==public['referenceIndex'] else public['inclusionProbability']),
            'Actual sampled reference, offered command and uniform alternative inclusion must remain exact.')
        p.require(len(arm['outcomes'])==2, 'Every proposed action requires both original paired future streams.')
        rewards=[]
        for replicate,outcome in enumerate(arm['outcomes']):
            terminal=outcome['terminal']
            p.require(outcome['replicate']==replicate and
                terminal['continuationSeed']==public['futureSeeds'][replicate] and outcome['replayVerified'] is True,
                'Original paired future identity and canonical verification changed.')
            p.sha(outcome['canonicalSha256'])
            evidence.terminal_reward(dict(controlledSeat=native_public['request']['seat'],
                terminal=dict(terminal,gameDecisions=terminal['decisions'])))
            rewards.append(terminal['reward'])
        actions.append(dict(actionIndex=action_index,rewards=rewards))
    return dict(publicSha256=typed_public.sha256,futureSeeds=public['futureSeeds'],actions=actions)


class Corpus:
    def __init__(self, plan, corpus_subject):
        self.plan=plan;self.subject=corpus_subject;self.path=p.checked_subject(corpus_subject)
        self.record=p.read(self.path);row=self.record
        p.require(row['kind']=='cpe-paired-corpus-1' and row['complete'] is True and
            row['origins']==640 and row['trainOrigins']==512 and row['validationOrigins']==128 and
            row['protocolDigest']==plan.sha256 and row['exposurePassed'] is True,
            'A numerical head reader requires its complete fixed public exposure admission.')
        p.head(row['head']);self.chunks=[]
        for item in row['chunks']:
            path=p.checked_subject(item);receipt=p.read(path)
            archive=path.with_name(receipt['archive']);storage.verify_archive(archive,receipt)
            self.chunks.append((receipt['chunk'],archive,receipt))
        self.chunks.sort(key=lambda item:item[0])
        p.require([item[0] for item in self.chunks]==list(range(10)), 'Every original cohort shard must be present.')
        p.require(storage.cohort_corpus(plan,row['iteration'],row['head'],row['chunks'])==row,
            'The complete public head corpus must reproduce its admission exactly.')

    def context(self, dictionary, original, current):
        import cpe_paired_data as data
        import cpe_paired_policy as policy
        head=policy.Head(self.record['head'])
        return data.Context(head=head,iteration=self.record['iteration'],source_sha256=self.subject['sha256'],
            scope_sha256=self.record['scopeSha256'],bundle_scope_sha256=self.record['bundleScopeSha256'],
            ecology_sha256=self.record['ecologySha256'],
            request_bank_sha256=p.digest(self.plan.cohort(self.record['iteration'],head.value)['origins']),
            dictionary_sha256=p.file_sha(dictionary),dictionary_semantic_sha256=p.digest(p.read(dictionary)),
            original_model_sha256=original.actor(head).sha256,current_model_sha256=current.actor(head).sha256,
            original_bundle_sha256=original.sha256,current_bundle_sha256=current.sha256)

    def public(self, split):
        p.require(split in ('TRAIN','VALIDATION'), 'Only the original prospective split can be decoded.')
        for chunk,archive,receipt in self.chunks:
            if (chunk<8)!=(split=='TRAIN'):continue
            values=sorted(storage.public_members(archive,receipt),key=lambda row:row['request']['globalIndex'])
            p.require([row['request']['globalIndex'] for row in values]==list(p.indices(chunk)),
                'Public world order and coverage must remain exact.')
            for value in values:
                p.require(value['request']==p.request(value['request']['globalIndex'],self.record['head']) and
                    value['request']['split']==split, 'A public observation crossed head or split boundaries.')
                yield value

    def _labels(self, split, public, typed):
        native={row['request']['globalIndex']:row for row in public}
        typed={row.record['index']:row for row in typed};result={}
        for chunk,archive,receipt in self.chunks:
            if (chunk<8)!=(split=='TRAIN'):continue
            members={row['labels']['file']:row['globalIndex'] for row in receipt['origins']}
            with tarfile.open(archive,'r:gz') as source:
                for member in source:
                    if member.name not in members:continue
                    index=members[member.name]
                    result[index]=normalized_labels(storage.member_json(source,member,receipt),native[index],typed[index])
        expected=list(range(512)) if split=='TRAIN' else list(range(512,640))
        p.require(sorted(result)==expected, 'Labels must retain every independent requested split position.')
        return tuple(result[index] for index in expected)

    def training(self, context, current, original, original_corpus):
        import cpe_paired_data as data
        values=tuple(self.public('TRAIN'))
        roots=tuple(data.PublicRoot.from_record(normalized_public(row),context,current.model,split='TRAIN') for row in values)
        labels=self._labels('TRAIN',values,roots)
        targets=tuple(None if label is None else data.PairedTarget.from_record(root,label)
            for root,label in zip(roots,labels))
        p.require(original_corpus.record['iteration']==1 and
            original_corpus.record['head']==self.record['head'] and
            original_corpus.record['scopeSha256']==self.record['scopeSha256'],
            'A head must retain its own complete original-policy backbone anchor corpus.')
        anchors=tuple(data.AnchorWorld.from_record(normalized_anchor(row,original.sha256),original)
            for row in original_corpus.public('TRAIN'))
        return data.Training(context,roots,targets,anchors,self.record['ready']['VALIDATION'])

    def validation_public(self, context, current):
        import cpe_paired_data as data
        return tuple(data.PublicRoot.from_record(normalized_public(row),context,current.model,split='VALIDATION')
            for row in self.public('VALIDATION'))

    def validation_labels(self, context, current, sealed_subject, both_seals):
        """The numerical intrinsic all-head/two-iteration barrier precedes label bytes."""
        import cpe_paired_validation as validation
        seals,_=validation.validate_seal_pair(both_seals)
        p.require(sealed_subject==both_seals[context.iteration],
            'The callback must use its exact admitted all-head probability seal.')
        item=seals[context.iteration]['heads'][context.head.value]
        p.require(item['context']==vars(context), 'Sealed head context differs from its exact original corpus.')
        values=tuple(self.public('VALIDATION'));typed=self.validation_public(context,current)
        p.require([row.sha256 for row in typed]==[row['publicSha256'] for row in item['rows']],
            'Every validation input must equal its previously sealed public subject.')
        return self._labels('VALIDATION',values,typed)
