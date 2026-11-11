"""Frozen state-baseline predictions and two matched actor-only purchase updates.

No critic is trained here. Original observations, behavior likelihoods and values remain
untouched. The external baseline sees only the existing state feature vector, before updates.
"""
from pathlib import Path

import torch

import purchase_ecology_data as ecology
import purchase_policy as policy
import purchase_value_benchmark as benchmark
import purchase_value_data as data

ORDINARY = 'b7c42a6f347a77c9bd9ff5d29778ea74650a9affe5fdb16738a8bea8d75d17d4'
EXPANSION = 'feb80d4363a93b266c94bc46e0d75ff4b0cb1f17a55d0f6d77782715862f44ef'
CRITIC = '897d2f0106064863cb6a4a718bae68c79d4d4cc0e0139ad4e5d5705aa58959f6'
CONTRACT = '120748a9cfe6e018a6885108c0b652990d034a41cfc870600fb6a04bbe22ef45'
FEATURES = '8261e8bfa69892f6971fb6fca340860610d59afb93c26bcf264039d460bb6864'


def read_collection(directory, model_path, model, *, contract):
    rows, provenance = ecology.read_collection(directory, model_path, model, contract=contract,
        expected_ordinary=ORDINARY, expected_expansion=EXPANSION)
    return rows, dict(provenance, readerSha256=policy.file_digest(__file__))


def state_values(critic, rows, names, maximum=256):
    """Float32 inference, original units, missing zero; no action or outcome inputs."""
    indices = {name:index for index,name in enumerate(names)}
    result = []
    critic.eval()
    with torch.no_grad():
        for start in range(0, len(rows), maximum):
            batch = rows[start:start+maximum]
            inputs = torch.zeros((len(batch),len(names)),dtype=torch.float32)
            for index,row in enumerate(batch):
                columns, values = data.encode_state(row['observation']['stateFeatures'], indices)
                inputs[index,columns] = torch.tensor(values,dtype=torch.float32)
            values = critic(inputs)
            policy.require(bool(torch.isfinite(values).all()) and bool(((0<=values)&(values<=1)).all()),
                           'Frozen critic predictions must be finite terminal-reward values.')
            result.extend(float(value) for value in values)
    return result


def predict(collection, model_path, checkpoint, contract_path, feature_path, output,
            *, expected_model_sha=ecology.C):
    policy.require(not Path(output).exists(), 'Preserve previously frozen baseline predictions.')
    policy.require(policy.file_digest(checkpoint) == CRITIC
        and policy.file_digest(contract_path) == CONTRACT and policy.file_digest(feature_path) == FEATURES
        and policy.file_digest(model_path) == expected_model_sha,
        'Use only the independently audited fresh64 critic and exact declared behavior actor.')
    torch.set_num_threads(1)
    torch.use_deterministic_algorithms(True)
    model = policy.validate_model(policy.read_json(model_path))
    names = policy.read_json(feature_path)
    policy.require(names == model['stateFeatureNames'] and len(names) == data.STATE_COUNT,
                   'Critic and behavior actor require identical original state dictionaries.')
    rows, provenance = read_collection(collection, model_path, model, contract=policy.PURCHASE_CONTRACT)
    critic = benchmark.Critic((64,),0)
    critic.load_state_dict(torch.load(checkpoint,map_location='cpu',weights_only=True),strict=True)
    values = state_values(critic,rows,names)
    report = dict(kind='purchase-frozen-state-baselines-1',
        collectionSha256=provenance['collectionSha256'],gameHashes=provenance['gameHashes'],
        behaviorModelSha256=policy.file_digest(model_path),newFits=0,
        predictor=dict(checkpointSha256=CRITIC,inferenceContractSha256=CONTRACT,
            stateDictionarySha256=FEATURES,sourceSha256=policy.file_digest(__file__),
            criticCodeSha256=policy.file_digest(benchmark.__file__),
            encoderCodeSha256=policy.file_digest(data.__file__),device='cpu',dtype='float32',
            torchVersion=torch.__version__,threads=1,maximumRows=256),
        predictions=[dict(gameSha256=provenance['gameHashes'][row['originIndex']],
                          decision=row['number'],value=value) for row,value in zip(rows,values)])
    policy.atomic_json(output,report)
    return report


def update(collection, model, config, baseline_file, output, arm):
    policy.require(arm in ('recorded','external'), 'Only the two frozen baseline arms are supported.')
    policy.require(policy.file_digest(model) == ecology.C, 'Both arms start from the exact C64 actor.')
    objective = policy.ActorOnlyObjective(arm,str(baseline_file) if arm=='external' else None)
    return policy.update(model,collection,config,output,collection_reader=read_collection,
                         entrypoint=Path(__file__),actor_only=objective)
