"""Completed three-role metrics, immutable chunks and two-replica admission; no games."""
import copy
import gzip
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import expansion_baseline_screen as screen
from test_expansion_baseline_loop import protocol as loop_protocol


def worlds(replica='PRIMARY_DESKTOP', treatment=.8, control=.4, raw=.5):
    def game(value):
        return dict(wins=[value]*4,rewards=[value]*4,ranks=[value]*4,
                    scores=[{'total':10}]*4)
    return [dict(index=i,replica=replica,seat=i%4,
        roles={role:game(value) for role,value in zip(screen.ROLES,(treatment,control,raw))}) for i in range(1024)]


class ScreenTests(unittest.TestCase):
    def setUp(self):
        temporary=TemporaryDirectory();self.addCleanup(temporary.cleanup);self.root=Path(temporary.name)

    def test_shared_games_produce_exact_three_paired_contrasts(self):
        report=screen.summarize(worlds())
        self.assertAlmostEqual(report['treatmentMinusControl']['rewards']['mean'],.4)
        self.assertAlmostEqual(report['treatmentMinusG3']['rewards']['mean'],.3)
        self.assertAlmostEqual(report['controlMinusG3']['rewards']['mean'],-.1)
        self.assertTrue(report['developmentGatePassed'])
        self.assertFalse(report['qualification'])

    def test_incomplete_or_duplicate_bank_never_exposes_metrics(self):
        values=worlds()
        for invalid in (values[:-1],values[:-1]+[values[0]]):
            with self.assertRaisesRegex(ValueError,'completed bank'):screen.summarize(invalid)

    def test_mixed_replica_rows_reject(self):
        values=worlds();values[-1]['replica']='MAC_REPLICATION'
        with self.assertRaisesRegex(ValueError,'single declared replica'):screen.summarize(values)

    def test_primary_mechanism_does_not_replace_raw_strength_gate(self):
        report=screen.summarize(worlds(treatment=.4,control=.2,raw=.5))
        self.assertTrue(report['primaryMechanismPassed'])
        self.assertFalse(report['developmentGatePassed'])

    def test_zero_difference_is_not_positive_gate(self):
        report=screen.summarize(worlds(treatment=.4,control=.4,raw=.1))
        self.assertFalse(report['primaryMechanismPassed'])

    def test_chaos_uses_controlled_seat_and_original_null_total(self):
        values=worlds()
        for row in values:row['roles']['TREATMENT']['scores'][row['seat']]={'total':None}
        report=screen.summarize(values)
        self.assertEqual(report['treatmentMinusG3']['chaos']['mean'],1.)

    def test_prepare_omits_science_seconds_and_chunk_clamps_to_original_clock(self):
        protocol=dict(java={'executable':'java'},heapMiB=6144,machineCpus=16,runtime='/synthetic')
        with patch.object(screen.exp,'remaining_seconds',return_value=10000):
            prepare=screen.native_command(protocol,'prepare','source','output')
            chunk=screen.native_command(protocol,'chunk','source','output')
        self.assertNotIn('--seconds',prepare)
        self.assertEqual(chunk[chunk.index('--seconds')+1],'7200')
        self.assertIn('-XX:ActiveProcessorCount=16',chunk)
        with patch.object(screen.exp,'remaining_seconds',return_value=15):
            command=screen.native_command(protocol,'historical','source','output')
        self.assertEqual(command[command.index('--seconds')+1],'15')

    def chunk(self):
        root=self.root/'chunk';root.mkdir()
        for i in range(512):(root/f'world-{i}.json.gz').write_bytes(gzip.compress(b'{}'))
        for i in (0,341):
            for role in screen.ROLES:
                for name in ('canonical','replay'):screen.document(root/f'{name}-{i}-{role}.json',{})
        files=screen.inventory(root)
        screen.document(root/'finished.json',dict(kind=screen.KIND,signature='fixture',stage='chunk-0',
            complete=True,worlds=512,reproductions=6,newFits=0,qualification=False,
            files={name:item['sha256'] for name,item in files.items()}))
        return root

    def test_chunk_members_are_verified_before_exact_originals_are_removed(self):
        root=self.chunk();target=self.root/'archives/chunk-0.tar.gz'
        record=screen.archive_chunk(root,target,0,'fixture')
        self.assertFalse(root.exists())
        screen.storage.verify_members(target,record['files'],record['archiveSha256'])
        self.assertEqual(record['kind'],'expansion-baseline-chunk-storage-1')
        self.assertEqual(len(record['files']),525)

    def test_archive_member_tamper_rejects_and_preserves_raw(self):
        root=self.chunk();(root/'world-511.json.gz').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError,'membership'):screen.archive_chunk(root,self.root/'bad.tar.gz',0,'fixture')
        self.assertTrue((root/'world-0.json.gz').is_file())
        self.assertFalse((self.root/'bad.tar.gz').exists())

    def test_archive_verification_failure_preserves_original_members(self):
        root=self.chunk()
        with patch.object(screen.storage,'verify_members',side_effect=ValueError('fixture verification')):
            with self.assertRaisesRegex(ValueError,'fixture verification'):
                screen.archive_chunk(root,self.root/'archive.tar.gz',0,'fixture')
        self.assertTrue((root/'world-511.json.gz').is_file())

    def test_original_arena_paths_resolve_without_changing_role_semantics(self):
        base=self.root/'base';base.mkdir()
        model=base/'model.json';screen.document(model,{})
        original=dict(candidate='model.json',incumbent='model.json',opponents=['model.json'],
            jointPolicy={name:'model.json' for name in ('baseline','sharedBundle','candidateBundle','incumbentBundle')},
            expansionImitation=dict(candidateModel='model.json',incumbentModel='model.json',opponentModels=['model.json']),
            tokenOrder=dict(candidateSelector='FIXED_16_TO_15',incumbentSelector='FIXED_16_TO_15',opponentSelectors=['FIXED_16_TO_15']))
        arena=base/'arena.json';screen.document(arena,original)
        protocol=dict(arena=str(arena),arenaSha256=screen.sha(arena),files={str(model.resolve()):screen.sha(model)},
            screenBank=dict(nativeOffset=20,count=1024))
        admitted=dict(subjects={name:screen.subject(model) for name in ('treatmentPolicy','controlPolicy')})
        result=screen.arena_source(protocol,admitted)
        self.assertEqual(result['candidate'],str(model.resolve()))
        self.assertEqual(result['jointPolicy']['baseline'],str(model.resolve()))
        self.assertEqual(result['tokenOrder'],original['tokenOrder'])
        self.assertEqual(screen.read(arena),original)
        self.assertEqual(result['pairsPerConfiguration'],1024)

    def test_ineligible_learning_rejects_before_any_final_actor_access(self):
        audit=self.root/'audit.json'
        screen.document(audit,dict(kind='expansion-baseline-learning-audit-1',passed=True,complete=False))
        with patch.object(screen,'subject') as opening:
            with self.assertRaisesRegex(ValueError,'complete two-arm'):
                screen.admission({},self.root/'unused',self.root/'learning',audit)
        opening.assert_not_called()


class CombinedTests(unittest.TestCase):
    def setUp(self):
        temporary=TemporaryDirectory();self.addCleanup(temporary.cleanup);self.root=Path(temporary.name)

    def inputs(self, mac_treatment=.8, desktop_treatment=.8):
        inputs={}
        for ordinal,replica in enumerate(('PRIMARY_DESKTOP','MAC_REPLICATION')):
            root=self.root/replica;root.mkdir()
            protocol=loop_protocol(root,replica)
            original={name:protocol['actor'] for name in ('candidate','incumbent')}
            original['opponents']=[protocol['actor']]
            screen.document(root/'arena.json',original)
            protocol.update(arena=str(root/'arena.json'),arenaSha256=screen.sha(root/'arena.json'))
            baseline=dict(path=str(root/'actor.json'),sha256='b'*64,snapshot='prior.json')
            phase=dict(path=str(root/'actor.json'),sha256='c'*64,encoderVersion='synthetic',
                       stateFeatureNames=['state'],actionFeatureNames=['action'])
            bundle=dict(path=str(root/'bundle.json'),sha256=str(ordinal)*64,kind='joint',
                configuration='synthetic',baselineSha256='b'*64,card=phase,purchase=phase)
            prepared=dict(configuration='synthetic',rulesVersion=5,dataVersion=1,actorVersion='actor2',
                dictionaries={'stateFeatureNames':['state'],'actionFeatureNames':['action']},
                controlledSeat='index % playerCount',opponentIndices=[0,0,0,0],reward='original',
                policyBindings=dict(jointPolicy=dict(version='joint',baseline=baseline,
                    **{name:bundle for name in ('sharedBundle','candidateBundle','incumbentBundle')}),
                    expansionImitation={'candidateModel':baseline},tokenOrder={'candidateSelector':'fixed'}))
            screen.document(root/'prepared.json',prepared)
            protocol['prepared']=screen.subject(root/'prepared.json')
            jar=root/'runtime/lib/synthetic.jar';jar.parent.mkdir(parents=True);jar.write_bytes(b'synthetic')
            protocol['runtime']=str(root/'runtime');protocol['files'][str(jar)]=screen.sha(jar)
            # All numbers are synthetic metadata, never reserved scientific streams.
            protocol['offsets']=[100*ordinal+i for i in range(8)]
            protocol['screenBank']['nativeOffset']=100*ordinal+20
            protocol['shuffleSeeds']=[1000000+1000*ordinal+4*i for i in range(8)]
            screen.document(root/'protocol.json',protocol)
            report=screen.summarize(worlds(replica,treatment=desktop_treatment if ordinal==0 else mac_treatment))
            screen.document(root/'report.json',report)
            audit=dict(replica=replica,complete=True,eligibleFinalActors=True,
                protocolSha256=screen.sha(root/'protocol.json'),
                preparedSha256=screen.sha(root/'prepared.json'),
                arms={'TREATMENT':dict(policyPath=str(root/'only-final8.json'),policySha256='a'*64)})
            screen.document(root/'learningAudit.json',audit)
            terminal=dict(kind='expansion-baseline-terminal-audit-1',replica=replica,passed=True,
                status='completed',allOwnedProcessesExited=True,terminalExecutions=7198,
                protocolSha256=screen.sha(root/'protocol.json'),reportSha256=screen.sha(root/'report.json'),
                learningAuditSha256=screen.sha(root/'learningAudit.json'))
            screen.document(root/'terminalAudit.json',terminal)
            inputs[replica]={name:screen.subject(root/f'{name}.json') for name in
                ('protocol','report','learningAudit','terminalAudit','prepared','arena')}
        return inputs

    def test_both_mechanisms_and_desktop_raw_gate_nominate_only_desktop(self):
        result=screen.combine(self.inputs())
        self.assertTrue(result['nominationPassed'])
        self.assertEqual(result['nominee']['replica'],'PRIMARY_DESKTOP')
        self.assertEqual(result['nominee']['batch'],8)
        self.assertEqual(result['terminalExecutions'],14396)
        self.assertFalse(result['qualification'])

    def test_failed_replication_prevents_nomination(self):
        result=screen.combine(self.inputs(mac_treatment=.3))
        self.assertFalse(result['nominationPassed'])
        self.assertIsNone(result['nominee'])

    def test_successful_mac_never_replaces_failed_desktop(self):
        result=screen.combine(self.inputs(desktop_treatment=.45))
        self.assertTrue(result['mechanismPassed']['MAC_REPLICATION'])
        self.assertIsNone(result['nominee'])

    def test_one_completed_replica_is_not_interpreted(self):
        values=self.inputs();del values['MAC_REPLICATION']
        with self.assertRaisesRegex(ValueError,'Both exact replicas'):screen.combine(values)

    def test_mutated_report_cannot_satisfy_admitted_terminal(self):
        values=self.inputs();path=Path(values['MAC_REPLICATION']['report']['path']);path.write_text('{}')
        with self.assertRaisesRegex(ValueError,'subject changed'):screen.combine(values)

    def test_running_groups_prevent_interpretation(self):
        values=self.inputs();item=values['MAC_REPLICATION']['terminalAudit']
        value=screen.read(item['path']);value['allOwnedProcessesExited']=False
        screen.document(item['path'],value,immutable=False);item['sha256']=screen.sha(item['path'])
        with self.assertRaisesRegex(ValueError,'cleaned'):screen.combine(values)

    def test_reused_replica_streams_reject(self):
        values=self.inputs();primary=screen.read(values['PRIMARY_DESKTOP']['protocol']['path'])
        item=values['MAC_REPLICATION']['protocol'];other=screen.read(item['path'])
        other['offsets']=primary['offsets']
        screen.document(item['path'],other,immutable=False);item['sha256']=screen.sha(item['path'])
        for name in ('learningAudit','terminalAudit'):
            current=values['MAC_REPLICATION'][name];value=screen.read(current['path'])
            value['protocolSha256']=item['sha256']
            if name=='terminalAudit':value['learningAuditSha256']=values['MAC_REPLICATION']['learningAudit']['sha256']
            screen.document(current['path'],value,immutable=False);current['sha256']=screen.sha(current['path'])
        with self.assertRaisesRegex(ValueError,'disjoint'):screen.combine(values)

    def test_different_g3_component_bytes_cannot_be_combined_as_replication(self):
        values=self.inputs();mac=values['MAC_REPLICATION']
        prepared=screen.read(mac['prepared']['path'])
        prepared['policyBindings']['jointPolicy']['sharedBundle']['card']['sha256']='d'*64
        screen.document(mac['prepared']['path'],prepared,immutable=False)
        mac['prepared']['sha256']=screen.sha(mac['prepared']['path'])
        protocol=screen.read(mac['protocol']['path']);protocol['prepared']['sha256']=mac['prepared']['sha256']
        screen.document(mac['protocol']['path'],protocol,immutable=False);mac['protocol']['sha256']=screen.sha(mac['protocol']['path'])
        audit=screen.read(mac['learningAudit']['path'])
        audit.update(protocolSha256=mac['protocol']['sha256'],preparedSha256=mac['prepared']['sha256'])
        screen.document(mac['learningAudit']['path'],audit,immutable=False)
        mac['learningAudit']['sha256']=screen.sha(mac['learningAudit']['path'])
        terminal=screen.read(mac['terminalAudit']['path'])
        terminal.update(protocolSha256=mac['protocol']['sha256'],learningAuditSha256=mac['learningAudit']['sha256'])
        screen.document(mac['terminalAudit']['path'],terminal,immutable=False)
        mac['terminalAudit']['sha256']=screen.sha(mac['terminalAudit']['path'])
        with self.assertRaisesRegex(ValueError,'identical fixed science'):screen.combine(values)


if __name__=='__main__':unittest.main()
