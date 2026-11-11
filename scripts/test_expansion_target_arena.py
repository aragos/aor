"""Index-bound target models and runtime snapshots must agree with each final bank."""
import copy
from pathlib import Path
import unittest
from unittest import mock
import test_expansion_target_search as fixtures
import expansion_target_search as target
import expansion_target_arena as arena


class TargetArenaTest(unittest.TestCase):
    setUp = fixtures.TargetSearchTest.setUp
    bundle = fixtures.TargetSearchTest.bundle
    def reports(self,source):
        metadata=dict(version='expansion-imitation-1',decision='expand',featureNames=list(arena.FEATURES))
        def record(path,snapshot):
            if path is None:return None
            (self.root/snapshot).write_bytes(Path(path).read_bytes())
            return dict(path=path,sha256=arena.sha(path),snapshot=snapshot)
        for role,value in source['expansionImitation'].items():
            metadata[role]=([record(path,f'expansion-imitation-opponent-{index}.json') for index,path in enumerate(value)]
                if role=='opponentModels' else record(value,'expansion-imitation-'+role.removesuffix('Model')+'.json'))
        return [dict(expansionImitation=metadata,tokenOrder=copy.deepcopy(source['tokenOrder']))]

    def test_exact_mixed_native_roles_accepted(self):
        source=target.source(self.protocol,self.bundle(),'confirm');reports=self.reports(source)
        with mock.patch.object(arena.token,'verify_environment'):arena.verify_overlay(self.protocol,source,self.root,reports)

    def test_exact_homogeneous_native_roles_accepted(self):
        source=target.source(self.protocol,self.bundle(),'heldout');reports=self.reports(source)
        with mock.patch.object(arena.token,'verify_environment'):arena.verify_overlay(self.protocol,source,self.root,reports)

    def test_final_bank_cannot_reuse_weaker_mixed_lineup(self):
        source=target.source(self.protocol,self.bundle(),'confirm')
        source['seed']=self.protocol['worldBanks']['heldout']['nativeOffset']
        with self.assertRaisesRegex(ValueError,'bank-specific'):arena.verify_overlay(self.protocol,source,self.root,[])

    def test_evaluated_token_selector_cannot_drift(self):
        source=target.source(self.protocol,self.bundle(),'confirm');source['tokenOrder']['incumbentSelector']='ORDINARY'
        with self.assertRaisesRegex(ValueError,'bank-specific'):arena.verify_overlay(self.protocol,source,self.root,[])

    def test_rival_expansion_snapshot_corruption_rejected(self):
        source=target.source(self.protocol,self.bundle(),'confirm');reports=self.reports(source)
        (self.root/'expansion-imitation-opponent-3.json').write_text('{}')
        with mock.patch.object(arena.token,'verify_environment'),self.assertRaisesRegex(ValueError,'snapshot changed'):
            arena.verify_overlay(self.protocol,source,self.root,reports)

    def test_native_token_role_reassignment_rejected(self):
        source=target.source(self.protocol,self.bundle(),'confirm');reports=self.reports(source)
        reports[0]['tokenOrder']['opponentSelectors'].reverse()
        with mock.patch.object(arena.token,'verify_environment'),self.assertRaisesRegex(ValueError,'roles or token'):
            arena.verify_overlay(self.protocol,source,self.root,reports)


if __name__=='__main__':unittest.main()
