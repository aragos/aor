"""Pure dispatch/command/environment tests; no processes, numerical fits or games are launched."""
import json
from pathlib import Path
import subprocess
import tempfile
import types
import unittest
from unittest.mock import patch

import token_eight_tail_campaign as supervisor


def neighbor_target():
    owner = types.SimpleNamespace(inspect_numerical=object())
    return types.SimpleNamespace(
        STAGES=('augment', 'fit', 'screen', 'confirm', 'heldout'),
        CEILINGS=(21600, 600, 1800, 3600, 7200),
        numerical_environment=lambda protocol: owner,
    )


class DispatchTests(unittest.TestCase):
    def test_legacy_profile_retains_original_stage_commands_and_caps(self):
        root = Path('/frozen')
        protocol = {'kind': supervisor.legacy_target.KIND, 'python': {'executable': '/python'}}
        result = supervisor.campaign_spec(root, protocol, {'input': 'sha'})
        expected = dict(schemaVersion=1, kind='automata-campaign', inputs={'input': 'sha'},
            limits=dict(maximumSeconds=21600, maximumDataBytes=4*supervisor.exp.GIB,
                        maximumRssBytes=4*supervisor.exp.GIB, minimumFreeBytes=2*supervisor.exp.GIB),
            stages=[dict(id=name, dependsOn=[] if index == 0 else [supervisor.legacy_target.STAGES[index-1]],
                maximumSeconds=supervisor.legacy_target.CEILINGS[index], maximumAttempts=1,
                result=f'results/{name}.json',
                command=['/python', '-E', '-s', '-S', '-B', '/frozen/scripts/automata_token_eight_tail.py',
                         'stage', '--protocol', '/frozen/protocol.json', '--output', '{output}', '--stage', name])
                for index, name in enumerate(supervisor.legacy_target.STAGES)])
        self.assertEqual(expected, result)

    def test_fixed_reference_profile_retains_legacy_target_and_mac_allocation(self):
        protocol = {'kind': supervisor.legacy_target.fixed.CAMPAIGN_KIND}
        self.assertIs(supervisor.legacy_target, supervisor.target_for(protocol))
        self.assertEqual({'thisWorkers': 6, 'host': 'mac', 'logicalCpus': supervisor.os.cpu_count(),
                          'otherMacWorkers': 0}, supervisor.allocation_for(protocol))

    def test_original_desktop_allocation_stays_six_plus_ten(self):
        self.assertEqual({'thisWorkers': 6, 'otherDesktopWorkers': 10, 'desktopLogicalCpus': 16},
                         supervisor.allocation_for({'kind': supervisor.legacy_target.KIND}))

    def test_neighbor_profile_uses_new_target_and_one_bounded_stage_chain(self):
        protocol = {'kind': supervisor.NEIGHBOR_KIND, 'python': {'executable': '/python'}}
        with patch.object(supervisor.importlib, 'import_module', return_value=neighbor_target()) as imported:
            result = supervisor.campaign_spec(Path('/frozen'), protocol, {})
        imported.assert_called_once_with('automata_token_reference_neighbors')
        self.assertEqual(['augment', 'fit', 'screen', 'confirm', 'heldout'], [s['id'] for s in result['stages']])
        self.assertEqual([21600, 600, 1800, 3600, 7200], [s['maximumSeconds'] for s in result['stages']])
        self.assertEqual([[], ['augment'], ['fit'], ['screen'], ['confirm']],
                         [s['dependsOn'] for s in result['stages']])
        self.assertTrue(all(s['maximumAttempts'] == 1 for s in result['stages']))
        self.assertTrue(all(s['command'][5] == '/frozen/scripts/automata_token_reference_neighbors.py'
                            for s in result['stages']))
        self.assertEqual(21600, result['limits']['maximumSeconds'])

    def test_neighbor_allocation_requires_its_own_ten_worker_desktop_slot(self):
        self.assertEqual({'thisWorkers': 10, 'otherDesktopWorkers': 0, 'desktopLogicalCpus': 16},
                         supervisor.allocation_for({'kind': supervisor.NEIGHBOR_KIND}))

    def test_neighbor_numerical_inspection_uses_its_declared_owner(self):
        target = neighbor_target()
        with patch.object(supervisor.importlib, 'import_module', return_value=target):
            actual = supervisor.numerical_inspector({'kind': supervisor.NEIGHBOR_KIND})
        self.assertIs(target.numerical_environment({}).inspect_numerical, actual)

    def test_unknown_profile_cannot_select_a_default_target(self):
        with self.assertRaisesRegex(ValueError, 'Unknown explicitly'):
            supervisor.target_for({'kind': 'undeclared'})

    def test_neighbor_preflight_binds_augment_environment_and_new_validator(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root/'scripts').mkdir()
            for name in ('token_eight_tail_campaign.py', 'automata_token_reference_neighbors.py'):
                (root/'scripts'/name).write_text(name)
            protocol = {'kind': supervisor.NEIGHBOR_KIND, 'python': {'executable': '/python'},
                        'environment': {'variables': {}}}
            path = root/'protocol.json'
            path.write_text(json.dumps(protocol))
            (root/'environment.json').write_text('{}')
            output = {'passed': True, 'nativeGames': 0, 'protocolSha256': supervisor.sha(path)}
            completed = subprocess.CompletedProcess([], 0, json.dumps(output), '')
            with patch.object(supervisor.importlib, 'import_module', return_value=neighbor_target()), \
                 patch.object(supervisor.subprocess, 'run', return_value=completed) as process:
                receipt = supervisor.external_child_preflight(root, path)
            self.assertEqual('augment', process.call_args.kwargs['env']['AOR_CAMPAIGN_STAGE_ID'])
            self.assertEqual(60, process.call_args.kwargs['timeout'])
            self.assertEqual(supervisor.sha(root/'scripts/automata_token_reference_neighbors.py'),
                             receipt['validatorSha256'])
            self.assertEqual(0, receipt['nativeGames'])


if __name__ == '__main__':
    unittest.main()
