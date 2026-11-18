"""Constructed original-controller/profile joins; no controller or scientific process runs."""
from pathlib import Path
import tempfile
import unittest

import coordinated_es_storage as storage
import coordinated_win_execution as execution
import coordinated_win_plan as p


class ProfileBindingTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve()
        self.protocol=storage.publish_json(self.root/'protocol.json',dict(constructedProtocol=True))
        self.profile=storage.publish_json(self.root/'profile.json',dict(constructedProfile=True))
        self.owner=dict(kind=p.PREFIX+'-worker-owner-1',profile=self.profile)
        self.spec=dict(inputs={item['path']:item['sha256'] for item in (self.protocol,self.profile)},
            stages=[dict(id=p.PREFIX,command=['constructed-python','stage','--protocol',self.protocol['path'],
                '--profile',self.profile['path']])])

    def verify(self):
        storage.publish_json(self.root/'campaign.json',self.spec)
        storage.publish_json(self.root/'run/study/worker-owner.json',self.owner)
        execution.verify_profile(self.protocol['path'],self.root)

    def test_original_profile_and_protocol_are_admitted(self):self.verify()

    def test_worker_cannot_replace_profile_with_another_valid_file(self):
        self.owner['profile']=storage.publish_json(self.root/'replacement.json',dict(constructedProfile=True))
        with self.assertRaisesRegex(ValueError,'exact pinned controller'):self.verify()

    def test_profile_argument_requires_its_original_input_hash(self):
        self.spec['inputs'][self.profile['path']]='f'*64
        with self.assertRaisesRegex(ValueError,'exact pinned controller'):self.verify()

    def test_protocol_argument_cannot_name_another_subject(self):
        other=storage.publish_json(self.root/'other-protocol.json',dict(constructedProtocol=True))
        self.spec['stages'][0]['command'][3]=other['path']
        self.spec['inputs'][other['path']]=other['sha256']
        with self.assertRaisesRegex(ValueError,'exact pinned controller'):self.verify()

    def test_duplicate_profile_argument_is_rejected(self):
        self.spec['stages'][0]['command']+=['--profile',self.profile['path']]
        with self.assertRaisesRegex(ValueError,'exactly one profile'):self.verify()


if __name__=='__main__':unittest.main()
