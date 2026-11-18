"""Registered stage boundaries using only constructed parents, arrays and outcomes."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import coordinated_es_math as es
import token_focus_numeric as numeric
import test_token_focus_numeric as fixture


class StageSpecTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fixture.NumericProtocolTests.setUpClass()
        cls.base = fixture.NumericProtocolTests
        helper = cls.base()
        helper.case = cls.base.root / 'constructed-parent96'
        helper.case.mkdir()
        updated = helper.update()
        final = helper.execute('final', dict(kind='token-focus-step-numeric-final-1',
            preparation=cls.base.prep, update=updated), name='final')
        cls.origin = fixture.absolute(final['path'], fixture.read(final)['bundle'])
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()
        fixture.NumericProtocolTests.tearDownClass()

    def setUp(self):
        self.case = self.root / self._testMethodName
        self.case.mkdir()

    def parent(self, *, complete=True, signal=False, count=1560):
        parent = self.case / ('parent-' + str(len(list(self.case.iterdir()))))
        parent.mkdir()
        audit = fixture.write(parent / 'completion-audit.json', dict(
            kind='token-focus-step-original-audit-1', passed=True, complete=True, qualified=False,
            protocolSha256='a' * 64, terminalExecutions=count, researchSignal=signal,
            artifactPins={self.origin['path']:self.origin['sha256']}))
        terminal = fixture.write(parent / 'terminal.json', dict(kind='token-focus-step-terminal-1',
            study=96, complete=complete, status='completed' if complete else 'failed', qualified=False,
            protocolSha256='a' * 64, candidateBundle=self.origin, researchSignal=signal,
            knownCompletedTerminalExecutions=count, reservedTerminalUpperBound=count,
            completionAuditSha256=audit['sha256']))
        closure = fixture.write(parent / 'closure.json', dict(kind='token-focus-step-physical-closure-1',
            study=96, passed=True, scienceComplete=complete, qualified=False,
            protocolSha256='a' * 64, allOwnedProcessesExited=True, originalClocksRenewed=False,
            auditReruns=0, actualCompletedLowerBound=count, actualAttemptedLowerBound=count,
            actualAttemptedUpperBound=count, knownCompletedTerminalExecutions=count,
            controllerReservedTerminalUpperBound=count,
            artifactPins={self.origin['path']:self.origin['sha256'],audit['path']:audit['sha256'],
                          terminal['path']:terminal['sha256']}))
        return dict(parentTerminal=terminal,parentClosure=closure,parentTrialProtocolSha256='a' * 64,
            originPolicy=dict(study=96,tokenNeighborMixture=None,
                original96Final=self.origin,original96Audit=audit))

    def request(self, *, complete=True, signal=False, count=1560):
        schedule = copy.deepcopy(self.base.schedule_value)
        schedule.update(projectionSeed=211,directionSeed=223)
        schedule_item = fixture.write(self.case / 'schedule.json',schedule)
        protocol = fixture.read(self.base.protocol)
        protocol.update(kind='token-focus-second-campaign-1',study=97,
            methodSha256=numeric.STAGES[97].method_sha,numericStageSpec=numeric.STAGES[97].record,
            originWeightsSha256=self.origin['sha256'],seedSchedule=schedule_item,
            scheduleSha256=schedule_item['sha256'],reservedSchedule=schedule,
            **self.parent(complete=complete,signal=signal,count=count))
        protocol_item = fixture.write(self.case / 'protocol.json',protocol)
        return dict(kind='token-focus-step-numeric-prepare-1',protocol=protocol_item,
            seedSchedule=schedule_item,originBundle=self.origin,inferenceAsset=self.base.asset)

    def execute(self,mode,request,name,stage=None):
        item=fixture.write(self.case / (name+'-request.json'),request)
        output=self.case/name
        numeric.run(mode,item['path'],output,owner_guard=lambda:None,
            stage=numeric.STAGES[97] if stage is None else stage)
        return dict(path=str(output/'receipt.json'),sha256=numeric.wire.sha((output/'receipt.json').read_bytes()))

    def test_both_holdout_outcomes_admit_the_identical_audited_parent_final(self):
        for signal in (False,True):
            protocol=self.parent(signal=signal)
            numeric.closed96_origin(numeric.wire.Inputs(),protocol,self.origin)

    def test_incomplete96_is_rejected_before_any_factors(self):
        request=self.request(complete=False)
        with patch.object(numeric.projection,'generate') as draw:
            with self.assertRaisesRegex(ValueError,'originally complete1560'):
                self.execute('prepare',request,'invalid')
            draw.assert_not_called()

    def test_partial_execution_counts_cannot_supply_a_starting_model(self):
        request=self.request(count=1032)
        with patch.object(numeric.projection,'generate') as draw:
            with self.assertRaisesRegex(ValueError,'originally complete1560'):
                self.execute('prepare',request,'invalid')
            draw.assert_not_called()

    def test_resealing_protocol_to94_does_not_create_a_fallback_origin(self):
        request=self.request()
        protocol=fixture.read(request['protocol'])
        protocol['originWeightsSha256']=self.base.origin['sha256']
        request=dict(request,originBundle=self.base.origin,
            protocol=fixture.write(self.case/'fallback-protocol.json',protocol))
        with patch.object(numeric.projection,'generate') as draw:
            with self.assertRaisesRegex(ValueError,'no fallback'):
                self.execute('prepare',request,'invalid')
            draw.assert_not_called()

    def test_wrong_parent_audit_cannot_cross_the_closed_origin_boundary(self):
        request=self.request()
        protocol=fixture.read(request['protocol'])
        wrong=fixture.write(self.case/'wrong-audit.json',dict(fixture.read(
            protocol['originPolicy']['original96Audit']),protocolSha256='b'*64))
        protocol['originPolicy']=dict(protocol['originPolicy'],original96Audit=wrong)
        request=dict(request,protocol=fixture.write(self.case/'wrong-protocol.json',protocol))
        with patch.object(numeric.projection,'generate') as draw:
            with self.assertRaisesRegex(ValueError,'join exactly'):
                self.execute('prepare',request,'invalid')
            draw.assert_not_called()

    def test_default96_cannot_silently_execute_a97_protocol(self):
        with patch.object(numeric.projection,'generate') as draw:
            with self.assertRaisesRegex(ValueError,'exact one-step protocol'):
                self.execute('prepare',self.request(),'invalid',stage=numeric.DEFAULT_STAGE)
            draw.assert_not_called()

    def test97_population_and_final_retain_the_same_stage_and_immutable_heads(self):
        prep=self.execute('prepare',self.request(),'prepared')
        prepared=fixture.read(prep)
        self.assertEqual(prepared['stageSpec'],numeric.STAGES[97].record)
        self.assertEqual(prepared['numericalOriginBundleSha256'],self.origin['sha256'])
        center=fixture.absolute(prep['path'],prepared['center'])
        pop=self.execute('population',dict(kind='token-focus-step-numeric-population-1',
            preparation=prep,center=center),'population')
        pop_item=fixture.absolute(pop['path'],fixture.read(pop)['population']);population=fixture.read(pop_item)
        values=dict(kind='token-focus-step-outcomes-1',complete=True,verified=True,
            populationSha256=pop_item['sha256'],generationBankSha256=population['generationBankSha256'],
            worldSeeds=population['worldSeeds'],members=[dict(index=row['index'],direction=row['direction'],
                sign=row['sign'],bundleSha256=row['bundle']['sha256'],
                winShares=[.5]*16 if row['sign']==1 else [.25]*16) for row in population['members']])
        outcomes=fixture.write(self.case/'outcomes.json',values)
        updated=self.execute('update',dict(kind='token-focus-step-numeric-update-1',
            preparation=prep,population=pop,outcomes=outcomes),'updated')
        final=self.execute('final',dict(kind='token-focus-step-numeric-final-1',
            preparation=prep,update=updated),'final')
        result=fixture.read(final)
        self.assertEqual(result['stageSpec'],numeric.STAGES[97].record)
        bundle=fixture.read(fixture.absolute(final['path'],result['bundle']))
        origin=fixture.read(self.origin)
        for head in ('CARD','PURCHASE','EXPANSION'):
            self.assertEqual(bundle['heads'][head]['sha256'],origin['heads'][head]['sha256'])

    def test_preparation_receipts_cannot_cross_registered_studies(self):
        request=dict(kind='token-focus-step-numeric-population-1',preparation=self.base.prep,
            center=self.base.center0)
        with self.assertRaisesRegex(ValueError,'registered study boundary'):
            self.execute('population',request,'invalid')

    def test_unregistered_stage_cannot_be_constructed(self):
        with self.assertRaisesRegex(ValueError,'registered TOKEN'):
            numeric.StageSpec(98)

    def test_default96_artifact_bytes_match_the_original_frozen_numeric_owner(self):
        original=Path('/Users/aragos/Projects/aor/.aor-training/automata-token-focus-step-20261118/numeric-source/source/token_focus_numeric.py')
        spec=importlib.util.spec_from_file_location('_original_numeric96',original)
        legacy=importlib.util.module_from_spec(spec);sys.modules[spec.name]=legacy;spec.loader.exec_module(legacy)
        request=fixture.write(self.case/'legacy-request.json',self.base.prepare_request)
        legacy.run('prepare',request['path'],self.case/'legacy',owner_guard=lambda:None)
        numeric.run('prepare',request['path'],self.case/'current',owner_guard=lambda:None)
        for name in ('projection.json','directions.json','direction-draw.json','center.json','origin/bundle.json'):
            self.assertEqual((self.case/'legacy'/name).read_bytes(),(self.case/'current'/name).read_bytes())
        self.assertNotIn('stageSpec',fixture.read(dict(path=str(self.case/'current/receipt.json'))))


if __name__=='__main__':
    unittest.main()
