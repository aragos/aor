"""Fixed70 corpus→two paired fits→sealed TEST DAG; no actor or runtime updates.

The unchanged68 native owner collects all sibling outcomes. TEST result/canonical bodies remain
opaque to corpus admission. The old67 prediction seal is only the existing mechanical native-tail
admission contract; the learned TEST choice is a separate, honestly named later predictor.
"""
import argparse
from pathlib import Path
import os
import shutil
import sys

import automata_experiments as exp
import automata_purchase_continuation as legacy
import purchase_ecology as execution
import purchase_plan_difference_data as data

read, sha, require, document = exp.read, exp.sha, exp.require, exp.document
KIND = 'purchase-plan-difference-campaign-1'
NATIVE_COMMIT = '96c5edcf3c936325f2c4920cf48a7a87de666665'
STAGES = {'collect': 15000, 'fit': 1800, 'test': 600, 'report': 600}
AUDIT = '14d69e3c14e753404755a0deb1ab098f8c51c98af1501aa36d95c38319f8fea4'
ORIGINS = '09e04242e10992ecb8f177cfb7b8b92134f17922b14b85b5fcb12a3bfc8bf7cd'
EPOCH_SEEDS = tuple(range(70100000, 70100020))
MODEL_REVIEW = '3d6d296d3ad795c116c2204927def211f067f8f6c2581a9c721b75eaa40aa192'
ROLES = dict(ordinary='b7c42a6f347a77c9bd9ff5d29778ea74650a9affe5fdb16738a8bea8d75d17d4',
    expansion='feb80d4363a93b266c94bc46e0d75ff4b0cb1f17a55d0f6d77782715862f44ef',
    prior='c4dd1609c737151caadfe0cb85efe406cee84f02b801877af35d65128488bcb0',
    bundle='65481635981a22e9a80672a684419ebf8d3342a29830712ccf51bf48c8da936d',
    card='916de6e6ec41b3cc708f51155044956b1d3ebdb68e3a171f660a57123a8b00bf',
    purchase='6613908b272cee6fb5789dc0ac8d8e6a14853d743ff6b9e08ba322a7d7a23f79')
GIB = 1024**3
GPU_VARIABLES = dict(OMP_NUM_THREADS='1', MKL_NUM_THREADS='1', OPENBLAS_NUM_THREADS='1',
    NUMEXPR_NUM_THREADS='1', CUBLAS_WORKSPACE_CONFIG=':4096:8', CUDA_VISIBLE_DEVICES='0', PYTHONDONTWRITEBYTECODE='1')


def joint_source(protocol, chunk):
    require(type(chunk) is int and 1 <= chunk <= 8, 'Exactly eight prescribed independent chunk sources exist.')
    roles = {key: item['path'] for key, item in protocol['roles'].items()}
    return dict(schemaVersion=1, kind='joint-collect', configuration=exp.CONFIGURATION,
        games=256, seed=70004148 + (chunk-1)*11, baseline=roles['ordinary'], bundle=roles['bundle'],
        opponents=[roles['bundle']], jointStateChronology='joint-state-chronology-1',
        sharedPurchaseBaseline=roles['prior'], experimentalRulesTransfer=legacy.RULES_TRANSFER,
        continuationProtocol='ARENA_OVERLAYS_1',
        expansionImitation=dict(candidateModel=roles['expansion'], opponentModels=[roles['expansion']]),
        tokenOrder=dict(candidateSelector='FIXED_16_TO_15', opponentSelectors=['FIXED_16_TO_15']))


def ranking_source(collection, descriptor, origins):
    return dict(kind='purchase-plan-ranking-1', collection=collection, predictorDescriptor=descriptor,
        streams=[dict(proposals=row['proposalSeeds'], futures=row['futureChanceSeeds']) for row in origins])


def validate_inputs(protocol):
    require(protocol['kind'] == KIND and protocol['nativeSourceCommit'] == NATIVE_COMMIT
        and protocol['workers'] == 10 and protocol['heapMiB'] == 6144 and protocol['maximumSeconds'] == 18000
        and protocol['readOnlyAuditSeconds'] == 900 and protocol['maximumAttempts'] == 1
        and protocol['stageSeconds'] == STAGES and protocol['maximumTerminalGames'] == 67584
        and protocol['maximumDataBytes'] == protocol['minimumFreeBytes'] == 8*GIB
        and protocol['maximumRssBytes'] == 12*GIB and protocol['numericalMaximumRssBytes'] == 6*GIB
        and protocol['minimumGpuFreeBytes'] == 4*GIB and protocol['epochSeeds'] == list(EPOCH_SEEDS)
        and protocol['maximumCriticFits'] == 2
        and all(protocol[key] is False for key in ('actorFits','runtimeActivation','automaticRetry','automaticSuccessor')),
        'One fixed five-hour paired-difference study, two critics and no actor/runtime changes are required.')
    for key, digest in (('seedAudit',AUDIT), ('origins',ORIGINS), ('modelReview',MODEL_REVIEW)):
        require(protocol[key]['sha256'] == digest and sha(protocol[key]['path']) == digest,
                 'Frozen prospective ancestry/model admission changed: ' + key)
    require(sha(protocol['critic']) == data.CHECKPOINT and sha(protocol['dictionary']) == data.DICTIONARY,
             'Exact admitted67 checkpoint and original7244 dictionary required.')
    require(set(protocol['roles']) == set(ROLES) and all(item['sha256'] == ROLES[key] and sha(item['path']) == ROLES[key]
        for key,item in protocol['roles'].items()), 'Exact fixed G3/M8/E/T/F0 roles cannot change.')
    origins, audit = read(protocol['origins']['path']), read(protocol['seedAudit']['path'])
    require(audit['seedBanks']['epoch-order'] == dict(role='shared-two-arm-epoch-order',count=20,seeds=list(EPOCH_SEEDS)),
             'Both fixed fits must use the same twenty prospectively reserved epoch-order streams.')
    require(len(origins) == 2048 and len(protocol['chunks']) == 8, 'No missing-origin refill or additional chunk exists.')
    for c, chunk in enumerate(protocol['chunks'], 1):
        rows = origins[(c-1)*256:c*256]
        require(chunk['index'] == c and chunk['split'] == data.split((c-1)*256), 'Whole-world chunk split changed.')
        collection = joint_source(protocol,c)
        require(read(chunk['collection']['path']) == collection and sha(chunk['collection']['path']) == chunk['collection']['sha256'],
                 'Original native collection roles/source changed.')
        names = ['origin'] + [f'proposal-{i}' for i in range(2,8)] + [f'future-{i}' for i in range(1,5)]
        banks = [audit['seedBanks'][f'chunk-{c:02d}/{name}'] for name in names]
        require(all(bank['count'] == 256 and bank['chunk'] == c and bank['split'] == chunk['split']
            and bank['nativeOffset'] == collection['seed']+j
            and bank['seeds'] == exp.arena_seeds(collection['seed']+j,256) for j,bank in enumerate(banks)),
            'Every original/proposal/future bank must be the exact frozen consecutive native stream.')
        require(all(row == dict(chunk=c,controlledSeat=i%4,targetEpoch=1+(i//4)%3,globalIndex=(c-1)*256+i,
            index=i,split=chunk['split'],gameSeed=banks[0]['seeds'][i],
            proposalSeeds=[b['seeds'][i] for b in banks[1:7]],futureChanceSeeds=[b['seeds'][i] for b in banks[7:]])
            for i,row in enumerate(rows)), 'Fixed local seat/epoch schedules and streams must remain exact.')
        source = ranking_source(chunk['collection'],protocol['predictorDescriptor'],rows)
        require(read(chunk['source']['path']) == source and sha(chunk['source']['path']) == chunk['source']['sha256'],
                 'Unchanged native plan source/paired futures changed.')
        prepared = read(chunk['prepared']['path'])
        require(sha(chunk['prepared']['path']) == chunk['prepared']['sha256'] and prepared['origins'] == 256
            and prepared['sourceSha256'] == chunk['source']['sha256'] and prepared['maximumTerminalGames'] == 8448
            and prepared['collection']['seeds'] == banks[0]['seeds']
            and prepared['predictorDescriptorSha256'] == protocol['predictorDescriptor']['sha256'],
            'Every source requires its own exact zero-game native metadata proof.')
        require(all(protocol['inputs'].get(item['path']) == item['sha256'] for item in prepared['inputs']),
                 'Every native raw subject must be frozen in the enclosing campaign.')
    require(all(sha(p) == h for p,h in protocol['inputs'].items()), 'A frozen runtime/model/source/environment input changed.')


def native_command(protocol, chunk, output, mode):
    require(mode in ('public','tails'), 'Only original native plan collection modes exist.')
    command = [protocol['java']['executable'],'-Xmx6144m','-XX:ActiveProcessorCount=10',
        '-XX:ParallelGCThreads=1','-XX:ConcGCThreads=1','-cp',str(Path(protocol['runtime'])/'lib/*'),
        'dev.aragos.aor.training.TrainingMainKt','--mode','purchase-plan-'+mode,
        '--source',chunk['source']['path'],'--output',str(output/mode),'--workers','10',
        '--hours',str(min(7200,exp.remaining_seconds())/3600)]
    if mode == 'tails': command += ['--public',str(output/'public'),'--predictions',str(output/'predictions/predictions.json')]
    return command


def numerical_command(protocol, protocol_path, output, mode):
    require(mode in ('seal','numeric-fit','numeric-test','numeric-preflight'), 'Unknown fixed numerical operation.')
    prefix = [protocol['cpuPython']] if mode == 'seal' else ['/usr/bin/env',*[k+'='+v for k,v in GPU_VARIABLES.items()],protocol['gpuPython']]
    return prefix + ['-E','-s','-B',str(Path(__file__).resolve()),mode,'--protocol',str(protocol_path),'--output',str(output)]


def collect(protocol, protocol_path, output):
    require(not (output/'chunks').exists() and not (output/'corpus.json').exists(), 'Collection permits one fresh attempt only.')
    names = data.states.dictionary_names(read(protocol['dictionary']))
    records, origins, terminals = [], [], 0
    for chunk in protocol['chunks']:
        exp.remaining_seconds()
        root = output/'chunks'/f'chunk-{chunk["index"]:02d}'
        root.mkdir(parents=True)
        execution.run_child(protocol,native_command(protocol,chunk,root,'public'),root/'public.log')
        execution.run_child(protocol,numerical_command(protocol,protocol_path,root,'seal'),root/'seal.log')
        execution.run_child(protocol,native_command(protocol,chunk,root,'tails'),root/'tails.log')
        verified = data.completed_chunk(root,read(chunk['prepared']['path']),read(chunk['source']['path']),chunk['index'],names)
        receipt = data.archive(root,verified['files'])
        record = {key:verified[key] for key in ('sourceSha256','signature','gameSeeds','futureStreams')}
        record.update(index=chunk['index'],split=chunk['split'],archive=str((root/receipt['archive']).relative_to(output)),
            archiveSha256=receipt['archiveSha256'],files=receipt['files'])
        records.append(record);origins.extend(verified['origins']);terminals+=verified['terminalGames']
        document(root/'receipt.json',dict(kind='purchase-plan-difference-chunk-1',index=chunk['index'],
            terminalGames=verified['terminalGames'],tailGames=verified['tailGames'],semanticLabelsDecoded=False,
            storageSha256=sha(root/'storage.json'),record=record,origins=verified['origins']))
    require(terminals <= 67584, 'The prospective terminal execution cap cannot expand.')
    shutil.copyfile(protocol['dictionary'],output/'dictionary.json')
    corpus = dict(kind=data.KIND,dictionaryFile='dictionary.json',dictionary=read(protocol['dictionary']),
        dictionarySha256=data.DICTIONARY,stateFeatureNames=names,warmCheckpointSha256=data.CHECKPOINT,
        sourceSha256=sha(protocol_path),origins=origins,chunks=records)
    data.verify_corpus(corpus)
    document(output/'corpus.json',corpus)
    return dict(terminalGames=terminals,originalWorlds=2048,semanticLabelsDecoded=False,
        informativeTrain=sum(o['informative'] for o in origins[:1536]),
        informativeValidation=sum(o['informative'] for o in origins[1536:1792]),
        readyTest=sum(o['status']=='READY' for o in origins[1792:]),criticFits=0)


def numerical(protocol, protocol_path, output, mode):
    if mode == 'seal':
        import purchase_ecology_environment as environment
        import purchase_plan_predictor as predictor
        import purchase_plan_ranking as ranking
        environment.verify(read(protocol['cpuEnvironment']))
        predictor.configure()
        require(not (output/'tails').exists(), 'Native tails must wait for the original fixed67 public seal.')
        return ranking.predict_bank(output/'public',protocol['predictorDescriptor']['path'],output/'predictions')
    import torch
    import purchase_value_campaign as gpu
    import purchase_value_benchmark as benchmark
    import purchase_plan_difference_fit as fit
    gpu.verify_environment(read(protocol['gpuEnvironment']),variables=GPU_VARIABLES)
    benchmark.configure(threads=1)
    def guard():
        exp.remaining_seconds()
        import automata_campaign as controller
        require(sum(p.rss_bytes for p in controller.processes.sample_group(os.getpgrp())) < protocol['numericalMaximumRssBytes'],
                 'The numerical process group exceeded its frozen6GiB bound.')
        require(shutil.disk_usage(output.parent).free >= protocol['minimumFreeBytes'], 'The original8GiB free floor is mandatory.')
    if mode == 'numeric-preflight':
        fit.frozen.load_subjects(protocol['critic'],protocol['dictionary'])
        require(not output.exists(), 'Zero-fit preflight cannot create a scientific output.')
        return dict(passed=True,newGames=0,actualFits=0,corpusOpened=False,retainedStateInference=0,
            protocolSha256=sha(protocol_path),gpuEnvironmentSha256=sha(protocol['gpuEnvironment']))
    guard()
    require(torch.cuda.mem_get_info()[0] >= protocol['minimumGpuFreeBytes'], 'At least4GiB free VRAM is required before numerical work.')
    if mode == 'numeric-fit':
        cache = data.build_cache(output/'corpus.json',output/'development-cache','DEVELOPMENT',guard)
        return fit.fit(cache,read(output/'corpus.json'),output/'fits',fit.Config(EPOCH_SEEDS),guard,'cuda:0',
            checkpoint=protocol['critic'],dictionary=protocol['dictionary'])
    import purchase_plan_difference_evaluate as evaluate
    return evaluate.evaluate(output/'fits/selected.json',output/'corpus.json',output/'test',guard,
        checkpoint=protocol['critic'],dictionary=protocol['dictionary'])


def completed_stage(output, name):
    result = read(output/'results'/(name+'.json'))
    require(result['status']=='passed' and all(sha(data.relative(output,p))==h for p,h in result['artifacts'].items()),
             'Every previous mechanical stage must be complete and unchanged before advancement.')
    return result


def stage(protocol, protocol_path, output, name):
    require(os.environ.get('AOR_CAMPAIGN_STAGE_ID') == name and not (output/'results'/(name+'.json')).exists(),
             'Only one fixed controller-owned stage attempt is allowed.')
    for previous in list(STAGES)[:list(STAGES).index(name)]: completed_stage(output,previous)
    if name == 'collect':
        metrics = collect(protocol,protocol_path,output)
        artifacts = [output/'corpus.json',output/'dictionary.json'] + [p for p in (output/'chunks').rglob('*') if p.is_file() and p.name!='.DS_Store']
    elif name in ('fit','test'):
        execution.run_child(protocol,numerical_command(protocol,protocol_path,output,'numeric-'+name),output/(name+'-child.log'))
        if name == 'fit':
            selected = read(output/'fits/selected.json')
            metrics = dict(criticFits=2,selectedModelSha256=selected['modelSha256'],testInspected=False)
            directories = ('development-cache','fits')
        else:
            report = read(output/'test/report.json')
            metrics = dict(criticFits=0,testLabelsDecoded=report.get('semanticLabelsDecoded',False),
                skipped=report['kind']=='purchase-plan-difference-test-skipped-1')
            directories = ('test',)
        artifacts = [p for directory in directories for p in (output/directory).rglob('*') if p.is_file() and p.name!='.DS_Store'] + [output/(name+'-child.log')]
    else:
        selected, test = read(output/'fits/selected.json'),read(output/'test/report.json')
        report = dict(kind='purchase-plan-difference-completion-1',corpusSha256=sha(output/'corpus.json'),
            selectionSha256=sha(output/'fits/selected.json'),testReportSha256=sha(output/'test/report.json'),
            terminalGames=read(output/'results/collect.json')['metrics']['terminalGames'],criticFits=2,
            selectedArm=selected['arm'],selectedEpoch=selected['epoch'],selectedModelSha256=selected['modelSha256'],
            testSkipped=selected['unchanged'],futureResearchGate=test['futureResearchGate'],
            strengthQualification=False,runtimeActivation=False,automaticSuccessor=False)
        document(output/'report.json',report)
        artifacts,metrics=[output/'report.json'],report
    return exp.stage_result(output,name,True,'Completed fixed70 '+name+' stage; no adaptive successor.',metrics,artifacts)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=(*STAGES,'seal','numeric-fit','numeric-test','numeric-preflight'))
    parser.add_argument('--protocol',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();protocol=read(args.protocol);validate_inputs(protocol)
    if args.command in STAGES: stage(protocol,args.protocol,args.output,args.command)
    else:
        result=numerical(protocol,args.protocol,args.output,args.command)
        if args.command=='numeric-preflight':
            import json
            print(json.dumps(result,sort_keys=True))


if __name__ == '__main__': main()
