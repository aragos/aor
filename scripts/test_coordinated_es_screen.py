"""Constructed complete-bank and final-publication identity checks; no model inference."""
from pathlib import Path
import tempfile
import unittest

import automata_experiments as exp
import coordinated_es_admission as admission
import coordinated_es_plan as p
import coordinated_es_screen as s
import coordinated_es_storage as storage
from token_paired_campaign_audit import Pins
from test_coordinated_es_plan import protocol
from test_token_paired_screen import world as original_world


def world(plan,index):
    value=original_world(plan,index);value['kind']=s.KIND
    value['roles']={'FINAL':value['roles']['FINAL'],'RAW_G3':value['roles']['RAW_T']}
    return value


def zero_report():
    return dict(kind=p.PREFIX+'-screen-report-1',worlds=2048,intervalMultiplier=1.96,
        finalMinusRawG3={metric:exp.interval([0.]*2048) for metric in ('wins','rewards','ranks','chaos')},
        developmentGatePassed=False,qualification=False,activated=False,automaticSuccessor=False)


class ScreenTests(unittest.TestCase):
    def test_complete_zero_gain_has_no_candidate(self):
        plan=p.Plan.from_record(protocol())
        self.assertFalse(s.verify_report([world(plan,index) for index in range(2048)],zero_report()))

    def test_partial_world_bank_cannot_open_effects(self):
        with self.assertRaisesRegex(ValueError,'complete original'):s.verify_report([],zero_report())

    def test_interval_owner_cannot_change(self):
        plan=p.Plan.from_record(protocol());report=zero_report();report['intervalMultiplier']=1.95996398454
        with self.assertRaisesRegex(ValueError,'complete original'):
            s.verify_report([world(plan,index) for index in range(2048)],report)

    def test_report_cannot_invent_positive_reward(self):
        plan=p.Plan.from_record(protocol());report=zero_report();report['finalMinusRawG3']['rewards']['mean']=.01
        with self.assertRaisesRegex(ValueError,'intervals'):
            s.verify_report([world(plan,index) for index in range(2048)],report)

    def test_development_result_cannot_activate_or_start_a_successor(self):
        plan=p.Plan.from_record(protocol());report=zero_report();report['automaticSuccessor']=True
        with self.assertRaisesRegex(ValueError,'qualification'):
            s.verify_report([world(plan,index) for index in range(2048)],report)

    def test_role_rewards_remain_authoritative(self):
        plan=p.Plan.from_record(protocol());value=world(plan,0)
        s.shared.validate_world(plan,value,0,'b'*64,kind=s.KIND,roles=s.ROLES)
        value['roles']['RAW_G3']['rewards'][0]=.5
        with self.assertRaisesRegex(ValueError,'terminal label'):
            s.shared.validate_world(plan,value,0,'b'*64,kind=s.KIND,roles=s.ROLES)


class HistoricalIndicesTests(unittest.TestCase):
    def setUp(self):
        self.plan=p.Plan.from_record(protocol());original=self.plan.record['original']
        self.gate=dict(kind=p.PREFIX+'-historical-admission-1',complete=True,passed=True,
            zeroFailures=True,protocolSha256='a'*64,scopeSha256=original['scopeSha256'],
            ecologySha256=original['ecologySha256'],originalBundleSha256=original['bundleSha256'],
            scientificTerminalExecutions=2,attemptedGames=2,completedGames=2,
            controls=[dict(globalIndex=global_index,index=local_index,side='candidate',
                canonicalVerified=True,execution='ZERO_REFERENCE') for global_index,local_index in ((0,0),(4095,511))])

    def test_global4095_retains_original_chunk_local511(self):
        s.verify_historical(self.gate,self.plan,'a'*64)

    def test_global_identity_cannot_be_replaced_by_local_index(self):
        self.gate['controls'][1]['globalIndex']=511
        with self.assertRaisesRegex(ValueError,'historical sides'):s.verify_historical(self.gate,self.plan,'a'*64)

    def test_global_index_cannot_be_used_as_native_execution_index(self):
        self.gate['controls'][1]['index']=4095
        with self.assertRaisesRegex(ValueError,'historical sides'):s.verify_historical(self.gate,self.plan,'a'*64)

    def test_different_local_world_cannot_reuse_declared_global_identity(self):
        self.gate['controls'][1]['index']=510
        with self.assertRaisesRegex(ValueError,'historical sides'):s.verify_historical(self.gate,self.plan,'a'*64)


class ShardIdentityTests(unittest.TestCase):
    def setUp(self):
        self.plan=p.Plan.from_record(protocol());self.worlds={index:world(self.plan,index) for index in range(128,256)}
        self.bindings=dict(protocolSha256='c'*64,finalBundleSha256='d'*64,calibrationSha256='e'*64)
        identity=dict(seeds=self.plan.record['screenBank']['seeds'],roles=list(s.ROLES),
            replayIndices=list(p.SCREEN_REPLAYS),scopeSha256='2'*64,ecologySha256='3'*64,
            originalBundleSha256='1'*64,**self.bindings)
        self.files={f'world-{index}.json.gz':dict(sha256='f'*64,size=1) for index in self.worlds}
        self.files['finished.json']=dict(sha256='f'*64,size=1)
        self.files.update({name:dict(sha256='f'*64,size=1) for name in
            ('manifest.json','started.json','study-source.json','arena-source.json','admission.json','historical-admission.json')})
        self.manifest=dict(kind=s.KIND,globalStart=128,count=128,signature='a'*64,scienceSha256='b'*64,
            scienceIdentity=identity,ownedReplayIndices=[],**{key:dict(sha256='f'*64,snapshot=name) for key,name in
                (('source','study-source.json'),('arena','arena-source.json'),('admission','admission.json'),
                 ('historicalAdmission','historical-admission.json'))})
        self.finished=dict(kind=s.KIND,complete=True,stage='chunk',globalStart=128,count=128,signature='a'*64,
            scienceSha256='b'*64,scienceIdentity=identity,finalAdmissionSha256='f'*64,historicalAdmissionSha256='f'*64,
            files={name:item['sha256'] for name,item in self.files.items() if name!='finished.json'},reproductions=0,
            scientificTerminalExecutions=256,attemptedGames=256,completedGames=256,
            worlds=[dict(index=index,file=f'world-{index}.json.gz',sha256='f'*64) for index in self.worlds])

    def check(self):
        s.chunk_evidence(self.plan,1,self.files,self.manifest,self.finished,self.worlds,{}, {},'f'*64,'f'*64,self.bindings)

    def test_exact_complete_shard_has256_actual_games(self):
        self.check()

    def test_screened_bundle_cannot_differ_from_portable_admission(self):
        self.bindings['finalBundleSha256']='0'*64
        with self.assertRaisesRegex(ValueError,'fixed role'):self.check()

    def test_attempted_game_failure_cannot_be_hidden_by_complete_counts(self):
        self.finished['attemptedGames']=257
        with self.assertRaisesRegex(ValueError,'original completed'):self.check()

    def test_worlds_alone_cannot_replace_native_startup_and_subjects(self):
        del self.files['started.json'];del self.finished['files']['started.json']
        with self.assertRaisesRegex(ValueError,'startup/manifest'):self.check()

    def test_snapshot_cannot_differ_from_declared_admission(self):
        self.manifest['admission']['sha256']='0'*64
        with self.assertRaisesRegex(ValueError,'snapshot'):self.check()


class FinalIdentityTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name);self.final=self.root/'final';self.final.mkdir()
        self.files={}
        for name in ('originalBundle','projection','calibration','center','historical'):
            self.files[name]=storage.publish_json(self.final/(name+'.json'),dict(constructed=name))
        heads={}
        for head in p.HEADS:
            item=storage.publish_json(self.final/(head+'.json'),dict(constructed=head))
            heads[head]=dict(path=head+'.json',sha256=item['sha256'],dictionarySha256='6'*64)
        self.files['finalBundle']=storage.publish_json(self.final/'finalBundle.json',dict(kind=p.PREFIX+'-bundle-1',heads=heads))
        row=protocol();row['original']['bundleSha256']=self.files['originalBundle']['sha256']
        row['original']['projectionSha256']=self.files['projection']['sha256'];self.plan=p.Plan.from_record(row)
        path=self.final/'protocol.json';path.write_bytes(self.plan.wire+b'\n');self.files['protocol']=p.subject(path)
        self.proof=dict(kind=p.PREFIX+'-center-materialization-1',generation=16,finalCandidate=True,
            **{field:self.files[name]['sha256'] for name,field in
                (('protocol','protocolSha256'),('center','centerSha256'),('projection','projectionSha256'),
                 ('originalBundle','originalBundleSha256'),('calibration','calibrationSha256'),('finalBundle','bundleSha256'))})
        self.files['centerMaterialization']=storage.publish_json(self.final/'materialization.json',self.proof)
        self.files['generationAudit']=storage.publish_json(self.final/'generation-audit.json',dict(
            kind=p.PREFIX+'-generation-audit-1',passed=True,complete=True,completedGenerations=16,
            finalCenterSha256=self.files['center']['sha256']))
        self.record=dict(kind=p.PREFIX+'-final-admission-1',complete=True,completedGenerations=16,
            validationEffectsUsedForSelection=False,scopeSha256=row['original']['scopeSha256'],
            ecologySha256=row['original']['ecologySha256'],**{name:admission.relative(item['path'],self.final/'admission.json')
                for name,item in self.files.items()})

    def publish(self):return storage.publish_json(self.final/'admission.json',self.record)

    def test_exact_final_materialization_identity_is_exposed(self):
        subjects,identity=s.final_bindings(self.plan,self.files['protocol']['sha256'],self.publish(),Pins())
        self.assertEqual(subjects['finalBundle'],self.files['finalBundle'])
        self.assertEqual(identity['calibrationSha256'],self.files['calibration']['sha256'])

    def test_earlier_center_cannot_be_called_final(self):
        self.record['completedGenerations']=15
        with self.assertRaisesRegex(ValueError,'mean16'):
            s.final_bindings(self.plan,self.files['protocol']['sha256'],self.publish(),Pins())

    def test_bundle_substitution_cannot_reuse_a_valid_materialization(self):
        other=p.read(self.files['finalBundle']['path']);other['heads']['CARD']=dict(other['heads']['TOKEN'])
        changed=storage.publish_json(self.final/'other-bundle.json',other)
        self.record['finalBundle']=admission.relative(changed['path'],self.final/'admission.json')
        with self.assertRaisesRegex(ValueError,'exact materialization'):
            s.final_bindings(self.plan,self.files['protocol']['sha256'],self.publish(),Pins())

    def test_changed_final_head_bytes_fail_before_eligibility(self):
        final=self.publish();(self.final/'TOKEN.json').write_text('{}')
        with self.assertRaises(ValueError):
            s.final_bindings(self.plan,self.files['protocol']['sha256'],final,Pins())

    def test_terminal_eligibility_cannot_substitute_the_screened_bundle(self):
        final=self.publish();complete=storage.publish_json(self.root/'screen-complete.json',dict(
            complete=True,protocolSha256=self.files['protocol']['sha256'],finalAdmission=final,
            historicalAdmission=self.files['historical'],chunks=[]))
        changed=storage.publish_json(self.root/'unrelated.json',{'different':True})
        storage.publish_json(self.root/'terminal.json',dict(finalAdmission=final,screenComplete=complete,finalBundle=changed))
        with self.assertRaisesRegex(ValueError,'Terminal eligibility'):
            s.audit_completed(self.files['protocol'],self.root,'DESKTOP',Pins())

    def test_portable_subject_cannot_escape_admission_directory(self):
        outside=storage.publish_json(self.root/'outside.json',{})
        with self.assertRaisesRegex(ValueError,'normalized relative paths'):
            admission.resolve({'path':'../outside.json','sha256':outside['sha256']},self.final/'admission.json')


if __name__=='__main__':unittest.main()
