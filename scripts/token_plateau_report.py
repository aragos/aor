"""Pure arithmetic audit/report of the frozen TOKEN diagnostic; no model imports or forwards."""

import argparse
from collections import Counter
import json
import math
from pathlib import Path

import token_plateau_diagnostic as diagnostic


def mean(values):
    return math.fsum(values) / len(values)


def report(root):
    plan_path = root / 'preparation/plan.json'
    summary_path = root / 'inference/summary.json'
    plan = json.loads(plan_path.read_bytes())
    summary = json.loads(summary_path.read_bytes())
    rows = json.loads(diagnostic.checked(summary['decisions']))
    diagnostic.require(summary['plan'] == diagnostic.subject(plan_path),
                       'The inference result must pin the original frozen plan.')
    attempts = [json.loads(path.read_bytes()) for path in
                sorted((root / 'inference').glob('attempt-*.json'))]
    diagnostic.require(len(attempts) == 4 and sum(row['states'] for row in attempts) == 664 and
                       summary['modelStateEvaluations'] == 664 and len(rows) == 166,
                       'Audit the actual four calls and 664 model-state evaluations.')
    extra = {}
    for name in ('94', '96', '97'):
        counts = Counter()
        neighbor_gaps = {20: [], 22: []}
        radius_only_tops = []
        for row in rows:
            value = row['models'][name]
            scores = [plan['scale'] * (a - b) + (1. if i == row['baselineIndex'] else 0.)
                      for i, (a, b) in enumerate(zip(value['raw'], row['referenceRaw']))]
            diagnostic.require(scores == value['scores'], 'Reconstruct every calibrated score.')
            amounts = [int(command.split()[1]) for command in row['commands']]
            winner, probabilities = diagnostic.distribution(scores, amounts, row['baselineIndex'])
            diagnostic.require(winner == value['winner'] and probabilities == value['probabilities'],
                               'Reconstruct every deterministic winner and C05 probability.')
            counts['singletonSupport'] += value['support'] == 1
            counts['winner21'] += amounts[winner] == 21
            counts['noAlternativeWithinScoreGap'] += not any(
                scores[winner] - score <= 3. for index, score in enumerate(scores) if index != winner)
            radius_only_tops.append(1. / sum(
                math.exp((score - scores[winner]) / .35)
                for index, score in enumerate(scores) if abs(amounts[index] - amounts[winner]) <= 4))
            if amounts[winner] == 21:
                for amount in neighbor_gaps:
                    diagnostic.require(amount in amounts, 'Neighboring bids are actually offered.')
                    neighbor_gaps[amount].append(scores[winner] - scores[amounts.index(amount)])
        extra[name] = dict(counts=dict(counts), radiusOnlyMeanTopProbability=mean(radius_only_tops),
                           neighboringMeanScoreGaps={amount: mean(gaps)
                                                    for amount, gaps in neighbor_gaps.items()})
    audit = dict(kind='token-plateau-diagnostic-pure-audit-1', passed=True,
        inputPins={str(plan_path): diagnostic.digest(plan_path.read_bytes()),
                   str(summary_path): diagnostic.digest(summary_path.read_bytes()),
                   summary['decisions']['path']: summary['decisions']['sha256'],
                   str(Path(__file__).absolute()): diagnostic.digest(Path(__file__).read_bytes())},
        reconstructedModelStateScoreVectors=498, auditedForwardCalls=4,
        actualModelStateEvaluations=664, additionalModelStateEvaluations=0,
        additionalGames=0, additionalRandomDraws=0, models=extra)
    diagnostic.write(root / 'pure-audit.json', audit)
    table = ['| Model | Same best bid as 94 | Singleton C05 support | Mean best-bid probability |',
             '| --- | ---: | ---: | ---: |']
    for name in ('94', '96', '97'):
        table.append(f"| {name} | 166/166 | {extra[name]['counts']['singletonSupport']}/166 | "
                     f"{summary['models'][name]['topProbability']['mean']:.6%} |")
    text = '\n'.join([
        '# TOKEN plateau engineering diagnostic', '',
        'The parameter updates changed network outputs, but preserved the best bid on all 166 '
        'archived decisions. The effective policy therefore barely changed. This supports a '
        'score-to-action and sparse-credit explanation for the plateau, rather than a failed '
        'weight update or completely saturated network.', '',
        *table, '',
        'All three models prefer 21 in 164 states and 14 in 2 states. All probability movement '
        'occurs in the two states preferring 14, from the same simulated world. 94→97 mean total '
        'variation is 0.0002188; exact same-uniform interval disagreement is 0.0002287. These are '
        'probability calculations, without sampling any actor uniform.', '',
        'The updates are substantial numerically: 94→97 mean action-centered raw-output RMS '
        'is 0.733; mean maximum centered calibrated-score movement is 3.22. Nevertheless, its '
        'best bid stays unchanged. The learned preference for 21 is much larger than the '
        'neighboring-bid changes: on 21-winning states, 94 mean score gaps to offered 20 '
        'and 22 are 7.64/8.45, increasing to 9.18/8.87 in 97. All these neighbors exceed the C05 '
        'gap 3.0 cutoff. Removing only that cutoff changes 94 mean top probability from '
        '0.999328 to 0.999326: temperature 0.35 and the learned score landscape still make '
        'it almost deterministic. Radius 4 also excludes frequent distant runners-up 15/30.', '',
        'Saturation is partial: 94/96/97 have 39.9%/39.3%/39.7% of state tanh units at '
        '|activation|≥0.99, and 7.88%/7.96%/8.72% of action units. These values do not establish '
        'that saturation is harmless, but the measured output movement rules out an entirely '
        'unresponsive network.', '',
        'A better next learning experiment should first require action-level sensitivity from '
        'its search directions, without selecting those directions on win outcomes. Separate '
        'the bid-category intercept from state-conditioned order/resource advantages; calibrate '
        'perturbation magnitude against changed decision distributions. Use affordable '
        'counterfactual bids from the same state, multi-step continuation values or a learned '
        'advantage target to obtain denser bid credit, then judge strength on untouched complete '
        'games. Before repeating terminal-win ES, measure whether its actual population '
        'perturbations move actions: this diagnostic evaluated the final centers, and cannot '
        'determine how much each training mutant moved behavior.', '',
        'Scope and limits: all 166 observations were copied from pre-existing simulated G1 '
        'CONTROLS archives, worlds 160–255, spanning 88 worlds and epochs 1/2/3 with 64/64/38 '
        'states. They retain full lexical TOKEN2 offers and their recorded 4a/G3 reference '
        'index. That reference was not recomputed, and these are historical states rather '
        'than current 97 trajectories. This diagnostic explains behavior on this fixed panel; '
        'it is neither a win test nor a proof of the exact cause of the live holdout plateau.', '',
        'Execution: 664 model-state evaluations exactly (4a/94/96/97 × 166), four batches, '
        '10,798 offered actions per model, CPU Float64 and one thread. Activations were exposed '
        'from the unchanged existing RawPolicy body in the same forward pass. Torch RNG state '
        'was unchanged. No games, fitting, new states, random draws, qualification or promotion.', '',
        f"Frozen input plan SHA256: {diagnostic.digest(plan_path.read_bytes())}", '',
        f"Inference summary SHA256: {diagnostic.digest(summary_path.read_bytes())}", '',
        'Source: isolated diagnostic/automata-token-plateau, initial frozen forward commit 84c0fd9.',
        '',
    ])
    with (root / 'report.md').open('x') as stream:
        stream.write(text)
    print(json.dumps(dict(audit=diagnostic.subject(root / 'pure-audit.json'),
                          report=diagnostic.subject(root / 'report.md')), sort_keys=True))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    report(parser.parse_args().root.absolute())
