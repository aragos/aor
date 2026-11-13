"""One Desktop CPU1 numerical process per fixed update; public/label owners remain separate."""
import argparse
import os
from pathlib import Path

import automata_experiments as exp
import token_paired_campaign_plan as p
import token_paired_storage as storage


def update(protocol_path, profile_path, corpus_path, original_corpus_path, current_path,
           output, seal_path, previous=None):
    import purchase_ecology_environment as environment
    import token_paired_data as data
    import token_paired_fit as learner
    import token_paired_validation as validation
    from token_paired_corpus import Corpus
    protocol=p.read(protocol_path);plan=p.Plan.from_record(protocol);profile=p.read(profile_path)
    p.require(profile['host']=='DESKTOP' and profile['protocolSha256']==p.file_sha(protocol_path),
        'Only the exact single Desktop numerical owner may update either iteration.')
    environment.verify(profile['numerical'])
    exp.remaining_seconds()
    original_path=p.checked_subject(profile['originalActor']);dictionary=p.checked_subject(profile['dictionary'])
    original=data.FrozenActor(original_path.read_bytes(),profile['originalActor']['sha256'])
    current=data.FrozenActor(Path(current_path).read_bytes(),p.file_sha(current_path))
    corpus=Corpus(plan,p.subject(corpus_path));original_corpus=Corpus(plan,p.subject(original_corpus_path))
    iteration=corpus.record['iteration']
    expected=original.sha256 if iteration==1 else learner.completed(previous)['outputModelSha256']
    p.require(current.sha256==expected and corpus.record['currentActorSha256']==expected and
        (previous is None)==(iteration==1), 'An update cannot choose another initializer or earlier checkpoint.')
    context=corpus.context(dictionary,original,current)
    training=corpus.training(context,current.model,original.model,original_corpus)
    deadline=min(float(os.environ[name]) for name in
        ('AOR_CAMPAIGN_DEADLINE_EPOCH','AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH') if name in os.environ)
    config=learner.Config(tuple(plan.record['iterations'][iteration-1]['shuffleSeeds']),deadline)
    done=learner.fit(training,original,current,dictionary.read_bytes(),output,config,previous=previous)
    if done['operationalPassed']:
        seal=validation.seal_validation(output,original,current,dictionary.read_bytes(),seal_path,
            lambda:corpus.validation_public(context,current.model))
    else:seal=None
    result=dict(kind='token-paired-numeric-stage-1',iteration=iteration,complete=True,
        protocolSha256=p.file_sha(protocol_path),corpus=p.subject(corpus_path),
        fitCompletion=p.subject(Path(output)/'finished.json'),validationProbabilitySeal=seal,
        operationalPassed=done['operationalPassed'],acceptedEpochs=done['acceptedEpochs'],
        actorChanged=done['actorChanged'],outputModelSha256=done['outputModelSha256'])
    storage.publish_json(Path(output).parent/'numeric-complete.json',result)
    return result


def effects(protocol_path, profile_path, iterations_root):
    """Exactly the two fixed validation diagnostics, after both fits and both probability seals."""
    import token_paired_data as data
    import token_paired_fit as learner
    import token_paired_validation as validation
    from token_paired_corpus import Corpus
    plan=p.Plan.from_record(p.read(protocol_path));profile=p.read(profile_path);root=Path(iterations_root)
    p.require(profile['host']=='DESKTOP' and profile['protocolSha256']==p.file_sha(protocol_path),
        'Only the original Desktop actor owner opens completed validation labels.')
    paths=[root/f'iteration-{i}' for i in (1,2)]
    completed=[learner.completed(path/'fit') for path in paths]
    p.require(all(row['operationalPassed'] for row in completed) and
        [row['identity']['context']['iteration'] for row in completed]==[1,2] and
        completed[1]['identity']['predecessor']['finishedSha256']==p.file_sha(paths[0]/'fit/finished.json'),
        'Both completed fixed updates must precede either validation effect.')
    for path in paths:
        done=learner.completed(path/'fit');context=data.Context(**done['identity']['context'])
        corpus=Corpus(plan,p.subject(path/'corpus.json'))
        current_path=p.checked_subject(profile['originalActor']) if context.iteration==1 else paths[0]/'fit/policy.json'
        current=data.FrozenActor(current_path.read_bytes(),p.file_sha(current_path))
        validation.evaluate_validation(p.subject(path/'validation.json'),path/'validation-effect.json',
            corpus.validation_callback(context,current.model,paths[1]/'fit'),final_fit_directory=paths[1]/'fit')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=('update','effects'));parser.add_argument('--protocol',type=Path,required=True)
    parser.add_argument('--profile',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--corpus',type=Path);parser.add_argument('--original-corpus',type=Path)
    parser.add_argument('--current',type=Path);parser.add_argument('--seal',type=Path);parser.add_argument('--previous',type=Path)
    args=parser.parse_args()
    if args.mode=='update':
        p.require(all(value is not None for value in (args.corpus,args.original_corpus,args.current,args.seal)),
            'A fixed update requires all original corpus/actor/probability-seal paths.')
        update(args.protocol,args.profile,args.corpus,args.original_corpus,args.current,args.output,args.seal,args.previous)
    else:effects(args.protocol,args.profile,args.output)
