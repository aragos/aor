"""Typed numerical projection of verified public/label sidecars, never full native origins.

The complete corpus receipt binds ten immutable archives. Training reads only TRAIN labels.
Validation labels are reachable only through the fitter's sealed, both-iterations-complete callback.
"""
import json
from pathlib import Path
import tarfile

import expansion_training_evidence as evidence
import token_paired_campaign_plan as p
import token_paired_storage as storage
import token_paired_objective as objectives


def normalized_public(value):
    request=value['request'];root=value['publicRoot']
    result=dict(index=request['globalIndex'],split=request['split'],status=value['status'],
        requestSha256=p.digest({key:value[key] for key in
            ('signature','scopeSha256','scienceSha256','iteration','request','streams','originSha256')}),
        decisionNumber=None,batchStartNumber=None,observation=None,currentProbabilities=None,
        originalProbabilities=None,sampledIndex=None,proposedIndices=None,inclusionProbability=None,
        futureSeeds=value['streams']['future'])
    if root is not None:
        result.update(decisionNumber=root['number'],batchStartNumber=root['batchStartNumber'],
            observation=root['observation'],currentProbabilities=root['currentProbabilities'],
            originalProbabilities=root['originalProbabilities'],sampledIndex=root['sampledCommandIndex'],
            proposedIndices=root['proposedIndices'],inclusionProbability=root['inclusionProbability'])
    return result


def normalized_anchor(value):
    index=value['request']['globalIndex']
    p.require(value['iteration']==1 and index<512,'Only original TRAIN backbones supply the fixed anchor.')
    observations=[]
    for row in value['anchors']:
        p.require(row['iteration']==1 and row['globalIndex']==index,
            'An original anchor cannot change world or iteration.')
        observations.append(dict(decisionNumber=row['number'],batchStartNumber=row['batchStartNumber'],
            observation=row['observation'],originalProbabilities=row['originalProbabilities']))
    return dict(index=index,split='TRAIN',observations=observations)


def normalized_labels(value, native_public, typed_public,*,objective=objectives.MIXED):
    """Actual terminal scores independently own the normalized reward; sibling returns stay paired."""
    objectives.validate(objective)
    for key in ('signature','scopeSha256','scienceSha256','iteration','request','streams','status','originSha256'):
        p.require(value[key]==native_public[key], 'Label sidecar differs from the original public world: '+key)
    p.require(value['kind']=='token-paired-labels-1', 'Unknown paired-label contract.')
    public=typed_public.record
    if public['status']!='READY':
        p.require(value['arms']==[], 'Missing and singleton roots must not invent intervention returns.')
        return None
    arms=value['arms'];p.require(len(arms)==len(public['proposedIndices']), 'Every proposed sibling is required.')
    actions=[]
    for action_index,arm in zip(public['proposedIndices'],arms):
        p.require(arm['actionIndex']==action_index and
            arm['command']==public['observation']['actions'][action_index]['command'] and
            arm['inclusionProbability']==(1. if action_index==public['observation']['baselineIndex']
                else public['inclusionProbability']), 'Original action command/index/inclusion probability changed.')
        outcomes=arm['outcomes'];p.require(len(outcomes)==2,'Every sibling has exactly two paired futures.')
        rewards=[]
        for replicate,outcome in enumerate(outcomes):
            terminal=outcome['terminal']
            p.require(outcome['replicate']==replicate and terminal['continuationSeed']==public['futureSeeds'][replicate]
                and outcome['replayVerified'] is True, 'Paired future/canonical replay identity changed.')
            p.sha(outcome['canonicalSha256'])
            game=dict(controlledSeat=native_public['request']['seat'],terminal=dict(terminal,gameDecisions=terminal['decisions']))
            if objective==objectives.WIN:rewards.append(evidence.terminal_win_share(game))
            else:
                evidence.terminal_reward(game);rewards.append(terminal['reward'])
        actions.append(dict(actionIndex=action_index,**{'winShares' if objective==objectives.WIN else 'rewards':rewards}))
    result=dict(publicSha256=typed_public.sha256,futureSeeds=public['futureSeeds'],actions=actions)
    if objective==objectives.WIN:result['objectiveVersion']=objective
    return result


class Corpus:
    def __init__(self, plan, corpus_subject):
        self.plan=plan;self.subject=corpus_subject;self.path=p.checked_subject(corpus_subject)
        self.record=p.read(self.path)
        p.require(self.record['kind']=='token-paired-corpus-1' and self.record['complete'] is True and
            self.record['origins']==640 and self.record['trainOrigins']==512 and
            self.record['validationOrigins']==128 and self.record['protocolDigest']==plan.sha256 and
            self.record['exposurePassed'] is True, 'The complete fixed public corpus must pass its exposure floors.')
        self.chunks=[]
        for item in self.record['chunks']:
            path=p.checked_subject(item);receipt=p.read(path)
            archive=path.with_name(receipt['archive']);storage.verify_archive(archive,receipt)
            self.chunks.append((receipt['chunk'],archive,receipt))
        self.chunks.sort(key=lambda row:row[0])
        p.require([row[0] for row in self.chunks]==list(range(10)), 'All exact native chunks must be present.')
        recomputed=storage.iteration_corpus(plan,self.record['iteration'],self.record['chunks'])
        p.require(recomputed==self.record,'The complete corpus admission must be independently reproducible.')

    def context(self, dictionary, original, current,*,objective=objectives.MIXED):
        import token_paired_data as data
        objectives.validate(objective)
        context=data.WinContext if objective==objectives.WIN else data.Context
        return context(iteration=self.record['iteration'],source_sha256=self.subject['sha256'],
            scope_sha256=self.record['scopeSha256'],
            request_bank_sha256=p.digest(self.plan.record['iterations'][self.record['iteration']-1]['origins']),
            dictionary_sha256=p.file_sha(dictionary),dictionary_semantic_sha256=p.digest(p.read(dictionary)),
            original_model_sha256=original.sha256,current_model_sha256=current.sha256)

    def public(self, split):
        p.require(split in ('TRAIN','VALIDATION'),'Only the fixed original split may be opened.')
        for chunk,archive,receipt in self.chunks:
            if (chunk<8)!=(split=='TRAIN'):continue
            values=sorted(storage.public_members(archive,receipt),key=lambda value:value['request']['globalIndex'])
            p.require([row['request']['globalIndex'] for row in values]==list(p.indices(chunk)),
                'Public sidecar global index coverage changed.')
            for value in values:
                p.require(value['request']['split']==split,'Public sidecar crossed a frozen split.')
                yield value

    def _labels(self, split, public, typed,*,objective=objectives.MIXED):
        # Private method: callers are the TRAIN assembly or the admitted validation callback below.
        native={row['request']['globalIndex']:row for row in public}
        typed={row.record['index']:row for row in typed}
        result={}
        for chunk,archive,receipt in self.chunks:
            if (chunk<8)!=(split=='TRAIN'):continue
            members={row['labels']['file']:row['globalIndex'] for row in receipt['origins']}
            with tarfile.open(archive,'r:gz') as source:
                for member in source:
                    if member.name not in members:continue
                    index=members[member.name]
                    label=storage.member_json(source,member,receipt)
                    result[index]=normalized_labels(label,native[index],typed[index],objective=objective)
        expected=list(range(512)) if split=='TRAIN' else list(range(512,640))
        p.require(sorted(result)==expected,'Labels must retain every original split position.')
        return tuple(result[index] for index in expected)

    def training(self, context, model, original_model, original_corpus):
        import token_paired_data as data
        values=tuple(self.public('TRAIN'))
        roots=tuple(data.PublicRoot.from_record(normalized_public(value),context,model,split='TRAIN') for value in values)
        labels=self._labels('TRAIN',values,roots,objective=context.objective)
        target_type=data.WinPairedTarget if context.objective==objectives.WIN else data.PairedTarget
        targets=tuple(None if label is None else target_type.from_record(root,label)
            for root,label in zip(roots,labels))
        p.require(original_corpus.record['iteration']==1 and
            original_corpus.record['scopeSha256']==self.record['scopeSha256'],
            'The complete original anchor bank must retain the same fixed scope.')
        anchors=tuple(data.AnchorWorld.from_record(normalized_anchor(value),original_model)
            for value in original_corpus.public('TRAIN'))
        return data.Training(context,roots,targets,anchors,self.record['ready']['VALIDATION'])

    def validation_public(self, context, model):
        import token_paired_data as data
        return tuple(data.PublicRoot.from_record(normalized_public(value),context,model,split='VALIDATION')
            for value in self.public('VALIDATION'))

    def validation_callback(self, context, model, final_fit_directory):
        """Return the callback; intrinsic fit/seal barriers run again before any label JSON is read."""
        def load(sealed_subject):
            import token_paired_fit as fit
            seal_path=p.checked_subject(sealed_subject);seal=p.read(seal_path)
            final=fit.completed(final_fit_directory)
            p.require(final['operationalPassed'] and final['identity']['context']['iteration']==2 and
                final['identity']['context']['scope_sha256']==context.scope_sha256 and
                seal['kind']=='token-paired-validation-probabilities-1' and seal['complete'] is True and
                seal['semanticLabelsDecoded'] is False and seal['context']==vars(context),
                'Both iterations and the exact persisted probability seal must precede validation labels.')
            fit_directory=Path(seal['fitDirectory']);done=fit.completed(fit_directory)
            p.require(p.file_sha(fit_directory/'finished.json')==seal['fitFinishedSha256'] and
                done['outputModelSha256']==seal['modelSha256'] and done['operationalPassed'] and
                (p.file_sha(Path(final_fit_directory)/'finished.json')==seal['fitFinishedSha256']
                 if context.iteration==2 else final['identity']['predecessor']['finishedSha256']==seal['fitFinishedSha256']),
                'The final update and selected validation actor must retain their exact original chain.')
            values=tuple(self.public('VALIDATION'))
            typed=self.validation_public(context,model)
            p.require([row.sha256 for row in typed]==[row['publicSha256'] for row in seal['rows']],
                'Validation public inputs must equal the original sealed prediction subjects.')
            return self._labels('VALIDATION',values,typed)
        return load
