"""Explicit strong-rival collection provenance; frozen state-value inference is unchanged.

The state predictor remains purchase_actor_baseline.state_values, with its original source bytes
and exact46 predictor identity. Only validation of the new collection ecology has a new owner;
its source hash is separately bound to the learner provenance and prediction artifact.
"""
from pathlib import Path

import automata_experiments as exp
import purchase_ecology_data as data
import purchase_baseline_pilot as matched

G2='09e29e5b25b76bdad3c2e082e210322d16851bfd8a978ce123b6a8b0fbf8b2af'
ECOLOGY=data.CollectionRivalEcology.HOMOGENEOUS


def verify_collection(directory,*,expected_ordinary=matched.ORDINARY,expected_expansion=matched.EXPANSION):
    return data.verify_collection(directory,expected_ordinary=expected_ordinary,
        expected_expansion=expected_expansion,expected_purchase=G2,collection_ecology=ECOLOGY)


def read_collection(directory,model_path,model,*,contract):
    rows,provenance=data.read_collection(directory,model_path,model,contract=contract,
        expected_ordinary=matched.ORDINARY,expected_expansion=matched.EXPANSION,
        expected_purchase=G2,collection_ecology=ECOLOGY)
    return rows,dict(provenance,readerSha256=exp.sha(__file__))


def predictor_identity(checkpoint,contract,features,torch_version):
    """Identify the same state-value math/weights, independently of the collection reader."""
    directory=Path(__file__).parent
    return dict(checkpointSha256=exp.sha(checkpoint),inferenceContractSha256=exp.sha(contract),
        stateDictionarySha256=exp.sha(features),sourceSha256=exp.sha(directory/'purchase_actor_baseline.py'),
        criticCodeSha256=exp.sha(directory/'purchase_value_benchmark.py'),
        encoderCodeSha256=exp.sha(directory/'purchase_value_data.py'),device='cpu',dtype='float32',
        torchVersion=torch_version,threads=1,maximumRows=256)


def predict(collection,model_path,checkpoint,contract_path,feature_path,output,*,expected_model_sha):
    import torch
    import purchase_actor_baseline as baseline
    import purchase_policy as policy
    policy.require(not Path(output).exists(),'Preserve frozen strong-ecology state predictions.')
    policy.require(exp.sha(checkpoint)==baseline.CRITIC and exp.sha(contract_path)==baseline.CONTRACT
        and exp.sha(feature_path)==baseline.FEATURES and exp.sha(model_path)==expected_model_sha,
        'Strong collection retains the original audited fresh64 critic and exact behavior actor.')
    torch.set_num_threads(1);torch.use_deterministic_algorithms(True)
    model=policy.validate_model(policy.read_json(model_path));names=policy.read_json(feature_path)
    policy.require(names==model['stateFeatureNames'] and len(names)==baseline.data.STATE_COUNT,
                   'Critic and behavior actor retain their identical original state dictionaries.')
    rows,provenance=read_collection(collection,model_path,model,contract=policy.PURCHASE_CONTRACT)
    critic=baseline.benchmark.Critic((64,),0)
    critic.load_state_dict(torch.load(checkpoint,map_location='cpu',weights_only=True),strict=True)
    values=baseline.state_values(critic,rows,names)
    report=dict(kind='purchase-frozen-state-baselines-1',collectionSha256=provenance['collectionSha256'],
        gameHashes=provenance['gameHashes'],behaviorModelSha256=exp.sha(model_path),newFits=0,
        predictor=predictor_identity(checkpoint,contract_path,feature_path,torch.__version__),
        collectionReader=dict(version='purchase-strong-collection-reader-1',sha256=exp.sha(__file__),
                              rivalActorSha256=G2,ecology=ECOLOGY.value),
        predictions=[dict(gameSha256=provenance['gameHashes'][row['originIndex']],
                          decision=row['number'],value=value) for row,value in zip(rows,values)])
    policy.atomic_json(output,report)
    return report
