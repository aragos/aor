"""Portable, exact two-iteration publication; no effect-dependent model choice or inference."""
import os
from pathlib import Path
import shutil

import purchase_storage as store
import token_paired_campaign_plan as p
import token_paired_storage as s

FIT_FILES=('finished.json','identity.json','policy.json','checkpoint.json','history.json')


def relative_subject(path, owner):
    path=Path(path).resolve(strict=True);owner=Path(owner).resolve()
    return dict(path=os.path.relpath(path,owner.parent),sha256=p.file_sha(path))


def resolve(value, owner):
    path=Path(owner).parent/value['path']
    return p.checked_subject(dict(value,path=str(path.resolve(strict=True))))


def copy_subject(source, target):
    source=p.checked_subject(source);target=Path(target);target.parent.mkdir(parents=True,exist_ok=True)
    expected=p.file_sha(source)
    with source.open('rb') as content:
        store.publish(target,lambda stream:shutil.copyfileobj(content,stream),expected)
    return target


def fit_receipt(directory):
    """Raw artifact/identity binding without importing numerical libraries or parsing model tensors."""
    directory=Path(directory);done=p.read(directory/'finished.json')
    p.require(done['kind']=='token-paired-fit-complete-1' and done['complete'] is True and
        done['operationalPassed'] is True and done['actorChanged'] is True and
        type(done['acceptedEpochs']) is int and 1<=done['acceptedEpochs']<=20,
        'Only one complete changed fixed fit may advance the campaign.')
    p.require(set(done['artifacts'])==set(FIT_FILES)-{'finished.json'} and
        all(p.file_sha(directory/name)==digest for name,digest in done['artifacts'].items()) and
        p.read(directory/'identity.json')==done['identity'] and
        p.file_sha(directory/'policy.json')==done['outputModelSha256'],
        'A completed actor must retain its exact model/Adam/history/identity transaction.')
    return done


def publish_iteration(protocol, original, corpus, fit_directory, seal, portable):
    """Copy exact completed bytes and publish relative references valid unchanged on either host."""
    protocol_path=p.checked_subject(protocol);plan=p.Plan.from_record(p.read(protocol_path))
    corpus_path=p.checked_subject(corpus);value=p.read(corpus_path)
    directory=Path(fit_directory);done=fit_receipt(directory);identity=done['identity'];context=identity['context']
    iteration=context['iteration'];p.integer(iteration,1,2,'Completed iteration')
    seal_path=p.checked_subject(seal);probabilities=p.read(seal_path)
    p.require(value['kind']=='token-paired-corpus-1' and value['complete'] is True and
        value['exposurePassed'] is True and value['iteration']==iteration and
        value['protocolDigest']==plan.sha256 and value['origins']==640 and
        value['ready']['TRAIN']>=256 and value['ready']['VALIDATION']>=64 and
        context['source_sha256']==corpus['sha256'] and context['scope_sha256']==value['scopeSha256'] and
        context['original_model_sha256']==original['sha256']==plan.record['originalActorSha256'] and
        context['current_model_sha256']==value['currentActorSha256'] and
        context['dictionary_sha256']==plan.record['dictionarySha256'],
        'Fit must consume the exact complete corpus, fixed original actor and native dictionary.')
    p.require(probabilities['kind']=='token-paired-validation-probabilities-1' and probabilities['complete'] is True
        and probabilities['semanticLabelsDecoded'] is False and probabilities['context']==context and
        probabilities['fitFinishedSha256']==p.file_sha(directory/'finished.json') and
        probabilities['modelSha256']==done['outputModelSha256'] and len(probabilities['rows'])==128 and
        probabilities['counts']['READY']==value['ready']['VALIDATION'] and
        identity['publicCoverage']==dict(trainingReady=value['ready']['TRAIN'],validationReady=value['ready']['VALIDATION']),
        'The complete persisted validation probabilities must bind this exact changed actor before publication.')
    portable=Path(portable);target=portable/'iterations'/f'iteration-{iteration}'
    p.require(not target.exists(),'Each iteration admission is immutable and once-only.')
    if iteration==1:
        p.require(not portable.exists() and identity['predecessor'] is None and
            context['current_model_sha256']==original['sha256'],'Iteration1 must start with the sole original actor.')
        portable.mkdir(parents=True)
        copy_subject(protocol,portable/'protocol.json');copy_subject(original,portable/'original-policy.json')
        previous=None;current=portable/'original-policy.json'
    else:
        previous_path=portable/'iterations/iteration-1/admission.json'
        old=verify_iteration(previous_path)
        p.require(p.file_sha(portable/'protocol.json')==protocol['sha256'] and
            old['scopeSha256']==context['scope_sha256'] and
            old['outputActor']['sha256']==context['current_model_sha256'] and
            identity['predecessor']==dict(finishedSha256=old['fitCompletion']['sha256'],
                modelSha256=old['outputActor']['sha256']), 'Iteration2 must continue the sole admitted first actor.')
        previous=previous_path;current=resolve(old['outputActor'],previous_path)
    target.mkdir(parents=True)
    copy_subject(corpus,target/'corpus.json');copy_subject(seal,target/'validation.json')
    for name in FIT_FILES:copy_subject(p.subject(directory/name),target/'fit'/name)
    admission=target/'admission.json'
    result=dict(kind='token-paired-iteration-admission-1',iteration=iteration,
        scopeSha256=value['scopeSha256'],scienceSha256=value['scienceSha256'],protocolSha256=protocol['sha256'],
        currentActor=relative_subject(current,admission),outputActor=relative_subject(target/'fit/policy.json',admission),
        originalActor=relative_subject(portable/'original-policy.json',admission),
        corpus=relative_subject(target/'corpus.json',admission),fitCompletion=relative_subject(target/'fit/finished.json',admission),
        validationProbabilitySeal=relative_subject(target/'validation.json',admission),
        previousAdmission=None if previous is None else relative_subject(previous,admission),ready=value['ready'],
        acceptedEpochs=done['acceptedEpochs'],actorChanged=True)
    s.publish_json(admission,result);verify_iteration(admission)
    return p.subject(admission)


def verify_iteration(path):
    path=Path(path);value=p.read(path)
    p.require(value['kind']=='token-paired-iteration-admission-1' and value['actorChanged'] is True and
        value['ready']['TRAIN']>=256 and value['ready']['VALIDATION']>=64,
        'A portable iteration must pass the exact public exposure and learning-health gates.')
    for name in ('currentActor','outputActor','originalActor','corpus','fitCompletion','validationProbabilitySeal'):
        resolve(value[name],path)
    done=fit_receipt(resolve(value['fitCompletion'],path).parent)
    corpus=p.read(resolve(value['corpus'],path));seal=p.read(resolve(value['validationProbabilitySeal'],path))
    context=done['identity']['context']
    p.require(context['iteration']==value['iteration']==corpus['iteration'] and
        context['source_sha256']==value['corpus']['sha256'] and
        context['scope_sha256']==value['scopeSha256']==corpus['scopeSha256'] and
        value['scienceSha256']==corpus['scienceSha256'] and
        context['original_model_sha256']==value['originalActor']['sha256'] and
        context['current_model_sha256']==value['currentActor']['sha256'] and
        done['outputModelSha256']==value['outputActor']['sha256'] and
        done['acceptedEpochs']==value['acceptedEpochs'] and
        seal['modelSha256']==value['outputActor']['sha256'] and
        seal['fitFinishedSha256']==value['fitCompletion']['sha256'] and seal['context']==context and
        seal['semanticLabelsDecoded'] is False and seal['counts']['READY']==value['ready']['VALIDATION'],
        'Portable model/corpus/probability subjects must retain the exact numerical chain.')
    if value['iteration']==1:
        p.require(value['previousAdmission'] is None and done['identity']['predecessor'] is None and
            value['currentActor']['sha256']==value['originalActor']['sha256'], 'The first actor must be original π0.')
    else:
        p.require(value['iteration']==2 and value['previousAdmission'] is not None,'Only the second fixed update may follow.')
        old=verify_iteration(resolve(value['previousAdmission'],path))
        p.require(old['iteration']==1 and old['protocolSha256']==value['protocolSha256'] and
            old['scopeSha256']==value['scopeSha256'] and old['outputActor']['sha256']==value['currentActor']['sha256'] and
            done['identity']['predecessor']==dict(finishedSha256=old['fitCompletion']['sha256'],
                modelSha256=old['outputActor']['sha256']), 'The final actor cannot switch predecessor or recipe scope.')
    return value


def publish_final(portable):
    portable=Path(portable);protocol=portable/'protocol.json';plan=p.Plan.from_record(p.read(protocol))
    paths=[portable/'iterations'/f'iteration-{i}'/'admission.json' for i in (1,2)]
    rows=[verify_iteration(path) for path in paths]
    target=portable/'final-admission.json'
    p.require(all(row['protocolSha256']==p.file_sha(protocol) for row in rows),
        'Both iteration publications must bind the exact shared scientific protocol.')
    result=dict(kind='token-paired-final-screen-admission-1',protocol=relative_subject(protocol,target),
        scopeSha256=rows[1]['scopeSha256'],iterations=[relative_subject(path,target) for path in paths],
        finalActor=relative_subject(resolve(rows[1]['outputActor'],paths[1]),target),
        originalActor=relative_subject(portable/'original-policy.json',target),ecologySha256=plan.record['ecologySha256'],
        completedIterations=2,learnerHost='DESKTOP',operationallyComplete=True,
        validationEffectsUsedForSelection=False)
    s.publish_json(target,result)
    return p.subject(target)
