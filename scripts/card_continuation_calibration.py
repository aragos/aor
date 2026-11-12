"""Outcome-free, fixed-KL output-head perturbations; no optimizer or policy selection."""
import copy
from dataclasses import dataclass
import hashlib
import math

import torch
import card_policy
import purchase_policy as policy

TARGET = .02
TOLERANCE = 1e-6
STYLES = ('D1_PLUS', 'D1_MINUS', 'D2_PLUS', 'D2_MINUS')
PHASES = ('card', 'purchase')


def direction(seed, phase):
    policy.integer(seed, 'Direction seed', 0, 2**31-1)
    policy.require(phase in PHASES, 'A direction belongs to the card or purchase head.')
    return tuple((2*(hashlib.sha256(
        f'aor-cp-direction-1|{seed}|{phase}|{i}'.encode('ascii')).digest()[-1] & 1)-1)/8
        for i in range(64))


@dataclass(frozen=True)
class PublicRow:
    world: int
    phase: str
    number: int
    gameSha256: str
    observation: dict

    @classmethod
    def read(cls, value, model):
        policy.require(set(value) == {'world', 'phase', 'number', 'gameSha256', 'observation'},
                       'Calibration rows admit only original own/public inputs and provenance.')
        policy.integer(value['world'], 'Original TRAIN world', 0, 63)
        policy.integer(value['number'], 'Absolute PLAYER decision', 0, 12000)
        phase = value['phase']
        policy.require(phase in PHASES, 'Calibration phase must be explicit.')
        digest = value['gameSha256']
        policy.require(isinstance(digest, str) and len(digest) == 64
                       and all(c in '0123456789abcdef' for c in digest), 'Exact original game hash required.')
        contract = card_policy.CONTRACT if phase == 'card' else policy.PURCHASE_CONTRACT
        policy.validate_observation(value['observation'], model, contract=contract)
        policy.require(len(value['observation']['actions']) > 1, 'Forced rows have zero KL and are excluded.')
        return cls(value['world'], phase, value['number'], digest, copy.deepcopy(value['observation']))


class FrozenBasis:
    """Shared legacy network owners compute each public basis once, with no gradient or update."""
    def __init__(self, model, phase, rows):
        policy.require(phase in PHASES and rows and all(r.phase == phase for r in rows),
                       'One nonempty phase is required for a frozen calibration basis.')
        self.worlds = tuple(r.world for r in rows)
        self.model = copy.deepcopy(model)
        self.network = card_policy.CardPolicy(model) if phase == 'card' else policy.PurchasePolicy(model)
        self.network.requires_grad_(False)
        self.original = self.network.actorWeights.detach().clone()
        self.batches = []
        counts = {world: sum(r.world == world for r in rows) for world in {r.world for r in rows}}
        self.weights = torch.tensor([1/(len(counts)*counts[r.world]) for r in rows], dtype=policy.DTYPE)
        # Chunk by both existing numerical limits, preserving original row order.
        chunk = []
        for row in rows:
            if chunk and (len(chunk) == 256 or sum(len(r.observation['actions']) for r in chunk)
                          + len(row.observation['actions']) > 16384):
                self._append(chunk); chunk = []
            chunk.append(row)
        self._append(chunk)
        with torch.no_grad():
            self.reference = self.log_probabilities(self.original)

    def _append(self, rows):
        with torch.no_grad():
            prepared, _, basis = self.network.actor_basis([r.observation for r in rows])
        self.batches.append((prepared, basis.detach()))

    def log_probabilities(self, weights):
        with torch.no_grad():
            self.network.actorWeights.copy_(weights)
            return [p for prepared, basis in self.batches
                    for p in self.network.actor_log_probabilities(prepared, basis)]

    def divergence(self, weights):
        logs = self.log_probabilities(weights)
        kl = torch.stack([(old.exp()*(old-new)).sum() for old,new in zip(self.reference, logs)])
        policy.require(bool(torch.isfinite(kl).all()), 'Calibration KL must remain finite.')
        return float((self.weights*kl).sum()), kl, logs


def calibrated_head(basis, vector):
    """One fixed geometric sign bracket, then40 bisections; no monotonicity assumption."""
    policy.require(len(vector) == 64 and all(math.isfinite(x) for x in vector),
                   'A direction requires64 finite coordinates.')
    delta = torch.tensor(vector, dtype=policy.DTYPE)
    low = 0.0
    history = [dict(amplitude=0.0, kl=0.0)]
    high = None
    for exponent in range(-6, 7):
        amplitude = 2.0**exponent
        value, _, _ = basis.divergence(basis.original + amplitude*delta)
        history.append(dict(amplitude=amplitude, kl=value))
        if value >= TARGET:
            high = amplitude
            break
        low = amplitude
    policy.require(high is not None, 'KL target was not bracketed before the fixed amplitude64 cap; stop.')
    for _ in range(40):
        middle = (low+high)/2
        value, _, _ = basis.divergence(basis.original + middle*delta)
        history.append(dict(amplitude=middle, kl=value))
        if value < TARGET: low = middle
        else: high = middle
    scale = (low+high)/2
    weights = basis.original + scale*delta
    value, kl, logs = basis.divergence(weights)
    policy.require(abs(value-TARGET) <= TOLERANCE, 'Fixed calibration failed its absolute KL tolerance.')
    tv = torch.stack([.5*torch.abs(old.exp()-new.exp()).sum()
                      for old,new in zip(basis.reference, logs)])
    residuals = torch.cat([features @ weights for _,features in basis.batches])
    output = copy.deepcopy(basis.model)
    output['actorWeights'] = weights.tolist()
    world_kl = {str(world): float(kl[[i for i,w in enumerate(basis.worlds) if w == world]].mean())
                for world in sorted(set(basis.worlds))}
    return output, dict(worldMeanKl=world_kl, scale=scale, targetKl=TARGET, actualKl=value, tolerance=TOLERANCE,
        klMaximum=float(kl.max()), klP95=float(torch.quantile(kl,.95)),
        meanTv=float((tv*basis.weights).sum()), maximumTv=float(tv.max()),
        clippedActionFraction=float((residuals.abs() >= policy.RESIDUAL_LIMIT).double().mean()),
        bracketAndBisection=history, actualFits=0)


def calibrate(models, rows, seeds):
    """Fixed admission and four styles. Callers bind raw G3/cohort/seed identities before use."""
    policy.require(set(models) == set(PHASES) and len(seeds) == 2 and seeds[1] == seeds[0]+1,
                   'Two consecutive audited direction integers and both unchanged heads are required.')
    policy.require(all(isinstance(row, dict) and row.get('phase') in PHASES for row in rows),
                   'Every public calibration row requires a card or purchase phase.')
    values = [PublicRow.read(row, models[row['phase']]) for row in rows]
    policy.require(len({(r.world,r.phase,r.number) for r in values}) == len(values),
                   'Calibration rows cannot be duplicated or relabeled.')
    output = {style: {} for style in STYLES}
    reports = {style: {} for style in STYLES}
    for phase in PHASES:
        phase_rows = [r for r in values if r.phase == phase]
        worlds = {r.world for r in phase_rows}
        policy.require(len(worlds) >= 32 and len(phase_rows) >= 256
                       and all(sum(r.world == world for r in phase_rows) <= 32 for world in worlds),
                       'The fixed TRAIN cohort requires32 worlds/256 rows per head and at most32 rows/world.')
        basis = FrozenBasis(models[phase], phase, phase_rows)
        directions = [direction(seed, phase) for seed in seeds]
        policy.require(directions[0] not in (directions[1], tuple(-x for x in directions[1])),
                       'Duplicate or opposite directions invalidate the preparation; no redraw.')
        for index, style in enumerate(STYLES):
            vector = tuple((1 if index % 2 == 0 else -1)*x for x in directions[index//2])
            output[style][phase], reports[style][phase] = calibrated_head(basis, vector)
            reports[style][phase].update(worlds=len(worlds), rows=len(phase_rows), direction=list(vector))
    return output, reports
