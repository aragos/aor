"""Typed expansion-only terminal MC; no aliases to purchase or two-head joint objectives."""
from dataclasses import dataclass, asdict
from pathlib import Path

import purchase_policy as policy


CONTRACT = policy.PolicyContract('aor-expansion-observation-2', 'aor-expansion-policy-2',
    'expansion', 1024, trajectoryEncoding='gzip-json-v1', schemaVersion=2)
ESTIMATOR = dict(version='expansion-terminal-mc-1', gamma=1., **{'lambda': 1.},
    baseline=.5, intermediateReward=0., forcedRowsIncluded=True,
    weighting='uniform-recorded-decision-mean', terminalReward='original-complete-world')


def initialize(schema, source, seed):
    """Transfer shared RawActorState columns only; caller pins the actual input and seed.

    Both encoder owners use identical RawActorState names/units. Their only common exceptions
    are phase-specific decision flags, excluded explicitly. This function never reads a model
    file or reserves a scientific seed. Tests may use synthetic dictionaries and tensors.
    """
    policy.validate_model(source)
    model = policy.initialize(schema, seed, contract=CONTRACT)
    source_names = source['stateFeatureNames']; names = model['stateFeatureNames']
    shared = sorted((set(source_names) & set(names)) -
        {name for name in source_names + names if name.startswith('decision.')})
    policy.require(shared, 'Expansion initialization requires verified shared RawActorState columns.')
    mapping = [(source_names.index(name), names.index(name)) for name in shared]
    model['stateWeights'] = [0.] * (policy.HIDDEN * len(names))
    for unit in range(policy.HIDDEN):
        for old, new in mapping:
            model['stateWeights'][unit * len(names) + new] = source['stateWeights'][unit * len(source_names) + old]
    model['stateBias'] = list(source['stateBias'])
    return policy.validate_model(model, contract=CONTRACT), shared


@dataclass(frozen=True)
class ExpansionMcObjective:
    """A complete, validated episode collection owns the unchanged behavior rows."""
    collection: object

    def __post_init__(self):
        from expansion_on_policy_data import ExpansionCollection
        policy.require(isinstance(self.collection, ExpansionCollection),
            'Expansion MC requires its separately typed completed episode collection.')

    def validate(self, contract, config):
        policy.require(contract == CONTRACT, 'Expansion MC requires schema2/EXPANSION_2 only.')
        policy.require(config.valueCoefficient == config.entropyCoefficient ==
            config.cardReferenceKlCoefficient == 0 and config.threads == 1,
            'Expansion MC is actor-only, CPU1, with no critic, entropy or imitation loss.')

    def phase_reader(self, directory, model_path, model, *, contract):
        rows, provenance = self.collection.phase_reader(directory, model_path, model, contract=contract)
        return [dict(row, _actorOnlyAdvantage=row['return']-.5) for row in rows], dict(
            provenance, readerSha256=policy.file_digest(__file__))

    def bind(self, rows, provenance, model_sha):
        collection = self.collection; collection.unchanged()
        policy.require(model_sha == collection.actor_sha and provenance == dict(
            collection.provenance, readerSha256=policy.file_digest(__file__)),
            'Expansion MC must retain its complete collection, behavior actor and source.')
        policy.require(len(rows) == len(collection.rows), 'Expansion MC must retain every original decision.')
        for row, original in zip(rows, collection.rows):
            policy.require(row.get('_prepared') is original.get('_prepared') and
                {k:v for k,v in row.items() if k != '_actorOnlyAdvantage'} == original and
                policy.number(row.get('_actorOnlyAdvantage'), 'Expansion MC advantage') == original['return']-.5,
                'Expansion MC cannot replace observations, behavior likelihood/value or terminal labels.')
        return rows, dict(version='expansion-mc-actor-only-1', phase='expansion', estimator=ESTIMATOR,
            ecology=collection.ecology, collectionSha256=provenance['collectionSha256'],
            gameHashes=provenance['gameHashes'], behaviorModelSha256=model_sha,
            frozenParameters=['valueWeights','valueBias'], optimizerInitialization='fresh-adam')


@dataclass(frozen=True)
class ExpansionMcAdamContinuation:
    """Five actor slots only, bound to this exact expansion ecology and MC recipe."""
    version: str = 'expansion-mc-adam-continuation-1'

    def validate(self, checkpoint_path, objective, model, config):
        policy.require(self.version == 'expansion-mc-adam-continuation-1' and
            objective['version'] == 'expansion-mc-actor-only-1', 'Unsupported expansion MC continuation.')
        previous, identity = policy.actor_adam_checkpoint(checkpoint_path, model)
        old = identity.get('expansionMcObjective', {})
        policy.require(all(old.get(key) == objective[key] for key in
            ('version','phase','estimator','ecology','frozenParameters')) and
            old.get('ecology') is not None and identity['actorVersion'] == CONTRACT.actor and
            {k:v for k,v in identity['config'].items() if k != 'seed'} ==
                {k:v for k,v in asdict(config).items() if k != 'seed'},
            'Expansion carry cannot cross ecology, phase, estimator or PPO recipe.')
        finished = policy.read_json(Path(checkpoint_path).parent/'finished.json')
        policy.require(finished.get('expansionMcObjective') == old and
            old['behaviorModelSha256'] == identity['modelSha256'] and
            finished['inputModelSha256'] == identity['modelSha256'] and
            finished['history'] == previous['history'] and finished['acceptedEpochs'] == previous['epoch'],
            'Expansion carry requires the exact completed MC update.')
        inherited = identity.get('expansionMcOptimizerContinuation')
        steps = 0 if inherited is None else policy.integer(inherited.get('actorAdamSteps'),
            'Inherited expansion Adam steps', 0, 2**53)
        steps = policy.validated_actor_adam_steps(previous, model, config, CONTRACT, steps)
        return dict(version=self.version, phase='expansion', actorAdamSteps=steps,
            checkpointSha256=policy.file_digest(checkpoint_path), previousIdentitySha256=policy.digest(identity))


def update(collection, config, output, *, checkpoint=None):
    """One numerical attempt; the outer finite runner owns its original deadline."""
    objective = ExpansionMcObjective(collection)
    report = policy.update(collection.actor_path, collection.directory, config, output,
        contract=CONTRACT, entrypoint=__file__, collection_reader=objective.phase_reader,
        expansion_mc=objective, optimizer_from=checkpoint,
        expansion_optimizer_continuation=ExpansionMcAdamContinuation() if checkpoint is not None else None)
    collection.unchanged()
    policy.require(report['stopReason'] in ('epochs-complete','behavior-kl-limit'),
        'An interrupted expansion update cannot publish a completed actor.')
    model = policy.read_json(Path(output)/'policy.json')
    ExpansionMcAdamContinuation().validate(Path(output)/'checkpoint.json', report['expansionMcObjective'], model, config)
    return report
