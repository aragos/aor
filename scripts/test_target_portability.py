"""Weighted parity is exact except elapsed time and never creates strength evidence."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

import target_portability as proof
import finalize_expansion_target_search as finalize


class TargetPortabilityTest(unittest.TestCase):
    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);self.root=Path(temp.name).resolve()
        self.bank=dict(nativeOffset=28000100,count=32,seeds=proof.target.exp.arena_seeds(28000100,32))
        self.template=dict(portability=dict(bank=self.bank))
        self.receipt=dict(schemaVersion=1,kind='expansion-target-weighted-portability-1',passed=True,
            qualificationPassed=False,fitting=False,coordinates=proof.COORDINATES,
            rawWeights=dict(zip(proof.target.models.FEATURES,[8.,-8.,16.,-16.])),bank=self.bank,
            comparedPairs=32,allOtherPairFieldsExact=True,
            excludedFields=['candidate.elapsedMillis','incumbent.elapsedMillis'],runtime=proof.target.RUNTIME_JARS,
            hosts={},artifactHashes={})
        for host,workers in (('mac',1),('desktop',6)):
            prefix='desktop-recovery' if host=='desktop' else host
            directory=self.root/prefix/'run';directory.mkdir(parents=True)
            files={}
            for name,value in [('identity.json',{'host':host}),('source.json',{'host':host,'source':True}),('status.json',{'status':'completed'})]:
                path=self.root/prefix/name;proof.target.document(path,value);files[name]=proof.sha(path)
            for name,value in [('run.json',dict(workers=workers,kind='strategy-arena',signature='signature')),
                               ('state.json',dict(phase='complete',signature='signature')),('finished.json',dict(at='complete'))]:
                path=directory/name;proof.target.document(path,value);files[name]=proof.sha(path)
            for i,seed in enumerate(self.bank['seeds']):
                game=dict(wins=[0.,1.,0.,0.],rewards=[.1,.4,.2,.3],ranks=[4,1,3,2],scores=[1,4,2,3],decisions=100,
                          elapsedMillis=10 if host=='mac' else 20)
                pair=dict(index=i,configuration=proof.target.exp.CONFIGURATION,seed=seed,seat=i%4,
                    opponentIndices=[(i//4+seat)%4 for seat in range(4)],candidate=game,incumbent=copy.deepcopy(game))
                proof.target.document(directory/f'pair-{i}.json',pair)
            self.receipt['hosts'][host]=dict(workers=workers,pairs=32,pairDirectory=prefix+'/run',artifactPrefix=prefix+'/',
                identitySha256=files['identity.json'],sourceSha256=files['source.json'],statusSha256=files['status.json'],
                nativeRunSha256=files['run.json'],nativeFinishedSha256=files['finished.json'])
        self.refresh()

    def refresh(self):
        self.receipt['artifactHashes']={str(path.relative_to(self.root)):proof.sha(path)
            for prefix in ('mac','desktop-recovery') for path in (self.root/prefix).rglob('*') if path.is_file()}
        (self.root/'receipt.json').write_text(json.dumps(self.receipt))
        self.digest=proof.sha(self.root/'receipt.json')

    def test_elapsed_only_difference_passes_with_explicit_recovery_paths(self):
        receipt=proof.verify(self.root,self.digest,self.template)
        self.assertTrue(receipt['passed'])
        self.assertFalse(receipt['qualificationPassed'])

    def test_other_pair_field_difference_rejected_even_with_updated_hashes(self):
        path=self.root/'desktop-recovery/run/pair-0.json';pair=proof.read(path)
        pair['candidate']['decisions']+=1;path.write_text(json.dumps(pair));self.refresh()
        with self.assertRaisesRegex(ValueError,'outside the two declared elapsed'):proof.verify(self.root,self.digest,self.template)

    def test_changed_artifact_without_receipt_update_rejected(self):
        (self.root/'mac/run/pair-0.json').write_text('{}')
        with self.assertRaisesRegex(ValueError,'artifacts changed'):proof.verify(self.root,self.digest,self.template)

    def test_nonzero_candidate_coordinates_cannot_be_replaced_with_zero_proof(self):
        self.receipt['coordinates']=[0.]*4;self.refresh()
        with self.assertRaisesRegex(ValueError,'exact operational scope'):proof.verify(self.root,self.digest,self.template)

    def test_native_worker_count_must_match_declared_host(self):
        path=self.root/'desktop-recovery/run/run.json';native=proof.read(path);native['workers']=1
        path.write_text(json.dumps(native));self.receipt['hosts']['desktop']['nativeRunSha256']=proof.sha(path);self.refresh()
        with self.assertRaisesRegex(ValueError,'worker count'):proof.verify(self.root,self.digest,self.template)

    def test_old_ai_runtime_cannot_pass_weighted_proof(self):
        self.receipt['runtime']=dict(self.receipt['runtime'],**{'ai-jvm.jar':'old'});self.refresh()
        with self.assertRaisesRegex(ValueError,'target runtime'):proof.verify(self.root,self.digest,self.template)

    def test_relative_proof_members_cannot_escape(self):
        with self.assertRaisesRegex(ValueError,'relative to their declared'):proof.member(self.root,'../outside')

    def test_unbound_transport_rejects_file_set_change(self):
        for name in ('scripts','runtime','inputs','audit','evidence','portability'):(self.root/name).mkdir(exist_ok=True)
        proof.target.document(self.root/'protocol-template.json',{})
        proof.target.document(self.root/'provenance.json',[])
        proof.target.document(self.root/'weighted-portability.json',{})
        files=dict(finalize.transport_files(self.root),**{'weighted-portability.json':proof.sha(self.root/'weighted-portability.json')})
        proof.target.document(self.root/'transport-manifest.json',dict(kind='expansion-target-search-transport',hostUnbound=True,files=files))
        digest=proof.sha(self.root/'transport-manifest.json')
        (self.root/'scripts/extra.py').write_text('extra')
        with self.assertRaisesRegex(ValueError,'file set changed'):finalize.verify_transport(self.root,digest)


if __name__=='__main__':unittest.main()
