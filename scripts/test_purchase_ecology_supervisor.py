"""Supervisor ownership and numerical isolation checks; no native games or updates."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import purchase_ecology_campaign as campaign
import purchase_ecology_environment as environment


def numerical_identity():
    return dict(executable='/venv/bin/python', torchThreads=1, deterministicAlgorithms=True,
                device='cpu', cuda=None, mpsUsed=False, gpuUsed=False)


def recovery_protocol():
    return dict(maximumSeconds=14400, python={'executable': '/pure-python', 'version': '3.14'},
        numerical={'resolvedExecutable': '/pure-python', 'pythonVersion': '3.14'},
        recovery=dict(originalStartedEpoch=1000, originalDeadlineEpoch=15400,
            originalTrainStartedEpoch=1005, originalTrainDeadlineEpoch=11805, launchByEpoch=2000,
            maximumSeconds=13400, trainMaximumSeconds=9805, prefixReceipt='/prefix-receipt.json',
            originalInputPins={'/original/input': 'a' * 64}))


class NumericalTest(unittest.TestCase):
    def test_inspection_preserves_venv_launcher_and_site_packages(self):
        identity = numerical_identity()
        result = subprocess.CompletedProcess([], 0, stdout=json.dumps(identity), stderr='')
        with patch.object(environment.subprocess, 'run', return_value=result) as run:
            self.assertEqual(identity, environment.inspect('/venv/bin/python'))
        self.assertEqual(['/venv/bin/python', '-E', '-s', '-B'], run.call_args.args[0][:4])
        self.assertNotIn('-S', run.call_args.args[0])

    def test_inspection_rejects_mps_execution(self):
        identity = dict(numerical_identity(), device='mps', mpsUsed=True)
        result = subprocess.CompletedProcess([], 0, stdout=json.dumps(identity), stderr='')
        with patch.object(environment.subprocess, 'run', return_value=result):
            with self.assertRaisesRegex(ValueError, 'CPU settings'):
                environment.inspect('/venv/bin/python')

    def test_inspection_fails_instead_of_installing_or_retrying(self):
        result = subprocess.CompletedProcess([], 1, stdout='', stderr='missing package')
        with patch.object(environment.subprocess, 'run', return_value=result) as run:
            with self.assertRaisesRegex(ValueError, 'missing package'):
                environment.inspect('/venv/bin/python')
        run.assert_called_once()

    def test_verify_rejects_changed_update_defaults(self):
        expected = dict(numerical_identity(), configDefaults={'learningRate': .0003})
        current = dict(expected, configDefaults={'learningRate': .001})
        with patch.object(environment, 'current', return_value=current):
            with self.assertRaisesRegex(ValueError, 'defaults'):
                environment.verify(expected)

    def test_standard_library_inventory_excludes_package_and_bytecode_directories(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            for name in ('os.py', 'site-packages/mutable.py', '__pycache__/cache.py', 'lib-dynload/module.so'):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('fixture')
            with patch.object(environment.sysconfig, 'get_path', return_value=str(root)), \
                 patch.object(environment.sysconfig, 'get_config_var', return_value=None):
                standard, files = environment.interpreter_files()
        self.assertEqual(root, standard)
        self.assertIn(root / 'os.py', files)
        self.assertIn(root / 'lib-dynload/module.so', files)
        self.assertNotIn(root / 'site-packages/mutable.py', files)
        self.assertNotIn(root / '__pycache__/cache.py', files)


class SupervisorTest(unittest.TestCase):
    def test_recovery_retains_original_global_and_actual_train_deadlines(self):
        spec = campaign.campaign_spec(Path('/package'), recovery_protocol(), {})
        self.assertEqual((13400, 15400), (spec['limits']['maximumSeconds'], spec['limits']['absoluteDeadlineEpoch']))
        self.assertEqual([(9805, 11805), (3600, 15400)],
                         [(row['maximumSeconds'], row['absoluteDeadlineEpoch']) for row in spec['stages']])
        self.assertTrue(all(row['maximumAttempts'] == 1 for row in spec['stages']))

    def test_recovery_rejects_renewed_allowance(self):
        protocol = recovery_protocol()
        protocol['recovery']['maximumSeconds'] += 1
        with self.assertRaisesRegex(ValueError, 'may not renew'):
            campaign.execution_limits(protocol)

    def test_recovery_rejects_different_pure_interpreter(self):
        protocol = recovery_protocol()
        protocol['python']['version'] = '3.9'
        with self.assertRaisesRegex(ValueError, 'same interpreter'):
            campaign.execution_limits(protocol)

    def test_recovery_rechecks_launch_window_after_preflight(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
            root = Path(directory)
            protocol = dict(recovery_protocol(), environment={'variables': {}}, frozenFiles={})
            campaign.document(root / 'protocol.json', protocol)
            protocol_sha = campaign.sha(root / 'protocol.json')
            spec = campaign.campaign_spec(root, protocol, {str(root / 'protocol.json'): protocol_sha})
            campaign.document(root / 'campaign.json', spec)
            options = SimpleNamespace(protocol_sha=protocol_sha, campaign_sha=campaign.sha(root / 'campaign.json'))
            with patch.object(campaign, 'verify_execution'), patch.object(campaign, 'validate_science'), \
                 patch.object(campaign, 'external_child_preflight', return_value={'passed': True}) as preflight, \
                 patch.object(campaign.time, 'time', return_value=2001), \
                 patch.object(campaign.controller, 'main') as launch:
                with self.assertRaisesRegex(ValueError, 'launch window expired'):
                    campaign.run(root, options)
            preflight.assert_called_once()
            launch.assert_not_called()

    def test_recovery_pins_original_prefix_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = root / 'original-input.json'
            original.write_text('unchanged')
            digest = campaign.sha(original)
            package = root / 'package'
            package.mkdir()
            protocol = dict(java={'files': {}}, python={'files': {}}, numerical={'files': {}},
                            recovery={'originalInputPins': {str(original): digest}})
            self.assertEqual({str(original): digest}, campaign.input_files(package, protocol))

    def test_recovery_rejects_conflicting_original_pin(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = root / 'input.json'
            original.write_text('unchanged')
            protocol = dict(java={'files': {}}, python={'files': {}}, numerical={'files': {}},
                            recovery={'originalInputPins': {str(original): '0' * 64}})
            with self.assertRaisesRegex(ValueError, 'pin conflicts'):
                campaign.input_files(root, protocol)

    def test_only_train_then_screen_with_original_total_budget(self):
        spec = campaign.campaign_spec(Path('/package'), {'python': {'executable': '/python'}}, {})
        self.assertEqual(14400, spec['limits']['maximumSeconds'])
        self.assertEqual([('train', [], 10800), ('screen', ['train'], 3600)],
                         [(row['id'], row['dependsOn'], row['maximumSeconds']) for row in spec['stages']])
        self.assertTrue(all(row['maximumAttempts'] == 1 for row in spec['stages']))
        self.assertTrue(all(row['command'][1:5] == ['-E', '-s', '-S', '-B'] for row in spec['stages']))
        self.assertEqual((4 * campaign.exp.GIB, 4 * campaign.exp.GIB, 2 * campaign.exp.GIB),
                         tuple(spec['limits'][key] for key in ('maximumDataBytes', 'maximumRssBytes', 'minimumFreeBytes')))

    def test_preflight_cannot_overwrite_prior_attempt(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'preflight-protocol.json').write_text('{}')
            with patch.object(campaign, 'external_child_preflight') as preflight:
                with self.assertRaisesRegex(ValueError, 'one exact-child'):
                    campaign.prepare_preflight(root)
            preflight.assert_not_called()

    def test_run_cannot_resume_existing_output(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
            root = Path(directory)
            protocol = dict(python={'executable': '/python'}, environment={'variables': {}}, frozenFiles={})
            campaign.document(root / 'protocol.json', protocol)
            protocol_sha = campaign.sha(root / 'protocol.json')
            spec = campaign.campaign_spec(root, protocol, {str(root / 'protocol.json'): protocol_sha})
            campaign.document(root / 'campaign.json', spec)
            (root / 'run').mkdir()
            options = SimpleNamespace(protocol_sha=protocol_sha, campaign_sha=campaign.sha(root / 'campaign.json'))
            with patch.object(campaign, 'verify_execution'), patch.object(campaign, 'validate_science'), \
                 patch.object(campaign, 'external_child_preflight') as preflight, \
                 patch.object(campaign.controller, 'main') as launch:
                with self.assertRaisesRegex(ValueError, 'One purchase attempt'):
                    campaign.run(root, options)
            preflight.assert_not_called()
            launch.assert_not_called()

    def test_external_preflight_uses_exact_environment_and_initial_stage_deadline(self):
        protocol = dict(python={'executable': '/pure-python'}, environment={'variables': {'HOME': '/user'}})
        result = dict(passed=True, nativeGames=0, protocolSha256='digest')
        completed = subprocess.CompletedProcess([], 0, stdout=json.dumps(result), stderr='')
        with patch.object(campaign, 'read', return_value=protocol), patch.object(campaign, 'sha', return_value='digest'), \
             patch.object(campaign.time, 'time', return_value=1000), \
             patch.object(campaign.subprocess, 'run', return_value=completed) as run:
            receipt = campaign.external_child_preflight(Path('/package'), Path('/package/protocol.json'))
        self.assertEqual(0, receipt['nativeGames'])
        self.assertEqual(['-E', '-s', '-S', '-B'], run.call_args.args[0][1:5])
        child_env = run.call_args.kwargs['env']
        self.assertEqual('11800', child_env['AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH'])
        self.assertEqual('15400', child_env['AOR_CAMPAIGN_DEADLINE_EPOCH'])
        self.assertEqual({'HOME'} | campaign.DYNAMIC_ENV, set(child_env))

    def test_runtime_rejects_unpinned_package_code(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
            root = Path(directory)
            (root / 'scripts').mkdir()
            (root / 'scripts/unreviewed.py').write_text('unexpected')
            environment_record = dict(platform=campaign.platform.platform(), machine=campaign.platform.machine(),
                                      logicalCpus=os.cpu_count(), variables={})
            campaign.document(root / 'environment.json', environment_record)
            protocol = dict(environment=environment_record, frozenFiles={},
                python=dict(executable=str(Path(campaign.sys.executable).resolve()), version=campaign.sys.version))
            flags = SimpleNamespace(ignore_environment=True, no_site=True, no_user_site=True)
            with patch.object(campaign, '__file__', str(root / 'scripts/purchase_ecology_campaign.py')), \
                 patch.object(campaign.sys, 'flags', flags), patch.object(campaign.sys, 'dont_write_bytecode', True):
                with self.assertRaisesRegex(ValueError, 'Unexpected or missing'):
                    campaign.verify_execution(protocol, root / 'protocol.json', root / 'run')


if __name__ == '__main__':
    unittest.main()
