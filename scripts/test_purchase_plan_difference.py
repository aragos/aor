"""Fixed profile/source/ordering tests only; no children, games or retained models."""
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import purchase_plan_difference as driver


def protocol_fixture():
    protocol=dict(kind=driver.KIND,nativeSourceCommit=driver.NATIVE_COMMIT,workers=10,heapMiB=6144,
        maximumSeconds=18000,readOnlyAuditSeconds=900,maximumAttempts=1,stageSeconds=dict(driver.STAGES),
        maximumTerminalGames=67584,maximumDataBytes=8*driver.GIB,minimumFreeBytes=8*driver.GIB,
        maximumRssBytes=12*driver.GIB,numericalMaximumRssBytes=6*driver.GIB,minimumGpuFreeBytes=4*driver.GIB,
        epochSeeds=list(driver.EPOCH_SEEDS),maximumCriticFits=2,actorFits=False,runtimeActivation=False,
        automaticRetry=False,automaticSuccessor=False,critic='/fixture/critic',dictionary='/fixture/dictionary',
        roles={k:dict(path='/fixture/'+k,sha256=h) for k,h in driver.ROLES.items()},
        predictorDescriptor=dict(path='/fixture/descriptor',sha256='d'*64),chunks=[],inputs={},
        java=dict(executable='/fixture/java'),runtime='/fixture/runtime',cpuPython='/fixture/cpu',gpuPython='/fixture/gpu')
    hashes={protocol['critic']:driver.data.CHECKPOINT,protocol['dictionary']:driver.data.DICTIONARY}
    for key,digest in [('seedAudit',driver.AUDIT),('origins',driver.ORIGINS),('modelReview',driver.MODEL_REVIEW)]:
        protocol[key]=dict(path='/fixture/'+key,sha256=digest);hashes[protocol[key]['path']]=digest
    hashes.update({v['path']:v['sha256'] for v in protocol['roles'].values()})
    origins,banks,documents=[],{},{}
    for chunk in range(1,9):
        source=driver.joint_source(protocol,chunk);offset=source['seed'];split=driver.data.split((chunk-1)*256)
        names=['origin']+[f'proposal-{i}' for i in range(2,8)]+[f'future-{i}' for i in range(1,5)]
        for j,name in enumerate(names):
            banks[f'chunk-{chunk:02d}/{name}']=dict(count=256,chunk=chunk,split=split,nativeOffset=offset+j,
                seeds=driver.exp.arena_seeds(offset+j,256))
        rows=[dict(chunk=chunk,controlledSeat=i%4,targetEpoch=1+(i//4)%3,globalIndex=(chunk-1)*256+i,index=i,
            split=split,gameSeed=-1000000000+offset+i*8191,
            proposalSeeds=[-1000000000+offset+j+i*8191 for j in range(1,7)],
            futureChanceSeeds=[-1000000000+offset+j+i*8191 for j in range(7,11)]) for i in range(256)]
        origins+=rows
        item=dict(index=chunk,split=split,**{k:dict(path=f'/fixture/{chunk}/{k}',sha256=str(chunk)*64)
                                             for k in ('collection','source','prepared')})
        documents[item['collection']['path']]=source
        documents[item['source']['path']]=driver.ranking_source(item['collection'],protocol['predictorDescriptor'],rows)
        documents[item['prepared']['path']]=dict(origins=256,sourceSha256=item['source']['sha256'],maximumTerminalGames=8448,
            collection=dict(seeds=[r['gameSeed'] for r in rows]),predictorDescriptorSha256='d'*64,
            inputs=list(protocol['roles'].values()))
        hashes.update({v['path']:v['sha256'] for k,v in item.items() if k in ('collection','source','prepared')})
        protocol['chunks'].append(item)
    banks['epoch-order']=dict(role='shared-two-arm-epoch-order',count=20,seeds=list(driver.EPOCH_SEEDS))
    documents[protocol['seedAudit']['path']]=dict(seedBanks=banks)
    documents[protocol['origins']['path']]=origins
    protocol['inputs']=hashes
    return protocol,documents,hashes


class SourceContractTest(unittest.TestCase):
    def validate(self,fixture):
        protocol,documents,hashes=fixture
        with mock.patch.object(driver,'read',side_effect=lambda p:documents[str(p)]), \
             mock.patch.object(driver,'sha',side_effect=lambda p:hashes[str(p)]):
            driver.validate_inputs(protocol)

    def test_actual_shaped_eight_chunk_schedule_is_accepted(self):
        self.validate(protocol_fixture())

    def test_source_preserves_sampled_g3_and_distinct_m8_f0(self):
        protocol,_,_=protocol_fixture();source=driver.joint_source(protocol,1)
        self.assertEqual('/fixture/ordinary',source['baseline'])
        self.assertEqual('/fixture/prior',source['sharedPurchaseBaseline'])
        self.assertEqual(['/fixture/bundle'],source['opponents'])
        self.assertEqual('/fixture/expansion',source['expansionImitation']['candidateModel'])
        self.assertNotIn('candidateSelection',source)

    def test_actual_original_worlds_are_disjoint_across_chunks(self):
        protocol,documents,_=protocol_fixture()
        seeds=[r['gameSeed'] for r in documents[protocol['origins']['path']]]
        self.assertEqual(2048,len(set(seeds)))
        self.assertEqual(70004148,driver.joint_source(protocol,1)['seed'])
        self.assertEqual(70004225,driver.joint_source(protocol,8)['seed'])

    def test_changed_split_is_rejected(self):
        fixture=protocol_fixture();fixture[0]['chunks'][7]['split']='TRAIN'
        with self.assertRaises(ValueError):self.validate(fixture)

    def test_changed_future_stream_is_rejected(self):
        fixture=protocol_fixture();fixture[1][fixture[0]['origins']['path']][-1]['futureChanceSeeds'][0]+=1
        with self.assertRaises(ValueError):self.validate(fixture)

    def test_global_index_never_replaces_native_local_index(self):
        fixture=protocol_fixture();fixture[1][fixture[0]['origins']['path']][256]['index']=256
        with self.assertRaises(ValueError):self.validate(fixture)

    def test_unaudited_shuffle_stream_is_rejected(self):
        fixture=protocol_fixture();fixture[1][fixture[0]['seedAudit']['path']]['seedBanks']['epoch-order']['seeds'][0]+=1
        with self.assertRaises(ValueError):self.validate(fixture)

    def test_no_third_fit_or_retry_is_admitted(self):
        fixture=protocol_fixture();fixture[0]['maximumCriticFits']=3
        with self.assertRaises(ValueError):self.validate(fixture)

    def test_fixed_scientific_subdeadlines_do_not_exceed_original_five_hours(self):
        self.assertEqual(18000,sum(driver.STAGES.values()))
        self.assertEqual(67584,8*256*(1+8*4))


class ExecutionContractTest(unittest.TestCase):
    def test_native_cli_hours_stays_under_existing_two_hour_parser_cap(self):
        protocol,_,_=protocol_fixture()
        with mock.patch.object(driver.exp,'remaining_seconds',return_value=14000):
            command=driver.native_command(protocol,protocol['chunks'][0],Path('/fixture/out'),'tails')
        self.assertEqual('2.0',command[command.index('--hours')+1])
        self.assertEqual('/fixture/out/predictions/predictions.json',command[command.index('--predictions')+1])

    def test_original_remaining_deadline_shortens_native_child(self):
        protocol,_,_=protocol_fixture()
        with mock.patch.object(driver.exp,'remaining_seconds',return_value=36):
            command=driver.native_command(protocol,protocol['chunks'][0],Path('/fixture/out'),'public')
        self.assertEqual(.01,float(command[command.index('--hours')+1]))

    def test_failed_preceding_stage_cannot_launch_next(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);driver.document(root/'results/collect.json',dict(status='rejected',artifacts={}))
            with mock.patch.dict(driver.os.environ,AOR_CAMPAIGN_STAGE_ID='fit'), \
                 mock.patch.object(driver.execution,'run_child',side_effect=AssertionError('Unexpected child')):
                with self.assertRaises(ValueError):driver.stage({},Path('/unused'),root,'fit')

    def test_gpu_child_uses_one_cpu_without_mutating_parent_environment(self):
        protocol,_,_=protocol_fixture();before=dict(driver.os.environ)
        command=driver.numerical_command(protocol,Path('/fixture/protocol'),Path('/fixture/run'),'numeric-fit')
        self.assertIn('OMP_NUM_THREADS=1',command)
        self.assertIn('CUBLAS_WORKSPACE_CONFIG=:4096:8',command)
        self.assertEqual(before,dict(driver.os.environ))


if __name__=='__main__':unittest.main()
