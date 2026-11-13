"""One Desktop CPU1 child owns each atomic C/P/E update and its public probability seal."""
import argparse
import os
from pathlib import Path

import automata_experiments as exp
import cpe_paired_admission as admission
import cpe_paired_campaign_plan as p
import cpe_paired_storage as storage


def load_bundle(subject):
    import cpe_paired_policy as policy
    value=admission.bundle_subject(subject);path=p.checked_subject(subject)
    actors=tuple(policy.FrozenActor(head,(path.parent/value['heads'][head.value]['path']).read_bytes(),
        value['heads'][head.value]['sha256']) for head in policy.HEADS)
    return policy.Bundle(path.read_bytes(),subject['sha256'],actors)


def admitted_corpora(plan, aggregate_subject):
    from cpe_paired_corpus import Corpus
    import cpe_paired_policy as policy
    aggregate=p.read(p.checked_subject(aggregate_subject))
    p.require(aggregate==storage.iteration_corpus(plan,aggregate['iteration'],aggregate['cohorts']) and
        aggregate['exposurePassed'] is True, 'All three complete head cohorts must pass before any numerical input.')
    return aggregate,{head:Corpus(plan,aggregate['cohorts'][head.value]) for head in policy.HEADS}


def update(protocol_path,profile_path,corpus_path,original_corpus_path,current_path,output,seal_path,previous=None):
    import purchase_ecology_environment as environment
    import cpe_paired_policy as policy
    import cpe_paired_fit as learner
    import cpe_paired_validation as validation
    plan=p.Plan.from_record(p.read(protocol_path));profile=p.read(profile_path)
    p.require(profile['kind']=='cpe-paired-host-1' and profile['host']=='DESKTOP' and
        profile['protocolSha256']==p.file_sha(protocol_path), 'Only the frozen Desktop numerical owner may update bundles.')
    environment.verify(profile['numerical']);exp.remaining_seconds()
    aggregate,corpora=admitted_corpora(plan,p.subject(corpus_path))
    original_aggregate,original_corpora=admitted_corpora(plan,p.subject(original_corpus_path))
    p.require(original_aggregate['iteration']==1, 'The immutable anchor corpus must be the original complete iteration.')
    iteration=aggregate['iteration']
    p.require((previous is None)==(iteration==1), 'Only the exact complete immediate predecessor may initialize an update.')
    expected=plan.record['originalBundleSha256'] if iteration==1 else learner.completed(previous)['outputBundleSha256']
    p.require(p.file_sha(current_path)==expected==aggregate['currentBundleSha256'] and
        profile['originalBundle']['sha256']==plan.record['originalBundleSha256'],
        'An update cannot choose another initial bundle, a mixed head or an earlier transaction.')
    original=load_bundle(profile['originalBundle']);current=load_bundle(p.subject(current_path))
    dictionaries={head:p.checked_subject(profile['dictionaries'][head.value]) for head in policy.HEADS}
    p.require(all(p.file_sha(path)==plan.record['heads'][head.value]['dictionarySha256']
        for head,path in dictionaries.items()), 'Every numerical head requires its exact protocol dictionary bytes.')
    contexts={head:corpora[head].context(dictionaries[head],original,current) for head in policy.HEADS}
    training={head:corpora[head].training(contexts[head],current.actor(head),original.actor(head),original_corpora[head])
        for head in policy.HEADS}
    deadline=min(float(os.environ[name]) for name in
        ('AOR_CAMPAIGN_DEADLINE_EPOCH','AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH') if name in os.environ)
    configs={head:learner.Config(tuple(plan.cohort(iteration,head.value)['shuffleSeeds']),deadline) for head in policy.HEADS}
    raw_dictionaries={head:path.read_bytes() for head,path in dictionaries.items()}
    done=learner.fit_bundle(training,original,current,raw_dictionaries,output,configs,previous=previous)
    if done['operationalPassed']:
        seal=validation.seal_validation(output,original,current,raw_dictionaries,seal_path,
            lambda head:corpora[head].validation_public(contexts[head],current.actor(head)))
        completion=p.subject(Path(output)/'finished.json');stopped=None
    else:
        seal=None;completion=None;stopped=p.subject(Path(output)/'stopped.json')
    result=dict(kind='cpe-paired-numeric-stage-1',iteration=iteration,complete=True,
        protocolSha256=p.file_sha(protocol_path),corpus=p.subject(corpus_path),
        fitCompletion=completion,stopReceipt=stopped,validationProbabilitySeal=seal,
        operationalPassed=done['operationalPassed'],outputBundleSha256=done['outputBundleSha256'])
    storage.publish_json(Path(output).parent/'numeric-complete.json',result)
    return result


def effects(protocol_path,profile_path,iterations_root):
    """Both complete atomic iterations and all-head seals precede any validation label."""
    import cpe_paired_data as data
    import cpe_paired_fit as learner
    import cpe_paired_validation as validation
    from cpe_paired_corpus import Corpus
    plan=p.Plan.from_record(p.read(protocol_path));profile=p.read(profile_path);root=Path(iterations_root)
    p.require(profile['host']=='DESKTOP' and profile['protocolSha256']==p.file_sha(protocol_path),
        'Only the original Desktop learner opens the two completed validation endpoints.')
    subjects={i:p.subject(root/f'iteration-{i}'/'validation.json') for i in (1,2)}
    seals,completed=validation.validate_seal_pair(subjects)
    current={1:load_bundle(profile['originalBundle']),2:learner.load_completed_bundle(root/'iteration-1/fit')}
    def labels(iteration,head,subject):
        context=data.Context.from_record(completed[iteration]['identity']['contexts'][head.value])
        aggregate=p.read(root/f'iteration-{iteration}'/'corpus.json')
        corpus=Corpus(plan,aggregate['cohorts'][head.value])
        return corpus.validation_labels(context,current[iteration].actor(head),subject,subjects)
    return validation.evaluate_validation(subjects,root/'validation-effect.json',labels)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=('update','effects'));parser.add_argument('--protocol',type=Path,required=True)
    parser.add_argument('--profile',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    for name in ('corpus','original-corpus','current','seal','previous'):parser.add_argument('--'+name,type=Path)
    args=parser.parse_args()
    if args.mode=='update':
        p.require(all(value is not None for value in (args.corpus,args.original_corpus,args.current,args.seal)),
            'A fixed atomic update requires exact original/current corpus, bundle and seal paths.')
        update(args.protocol,args.profile,args.corpus,args.original_corpus,args.current,args.output,args.seal,args.previous)
    else:effects(args.protocol,args.profile,args.output)
