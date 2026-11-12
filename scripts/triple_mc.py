"""Explicit three-head terminal MC, with no aliases to legacy GAE or expansion-only views."""
from dataclasses import asdict, dataclass
from enum import Enum
from pathlib import Path

import card_policy
import expansion_mc
import purchase_policy as policy


class Phase(Enum):
    EXPANSION = 'expansion'
    CARD = 'card'
    PURCHASE = 'purchase'


PHASES = tuple(phase.value for phase in Phase)
CONTRACTS = dict(expansion=expansion_mc.CONTRACT, card=card_policy.CONTRACT,
                 purchase=policy.PURCHASE_CONTRACT)
ESTIMATOR = dict(version='triple-terminal-mc-1', baseline=.5,
    intermediateReward=0., forcedRowsIncluded=True,
    weighting='uniform-recorded-decision-mean-per-head', terminalReward='original-complete-world')


@dataclass(frozen=True)
class TripleMcObjective:
    """One original phase of a validated complete three-head behavior collection."""
    collection: object
    phase: Phase

    def __post_init__(self):
        from triple_trajectory import TripleCollection
        policy.require(isinstance(self.collection, TripleCollection) and isinstance(self.phase, Phase),
            'Triple MC requires its new typed collection and explicit phase.')

    def validate(self, contract, config):
        policy.require(contract == CONTRACTS[self.phase.value],
            'Triple MC cannot alias another phase or encoder contract.')
        policy.require(config.valueCoefficient == config.entropyCoefficient ==
            config.cardReferenceKlCoefficient == 0 and config.threads == 1,
            'Triple MC is actor-only CPU1, with no critic, entropy or reference loss.')

    def phase_reader(self, directory, model_path, model, *, contract):
        policy.require(contract == CONTRACTS[self.phase.value], 'Triple reader phase differs.')
        rows, provenance = self.collection.phase_reader(directory, model_path, model, contract=contract)
        return [dict(row, _actorOnlyAdvantage=row['return']-.5) for row in rows], dict(
            provenance, readerSha256=policy.file_digest(__file__))

    def bind(self, rows, provenance, model_sha):
        collection = self.collection; phase = self.phase.value
        collection.unchanged()
        _, expected = collection.phase_reader(collection.directory, collection.heads[phase]['path'],
            collection.models[phase], contract=CONTRACTS[phase])
        policy.require(model_sha == collection.heads[phase]['sha256'] and provenance == dict(
            expected, readerSha256=policy.file_digest(__file__)),
            'Triple MC must retain its exact complete collection and all three behavior subjects.')
        originals = collection.rows[phase]
        policy.require(len(rows) == len(originals), 'Triple MC retains every original phase decision.')
        for row, original in zip(rows, originals):
            policy.require(row.get('_prepared') is original.get('_prepared') and
                {key:value for key,value in row.items() if key != '_actorOnlyAdvantage'} == original and
                policy.number(row.get('_actorOnlyAdvantage'), 'Triple MC advantage') == original['return']-.5,
                'Triple MC cannot replace observations, actions, behavior likelihood/value or terminal labels.')
        return rows, dict(version='triple-mc-actor-only-1', phase=phase, estimator=ESTIMATOR,
            ecology=collection.ecology, behaviorModels={p:collection.heads[p]['sha256'] for p in PHASES},
            collectionSha256=provenance['collectionSha256'], gameHashes=provenance['gameHashes'],
            frozenParameters=['valueWeights','valueBias'], optimizerInitialization='fresh-adam')


@dataclass(frozen=True)
class TripleMcAdamContinuation:
    """Carry only one of the three actor slots, never a critic or legacy phase identity.

    The atomic publisher separately verifies that all three incoming checkpoints belong to the
    same completed triple and match its output bundle. This owner verifies each intrinsic slot.
    """
    version: str = 'triple-mc-adam-continuation-1'

    def validate(self, checkpoint_path, objective, model, config):
        policy.require(self.version == 'triple-mc-adam-continuation-1' and
            objective.get('version') == 'triple-mc-actor-only-1',
            'Triple Adam requires the explicit three-head MC objective.')
        phase = Phase(objective['phase'])
        previous, identity = policy.actor_adam_checkpoint(checkpoint_path, model)
        old = identity.get('tripleMcObjective', {})
        policy.require(all(old.get(key) == objective[key] for key in
            ('version','phase','estimator','ecology','frozenParameters')) and
            old.get('ecology') is not None and identity['actorVersion'] == CONTRACTS[phase.value].actor and
            {k:v for k,v in identity['config'].items() if k != 'seed'} ==
                {k:v for k,v in asdict(config).items() if k != 'seed'},
            'Triple Adam cannot cross phase, ecology, terminal estimator or PPO recipe.')
        finished = policy.read_json(Path(checkpoint_path).parent/'finished.json')
        policy.require(set(old.get('behaviorModels', {})) == set(PHASES) and
            old['behaviorModels'][phase.value] == identity['modelSha256'] and
            finished.get('tripleMcObjective') == old and
            finished['inputModelSha256'] == identity['modelSha256'] and
            finished['history'] == previous['history'] and finished['acceptedEpochs'] == previous['epoch'],
            'Triple carry requires the exact completed original three-head phase update.')
        inherited = identity.get('tripleMcOptimizerContinuation')
        steps = 0 if inherited is None else policy.integer(inherited.get('actorAdamSteps'),
            'Inherited triple Adam steps', 0, 2**53)
        steps = policy.validated_actor_adam_steps(previous, model, config, CONTRACTS[phase.value], steps)
        return dict(version=self.version, phase=phase.value, actorAdamSteps=steps,
            checkpointSha256=policy.file_digest(checkpoint_path), previousIdentitySha256=policy.digest(identity))
