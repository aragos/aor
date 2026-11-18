"""Versioned detached purchase-advantage targets; no new rewards or inference inputs."""
from pathlib import Path

import purchase_actor_baseline as baseline
import purchase_policy as policy

ESTIMATOR = policy.PURCHASE_GAE_ESTIMATOR


def targets(rows, provenance, model_sha, baseline_file, output):
    """Read-only target derivation from already-validated complete behavior and frozen values."""
    path = Path(baseline_file).resolve()
    bound, _ = policy.ActorOnlyObjective('external',str(path)).bind(rows,provenance,model_sha)
    advantages = policy.purchase_gae(rows,[row['_actorOnlyBaseline'] for row in bound])
    document = dict(kind='purchase-precomputed-advantages-1',estimator=ESTIMATOR,
        collectionSha256=provenance['collectionSha256'],gameHashes=provenance['gameHashes'],
        behaviorModelSha256=model_sha,frozenStatePredictions=dict(path=str(path),sha256=policy.file_digest(path)),
        advantages=[dict(gameSha256=provenance['gameHashes'][row['originIndex']],
                         decision=row['number'],advantage=value) for row,value in zip(rows,advantages)])
    policy.require(not Path(output).exists(),'Preserve every frozen precomputed advantage file.')
    policy.atomic_json(output,document)
    return document


def critic_metrics(rows, provenance, baseline_file):
    """Descriptive complete-batch diagnostics only; never changes a target or continuation."""
    predictions=policy.read_json(baseline_file)['predictions']
    groups={};games={}
    for row,prediction in zip(rows,predictions):
        square=(prediction['value']-row['return'])**2
        groups.setdefault(row['decision'],[]).append(square)
        games.setdefault(row['originIndex'],[]).append(square)
    return dict(decisions=len(rows),games=provenance['games'],gamesWithDecisions=len(games),
        zeroDecisionGames=provenance['games']-len(games),
        decisionMeanMse=sum(sum(values) for values in groups.values())/len(rows),
        gameMeanMse=sum(sum(values)/len(values) for values in games.values())/len(games),
        phase={name:dict(rows=len(values),decisionMeanMse=sum(values)/len(values))
               for name,values in groups.items()},admissionOrSelection=False)


def update(collection, model_path, config, baseline_file, target_file, output, arm,
           optimizer_from=None, *, collection_reader=baseline.read_collection):
    policy.require(arm in ('mc','gae'),'Only the matched externalMC and GAE arms are supported.')
    objective=(policy.ActorOnlyObjective('external',str(baseline_file)) if arm=='mc' else
               policy.PurchaseAdvantageTargets(str(target_file)))
    return policy.update(model_path,collection,config,output,optimizer_from=optimizer_from,
        collection_reader=collection_reader,entrypoint=Path(__file__),actor_only=objective,
        actor_optimizer_continuation=policy.ActorAdamContinuation() if optimizer_from else None)
