"""Explicit typed phase objectives for detached joint credit, without a two-actor loop.

Both phases retain original behavior values/likelihoods and terminal returns. This owner admits
only the separately validated joint target reader; legacy purchase objectives are not aliases.
"""
from dataclasses import dataclass
from enum import Enum
from pathlib import Path

import joint_credit_targets as credit
import joint_trajectory as joint
import purchase_policy as policy


class Phase(Enum):
    CARD = 'card'
    PURCHASE = 'purchase'


@dataclass(frozen=True)
class JointPhaseActorOnlyObjective:
    """One original phase of a fully bound two-head collection and detached target clock."""
    targets: credit.DetachedJointTargets
    phase: Phase

    def __post_init__(self):
        policy.require(isinstance(self.targets, credit.DetachedJointTargets)
            and isinstance(self.phase, Phase), 'Joint actor credit requires typed detached targets and phase.')

    def validate(self, contract, config):
        policy.require(contract == joint.CONTRACTS[self.phase.value],
            'A joint objective can update only its declared CARD or PURCHASE actor.')
        policy.require(config.valueCoefficient == config.entropyCoefficient == 0
            and config.cardReferenceKlCoefficient == 0,
            'Joint actor-only credit requires zero critic, entropy and reference coefficients.')

    def bind(self, rows, provenance, model_sha):
        joint.unchanged(self.targets.pins)
        phase = self.phase.value
        expected_identity = dict(self.targets.identity, phase=phase)
        policy.require(provenance.get('jointDetachedTargets') == expected_identity
            and model_sha == self.targets.identity['behaviorModels'][phase]
            and provenance['collectionSha256'] == self.targets.collection.common['collectionSha256']
            and provenance['gameHashes'] == self.targets.collection.common['gameHashes'],
            'Joint credit must retain this phase, both behavior heads and the complete original collection.')
        expected_rows = self.targets.collection.rows[phase]
        policy.require(len(rows) == len(expected_rows) and all(
            row['originIndex'] == original['originIndex'] and row['number'] == original['number']
            and row.get('_prepared') is original.get('_prepared')
            and all(row[key] == original[key] for key in
                    ('observation', 'selectedIndex', 'behaviorLogProbability', 'behaviorValue', 'return'))
            for row, original in zip(rows, expected_rows)),
            'Joint target binding cannot change original actor observations, actions, behavior or labels.')
        for row in rows:
            expected = self.targets.targets[(provenance['gameHashes'][row['originIndex']],row['number'],phase)]
            policy.require('_actorOnlyBaseline' not in row and '_actorOnlyAdvantage' in row
                and abs(policy.number(row['_actorOnlyAdvantage'],'Detached joint advantage')-expected)<=1e-12,
                'A joint advantage must equal its independently rederived detached target.')
        descriptor = self.targets.identity['frozenStatePredictions']['predictorDescriptor']
        subject = policy.read_json(descriptor['path'])
        policy.require(policy.file_digest(descriptor['path']) == descriptor['sha256'],
            'The real shared predictor descriptor changed while binding joint credit.')
        identity = dict(expected_identity, version='joint-phase-actor-only-1',
            sharedPredictor=dict(descriptor,subject=subject),
            frozenParameters=['valueWeights','valueBias'],optimizerInitialization='fresh-adam')
        joint.unchanged(self.targets.pins)
        return rows, identity


@dataclass(frozen=True)
class JointPhaseAdamContinuation:
    """Carry five actor parameter slots only within one phase, clock and actual shared critic."""
    version: str = 'joint-phase-actor-adam-continuation-1'

    def validate(self, checkpoint_path, objective, model, config):
        policy.require(self.version == 'joint-phase-actor-adam-continuation-1'
            and objective['version'] == 'joint-phase-actor-only-1',
            'Joint Adam continuation requires its explicit joint phase objective.')
        previous, identity = policy.actor_adam_checkpoint(checkpoint_path,model)
        old = identity.get('jointActorOnlyObjective', {})
        policy.require(all(old.get(name) == objective[name] for name in
            ('version','phase','estimator','frozenParameters','sharedPredictor'))
            and old.get('sharedPredictor') is not None
            and {key:value for key,value in identity['config'].items() if key!='seed'}
                == {key:value for key,value in policy.asdict(config).items() if key!='seed'},
            'Joint Adam cannot cross phase, clock, real predictor descriptor or PPO recipe.')
        phase = Phase(objective['phase'])
        finished = policy.read_json(Path(checkpoint_path).parent/'finished.json')
        policy.require(identity['actorVersion'] == joint.CONTRACTS[phase.value].actor
            and old['behaviorModels'][phase.value] == identity['modelSha256']
            and finished.get('jointActorOnlyObjective') == old
            and finished['inputModelSha256'] == identity['modelSha256']
            and finished['history'] == previous['history']
            and finished['acceptedEpochs'] == previous['epoch'],
            'Joint carry must retain its exact completed phase actor, objective and epoch history.')
        inherited = identity.get('jointActorOptimizerContinuation')
        inherited_steps = 0 if inherited is None else policy.integer(
            inherited.get('actorAdamSteps'), 'Inherited joint Adam steps', 0, 2**53)
        steps = policy.validated_actor_adam_steps(previous, model, config,
            joint.CONTRACTS[phase.value], inherited_steps)
        return dict(version=self.version,phase=objective['phase'],actorAdamSteps=steps,
            checkpointSha256=policy.file_digest(checkpoint_path),
            previousIdentitySha256=policy.digest(identity))
