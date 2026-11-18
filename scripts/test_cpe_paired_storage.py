import copy
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import cpe_paired_campaign_plan as p
import cpe_paired_storage as storage
from test_cpe_paired_campaign_plan import fixture


def public_fixture():
    plan=p.Plan.from_record(fixture())
    native=dict(head='CARD',iteration=1,signature='1'*64,bundleScopeSha256='b'*64,
        scopeSha256=plan.record['heads']['CARD']['scopeSha256'],scienceSha256='2'*64)
    origin=dict(status='READY',origin={'sha256':'3'*64})
    value=dict(kind='cpe-paired-public-1',**{k:v for k,v in native.items() if k!='head'},
        request=p.request(0,'CARD'),streams=plan.cohort(1,'CARD')['origins'][0],status='READY',
        originSha256='3'*64,missingReason=None,observedWindows=7,branchingWindows=3,selectedOrdinal=2,
        publicRoot=dict(observation=dict(baselineIndex=0,actions=[{'command':'pass'},{'command':'card'}]),
            referenceIndex=1,sampledCommandIndex=1,proposedIndices=[1,0],inclusionProbability=1.,
            currentProbabilities=[.25,.75],originalProbabilities=[.6,.4]),anchors=[])
    return plan,native,origin,value


class StorageTests(unittest.TestCase):
    def test_nonzero_actor_reference_can_differ_from_original_network_prior(self):
        plan,native,origin,value=public_fixture()
        self.assertEqual(storage.public_record(value,origin,native,plan,0),'READY')
        self.assertEqual(value['publicRoot']['observation']['baselineIndex'],0)

    def test_reference_cannot_be_replaced_by_network_prior(self):
        plan,native,origin,value=public_fixture();value['publicRoot']['referenceIndex']=0
        with self.assertRaisesRegex(ValueError,'actual current sampled'):
            storage.public_record(value,origin,native,plan,0)

    def test_private_or_terminal_tree_is_rejected_before_public_admission(self):
        plan,native,origin,value=public_fixture();value['terminal']={'reward':.9}
        with self.assertRaisesRegex(ValueError,'exclude private'):
            storage.public_record(value,origin,native,plan,0)

    def test_reservoir_ordinal_cannot_exceed_recorded_branching_windows(self):
        plan,native,origin,value=public_fixture();value['selectedOrdinal']=4
        with self.assertRaisesRegex(ValueError,'ordinal'):
            storage.public_record(value,origin,native,plan,0)

    def test_singletons_do_not_silently_become_an_eligible_root(self):
        plan,native,origin,value=public_fixture();value['publicRoot']['observation']['actions']=[{'command':'pass'}]
        with self.assertRaisesRegex(ValueError,'offered action'):
            storage.public_record(value,origin,native,plan,0)

    def test_missing_root_retains_observed_singletons_without_labels(self):
        plan,native,origin,value=public_fixture()
        origin['status']='MISSING';value.update(status='MISSING',publicRoot=None,
            branchingWindows=0,selectedOrdinal=None,missingReason='NO_BRANCHING_WINDOW_IN_ASSIGNED_EPOCH')
        self.assertEqual(storage.public_record(value,origin,native,plan,0),'MISSING')

    def test_public_head_identity_cannot_cross_cohorts(self):
        plan,native,origin,value=public_fixture();value['request']['head']='PURCHASE'
        with self.assertRaisesRegex(ValueError,'head/request'):
            storage.public_record(value,origin,native,plan,0)

    def test_inclusion_probability_uses_all_legal_alternatives(self):
        plan,native,origin,value=public_fixture();root=value['publicRoot']
        root['observation']['actions']=[{'command':str(i)} for i in range(11)]
        root.update(referenceIndex=10,sampledCommandIndex=10,proposedIndices=[10,*range(8)],
            inclusionProbability=.8,currentProbabilities=[1/11]*11,originalProbabilities=[1/11]*11)
        self.assertEqual(storage.public_record(value,origin,native,plan,0),'READY')
        root['inclusionProbability']=1.
        with self.assertRaisesRegex(ValueError,'inclusion'):storage.public_record(value,origin,native,plan,0)

    def iteration_fixture(self, root, failing=None):
        plan=p.Plan.from_record(fixture());subjects={};rows={}
        for head in p.HEADS:
            row=dict(kind='cpe-paired-corpus-1',head=head,chunks=[],
                currentBundleSha256='a'*64,originalBundleSha256='a'*64,
                bundleScopeSha256='b'*64,ecologySha256='c'*64,
                exposurePassed=head!=failing,scientificTerminalGames=640)
            rows[head]=row;subjects[head]=storage.publish_json(root/(head+'.json'),row)
        return plan,subjects,rows

    def test_one_head_exposure_failure_stops_atomic_iteration(self):
        with tempfile.TemporaryDirectory() as directory:
            plan,subjects,rows=self.iteration_fixture(Path(directory),'PURCHASE')
            with patch.object(storage,'cohort_corpus',side_effect=lambda _p,_i,head,_s:rows[head]):
                result=storage.iteration_corpus(plan,1,subjects)
            self.assertFalse(result['exposurePassed'])
            self.assertEqual(result['independentOrigins'],1920)
            self.assertFalse(result['semanticValidationLabelsDecoded'])

    def test_all_head_cohorts_must_share_current_complete_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);plan,subjects,rows=self.iteration_fixture(root)
            rows['CARD']['currentBundleSha256']='e'*64
            (root/'CARD.json').write_bytes(p.canonical(rows['CARD']));subjects['CARD']=p.subject(root/'CARD.json')
            with patch.object(storage,'cohort_corpus',side_effect=lambda _p,_i,head,_s:rows[head]):
                with self.assertRaisesRegex(ValueError,'one immutable complete bundle'):
                    storage.iteration_corpus(plan,1,subjects)

    def test_public_stream_never_decodes_label_or_full_origin_bytes(self):
        import tarfile
        import io
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);archive=root/'evidence.tar.gz'
            raw={'public-0.json':b'{"constructed":true}',
                'labels-0.json':b'INVALID LABEL JSON','origin-0.json.gz':b'OPAQUE PRIVATE WORLD'}
            with tarfile.open(archive,'w:gz') as stream:
                for name,data in raw.items():
                    info=tarfile.TarInfo(name);info.size=len(data);stream.addfile(info,io.BytesIO(data))
            receipt=dict(archive=archive.name,archiveSha256=p.file_sha(archive),
                files={name:dict(size=len(data),sha256=__import__('hashlib').sha256(data).hexdigest())
                    for name,data in raw.items()},origins=[{'public':{'file':'public-0.json'}}])
            self.assertEqual(list(storage.public_members(archive,receipt)),[{'constructed':True}])


if __name__=='__main__':unittest.main()
