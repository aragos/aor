import copy
import hashlib
import io
import tarfile
from types import SimpleNamespace
import unittest

import token_paired_corpus as c
import token_paired_campaign_plan as p
import token_paired_storage as s


def public():
    observation=dict(encoderVersion='constructed',stateFeatures={'own.cash':1.},baselineIndex=0,
        actions=[dict(command='buy-tokens 1',features={}),dict(command='buy-tokens 2',features={})])
    return dict(kind='token-paired-public-1',signature='a'*64,scopeSha256='b'*64,scienceSha256='c'*64,
        iteration=1,request=p.request(0),streams=dict(gameSeed=10,proposalSeed=11,future=[12,13]),
        status='READY',originSha256='d'*64,missingReason=None,observedWindows=2,anchors=[],
        publicRoot=dict(number=20,batchStartNumber=18,botSeed=123,actorSeed=456,observation=observation,
            referenceIndex=0,sampledCommandIndex=1,behaviorLogProbability=-3.5,behaviorValue=.5,
            currentProbabilities=[.97,.03],originalProbabilities=[.97,.03],proposedIndices=[0,1],
            inclusionProbability=1.))


def labels(value):
    scores=[dict(player='p'+str(i+1),cash=4-i,advancePoints=0,misery=0,total=4-i,winner=i==0) for i in range(4)]
    result={key:value[key] for key in ('signature','scopeSha256','scienceSha256','iteration','request','streams','status','originSha256')}
    result.update(kind='token-paired-labels-1',arms=[dict(actionIndex=i,command=action['command'],
        inclusionProbability=1.,outcomes=[dict(replicate=j,canonicalSha256='f'*64,replayVerified=True,
            terminal=dict(reward=1.,winShare=1.,rankReward=1.,scores=scores,decisions=100,continuationSeed=seed))
                for j,seed in enumerate(value['streams']['future'])])
        for i,action in enumerate(value['publicRoot']['observation']['actions'])])
    return result


class CorpusTests(unittest.TestCase):
    def test_actor_projection_excludes_policy_seeds_and_outcomes(self):
        value=public();result=c.normalized_public(value)
        self.assertNotIn('actorSeed',result);self.assertNotIn('botSeed',result)
        self.assertNotIn('observedWindows',result);self.assertNotIn('behaviorValue',result)
        self.assertEqual(result['sampledIndex'],1);self.assertEqual(result['observation']['baselineIndex'],0)

    def test_terminal_reward_checked_from_scores_not_accepted_blindly(self):
        value=public();record=c.normalized_public(value);typed=SimpleNamespace(record=record,sha256=p.digest(record))
        label=labels(value);self.assertEqual(c.normalized_labels(label,value,typed)['actions'][1]['rewards'],[1.,1.])
        changed=copy.deepcopy(label);changed['arms'][1]['outcomes'][0]['terminal']['reward']=.1
        with self.assertRaisesRegex(ValueError,'terminal label'):c.normalized_labels(changed,value,typed)

    def test_future_stream_or_sibling_index_cannot_change(self):
        value=public();record=c.normalized_public(value);typed=SimpleNamespace(record=record,sha256=p.digest(record))
        label=labels(value);label['arms'][1]['outcomes'][0]['terminal']['continuationSeed']=99
        with self.assertRaisesRegex(ValueError,'future'):c.normalized_labels(label,value,typed)

    def test_singleton_retains_public_root_without_invented_returns(self):
        value=public();value['status']='SINGLETON';value['publicRoot']['observation']['actions']=value['publicRoot']['observation']['actions'][:1]
        record=c.normalized_public(value);typed=SimpleNamespace(record=record,sha256=p.digest(record))
        label=labels(value);label['arms']=[]
        self.assertIsNone(c.normalized_labels(label,value,typed))
        self.assertIsNotNone(record['observation'])

    def test_actual_member_bytes_verified_before_json_decode(self):
        payload=b'{"reward":0.123}'
        buffer=io.BytesIO()
        with tarfile.open(fileobj=buffer,mode='w:gz') as out:
            info=tarfile.TarInfo('labels-0.json');info.size=len(payload);out.addfile(info,io.BytesIO(payload))
        buffer.seek(0)
        with tarfile.open(fileobj=buffer,mode='r:gz') as source:
            member=source.next()
            receipt={'files':{'labels-0.json':dict(size=len(payload),sha256='0'*64)}}
            with self.assertRaisesRegex(ValueError,'semantic read'):s.member_json(source,member,receipt)

    def test_validation_world_cannot_supply_original_anchor(self):
        value=public();value['request']=p.request(512)
        with self.assertRaisesRegex(ValueError,'original TRAIN'):c.normalized_anchor(value)


if __name__=='__main__':unittest.main()
