"""Separately authorized, fixed73 public calibration; never reads trajectories or outcomes."""
import argparse
import copy
import json
from pathlib import Path

import torch
import card_continuation_calibration as calibration
import joint_trajectory as joint
import purchase_policy as policy

G3 = '65481635981a22e9a80672a684419ebf8d3342a29830712ccf51bf48c8da936d'
CARD = '916de6e6ec41b3cc708f51155044956b1d3ebdb68e3a171f660a57123a8b00bf'
PURCHASE = '6613908b272cee6fb5789dc0ac8d8e6a14853d743ff6b9e08ba322a7d7a23f79'
F0 = 'c4dd1609c737151caadfe0cb85efe406cee84f02b801877af35d65128488bcb0'
AUDIT = 'da5956f7b52190f754b6baad8fc3468ab748dc96aa41025312737547af30035a'
require, read, sha = policy.require, policy.read_json, policy.file_digest


def public_rows(receipt, path, models):
    """Validate only the separately projected public file; no full-game loader exists here."""
    require(receipt['kind'] == 'card-calibration-public-1' and receipt['passed'] is True
            and receipt['cohort'] == '53/chronological/batch-32/worlds-0-63'
            and receipt['maximumRowsPerHeadWorld'] == 32
            and all(receipt[key] is False for key in (
                'terminalLabelsDecoded', 'behaviorChoicesDecoded', 'behaviorValuesDecoded'))
            and all(receipt[key] == 0 for key in ('newGames', 'policyEvaluations', 'actualFits'))
            and receipt['rowsSha256'] == sha(path),
            'Exact outcome-free public cohort receipt and rows are required.')
    require(len(receipt['originalGameSha256']) == 64
            and len(set(receipt['originalGameSha256'])) == 64,
            'The preselected64 original game identities must remain distinct.')
    for phase, model in models.items():
        expected = {key: model[key] for key in
                    ('encoderVersion', 'stateFeatureNames', 'actionFeatureNames')}
        expected['actorVersion'] = joint.CONTRACTS[phase].actor
        require(receipt['phaseDictionaries'][phase] == expected,
                'Original own/public dictionaries and encoder versions must match exact G3.')
    rows = []
    previous = {}
    counts = {phase: 0 for phase in calibration.PHASES}
    with Path(path).open() as stream:
        for line in stream:
            row = json.loads(line)
            require(isinstance(row, dict) and row.get('phase') in calibration.PHASES,
                    'A public row requires an explicit known head.')
            parsed = calibration.PublicRow.read(row, models[row['phase']])
            identity = parsed.world, parsed.phase
            require(parsed.number > previous.get(identity, -1)
                    and parsed.gameSha256 == receipt['originalGameSha256'][parsed.world],
                    'Original absolute ordering and world hash must match the projected cohort.')
            previous[identity] = parsed.number
            counts[parsed.phase] += 1
            rows.append(row)
    require(counts == receipt['rows'], 'Public row counts changed after projection.')
    return rows


def run(source, output):
    """One fixed deterministic calibration attempt; orchestration owns its absolute deadline."""
    source = Path(source).resolve(strict=True)
    pins = {str(source): sha(source)}
    protocol = read(source)
    require(set(protocol) == {'kind', 'bundle', 'publicReceipt', 'rows', 'seedAudit'}
            and protocol['kind'] == 'card-continuation-calibration-source-1',
            'Use the fixed explicit73 calibration source without recipe overrides.')

    def subject(name):
        value = protocol[name]
        require(set(value) == {'path', 'sha256'}, 'A calibration input requires its exact byte subject.')
        path = joint.pin(joint.resolved(value['path'], source.parent), pins)
        require(sha(path) == value['sha256'], 'A frozen calibration input changed.')
        return path

    bundle_path, public_path, rows_path, audit_path = (subject(name) for name in
        ('bundle', 'publicReceipt', 'rows', 'seedAudit'))
    require(sha(bundle_path) == G3 and sha(audit_path) == AUDIT,
            'Only the exact admitted G3 and prospectively frozen73 reservation are supported.')
    descriptor, models = joint.load_bundle(bundle_path, joint.CONFIGURATION, F0, pins)
    require(descriptor['card']['sha256'] == CARD and descriptor['purchase']['sha256'] == PURCHASE,
            'Calibration starts from both exact qualified G3 components.')
    rows = public_rows(read(public_path), rows_path, models)
    seeds = read(audit_path)['seedBanks']['directions']['seeds']
    require(seeds == [73100000, 73100001], 'Use the two original direction integers without redraw.')
    output = Path(output).absolute()
    output.mkdir(parents=True, exist_ok=False)
    torch.set_num_threads(1)
    torch.set_num_interop_threads(1)
    torch.use_deterministic_algorithms(True)
    changed, reports = calibration.calibrate(models, rows, seeds)
    styles = {}
    original = read(bundle_path)
    for style in calibration.STYLES:
        directory = output / style
        directory.mkdir()
        bundle = copy.deepcopy(original)
        proof = {}
        for phase in calibration.PHASES:
            model_path = directory / (phase + '.json')
            model_path.write_text(json.dumps(changed[style][phase], sort_keys=True, indent=2) + '\n')
            bundle[phase] = dict(path=model_path.name, sha256=sha(model_path))
            proof[phase] = dict(reports[style][phase], modelSha256=sha(model_path))
        target = directory / 'bundle.json'
        target.write_text(json.dumps(bundle, sort_keys=True, indent=2) + '\n')
        styles[style] = dict(proof, bundleSha256=sha(target))
    joint.unchanged(pins)
    result = dict(kind='card-continuation-calibration-1', passed=True,
        sourceSha256=sha(source), publicReceiptSha256=sha(public_path),
        publicRowsSha256=sha(rows_path), originalBundleSha256=G3, seedAuditSha256=AUDIT,
        directionSeeds=seeds, styles=styles, inputs=pins,
        numerical=dict(dtype='float64', cpuThreads=1, device='cpu'), actualFits=0,
        newGames=0, terminalLabelsDecoded=False, policySelection=False,
        qualificationPassed=False, runtimeActivation=False)
    (output / 'calibration.json').write_text(json.dumps(result, sort_keys=True, indent=2) + '\n')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    run(args.source, args.output)
