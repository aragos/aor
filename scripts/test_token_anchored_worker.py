"""Constructed file/lineage fixtures; no native process, neural forward, RNG or network."""
import base64
import hashlib
import json
from pathlib import Path
import tempfile
import time
import unittest

import robust_token_campaign as common
import token_anchored_worker as worker
import token_anchored_plan as protocol
from token_anchored_recipe import RECIPE
from test_token_anchored_plan import record


def write(path, value):
    common.write(path, value)
    return dict(path=str(path), sha256=common.sha(path))


def fixture(root):
    original = write(root/'original.json', dict(schemaVersion=3, encoderVersion='invented-TOKEN2',
        stateWeights=[.1], stateBias=[.2], actionWeights=[.3], actionBias=[.4], actorWeights=[0.],
        valueWeights=[0.], valueBias=0.))
    anchor = write(root/'anchor.json', dict(kind='invented-anchor-no-model-forward'))
    native = write(root/'training.jar', dict(kind='invented-runtime-never-executed'))
    arena = write(root/'arena.json', dict(kind='invented-arena-never-executed'))
    plan = record(); plan.update(originalActorSha256=original['sha256'],
        anchorDescriptorSha256=anchor['sha256'], nativeJarSha256=native['sha256'])
    subject = write(root/'protocol.json', plan)
    profile = dict(kind=worker.PROFILE_KIND, host='DESKTOP', root=str(root), workers=16,
        heapMiB=6144, protocol=subject, anchor=anchor, originalActor=original, arena=arena, nativeJar=native,
        pins={item['path']: item['sha256'] for item in (original, anchor, native, arena, subject)},
        java='/invented/java', classpath='/invented/runtime/*')
    profile_subject = write(root/'profile.json', profile)
    clock = common.science_clock(plan, time.time(), subject['sha256'], 'token-anchored')
    common.write(root/'clock.json', clock)
    return profile, profile_subject, protocol.Plan.from_record(plan, raw_sha256=subject['sha256']), clock


def model_request(profile, profile_subject, plan, clock):
    model = common.read(profile['originalActor']['path']); model['actorWeights'] = [.1]
    raw_model = common.canonical(model)
    context = dict(round=1, anchor_sha256=plan.record['anchorDescriptorSha256'],
        original_model_sha256=plan.record['originalActorSha256'], current_model_sha256=plan.record['originalActorSha256'],
        request_bank_sha256=protocol.shared.digest(plan.record['rounds'][0]['origins']), train_count=16,
        validation_count=4, probability_contract_sha256=protocol.shared.digest(plan.record['probabilityContract']))
    sha = hashlib.sha256(raw_model).hexdigest()
    done = dict(kind='token-anchored-paired-fit-complete-1', complete=True, identity=dict(recipe=RECIPE,
        context=context, predecessor=None), outputModelSha256=sha, qualificationClaimed=False, activated=False)
    files = {'policy.json': raw_model, 'finished.json': common.canonical(done)}
    return dict(kind='token-anchored-install-model-1', profileSha256=profile_subject['sha256'], clock=clock, round=1,
        files={name: dict(base64=base64.b64encode(raw).decode(), sha256=hashlib.sha256(raw).hexdigest())
            for name, raw in files.items()})


class WorkerTests(unittest.TestCase):
    def test_first_collection_source_is_exact_and_owned(self):
        with tempfile.TemporaryDirectory() as temporary:
            profile, _, plan, _ = fixture(Path(temporary))
            source = plan.collection_source(1, 0, profile, profile['originalActor'])
            request = dict(kind='token-anchored-worker-task-1', operation='collect', round=1, chunk=0,
                source=source, taskId='round-1/chunk-0/collect')
            worker.validate_source(profile, request)

    def test_changed_future_is_rejected_before_native_execution(self):
        with tempfile.TemporaryDirectory() as temporary:
            profile, _, plan, _ = fixture(Path(temporary))
            source = plan.collection_source(1, 0, profile, profile['originalActor'])
            source['origins'][0]['future'][0] += 123
            with self.assertRaisesRegex(ValueError, 'cannot change'):
                worker.validate_source(profile, dict(kind='token-anchored-worker-task-1', operation='collect',
                    round=1, chunk=0, source=source, taskId='round-1/chunk-0/collect'))

    def test_transport_tag_cannot_alias_another_task(self):
        with tempfile.TemporaryDirectory() as temporary:
            profile, _, plan, _ = fixture(Path(temporary))
            with self.assertRaisesRegex(ValueError, 'task ID'):
                worker.validate_source(profile, dict(kind='token-anchored-worker-task-1', operation='collect',
                    round=1, chunk=0, source=plan.collection_source(1, 0, profile, profile['originalActor']),
                    taskId='round-1/chunk-1/collect'))

    def test_model_installation_retains_sole_predecessor_and_exact_actor_scope(self):
        with tempfile.TemporaryDirectory() as temporary:
            profile, subject, plan, clock = fixture(Path(temporary))
            request = model_request(profile, subject, plan, clock)
            result = worker.install_model(subject['path'], request)
            current = worker.current_actor(profile, plan, 2)
            self.assertTrue(result['complete'])
            self.assertEqual(current['sha256'], request['files']['policy.json']['sha256'])

    def test_model_installation_is_once_only(self):
        with tempfile.TemporaryDirectory() as temporary:
            profile, subject, plan, clock = fixture(Path(temporary))
            request = model_request(profile, subject, plan, clock)
            worker.install_model(subject['path'], request)
            with self.assertRaisesRegex(ValueError, 'once-only'):
                worker.install_model(subject['path'], request)

    def test_expired_model_installation_creates_no_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); profile, subject, plan, clock = fixture(root)
            request = model_request(profile, subject, plan, clock)
            with self.assertRaisesRegex(ValueError, 'active original'):
                worker.install_model(subject['path'], request, now=lambda: clock['deadlineEpoch']+1)
            self.assertFalse((root/'models').exists())

    def test_corrupt_model_transport_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            profile, subject, plan, clock = fixture(Path(temporary))
            request = model_request(profile, subject, plan, clock)
            request['files']['policy.json']['sha256'] = '0'*64
            with self.assertRaisesRegex(ValueError, 'transport changed'):
                worker.install_model(subject['path'], request)

    def test_value_or_metadata_change_cannot_be_hidden_in_actor_installation(self):
        with tempfile.TemporaryDirectory() as temporary:
            profile, subject, plan, clock = fixture(Path(temporary))
            request = model_request(profile, subject, plan, clock)
            model = json.loads(base64.b64decode(request['files']['policy.json']['base64']))
            model['valueBias'] = .1; raw = common.canonical(model); digest = hashlib.sha256(raw).hexdigest()
            done = json.loads(base64.b64decode(request['files']['finished.json']['base64']))
            done['outputModelSha256'] = digest
            request['files'] = {name: dict(base64=base64.b64encode(value).decode(),
                sha256=hashlib.sha256(value).hexdigest()) for name, value in
                {'policy.json': raw, 'finished.json': common.canonical(done)}.items()}
            with self.assertRaisesRegex(ValueError, 'five actor arrays'):
                worker.install_model(subject['path'], request)


if __name__ == '__main__':
    unittest.main()
