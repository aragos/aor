"""Invented byte closures and native-shaped archives; no model decode or Simulation.

Opaque toy actors/references intentionally do not claim native actor admission. The separate
native bridge supplies that coverage. Real archive/report/confirmation owners run here.
"""
import copy
import gzip
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

import coordinated_qualification as legacy
import coordinated_win_qualification as q
import coordinated_win_qualification_plan as p
from token_paired_campaign_audit import Pins
from test_coordinated_qualification import game, native_report as old_report
from test_coordinated_win_qualification_plan import protocol_record


def native_report(worlds):
    value = old_report(worlds)
    value.update(kind=p.PREFIX+'-screen-report-1', gates=dict(wins=value['gates']['wins'], passed=value['gates']['wins']))
    return value


class Fixture:
    def __init__(self, root):
        self.root = Path(root).resolve(); self.inputs = self.root/'inputs'; self.inputs.mkdir(parents=True)
        def emit(name, value): return q.storage.publish_json(self.inputs/name, value)
        bundles = {}
        for label in ('current', 'original'):
            heads = {}
            for head in q.HEADS:
                item = emit(f'{label}/heads/{head}/policy.json', dict(constructedOnly=True, head=head, role=label))
                heads[head] = dict(path=f'heads/{head}/policy.json', sha256=item['sha256'], dictionarySha256='d'*64)
            bundles[label] = emit(f'{label}/bundle.json', dict(kind='coordinated-es-bundle-1', heads=heads))
        calibration = emit('calibration.json', dict(kind='coordinated-es-calibration-2', constructedOnly=True, opaque=True))
        references = {name: emit('references/'+name+'.json', dict(constructedOnly=True, reference=name))
            for name in ('qualifiedOrdinary', 'cardM8', 'purchaseF0', 'expansionE')}
        asset_path = self.inputs/'asset.json'
        self.asset = emit('asset.json', dict(kind='coordinated-inference-asset-1', schemaVersion=1,
            profile=p.PROFILE, supportProfile=p.SUPPORT, selectorVersion='incumbent-residual-margin-1',
            rulesVersion=p.RULES, dataVersion=p.DATA, configuration=p.APPLICATION_CONFIGURATION,
            currentBundle=q.relative(bundles['current']['path'], asset_path),
            originalBundle=q.relative(bundles['original']['path'], asset_path),
            calibration=q.relative(calibration['path'], asset_path),
            references=dict(**{name:q.relative(item['path'],asset_path) for name,item in references.items()},
                tokenControlVersion='FIXED_16_TO_15')))
        audit = emit('evidence/audit.json', dict(constructedOnly=True, inventedScientificAttestation=True))
        report = emit('evidence/report.json', dict(constructedOnly=True, inventedScientificAttestation=True))
        nomination_path = self.inputs/'nomination.json'
        self.nomination = emit('nomination.json', dict(kind='coordinated-policy-nomination-1', schemaVersion=1,
            passed=True, complete=True, assetSha256=self.asset['sha256'], profile=p.PROFILE,
            completionAudit=q.relative(audit['path'], nomination_path), developmentReport=q.relative(report['path'], nomination_path),
            learningWin95Passed=True, controlWin95Passed=True, nomination='SOLE_FINAL_MEAN', candidateAlternatives=0))
        self.arenas = {bank:emit(bank+'-arena.json',dict(constructedOnly=True,bank=bank)) for bank in p.BANKS}
        value = protocol_record()
        value.update(inferenceAssetSha256=self.asset['sha256'], nominationSha256=self.nomination['sha256'],
            candidateBundleSha256=bundles['current']['sha256'], originalBundleSha256=bundles['original']['sha256'],
            calibrationSha256=calibration['sha256'], references=dict(**{name+'Sha256':item['sha256'] for name,item in references.items()},
                tokenControlVersion='FIXED_16_TO_15'))
        self.protocol = self.inputs/'protocol.json'; emit('protocol.json', value)
        self.plan = p.Plan.from_file(self.protocol)

    def profile(self, host='DESKTOP'):
        return dict(kind=p.PREFIX+'-host-1', host=host, protocolSha256=self.plan.sha256,
            inferenceAsset=self.asset, nomination=self.nomination, arenas=self.arenas,
            files={str(path):p.file_sha(path) for path in self.inputs.rglob('*') if path.is_file()}, **p.host_limits(host))

    def binding(self, candidate):
        result = dict(version='coordinated-support-arena-1', supportProfile=p.SUPPORT, supportRoles='ALL_SEATS_CP',
            originalBundle=dict(sha256=self.plan.record['originalBundleSha256']),
            execution='CALIBRATED' if candidate else 'ZERO_REFERENCE',
            roles={'CANDIDATE':dict(sha256=self.plan.record['candidateBundleSha256'])} if candidate else {})
        if candidate: result['calibration'] = dict(sha256=self.plan.record['calibrationSha256'])
        return dict(coordinatedEs=result)

    def chunk(self, bank, chunk, positive=False, gate=None):
        remote = p.owner(chunk) == 'MAC'
        directory = self.root/('incoming/'+q.tag(bank,chunk) if remote else f'banks/{bank}/chunks/chunk-{chunk}')
        output = directory/'native'; output.mkdir(parents=True)
        subjects = dict(protocol=p.subject(self.protocol), inferenceAsset=self.asset, nomination=self.nomination,
            arena=self.arenas[bank], confirmation=gate)
        source = q.storage.publish_json(directory/'source.json', self.plan.source(bank,chunk,subjects))
        snapshot_items = dict(source=source, **{key:item for key,item in subjects.items() if item is not None})
        names = q.snapshots(bank)
        for key,name in names.items(): (output/name).write_bytes(Path(snapshot_items[key]['path']).read_bytes())
        identity = dict(kind=q.KIND, protocolSha256=self.plan.sha256, **q.identity(self.plan),
            originalBundleSha256=self.plan.record['originalBundleSha256'], calibrationSha256=self.plan.record['calibrationSha256'],
            ecologySha256='e'*64, ecology={}, bankName=bank, profile=p.PROFILE, supportProfile=p.SUPPORT,
            seeds=self.plan.record['banks'][bank]['seeds'], roles=list(p.ROLES), replayIndices=list(p.REPLAYS))
        science = p.digest(identity); signature = p.digest(dict(constructed=True,bank=bank,chunk=chunk))
        total = 256+2*len(p.replays(chunk))
        manifest = dict(kind=q.KIND, signature=signature, scienceSha256=science, scienceIdentity=identity,
            globalStart=chunk*128,count=128,**{key:dict(snapshot_items[key],snapshot=name) for key,name in names.items()},
            policyBindings=self.binding(True),controlPolicyBindings=self.binding(False),inputPins={},
            ownedReplayIndices=list(p.replays(chunk)),maximumTerminalExecutions=total)
        q.storage.publish_json(output/'manifest.json',manifest)
        q.storage.publish_json(output/'started.json',dict(signature=signature,workers=p.host_limits(p.owner(chunk))['workers'],maximumSeconds=100))
        rows=[]
        for index in p.indices(chunk):
            world=dict(kind=q.KIND,signature=signature,scienceSha256=science,index=index,
                seed=self.plan.record['banks'][bank]['seeds'][index],seat=index%4,configuration=p.CONFIGURATION,
                opponentIndices=[(index//4+seat)%4 for seat in range(4)] if bank=='mixed' else [0]*4,
                roles=dict(CANDIDATE=game(index%4,positive),CONTROL=game(index%4)))
            name=f'world-{index}.json.gz';(output/name).write_bytes(gzip.compress(p.canonical(world),mtime=0))
            rows.append(dict(index=index,file=name,sha256=p.file_sha(output/name),semanticSha256='d'*64))
            if index in p.replays(chunk):
                canonical=dict(header=dict(constructedOnly=True,index=index),entries=[])
                for role in p.ROLES:
                    q.storage.publish_json(output/f'canonical-{index}-{role}.json',canonical)
                    q.storage.publish_json(output/f'replay-{index}-{role}.json',dict(
                        **{key:world[key] for key in ('index','seed','seat','configuration','opponentIndices')},
                        **canonical,side='CANDIDATE',replayVerified=True,game=world['roles'][role],
                        coordinatedEs=self.binding(role=='CANDIDATE')['coordinatedEs']))
        finished=dict(kind=q.KIND,complete=True,stage='chunk',signature=signature,scienceSha256=science,
            scienceIdentity=identity,globalStart=chunk*128,count=128,worlds=rows,
            **q.report_bindings(self.plan,None),reproductions=2*len(p.replays(chunk)),
            scientificTerminalExecutions=total,attemptedGames=total,completedGames=total,
            files={name:row['sha256'] for name,row in q.storage.inventory(output).items()})
        q.storage.publish_json(output/'finished.json',finished)
        receipt=dict(kind=p.PREFIX+'-screen-chunk-receipt-1',protocolSha256=self.plan.sha256,bank=bank,
            chunk=chunk,host=p.owner(chunk),sourceSha256=source['sha256'],files=q.storage.inventory(output),
            completionJson=(output/'finished.json').read_text(),scienceSha256=science,worlds=128,games=256,
            reproductions=2*len(p.replays(chunk)),scientificTerminalExecutions=total)
        archive=directory/'evidence.tar.gz';q.storage.archive_collection(output,archive,receipt)
        path=directory/'evidence.tar.gz.json'
        if remote:
            path.rename(directory/'receipt.json');path=directory/'receipt.json'
            q.storage.publish_json(directory/'accepted.json',dict(kind=p.PREFIX+'-handoff-1',tag=q.tag(bank,chunk),
                receipt=p.subject(path),archive=p.subject(archive)))
        return p.subject(path)

    def complete(self, bank, positive=False, gate=None):
        receipts=[self.chunk(bank,chunk,positive,gate) for chunk in range(32)]
        started=time.time();profile=dict(self.profile(),_clock=p.Clock(started,started+p.SCIENCE_SECONDS))
        profile.update(java=dict(executable='/constructed/java'),heapMiB=6144,runtime='/constructed/runtime')
        def report_child(profile,command,log,**kwargs):
            source=Path(command[command.index('--worlds')+1]);output=Path(command[command.index('--output')+1])
            compact=p.read(source);output.mkdir()
            q.storage.publish_json(output/'report.json',native_report(compact['worlds']))
            completion=p.read(q.resolve(compact['chunks'][0],source,Pins()))
            q.storage.publish_json(output/'inputs.json',dict(sourceSha256=p.file_sha(source),chunks=compact['chunks'],
                scienceSha256=completion['scienceSha256'],**q.report_bindings(self.plan,completion)))
            q.storage.publish_json(output/'finished.json',dict(kind=p.PREFIX+'-screen-report-complete-1',complete=True,
                worlds=4096,newGames=0,newFits=0,files={name:row['sha256'] for name,row in q.storage.inventory(output).items()}))
        with patch.object(legacy.process,'run_child',side_effect=report_child):
            return q.complete_bank(self.plan,profile,self.root,bank,receipts)


class BoundaryTests(unittest.TestCase):
    def test_inference_asset_version1_rejects_legacy_v1_calibration(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture=Fixture(directory);profile=fixture.profile()
            # Rebind a constructed protocol consistently so rejection is the intrinsic version guard.
            path=fixture.inputs/'calibration.json'
            path.write_bytes(p.canonical(dict(kind='coordinated-es-calibration-1'))+b'\n')
            asset_path=Path(fixture.asset['path']);asset=p.read(asset_path)
            asset['calibration']['sha256']=p.file_sha(path);asset_path.write_bytes(p.canonical(asset)+b'\n')
            nomination_path=Path(fixture.nomination['path']);nomination=p.read(nomination_path)
            nomination['assetSha256']=p.file_sha(asset_path);nomination_path.write_bytes(p.canonical(nomination)+b'\n')
            value=fixture.plan.record;value.update(calibrationSha256=p.file_sha(path),
                inferenceAssetSha256=p.file_sha(asset_path),nominationSha256=p.file_sha(nomination_path))
            plan=p.Plan.from_record(value);profile.update(protocolSha256=plan.sha256,
                inferenceAsset=p.subject(asset_path),nomination=p.subject(nomination_path),
                files={str(item):p.file_sha(item) for item in fixture.inputs.rglob('*') if item.is_file()})
            with self.assertRaisesRegex(ValueError,'exact V2'):
                q.profile_admission(plan,profile)

    def test_inference_schema_version_is_a_typed_integer(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture=Fixture(directory);asset=p.read(fixture.asset['path']);asset['schemaVersion']=True
            with self.assertRaisesRegex(ValueError,'inference asset'):
                q.asset_record(fixture.plan,asset,p.read(fixture.nomination['path']))

    def test_finite_worker_never_opens_heldout_after_mixed_refusal(self):
        # Orchestration-only seam; complete archive/statistical joins have separate tests.
        with tempfile.TemporaryDirectory() as directory:
            fixture=Fixture(Path(directory)/'fixture');root=Path(directory)/'run'
            profile_path=q.storage.publish_json(Path(directory)/'profile.json',dict(constructedOnly=True))
            started=time.time()-1;deadline=started+p.SCIENCE_SECONDS
            def complete(plan,profile,root,bank,receipts):
                self.assertEqual(bank,'mixed');self.assertEqual(len(receipts),20)
                return q.storage.publish_json(root/'constructed-complete.json',dict(gatePassed=False))
            with patch.object(q,'check_profile',return_value=(fixture.plan,dict(host='DESKTOP'))), \
                    patch.object(q,'collect',return_value=dict(path='/constructed/shard',sha256='a'*64)) as collect, \
                    patch.object(q,'wait_receipts',side_effect=lambda profile,root,bank,rows:rows), \
                    patch.object(q,'complete_bank',side_effect=complete), \
                    patch.object(q,'publish_confirmation',side_effect=AssertionError('Heldout remains closed')), \
                    patch.dict(os.environ,{'AOR_CAMPAIGN_DEADLINE_EPOCH':str(deadline),
                        'AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH':str(deadline)}):
                ended=q.worker(fixture.protocol,Path(profile_path['path']),root,started,deadline)
            self.assertEqual(collect.call_count,20)
            self.assertEqual((ended['status'],ended['completedBanks']),('stopped',1))
            self.assertEqual(ended['clock']['deadlineEpoch'],deadline)

    def test_positive_win_negative_reward_only_passes_new_contract(self):
        worlds=[dict(index=i,seat=i%4,roles=dict(CANDIDATE=game(i%4,True),CONTROL=game(i%4))) for i in range(4096)]
        report=native_report(worlds);report['candidateMinusControl']['rewards'].update(mean=-.1,standardError=0.,lower99=-.1,upper99=-.1)
        self.assertTrue(q.report_gate(report))
        old=dict(report,kind=legacy.PREFIX+'-screen-report-1',gates=dict(wins=True,rewards=False,passed=False))
        self.assertFalse(legacy.report_gate(old))
        with self.assertRaises(ValueError):legacy.report_gate(report)

    def test_actual_asset_closure_binds_opaque_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture=Fixture(directory);profile=fixture.profile();q.profile_admission(fixture.plan,profile)
            (fixture.inputs/'evidence/audit.json').write_text('{}')
            with self.assertRaises(ValueError):q.profile_admission(fixture.plan,profile)

    def test_nomination_cannot_name_an_alternative(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture=Fixture(directory);nomination=p.read(fixture.nomination['path']);nomination['candidateAlternatives']=1
            with self.assertRaisesRegex(ValueError,'sole-final-mean'):
                q.asset_record(fixture.plan,p.read(fixture.asset['path']),nomination)

    def test_asset_subject_cannot_escape_or_follow_link(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture=Fixture(directory)
            with self.assertRaises(ValueError):q.asset_subject(dict(path='../outside',sha256='a'*64),fixture.asset['path'],Pins())
            target=fixture.inputs/'link';target.symlink_to(fixture.inputs/'calibration.json')
            with self.assertRaises(ValueError):q.asset_subject(dict(path='link',sha256=p.file_sha(target)),fixture.asset['path'],Pins())

    def test_native_shard_archive_joins_nominated_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture=Fixture(directory);item=fixture.chunk('mixed',1)
            receipt,_,worlds,_=q.archived(fixture.plan,item,Pins())
            self.assertEqual((receipt['games'],len(worlds)),(256,128))


if __name__ == '__main__': unittest.main()
