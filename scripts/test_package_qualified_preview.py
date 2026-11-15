"""Constructed metadata/opaque-byte fixtures only; no scientific inputs or policy inference."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import package_qualified_preview as package


class PackageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.request = self.root/'request.json'
        self.output = self.root/'packaged'
        self.space = patch.object(package.shutil, 'disk_usage')
        self.space.start().return_value.free = 1 << 50
        self.addCleanup(self.space.stop)
        self.inputs = self.fixture()

    def json(self, name, value):
        path = self.root/name
        path.parent.mkdir(parents=True, exist_ok=True)
        package.write(path, value)
        return dict(path=str(path), sha256=package.sha(path))

    def fixture(self):
        def local(subject, base):
            return dict(path=str(Path(subject['path']).relative_to(self.root/base)), sha256=subject['sha256'])
        bundles = {}
        for role in ('current', 'original'):
            heads = {head: local(self.json('inference/'+role+'/'+head+'.json',
                dict(constructedOnly=True, opaqueHead=head)), 'inference/'+role)
                for head in ('CARD', 'PURCHASE', 'EXPANSION', 'TOKEN')}
            bundles[role] = self.json('inference/'+role+'/bundle.json', dict(heads=heads))
        references = {key: local(self.json('inference/'+key+'.json', dict(constructedOnly=True)), 'inference')
            for key in ('qualifiedOrdinary', 'cardM8', 'purchaseF0', 'expansionE')}
        calibration = self.json('inference/calibration.json', dict(constructedOnly=True))
        asset = self.json('inference/asset.json', dict(kind='coordinated-inference-asset-1',
            profile=package.PROFILE, currentBundle=local(bundles['current'], 'inference'),
            originalBundle=local(bundles['original'], 'inference'),
            calibration=local(calibration, 'inference'), references=references))
        nomination = self.json('nomination/nomination.json', dict(kind='coordinated-policy-nomination-1',
            passed=True, complete=True, assetSha256=asset['sha256'],
            completionAudit=local(self.json('nomination/completion.json', dict(constructedOnly=True)), 'nomination'),
            developmentReport=local(self.json('nomination/report.json', dict(constructedOnly=True)), 'nomination')))
        banks = {name: dict(complete=True, pairs=4096, win99Passed=True,
            evidence=local(self.json('study/banks/'+name+'/complete.json', dict(constructedOnly=True)), 'study'),
            report=local(self.json('study/banks/'+name+'/report/report.json', dict(constructedOnly=True)), 'study'))
            for name in ('mixed', 'homogeneous')}
        certificate = self.json('study/qualification.json', dict(kind='coordinated-win-qualification-1',
            schemaVersion=1, profile=package.PROFILE, passed=True, complete=True,
            inferenceAssetSha256=asset['sha256'], nominationSha256=nomination['sha256'], banks=banks))
        original = '/original-desktop/study/qualification.json'
        pins = {original: certificate['sha256'], **{
            str(Path(original).parent/subject['path']): subject['sha256']
            for bank in banks.values() for subject in (bank['evidence'], bank['report'])}}
        result = dict(kind='coordinated-win-preview-package-request-1', protocolSha256='1'*64,
            clockSha256='2'*64, inferenceAsset=asset, nomination=nomination, qualification=certificate,
            rootReleaseReview=None)
        for host, prefix in (('DESKTOP', 'desktop'), ('MAC', 'mac')):
            evidence = self.json(prefix+'/evidence.json', dict(kind='coordinated-win-qualification-audit-1',
                host=host, passed=True, complete=True, status='completed', protocolSha256='1'*64,
                counts=package.COUNTS[host], execution=dict(newGames=0, newFits=0, modelForwards=0),
                qualified=host == 'DESKTOP', fullGlobalStatisticsRecomputed=host == 'DESKTOP',
                eligibleQualifiedUnit=dict(path=original, sha256=certificate['sha256']) if host == 'DESKTOP' else None,
                artifactPins=pins if host == 'DESKTOP' else {}))
            result[prefix+'Evidence'] = evidence
            result[prefix+'Watcher'] = self.json(prefix+'/watcher.json',
                dict(kind='coordinated-win-qualification-host-terminal-audit-1', host=host,
                    passed=True, complete=True, status='completed', allOwnedProcessesExited=True,
                    clockGuardStopped=True, protocolSha256='1'*64, clockSha256='2'*64,
                    evidence=evidence, counts=package.COUNTS[host]))
        result['rootReleaseReview'] = self.json('release.json', self.release(result))
        return result

    def release(self, inputs):
        return dict(kind='coordinated-win-preview-release-review-1', passed=True, complete=True,
            protocolSha256=inputs['protocolSha256'], clockSha256=inputs['clockSha256'],
            subjects={key: inputs[key]['sha256'] for key in package.SUBJECTS[:-1]},
            allOwnedProcessesExitedIndependently=True, packagingAuthorized=True, constructedOnly=True)

    def run_package(self, inputs=None):
        package.write(self.request, inputs or self.inputs)
        return package.package(self.request, self.output)

    def replace_subject(self, key, value):
        result = copy.deepcopy(self.inputs)
        result[key] = self.json('replaced-'+key+'.json', value)
        if key != 'rootReleaseReview':
            result['rootReleaseReview'] = self.json('replacement-release.json', self.release(result))
        return result

    def test_preserves_certificate_and_inference_bytes(self):
        result = self.run_package()
        self.assertEqual(package.sha(self.output/'qualification/qualification.json'),
            self.inputs['qualification']['sha256'])
        self.assertEqual(package.sha(self.output/'inference/asset.json'), self.inputs['inferenceAsset']['sha256'])
        self.assertTrue(result['qualificationCopiedByteExact'])
        self.assertFalse(result['serverStarted'])
        self.assertEqual(result['auditReruns'], 0)

    def test_refuses_incomplete_other_host(self):
        value = package.read(self.inputs['macWatcher']['path']); value['complete'] = False
        with self.assertRaisesRegex(ValueError, 'Both original completed watcher'):
            self.run_package(self.replace_subject('macWatcher', value))
        self.assertFalse(self.output.exists())

    def test_refuses_different_original_clock(self):
        value = package.read(self.inputs['macWatcher']['path']); value['clockSha256'] = '3'*64
        with self.assertRaisesRegex(ValueError, 'Both original completed watcher'):
            self.run_package(self.replace_subject('macWatcher', value))

    def test_refuses_unreviewed_release(self):
        with self.assertRaisesRegex(ValueError, 'final release review'):
            self.run_package(self.replace_subject('rootReleaseReview', dict(passed=False)))

    def test_refuses_unrelated_passed_release(self):
        with self.assertRaisesRegex(ValueError, 'final release review'):
            self.run_package(self.replace_subject('rootReleaseReview', dict(passed=True)))

    def test_preserves_admitted_hash_when_source_changes_during_assembly(self):
        original_read = package.read
        asset_path = Path(self.inputs['inferenceAsset']['path'])
        def changed_read(path):
            value = original_read(path)
            if Path(path).resolve() == asset_path.resolve():
                asset_path.write_bytes(asset_path.read_bytes()+b' ')
            return value
        with patch.object(package, 'read', side_effect=changed_read):
            with self.assertRaisesRegex(ValueError, 'changed after its subject'):
                self.run_package()
        self.assertFalse(self.output.exists())

    def test_refuses_changed_certificate_before_copy(self):
        value = package.read(self.inputs['qualification']['path'])
        value['banks']['homogeneous']['win99Passed'] = False
        with self.assertRaisesRegex(ValueError, 'Desktop-audited qualified certificate'):
            self.run_package(self.replace_subject('qualification', value))

    def test_refuses_changed_bank_bytes(self):
        (self.root/'study/banks/mixed/complete.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'absent, symlinked or changed'):
            self.run_package()
        self.assertFalse(self.output.exists())

    def test_refuses_escaping_subject(self):
        owner = self.root/'study/qualification.json'
        with self.assertRaisesRegex(ValueError, 'normalized inside'):
            package.relative(owner, dict(path='../release.json', sha256=self.inputs['rootReleaseReview']['sha256']))

    def test_refuses_existing_destination(self):
        self.output.mkdir()
        with self.assertRaisesRegex(ValueError, 'new-only'):
            self.run_package()


if __name__ == '__main__':
    unittest.main()
