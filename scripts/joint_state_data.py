"""One bounded chronological joint DATA collection; no fit, policy update, selection or successor."""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import sys
import time

import automata_campaign as controller
import automata_experiments as exp
import joint_state_corpus as corpus

read,sha,document,require=exp.read,exp.sha,exp.document,exp.require
KIND='joint-state-data-campaign-1'
CARD='98dd71c7911da94a6dc03151cde304589b1bc513b7c319e2d3c525bc3cc8689c'
G2='09e29e5b25b76bdad3c2e082e210322d16851bfd8a978ce123b6a8b0fbf8b2af'
M8='b7c42a6f347a77c9bd9ff5d29778ea74650a9affe5fdb16738a8bea8d75d17d4'
EXPANSION='feb80d4363a93b266c94bc46e0d75ff4b0cb1f17a55d0f6d77782715862f44ef'
F0='c4dd1609c737151caadfe0cb85efe406cee84f02b801877af35d65128488bcb0'
SEED_AUDIT='6e36f2486e72b9f3d15a6a452a984aa01eb8d7b42db317ea52eb61504f7925a2'
REPLAY_INDICES=(0,85,170,255)


def chunk_plan(seed_audit):
    names=[*[f'training-{i}' for i in range(1,13)],'validation-1','validation-2','test-1','test-2']
    return [dict(index=index,bank=name,split=name.split('-')[0],**seed_audit['seedBanks'][name],
        replayIndex=REPLAY_INDICES[(index-1)%4]) for index,name in enumerate(names,1)]


def source_document(protocol,chunk):
    return dict(schemaVersion=1,kind='joint-collect',configuration=exp.CONFIGURATION,games=256,
        seed=chunk['nativeOffset'],baseline=protocol['ordinary'],bundle=protocol['bundle'],
        opponents=[protocol['rivalBundle']],jointStateChronology='joint-state-chronology-1',
        sharedPurchaseBaseline=protocol['prior'],experimentalRulesTransfer=protocol['experimentalRulesTransfer'],
        continuationProtocol='ARENA_OVERLAYS_1',
        expansionImitation=dict(candidateModel=protocol['expansion'],opponentModels=[protocol['expansion']]),
        tokenOrder=dict(candidateSelector='FIXED_16_TO_15',opponentSelectors=['FIXED_16_TO_15']),
        replayIndices=[chunk['replayIndex']])


def validate_inputs(protocol):
    require(protocol['kind']==KIND and protocol['workers']==6 and protocol['maximumSeconds']==7200
        and protocol['maximumAttempts']==1 and protocol['maximumScientificTerminalGames']==4096
        and protocol['maximumHistoricalTerminalReproductions']==16 and protocol['maximumTerminalExecutions']==4112
        and all(protocol[key] is False for key in ('fitting','policyUpdates','selection','activation','automaticResume','automaticSuccessor')),
        'Use one two-hour Mac6 data-only attempt over exactly16x256 worlds and16 historical reproductions.')
    require(protocol['configuration']==exp.CONFIGURATION and sha(protocol['seedAudit'])==SEED_AUDIT,
        'The fixed configuration and full frozen52 ancestry audit are required.')
    audit=read(protocol['seedAudit'])
    require(audit['allIntersectionsZero'] and audit['liveMetadataScans']==0 and audit['humanInputsRead']==0
        and protocol['chunks']==chunk_plan(audit), 'Retain all prospective chunks/splits/replay indices unchanged.')
    exp.validate_banks({chunk['bank']:chunk['seeds'] for chunk in protocol['chunks']})
    for chunk in protocol['chunks']:
        require(chunk['count']==256 and chunk['seeds']==exp.arena_seeds(chunk['nativeOffset'],256),
            'Each fixed native world uses its declared arena formula.')
        require(read(Path(protocol['sources'])/f'chunk-{chunk["index"]:02d}.json')==source_document(protocol,chunk),
            'A frozen chunk source changed its roles, seed bank or replay index.')
    for key,digest in (('card',CARD),('purchase',G2),('ordinary',M8),('expansion',EXPANSION),('prior',F0)):
        require(sha(protocol[key])==digest,'A fixed joint behavior role changed: '+key)
    proof=read(protocol['sourceProof'])
    require(proof['gamesExecuted']==0 and len(proof['sources'])==16
        and all(row['sourceSha256']==sha(Path(protocol['sources'])/f'chunk-{index:02d}.json')
                and row['games']==256 and row['ordinarySha256']==M8 and row['purchasePriorSha256']==F0
                and row['cardSha256']==CARD and row['purchaseSha256']==G2
                for index,row in enumerate(proof['sources'],1)), 'Pre-game native source/policy proof changed.')
    card=read(protocol['card']);initial=read(protocol['cardInitialization'])
    require(initial['seed']==audit['seedBanks']['cardInitialization']['seeds'][0]==52001000
        and initial['sha256']==CARD and card['baselineProbability']==.97
        and all(value==0 for name in ('actorWeights','valueWeights') for value in card[name])
        and card['valueBias']==0, 'Only the prospectively initialized zero-residual card head is permitted.')
    for key,expected_card in (('bundle',CARD),('rivalBundle',None)):
        bundle=read(protocol[key]);base=Path(protocol[key]).parent
        require(bundle['kind']=='joint-card-purchase-1' and bundle['baselineSha256']==F0
            and bundle['configuration']==exp.CONFIGURATION and
            bundle['purchase']==dict(path=Path(protocol['purchase']).name,sha256=G2),
            'Both joint bundles retain the exact G2 actor and F0 purchase prior.')
        require((bundle['card'] is None if expected_card is None else
            bundle['card']==dict(path=Path(protocol['card']).name,sha256=expected_card)),
            'Rivals retain raw M8 card play; only the controlled seat samples its zero card residual.')
        require(sha(base/bundle['purchase']['path'])==G2,'Bundle relative purchase path changed.')
    require(protocol['policyBindings']==dict(cardModelSha256=CARD,purchaseModelSha256=G2,bundleSha256=sha(protocol['bundle'])),
        'Portable behavior identity must match native model bindings.')
    require(read(protocol['dictionary'])['stateFeatureNames']==sorted(
        ['card.'+name for name in card['stateFeatureNames']] +
        ['purchase.'+name for name in read(protocol['purchase'])['stateFeatureNames']]),
        'The complete phase-prefixed dictionary must match the original encoder units.')


def verify_native_roles(protocol,collection,chunk):
    manifest=read(collection/'collection.json')
    require(manifest['baseline']['sha256']==M8 and manifest['sharedPurchaseBaseline']['sha256']==F0
        and manifest['bundle']['baselineSha256']==F0 and manifest['bundle']['card']['sha256']==CARD
        and manifest['bundle']['purchase']['sha256']==G2 and len(manifest['opponents'])==1
        and manifest['opponents'][0]['card'] is None and manifest['opponents'][0]['purchase']['sha256']==G2
        and manifest['opponents'][0]['baselineSha256']==F0 and manifest['replayIndices']==[chunk['replayIndex']]
        and manifest['tokenOrder']==dict(candidateSelector='FIXED_16_TO_15',opponentSelectors=['FIXED_16_TO_15'])
        and manifest['expansionImitation']['candidateModel']['sha256']==EXPANSION
        and [row['sha256'] for row in manifest['expansionImitation']['opponentModels']]==[EXPANSION],
        'Native ordinary/card/purchase/expansion/token roles must match the frozen full ecology.')
    snapshots={'baseline.json':M8,'shared-purchase-baseline.json':F0,'card-policy.json':CARD,
        'purchase-policy.json':G2,'opponent-0-purchase-policy.json':G2,
        'expansion-imitation-candidate.json':EXPANSION,'expansion-imitation-opponent-0.json':EXPANSION}
    require(all(sha(collection/name)==digest for name,digest in snapshots.items()),'Native role snapshots changed.')
    proof=read(protocol['sourceProof'])
    require(proof['gamesExecuted']==0 and proof['sources'][chunk['index']-1]['signature']==manifest['signature'],
        'Collection signature must match the pre-game source/runtime proof.')
    return manifest


def execution_identity(protocol):
    require(str(Path(sys.executable).resolve())==protocol['python']['executable'] and sys.version==protocol['python']['version']
        and sys.flags.ignore_environment and sys.flags.no_user_site and sys.flags.no_site and sys.dont_write_bytecode,
        'Use the frozen clean stdlib interpreter.')
    dynamic={'AOR_CAMPAIGN_OUTPUT','AOR_CAMPAIGN_STAGE_ID','AOR_CAMPAIGN_DEADLINE_EPOCH','AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH'}
    require({key:value for key,value in os.environ.items() if key not in dynamic}==protocol['environment']['variables']
        and platform.platform()==protocol['environment']['platform'] and platform.machine()==protocol['environment']['machine']
        and os.cpu_count()==protocol['environment']['logicalCpus'],'The frozen Mac execution environment changed.')
    require(all(sha(path)==digest for path,digest in protocol['frozenFiles'].items()),'A frozen input/runtime/source changed.')


def collect(protocol,path,output):
    require(not (output/'chunks').exists() and not (output/'corpus.json').exists() and not (output/'progress.json').exists(),
        'One fresh data attempt only; never resume/retry a partial corpus.')
    dictionary=dict(sha256=sha(protocol['dictionary']),metadata=read(protocol['dictionary']))
    target_dictionary=output/'joint-state-dictionary.json';shutil.copyfile(protocol['dictionary'],target_dictionary)
    require(sha(target_dictionary)==dictionary['sha256'],'The portable dictionary changed during copying.')
    batches=[];games=[];started=time.time()
    for chunk in protocol['chunks']:
        exp.remaining_seconds()
        directory=output/f'chunks/chunk-{chunk["index"]:02d}'
        source=Path(protocol['sources'])/f'chunk-{chunk["index"]:02d}.json'
        exp.native(protocol,'joint-collect',source,directory/'collection',extra=('--workers',str(protocol['workers'])))
        exp.native(protocol,'joint-replay',directory/'collection',directory/'reproduction',create_output=False)
        exp.remaining_seconds()
        collection=directory/'collection';manifest=verify_native_roles(protocol,collection,chunk)
        require(read(collection/'source.json')==source_document(protocol,chunk)
            and manifest['sourceSha256']==sha(source) and manifest['seeds']==chunk['seeds']
            and sha(collection/'joint-state-dictionary.json')==dictionary['sha256'],
            'Native collection must bind its exact frozen source/worlds/dictionary.')
        expected,order=corpus.completed_files(directory,256,chunk['replayIndex'])
        rows=[corpus.origin(collection,index,chunk,dictionary,protocol['policyBindings']) for index in range(256)]
        stored=corpus.archive(directory,expected,order)
        exp.remaining_seconds()
        batch=dict(index=chunk['index'],split=chunk['split'],bank=chunk['bank'],signature=manifest['signature'],
            archive=str((directory/corpus.ARCHIVE).relative_to(output)),archiveSha256=stored['archiveSha256'],
            storageManifest=str((directory/corpus.MANIFEST).relative_to(output)),storageManifestSha256=sha(directory/corpus.MANIFEST),
            sourceSha256=sha(source),files={name:item['sha256'] for name,item in stored['files'].items()},
            replayIndex=chunk['replayIndex'],historicalReproductions=1,worlds=256,
            rows=sum(row['decisions'] for row in rows),nonzeros=sum(row['nonzeros'] for row in rows))
        document(directory/'receipt.json',dict(kind='joint-state-data-chunk-1',batch=batch,games=rows,
            expandedEvidenceRemoved=True,actualFits=0,newWorlds=256,historicalTerminalExecutions=1))
        batch=dict(batch,receipt=str((directory/'receipt.json').relative_to(output)),receiptSha256=sha(directory/'receipt.json'))
        batches.append(batch);games.extend(rows)
        document(output/'progress.json',dict(completedChunks=len(batches),completeWorlds=len(games),
            historicalReproductions=len(batches),elapsedSeconds=time.time()-started,actualFits=0),immutable=False)
        print(f'Completed and verified archived joint chunk {chunk["index"]}/16; worlds={len(games)}.',flush=True)
    result=dict(kind='joint-state-value-corpus-1',configuration=exp.CONFIGURATION,
        protocolSha256=sha(path),dictionaryPath='joint-state-dictionary.json',dictionarySha256=dictionary['sha256'],
        dictionary=dictionary['metadata'],stateFeatureNames=dictionary['metadata']['stateFeatureNames'],
        policyBindings=protocol['policyBindings'],games=games,batches=batches,
        splitCounts={split:sum(row['split']==split for row in games) for split in ('training','validation','test')},
        rows=sum(row['decisions'] for row in games),nonzeros=sum(row['nonzeros'] for row in games),
        zeroRowWorlds=sum(row['decisions']==0 for row in games),newWorlds=4096,historicalTerminalExecutions=16,
        actualFits=0,policyUpdates=0,activation=False)
    require(len(games)==4096 and len(batches)==16,'Only the complete prospective corpus can be published.')
    document(output/'corpus.json',result)
    artifacts=[output/'corpus.json',target_dictionary,output/'progress.json']
    artifacts.extend(file for directory in (output/'chunks').iterdir() for file in directory.iterdir() if file.is_file())
    return exp.stage_result(output,'collect',True,'All4096 joint worlds and16 historical reproductions archived; no fitting.',
        dict(completedChunks=16,newWorlds=4096,historicalTerminalExecutions=16,actualFits=0,rows=result['rows']),artifacts)


def campaign_spec(root,protocol,files):
    return dict(schemaVersion=1,kind='automata-campaign',inputs=files,
        limits=dict(maximumSeconds=7200,maximumDataBytes=8*exp.GIB,maximumRssBytes=6*exp.GIB,minimumFreeBytes=8*exp.GIB),
        stages=[dict(id='collect',dependsOn=[],maximumSeconds=7200,maximumAttempts=1,result='results/collect.json',
            command=[protocol['python']['executable'],'-E','-s','-S','-B',str(root/'scripts/joint_state_data.py'),'stage',
                '--protocol',str(root/'protocol.json'),'--output','{output}'])])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=('preflight','stage'));parser.add_argument('--protocol',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    protocol=read(args.protocol);validate_inputs(protocol);execution_identity(protocol)
    if args.command=='preflight':
        require(not args.output.exists(),'Zero-game preflight precedes the one attempt.')
        own=[row for row in controller.processes.sample_group(os.getpgrp()) if row.pid==os.getpid()]
        require(len(own)==1 and own[0].rss_bytes>0,'The bounded child requires working external process sampling.')
        print(json.dumps(dict(passed=True,newGames=0,actualFits=0,externalPsSamplerPassed=True,protocolSha256=sha(args.protocol))))
    else:collect(protocol,args.protocol,args.output)


if __name__=='__main__':main()
