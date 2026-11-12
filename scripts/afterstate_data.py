"""Fixed G3 six-context value data only; sequential lossless chunks, no actor/critic updates."""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import sys
import time

import afterstate_corpus as corpus
import afterstate_value_data as data
import automata_campaign as controller
import automata_experiments as exp
import automata_purchase_continuation as legacy
import purchase_ecology as execution

read,sha,document,require=exp.read,exp.sha,exp.document,exp.require
KIND='afterstate-data-campaign-1'
AUDIT='2417fba439347077bf4adc6d02d27f8e9cca9bfc07abc333303a3e5fa8f59f6d'
QUALIFIED='46424285f5d35b32ea6053eead57ba0aa1b8d676588777bbd170c92211bcf546'
ROLES=dict(ordinary='b7c42a6f347a77c9bd9ff5d29778ea74650a9affe5fdb16738a8bea8d75d17d4',
    expansion='feb80d4363a93b266c94bc46e0d75ff4b0cb1f17a55d0f6d77782715862f44ef',
    prior='c4dd1609c737151caadfe0cb85efe406cee84f02b801877af35d65128488bcb0',
    bundle='65481635981a22e9a80672a684419ebf8d3342a29830712ccf51bf48c8da936d',
    card='916de6e6ec41b3cc708f51155044956b1d3ebdb68e3a171f660a57123a8b00bf',
    purchase='6613908b272cee6fb5789dc0ac8d8e6a14853d743ff6b9e08ba322a7d7a23f79')


def chunk_plan(audit):
    result=[]
    for i in range(1,17):
        bank=audit['seedBanks'][f'chunk-{i:02d}']
        require(bank['split']==('TRAIN' if i<=12 else 'VALIDATION' if i<=14 else 'TEST'),
            'Audited whole-world split roles must remain unchanged.')
        result.append(dict(bank,index=i,bank=f'chunk-{i:02d}',split=data.split(i),replayIndex=(0,85,170,255)[(i-1)%4]))
    return result


def joint_source(protocol,chunk):
    return dict(schemaVersion=1,kind='joint-collect',configuration=exp.CONFIGURATION,games=256,
        seed=chunk['nativeOffset'],baseline=protocol['ordinary'],bundle=protocol['bundle'],opponents=[protocol['bundle']],
        jointStateChronology='joint-state-chronology-1',sharedPurchaseBaseline=protocol['prior'],
        experimentalRulesTransfer=legacy.RULES_TRANSFER,continuationProtocol='ARENA_OVERLAYS_1',
        expansionImitation=dict(candidateModel=protocol['expansion'],opponentModels=[protocol['expansion']]),
        tokenOrder=dict(candidateSelector='FIXED_16_TO_15',opponentSelectors=['FIXED_16_TO_15']),replayIndices=[chunk['replayIndex']])


def source(protocol,chunk):
    path=Path(protocol['sources'])/f'joint-{chunk["index"]:02d}.json'
    return dict(kind=data.VERSION,collection=dict(path=str(path),sha256=sha(path)),
        legacyDictionary=dict(path=protocol['legacyDictionary'],sha256=sha(protocol['legacyDictionary'])))


def validate_inputs(protocol):
    require(protocol['kind']==KIND and protocol['configuration']==exp.CONFIGURATION
        and protocol['workers']==10 and protocol['heapMiB']==6144 and protocol['maximumSeconds']==7200
        and protocol['maximumAttempts']==1 and protocol['maximumScientificTerminalGames']==4096
        and protocol['maximumHistoricalTerminalReproductions']==16 and protocol['maximumTerminalExecutions']==4112
        and all(protocol[key] is False for key in ('fitting','policyUpdates','selection','activation','automaticResume','automaticSuccessor')),
        'One fixed two-hour desktop10 data-only attempt over4096 worlds and16 reproductions is required.')
    require(sha(protocol['seedAudit'])==AUDIT and protocol['chunks']==chunk_plan(read(protocol['seedAudit'])),
            'Exact frozen ancestry and whole-world chunk/split/reproduction schedule are required.')
    require(sha(protocol['qualificationReview'])==QUALIFIED and read(protocol['qualificationReview'])['qualificationPassed'] is True,
            'Normal fixed G3 roles require their independently reviewed qualification.')
    require(all(sha(protocol[key])==digest for key,digest in ROLES.items()),'A fixed G3/ordinary/E/T/F0 raw role changed.')
    bundle=read(protocol['bundle']);base=Path(protocol['bundle']).parent
    require(bundle['baselineSha256']==ROLES['prior'] and bundle['configuration']==exp.CONFIGURATION
        and all(bundle[phase]['sha256']==ROLES[phase] and sha(base/bundle[phase]['path'])==ROLES[phase]
                for phase in ('card','purchase')), 'G3 relative components must retain their exact learned roles.')
    dictionary=read(protocol['dictionary']);data.dictionary_names(dictionary)
    require(read(protocol['legacyDictionary'])['stateFeatureNames']==dictionary['legacyStateFeatureNames'],
            'New value inputs preserve the exact old7241 coordinates before explicit appended keys.')
    proof=read(protocol['sourceProof'])
    require(proof['gamesExecuted']==0 and len(proof['sources'])==16,'All native sources require a zero-game parser proof.')
    for chunk,row in zip(protocol['chunks'],proof['sources']):
        joint=Path(protocol['sources'])/f'joint-{chunk["index"]:02d}.json';wrapper=Path(protocol['sources'])/f'chunk-{chunk["index"]:02d}.json'
        require(chunk['count']==256 and chunk['seeds']==exp.arena_seeds(chunk['nativeOffset'],256)
            and read(joint)==joint_source(protocol,chunk) and read(wrapper)==source(protocol,chunk)
            and row['sourceSha256']==sha(wrapper) and row['dictionary']==dictionary,
            'A native source changed role, seed, parser signature, or static dictionary.')
    exp.validate_banks({c['bank']:c['seeds'] for c in protocol['chunks']})


def native(protocol,mode,source_path,output):
    cap=1.0 if mode=='afterstate-replay' else 4.0
    output=Path(output);require(not output.exists(),'A native afterstate child starts only once.')
    output.parent.mkdir(parents=True,exist_ok=True)
    command=[protocol['java']['executable'],f'-Xmx{protocol["heapMiB"]}m',f'-XX:ActiveProcessorCount={protocol["workers"]}',
        '-XX:ParallelGCThreads=1','-XX:ConcGCThreads=1','-cp',str(Path(protocol['runtime'])/'lib/*'),
        'dev.aragos.aor.training.TrainingMainKt','--mode',mode,'--source',str(source_path),'--output',str(output),
        '--hours',str(min(cap,exp.remaining_seconds()/3600)),'--workers',str(1 if mode=='afterstate-replay' else protocol['workers'])]
    execution.run_child(protocol,command,output.with_suffix('.log'))


def verify_roles(protocol,manifest,chunk):
    roles=manifest['collection'];own=roles['bundle'];rivals=roles['opponents']
    require(roles['baseline']['sha256']==ROLES['ordinary'] and roles['sharedPurchaseBaseline']['sha256']==ROLES['prior']
        and own['sha256']==ROLES['bundle'] and own['card']['sha256']==ROLES['card'] and own['purchase']['sha256']==ROLES['purchase']
        and len(rivals)==1 and rivals[0]==own and roles['replayIndices']==[chunk['replayIndex']]
        and roles['seeds']==chunk['seeds'] and roles['tokenOrder']==dict(candidateSelector='FIXED_16_TO_15',opponentSelectors=['FIXED_16_TO_15'])
        and roles['expansionImitation']['candidateModel']['sha256']==ROLES['expansion']
        and [x['sha256'] for x in roles['expansionImitation']['opponentModels']]==[ROLES['expansion']],
        'Actual sampled G3 actor/prior/overlay roles or world scheduling changed.')
    proof=read(protocol['sourceProof'])['sources'][chunk['index']-1]
    require(manifest['signature']==proof['signature'],'Native runtime signature differs from zero-game source proof.')


def collect(protocol,path,output):
    output=Path(output);require(not(output/'chunks').exists() and not(output/'corpus.json').exists(),'One new corpus attempt only.')
    target=output/'dictionary.json';shutil.copyfile(protocol['dictionary'],target);dictionary=read(target);dictionary_sha=sha(target)
    games=[];batches=[];started=time.time()
    for chunk in protocol['chunks']:
        directory=output/f'chunks/chunk-{chunk["index"]:02d}';source_path=Path(protocol['sources'])/f'chunk-{chunk["index"]:02d}.json'
        native(protocol,'afterstate-collect',source_path,directory/'collection')
        native(protocol,'afterstate-replay',directory/'collection',directory/'reproduction')
        manifest=read(directory/'collection/manifest.json');verify_roles(protocol,manifest,chunk)
        require(manifest['sourceSha256']==sha(source_path) and sha(directory/'collection/dictionary.json')==dictionary_sha,'Exact source/static dictionary changed.')
        expected,order=corpus.completed_files(directory,256,chunk['replayIndex'])
        rows=[corpus.origin(directory/'collection',i,chunk,dictionary,dictionary_sha,manifest) for i in range(256)]
        stored=corpus.archive(directory,expected,order);exp.remaining_seconds()
        batch=dict(index=chunk['index'],split=chunk['split'],signature=manifest['signature'],sourceSha256=sha(source_path),
            archive=str((directory/'evidence.tar.gz').relative_to(output)),archiveSha256=stored['archiveSha256'],
            files={n:item['sha256'] for n,item in stored['files'].items()},replayIndex=chunk['replayIndex'])
        document(directory/'receipt.json',dict(kind='afterstate-data-chunk-1',batch=batch,games=rows,newWorlds=256,
            historicalTerminalReproductions=1,actualFits=0,labelsSemanticallyDecoded=False))
        batches.append(batch);games.extend(rows)
        document(output/'progress.json',dict(completedChunks=len(batches),completeWorlds=len(games),elapsedSeconds=time.time()-started,actualFits=0),immutable=False)
        print(f'Completed verified afterstate chunk {chunk["index"]}/16; worlds={len(games)}.',flush=True)
    result=dict(kind=data.KIND,configuration=exp.CONFIGURATION,protocolSha256=sha(path),dictionaryPath=target.name,dictionarySha256=dictionary_sha,
        dictionary=dictionary,stateFeatureNames=dictionary['stateFeatureNames'],games=games,batches=batches,
        splitCounts={name:sum(g['split']==name for g in games) for name in ('training','validation','test')},
        rows=sum(g['decisions'] for g in games),nonzeros=sum(g['nonzeros'] for g in games),newWorlds=4096,
        historicalTerminalReproductions=16,actualFits=0,policyUpdates=0,activation=False)
    data.verify_corpus(result);document(output/'corpus.json',result)
    artifacts=[output/'corpus.json',target,output/'progress.json']+[p for d in (output/'chunks').iterdir() for p in d.iterdir() if p.is_file()]
    return exp.stage_result(output,'collect',True,'All4096 original worlds and16 historical reproductions verified and archived.',
        dict(newWorlds=4096,historicalTerminalReproductions=16,actualFits=0,rows=result['rows']),artifacts)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('command',choices=('preflight','stage'))
    parser.add_argument('--protocol',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    protocol=read(args.protocol);validate_inputs(protocol)
    dynamic={'AOR_CAMPAIGN_OUTPUT','AOR_CAMPAIGN_STAGE_ID','AOR_CAMPAIGN_DEADLINE_EPOCH','AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH'}
    require(str(Path(sys.executable).resolve())==protocol['python']['executable'] and sys.version==protocol['python']['version']
        and sys.flags.ignore_environment and sys.flags.no_user_site and sys.flags.no_site and sys.dont_write_bytecode
        and {k:v for k,v in os.environ.items() if k not in dynamic}==protocol['environment']['variables']
        and platform.platform()==protocol['environment']['platform'] and platform.machine()==protocol['environment']['machine']
        and os.cpu_count()==protocol['environment']['logicalCpus']
        and os.environ.get('AOR_TRAINING_MACHINE_CPUS')==str(os.cpu_count()),
        'Use the frozen clean desktop interpreter/environment and actual host CPU allowance.')
    require(all(sha(p)==h for p,h in protocol['frozenFiles'].items()),'A frozen source/runtime/input changed.')
    if args.command=='preflight':
        require(not args.output.exists(),'Preflight cannot resume existing output.')
        own=[row for row in controller.processes.sample_group(os.getpgrp()) if row.pid==os.getpid()]
        require(len(own)==1 and own[0].rss_bytes>0,'External child process sampling must work before games.')
        print(json.dumps(dict(passed=True,newGames=0,actualFits=0,protocolSha256=sha(args.protocol))))
    else:collect(protocol,args.protocol,args.output)
if __name__=='__main__':main()
