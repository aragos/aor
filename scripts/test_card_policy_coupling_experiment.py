"""Coupling operational boundaries; synthetic metadata and opaque records only."""
import hashlib
import json
from pathlib import Path
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import card_advantage_experiment as experiment
import card_policy_coupling_experiment as coupling
from test_card_advantage_run import run as supervisor
from test_card_continuation_experiment import Fixture as PriorFixture, put


def analysis_report(output, expected):
    identity = {'fixed': 'analysis'}; put(output / 'identity.json', identity)
    digest = hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    result = dict(inputs=expected, identitySha256=digest, diagnosticOnly=True, policyPromotion=False,
                  coverage={'passed': True}, primary={'gatePassed': False})
    put(output / 'report.json', result)
    return result


class Fixture(PriorFixture):
    def __init__(self, root):
        super().__init__(root)
        for name in ('card_policy_coupling_experiment.py', 'card_policy_coupling_analysis.py'):
            (self.bin / name).write_text('frozen ' + name); self.entry(self.bin / name)
        self.reference = self.root / 'prior/continuation'
        rows = experiment.read(self.lab / 'requests.json')
        specification = dict(schemaVersion=3, configuration='qualified-profile',
            baseline=str(self.lab / 'inputs/baseline.json'), frozenPurchasePolicy=str(self.lab / 'inputs/purchase.json'),
            continuation=str(self.lab / 'inputs/priority.json'), origins=rows)
        source = self.reference.parent / 'continuation-source.json'; put(source, specification)
        self.reference.mkdir(); (self.reference / 'source.json').write_bytes(source.read_bytes())
        policies = {}
        for key, name in (('baseline', 'baseline.json'), ('frozenPurchasePolicy', 'frozen-purchase-policy.json'),
                          ('continuation', 'continuation.json')):
            path = Path(specification[key]); (self.reference / name).write_bytes(path.read_bytes())
            policies[key] = dict(path=str(path), sha256=experiment.sha(path))
        hashes = []
        for i in range(256):
            path = self.reference / f'origin-{i}.json.gz'; path.write_bytes(f'opaque {i}'.encode()); hashes.append(experiment.sha(path))
        put(self.reference / 'collection.json', dict(kind='card-continuation-diagnostic', source=str(source),
            sourceSha256=experiment.sha(source), signature='old', configuration='qualified-profile', origins=rows, **policies))
        put(self.reference / 'finished.json', dict(kind='card-continuation-diagnostic', signature='old', origins=256, originSha256=hashes))
        put(self.reference / 'state.json', dict(phase='complete', signature='old'))
        old = self.reference.parent; put(old / 'preflight.json', {'ready': True}); put(old / 'analysis/report.json', {'complete': True})
        put(old / 'window-result.json', dict(preflightSha256=experiment.sha(old / 'preflight.json'),
            analysisSha256=experiment.sha(old / 'analysis/report.json'), disposition='continuation-diagnostic-complete',
            diagnosticOnly=True, browserActivated=False, policyPromotion=False))
        linear = self.root / 'linear'; put(linear / 'report.json', dict(localGatePassed=False,
            diagnosticOnly=True, policyPromotion=False, validationDecoded=False))
        put(linear / 'finished.json', {'files': {'report.json': experiment.sha(linear / 'report.json')}})
        selected = rows[:64]; put(self.lab / 'coupling-requests.json', selected)
        chance = lambda seed, r: (seed << 4) ^ 0x37a51b20d ^ r
        put(self.lab / 'coupling-seeds.json', dict(passed=True, overlaps=[], banks=[{'name': 'coupling', 'seeds': [r['seed'] for r in selected]}],
            reusedChanceStreams=[chance(row['seed'], r) for row in selected for r in range(4)],
            newChanceStreams=[chance(row['seed'], r) for row in selected for r in range(4, 8)],
            newWorlds=0, historicalOverlaps=[], intentionalReuse=True))
        self.protocol.update(kind='card-policy-coupling-window', collection={'maximumSeconds': 2700, 'batchSize': 8, 'plan': coupling.PLAN},
            coverage=coupling.COVERAGE, analysis=coupling.ANALYSIS,
            seedReservation=self.entry(self.lab / 'coupling-seeds.json'), originRequests={'coupling': self.entry(self.lab / 'coupling-requests.json')},
            prerequisites={name:self.entry(path) for name,path in {
                'continuationManifest':self.reference / 'collection.json', 'continuationCompletion':self.reference / 'finished.json',
                'continuationPreflight':old / 'preflight.json', 'continuationResult':old / 'window-result.json',
                'continuationReport':old / 'analysis/report.json', 'linearReport':linear / 'report.json',
                'linearCompletion':linear / 'finished.json'}.items()})
        draft = {key:self.protocol[key] for key in ('configuration','collection','coverage','analysis','resources','seedReservation','originRequests')}
        put(self.lab / 'coupling-recipe.json', draft); self.protocol['recipeDraft'] = self.entry(self.lab / 'coupling-recipe.json'); self.save()

    def window(self):
        with patch.object(experiment, '__file__', str(self.bin / 'card_advantage_experiment.py')):
            return coupling.CouplingWindow(self.lab, self.lab / 'protocol.json')


class CouplingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.fixture = Fixture(Path(self.temp.name))
    def tearDown(self): self.temp.cleanup()

    def test_complete_consumed_inputs_are_hash_bound_without_payload_decode(self):
        window = self.fixture.window(); self.assertEqual(64, len(window.requests))
        self.assertFalse((window.lab / 'window-status.json').exists())

    def test_replaced_reference_bytes_stop_before_collection(self):
        (self.fixture.reference / 'origin-63.json.gz').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'record hashes'): self.fixture.window()

    def test_replacement_request_is_rejected_even_after_repinning(self):
        entry = self.fixture.protocol['originRequests']['coupling']; path = Path(entry['file'])
        rows = experiment.read(path); rows[0]['ordinal'] += 1; put(path, rows); self.fixture.repin(entry)
        draft = experiment.read(self.fixture.lab / 'coupling-recipe.json'); draft['originRequests'] = self.fixture.protocol['originRequests']
        put(self.fixture.lab / 'coupling-recipe.json', draft); self.fixture.repin(self.fixture.protocol['recipeDraft'])
        with self.assertRaisesRegex(ValueError, 'original consumed requests'): self.fixture.window()

    def test_unreserved_future_stream_is_rejected(self):
        entry = self.fixture.protocol['seedReservation']; path = Path(entry['file'])
        value = experiment.read(path); value['newChanceStreams'][0] += 20; put(path, value); self.fixture.repin(entry)
        draft = experiment.read(self.fixture.lab / 'coupling-recipe.json'); draft['seedReservation'] = entry
        put(self.fixture.lab / 'coupling-recipe.json', draft); self.fixture.repin(self.fixture.protocol['recipeDraft'])
        with self.assertRaisesRegex(ValueError, 'new streams four through seven'): self.fixture.window()

    def test_collect_uses_new_mode_without_any_priority_continuation(self):
        window = self.fixture.window(); output = self.fixture.source()
        put(output / 'collection.json', dict(kind='card-policy-coupling', schemaVersion=1, collection=coupling.PLAN,
            replicates=8, maxTailsPerOrigin=48, continuation=None, backbonePolicy='F', opponentPolicy='F', tailPolicy='F'))
        with patch.object(window, 'native', return_value=output) as native: window.collect()
        mode, name, source, seconds = native.call_args.args
        self.assertEqual(('coupling', 'coupling', 2700), (mode, name, seconds))
        self.assertEqual('card-policy-coupling', source['kind']); self.assertNotIn('continuation', source)

    def test_analysis_resume_cannot_renew_original_clock(self):
        window = self.fixture.window(); source = self.fixture.source()
        def interrupt(*args):
            put(window.lab / 'analysis/identity.json', {'partial': True}); raise RuntimeError('interrupted')
        with patch.object(window, 'execute', side_effect=interrupt):
            with self.assertRaisesRegex(RuntimeError, 'interrupted'): window.analyze(source)
        original = (window.lab / 'analysis-clock.json').read_bytes()
        def complete(*args): analysis_report(window.lab / 'analysis', experiment.read(window.lab / 'analysis-inputs.json'))
        with patch.object(window, 'execute', side_effect=complete) as execute: window.analyze(source)
        self.assertEqual(original, (window.lab / 'analysis-clock.json').read_bytes())
        self.assertIn('--resume', execute.call_args.args[1])
        with patch.object(window, 'execute') as execute: window.analyze(source)
        execute.assert_not_called()

    def test_analysis_cannot_start_new_clock_after_partial_output(self):
        window = self.fixture.window(); source = self.fixture.source(); put(window.lab / 'analysis/identity.json', {'partial': True})
        with patch.object(window, 'execute') as execute:
            with self.assertRaisesRegex(ValueError, 'missing clock'): window.analyze(source)
        execute.assert_not_called()

    def test_positive_diagnostic_never_promotes_or_launches_another_stage(self):
        window = self.fixture.window(); put(window.lab / 'analysis/report.json', {'complete': True})
        with patch.object(window, 'collect'), patch.object(window, 'analyze', return_value=dict(coverage={'passed':True}, primary={'gatePassed':True})):
            result = window.run()
        self.assertTrue(result['varianceReductionPassed']); self.assertFalse(result['policyPromotion']); self.assertFalse(result['browserActivated'])


class RoutingTest(unittest.TestCase):
    def test_old_modes_keep_native_commands_and_new_mode_uses_origin_records(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); supervisor.STOP.clear()
            for mode, native, prefix in (('collect','card-advantage-collect','origin'),
                    ('evaluate','card-advantage-evaluate','game'),('coupling','card-policy-coupling','origin')):
                with self.subTest(mode=mode):
                    value = SimpleNamespace(mode=mode, output=root, source=root/'input.json', data_root=root)
                    child = SimpleNamespace(poll=lambda:0, returncode=0)
                    with patch.object(supervisor, 'resources'), patch.object(supervisor.subprocess, 'Popen', return_value=child) as launch:
                        supervisor.launch_native(value, ['java'], ['--index','0'], 'native.log', time.time()+60)
                    command = launch.call_args.args[0]
                    self.assertEqual(native, command[command.index('--mode')+1])
                    self.assertEqual(prefix+'-0.json.gz', supervisor.record(value,0)[0].name)

    def test_coupling_completion_rejects_wrong_plan_without_decoding(self):
        from test_card_advantage_experiment import completed_collection
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve(); source=completed_collection(root)
            spec=experiment.read(source); del spec['continuation']; spec.update(kind='card-policy-coupling',schemaVersion=1,collection=coupling.PLAN)
            put(source,spec);(root/'source.json').write_bytes(source.read_bytes())
            manifest=experiment.read(root/'collection.json');manifest.update(kind='card-policy-coupling',collection=coupling.PLAN,sourceSha256=experiment.sha(source));put(root/'collection.json',manifest)
            self.assertTrue(experiment.completed_native(root,source))
            manifest['collection']=dict(coupling.PLAN,replicates=4);put(root/'collection.json',manifest)
            with self.assertRaisesRegex(ValueError,'Coupling completion'):experiment.completed_native(root,source)


if __name__ == '__main__': unittest.main()
