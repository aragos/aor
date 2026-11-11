"""Two explicitly frozen state-only critics; unchanged inference math and strong collection reader."""
from enum import Enum
from pathlib import Path

import automata_experiments as exp
import purchase_strong_data as strong

BASE_CONTRACT = '120748a9cfe6e018a6885108c0b652990d034a41cfc870600fb6a04bbe22ef45'
FEATURES = '8261e8bfa69892f6971fb6fca340860610d59afb93c26bcf264039d460bb6864'
REFRESH_AUDIT = '51a5afa20513d7862951f236893ac58fe09cf686dedcf503dd769533da0aff08'
OLD_AUDIT = 'e8cb486a3a5f16e583c64074e55460c1a17afb51f6130dd829c949c5298ceed5'


class CriticRole(Enum):
    OLD = 'old'
    REFRESHED = 'refreshed'

    @property
    def checkpoint_sha256(self):
        return {'old': '897d2f0106064863cb6a4a718bae68c79d4d4cc0e0139ad4e5d5705aa58959f6',
                'refreshed': 'c2ea78e7a225998e7c690bdce852f3502d8198a464f37fd1674bbc01142b7f17'}[self.value]


def inference_contract(role, torch_version):
    """Name the actual checkpoint; the old selection document is only the shared math reference."""
    exp.require(isinstance(role, CriticRole), 'A frozen critic requires an explicit old/refreshed role.')
    directory = Path(__file__).parent
    return dict(version='purchase-frozen-critic-contract-1', role=role.value,
        checkpointSha256=role.checkpoint_sha256,
        checkpointAuditSha256=OLD_AUDIT if role is CriticRole.OLD else REFRESH_AUDIT,
        baseInferenceContractSha256=BASE_CONTRACT, stateDictionarySha256=FEATURES,
        stateValueSourceSha256=exp.sha(directory/'purchase_actor_baseline.py'),
        criticCodeSha256=exp.sha(directory/'purchase_value_benchmark.py'),
        encoderCodeSha256=exp.sha(directory/'purchase_value_data.py'),
        stateFeatures=3556, inputs='actor-visible-stateFeatures-only', widths=[64],
        device='cpu', dtype='float32', torchVersion=torch_version, threads=1, maximumRows=256,
        missingValue=0., normalization='original-units', newCriticFits=0)


def predictor_identity(role, checkpoint, contract, features, torch_version):
    exp.require(isinstance(role, CriticRole), 'A predictor requires an explicit frozen critic role.')
    exp.require(exp.sha(checkpoint) == role.checkpoint_sha256 and exp.sha(features) == FEATURES
                and exp.read(contract) == inference_contract(role, torch_version),
                'The frozen critic role, inference descriptor or state dictionary changed.')
    value = strong.predictor_identity(checkpoint, contract, features, torch_version)
    return dict(value, contractVersion='purchase-frozen-critic-contract-1', criticRole=role.value)


class FrozenCriticAdapter:
    """Reuse the original state-value owner; provenance keeps the reader and adapter separate."""
    read_collection = staticmethod(strong.read_collection)

    def __init__(self, role):
        exp.require(isinstance(role, CriticRole), 'The adapter requires one explicit frozen critic role.')
        self.role = role

    def predict(self, collection, model_path, checkpoint, contract_path, feature_path, output,
                *, expected_model_sha):
        import torch
        import purchase_actor_baseline as baseline
        import purchase_policy as policy
        exp.require(not Path(output).exists(), 'Preserve each prebatch frozen prediction artifact.')
        identity = predictor_identity(self.role, checkpoint, contract_path, feature_path, torch.__version__)
        exp.require(exp.sha(model_path) == expected_model_sha, 'Predict only the exact current behavior actor.')
        torch.set_num_threads(1)
        torch.use_deterministic_algorithms(True)
        model = policy.validate_model(policy.read_json(model_path))
        names = policy.read_json(feature_path)
        exp.require(names == model['stateFeatureNames'] and len(names) == baseline.data.STATE_COUNT,
                    'Critic and behavior actor require the identical original state dictionary.')
        rows, provenance = strong.read_collection(collection, model_path, model, contract=policy.PURCHASE_CONTRACT)
        critic = baseline.benchmark.Critic((64,), 0)
        critic.load_state_dict(torch.load(checkpoint, map_location='cpu', weights_only=True), strict=True)
        values = baseline.state_values(critic, rows, names)
        report = dict(kind='purchase-frozen-state-baselines-1',
            collectionSha256=provenance['collectionSha256'], gameHashes=provenance['gameHashes'],
            behaviorModelSha256=exp.sha(model_path), newFits=0, predictor=identity,
            collectionReader=dict(version='purchase-strong-collection-reader-1',
                sha256=exp.sha(strong.__file__), rivalActorSha256=strong.G2, ecology=strong.ECOLOGY.value),
            predictionAdapter=dict(version='purchase-critic-refresh-adapter-1', sha256=exp.sha(__file__),
                                   role=self.role.value),
            predictions=[dict(gameSha256=provenance['gameHashes'][row['originIndex']],
                decision=row['number'], value=value) for row, value in zip(rows, values)])
        policy.atomic_json(output, report)
        return report
