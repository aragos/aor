import copy
import json
from pathlib import Path
import tempfile
import unittest

import token_paired_campaign_plan as p
import token_paired_storage as s
from test_token_paired_campaign_plan import fixture


def collection(directory, chunk=1):
    root=Path(directory)/'native';root.mkdir()
    def write(name,value):
        path=root/name;path.parent.mkdir(parents=True,exist_ok=True)
        path.write_bytes(p.canonical(value));return p.file_sha(path)
    actor_sha=write('current-actor.json',{})
    write('original-actor.json',{});arena_sha=write('arena.json',{})
    dictionary_sha=write('dictionary.json',{})
    bindings={'jointPolicy':{'card':{'sha256':'d'*64,'path':'/host-specific','snapshot':'card.json'}}}
    value=fixture();value.update(originalActorSha256=actor_sha,dictionarySha256=dictionary_sha,
        ecologySha256=p.digest(s.portable(bindings)))
    plan=p.Plan.from_record(value)
    source=plan.collection_source(1,chunk,p.owner(chunk),p.subject(root/'arena.json'),
        p.subject(root/'current-actor.json'),p.subject(root/'original-actor.json'))
    source_sha=write('source.json',source)
    snapshots={name:p.file_sha(root/name) for name in
        ('current-actor.json','original-actor.json','arena.json','dictionary.json','source.json')}
    native=dict(kind='token-paired-collect-1',iteration=1,signature='a'*64,scopeSha256='c'*64,scienceSha256='b'*64,
        scienceIdentity={'kind':'constructed'},range=dict(globalStart=chunk*64,count=64),
        source=dict(sha256=source_sha,snapshot='source.json'),
        arena=dict(sha256=arena_sha),currentActor=dict(sha256=actor_sha),originalActor=dict(sha256=actor_sha),
        dictionary=dict(path='dictionary.json',sha256=dictionary_sha),snapshots=snapshots,
        origins=value['iterations'][0]['origins'],requests=[p.request(i) for i in p.indices(chunk)],
        replayIndices=list(p.COLLECTION_REPLAYS),ownedReplayIndices=list(p.replays(chunk)),policyBindings=bindings,
        ecologySha256=value['ecologySha256'])
    write('prepared.json',native)
    rows=[]
    for index in p.indices(chunk):
        origin=f'origin-{index}.json.gz';public=f'public-{index}.json';labels=f'labels-{index}.json'
        # Deliberately opaque poisoned bytes prove that public admission does not parse labels/worlds.
        (root/origin).write_bytes(b'OPAQUE AUTHORITY');(root/labels).write_bytes(b'{"terminal":POISON}')
        row=dict(globalIndex=index,status='SINGLETON',origin=dict(file=origin,sha256=p.file_sha(root/origin)),
            public={},labels=dict(file=labels,sha256=p.file_sha(root/labels)),tailGames=0,anchorRows=0)
        payload=dict(kind='token-paired-public-1',signature=native['signature'],scopeSha256=native['scopeSha256'],scienceSha256=native['scienceSha256'],
            iteration=1,request=p.request(index),streams=value['iterations'][0]['origins'][index],
            status='SINGLETON',originSha256=row['origin']['sha256'],missingReason=None,observedWindows=1,anchors=[],
            publicRoot=dict(observation=dict(actions=[dict(command='PASS',features={})],baselineIndex=0),
                referenceIndex=0,sampledCommandIndex=0,proposedIndices=[0],inclusionProbability=0.))
        row['public']=dict(file=public,sha256=write(public,payload));rows.append(row)
    write('finished.json',dict(kind='token-paired-finished-1',complete=True,iteration=1,
        signature=native['signature'],scienceSha256=native['scienceSha256'],range=native['range'],
        origins=rows,backboneGames=64,tailGames=0,scientificTerminalGames=64,
        readyRoots=0,singletonRoots=64,missingRoots=0,capturedRoots=64))
    return plan,root,actor_sha


class StorageTests(unittest.TestCase):
    def verify(self,plan,root,actor,chunk=1):
        return s.verify_collection(plan,root,root/'source.json',chunk,1,actor,
            lambda *_:self.fail('This constructed chunk owns no replay.'))

    def test_public_admission_does_not_decode_labels_or_authority(self):
        with tempfile.TemporaryDirectory() as directory:
            plan,root,actor=collection(directory)
            receipt=self.verify(plan,root,actor)
            self.assertFalse(receipt['semanticValidationLabelsDecoded'])
            self.assertEqual(receipt['scientificTerminalGames'],64)

    def test_verified_archive_preserves_all_bytes_before_pruning(self):
        with tempfile.TemporaryDirectory() as directory:
            plan,root,actor=collection(directory);receipt=self.verify(plan,root,actor)
            archive=Path(directory)/'chunk-1.tar.gz';stored=s.archive_collection(root,archive,receipt)
            self.assertFalse(root.exists());s.verify_archive(archive,stored)
            self.assertEqual(len(list(s.public_members(archive,stored))),64)

    def test_changed_raw_label_subject_rejected_without_decoding(self):
        with tempfile.TemporaryDirectory() as directory:
            plan,root,actor=collection(directory);(root/'labels-64.json').write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError,'raw subjects'):self.verify(plan,root,actor)

    def test_public_outcome_field_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            plan,root,actor=collection(directory);path=root/'public-64.json'
            value=p.read(path);value['arms']=[];path.write_bytes(p.canonical(value))
            completed=p.read(root/'finished.json');completed['origins'][0]['public']['sha256']=p.file_sha(path)
            (root/'finished.json').write_bytes(p.canonical(completed))
            with self.assertRaisesRegex(ValueError,'exclude authority'):self.verify(plan,root,actor)

    def test_wrong_global_request_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            plan,root,actor=collection(directory);path=root/'prepared.json'
            native=p.read(path);native['requests'][0]=p.request(0);path.write_bytes(p.canonical(native))
            with self.assertRaisesRegex(ValueError,'global streams'):self.verify(plan,root,actor)

    def test_archive_refuses_changed_original_after_admission(self):
        with tempfile.TemporaryDirectory() as directory:
            plan,root,actor=collection(directory);receipt=self.verify(plan,root,actor)
            (root/'origin-64.json.gz').write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError,'after completed'):
                s.archive_collection(root,Path(directory)/'chunk.tar.gz',receipt)
            self.assertTrue(root.exists())

    def test_subject_and_archive_publication_never_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'receipt.json';s.publish_json(path,{'one':1})
            with self.assertRaises(Exception):s.publish_json(path,{'two':2})
            self.assertEqual(p.read(path),{'one':1})

    def test_symlink_and_traversal_rejected(self):
        for name in ('../outside','/absolute','a/../b','a\\b','./a'):
            with self.assertRaises(ValueError):s.safe(name)
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'real').write_text('x');(root/'alias').symlink_to(root/'real')
            with self.assertRaisesRegex(ValueError,'symlinks'):s.inventory(root)

    def test_portable_roles_remove_only_paths(self):
        value={'path':'/one','originalPath':'/two','sha256':'a'*64,'snapshot':'exact.json','roles':[{'x':1}]}
        self.assertEqual(s.portable(value),{'sha256':'a'*64,'snapshot':'exact.json','roles':[{'x':1}]})


if __name__=='__main__':unittest.main()
