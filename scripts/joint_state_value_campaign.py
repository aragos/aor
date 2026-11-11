"""One bounded shared-state GPU stage under the existing external campaign supervisor.

The immutable corpus is produced separately on the Mac. Native absolute paths are provenance
only: this stage consumes relative archive members and never executes a native game or actor.
Synthetic preflight cannot open the corpus. The final stage requires its completed audit.
"""
import argparse
from dataclasses import asdict
from pathlib import Path
import os
import shutil

import automata_experiments as exp
import joint_state_value_data as data
import purchase_value_campaign as gpu

KIND = 'joint-state-value-gpu-1'
REFRESHED = 'c2ea78e7a225998e7c690bdce852f3502d8198a464f37fd1674bbc01142b7f17'
SEED_AUDIT = '6e36f2486e72b9f3d15a6a452a984aa01eb8d7b42db317ea52eb61504f7925a2'
VARIABLES = dict(gpu.VARIABLES, OMP_NUM_THREADS='1', MKL_NUM_THREADS='1',
                 OPENBLAS_NUM_THREADS='1', NUMEXPR_NUM_THREADS='1')
GIB = 1024**3


def validate_inputs(protocol, *, corpus_required):
    data.require(protocol['kind'] == KIND and protocol['maximumSeconds'] == 3600
        and protocol['maximumAttempts'] == 1 and protocol['cpuThreads'] == 1
        and protocol['maximumRssBytes'] == 6*GIB and protocol['maximumDataBytes'] == 8*GIB
        and protocol['minimumFreeBytes'] == 8*GIB and protocol['minimumGpuFreeBytes'] == 4*GIB
        and protocol['maximumFits'] == 2 and protocol['newGames'] == 0
        and all(protocol[key] is False for key in ('actorTraining', 'qualificationPassed',
            'productionActivation', 'automaticNextCampaign', 'automaticResume')),
        'Use one finite two-critic prediction study, without games, actor training or activation.')
    seeds = data.read(protocol['seedAudit'])
    data.require(data.sha(protocol['seedAudit']) == SEED_AUDIT
        and seeds['allIntersectionsZero'] and protocol['banks'] == seeds['seedBanks']
        and protocol['fitConfig'] == asdict(data.config(protocol['banks'])),
        'The fixed52 ancestry, whole-world splits and numerical streams changed.')
    purchase_names = data.read(protocol['purchaseDictionary'])
    dictionary = data.read(protocol['jointDictionary'])
    names = data.dictionary_names(dictionary)
    data.require(len(purchase_names) == 3556 and len(names) == 7241
        and {name for name in names if name.startswith('purchase.')}
            == {'purchase.'+name for name in purchase_names}
        and data.sha(protocol['critic']) == REFRESHED,
        'The expanded critic must retain exact refreshed49 purchase weights and input columns.')
    proof = data.read(protocol['warmParity'])
    data.require(proof['passed'] and proof['newGames'] == proof['actualFits'] == 0
        and proof['criticSha256'] == data.sha(protocol['critic'])
        and proof['jointDictionarySha256'] == data.sha(protocol['jointDictionary'])
        and proof['purchaseDictionarySha256'] == data.sha(protocol['purchaseDictionary'])
        and proof['actualTrainingStates']['rows'] == 144
        and proof['actualTrainingStates']['maximumAbsoluteError'] <= 2e-6
        and proof['validationOrTestStatesUsed'] is False and proof['warmSeed'] == 52001001,
        'The frozen training-only prefit purchase equivalence proof is required.')
    environment = data.read(protocol['gpuEnvironment'])
    data.require(environment['cpuThreads'] == 1 and environment['variables'] == VARIABLES,
        'The separate GPU stage must retain one CPU thread and deterministic numerical settings.')
    if corpus_required:
        package = Path(protocol['corpusRoot'])
        data.require(data.sha(package/'corpus.json') == protocol['corpusSha256'],
            'The completed portable corpus binding changed.')
        corpus = data.read(package/'corpus.json'); data.verify_corpus(corpus)
        bank_names = [f'training-{index}' for index in range(1,13)] + [
            'validation-1', 'validation-2', 'test-1', 'test-2']
        expected_seeds = [seed for name in bank_names for seed in protocol['banks'][name]['seeds']]
        data.require([game['gameSeed'] for game in corpus['games']] == expected_seeds
            and corpus['policyBindings'] == protocol['policyBindings'],
            'The corpus must use every frozen52 world in order and its exact declared actors.')
        audit = data.read(protocol['collectionAudit'])
        data.require(audit['passed'] and audit['allOwnedProcessesExited']
            and audit['scientificTerminalGames'] == 4096 and audit['historicalReproductions'] == 16
            and audit['corpusSha256'] == protocol['corpusSha256']
            and corpus['dictionarySha256'] == data.sha(protocol['jointDictionary']),
            'Fit only the independently completed4096-world corpus after all16 reproductions.')
        data.require(protocol['coverageApproved'] is True,
            'The completed fixed-prior coverage gate must be reviewed before fitting.')
        return corpus
    return None


def estimated_cache_bytes(corpus):
    """Exact NPY payload plus a conservative header/receipt allowance, before allocation."""
    return 10*corpus['nonzeros'] + 13*corpus['rows'] + 24*len(corpus['games']) + 8 + 1024**2


def resource_preflight(protocol, corpus=None):
    import torch
    data.require(shutil.disk_usage(protocol['outputParent']).free >= protocol['minimumFreeBytes'],
        'The numerical stage requires at least8GiB free before allocating its cache.')
    free, total = torch.cuda.mem_get_info()
    data.require(free >= protocol['minimumGpuFreeBytes'], 'At least4GiB free GPU memory is required.')
    estimate = None if corpus is None else estimated_cache_bytes(corpus)
    data.require(estimate is None or estimate + GIB <= protocol['maximumDataBytes'],
        'The complete declared CSR plus1GiB fit/output allowance exceeds the frozen data cap.')
    return dict(gpuFreeBytes=free, gpuTotalBytes=total, estimatedCacheBytes=estimate,
                cpuThreads=1, maximumDenseRows=4096)


def synthetic_preflight(protocol, output):
    """Generated-state forward/gradient checks only; no actual corpus or scientific fit."""
    import torch
    import joint_state_value_fit as fit
    import purchase_value_benchmark as benchmark
    validate_inputs(protocol, corpus_required=False)
    gpu.verify_environment(data.read(protocol['gpuEnvironment']), variables=VARIABLES)
    benchmark.configure(threads=1)
    resources = resource_preflight(protocol)
    purchase_names = data.read(protocol['purchaseDictionary'])
    joint_names = data.read(protocol['jointDictionary'])['stateFeatureNames']
    original = torch.load(protocol['critic'], map_location='cpu', weights_only=True)
    expanded = fit.expand_warm_state(original, purchase_names, joint_names,
        data.config(protocol['banks']).smallSeed)
    # Deterministic generated rows consume no reserved scientific random stream.
    states = ((torch.arange(19*7241).reshape(19,7241) % 17)-8).float()/8
    cpu = benchmark.Critic((64,), 0, inputs=7241); cpu.load_state_dict(expanded)
    cuda = benchmark.Critic((64,), 0, inputs=7241).cuda(); cuda.load_state_dict(expanded)
    left, right = cpu(states), cuda(states.cuda())
    torch.testing.assert_close(left, right.cpu(), rtol=2e-5, atol=2e-6)
    target = torch.linspace(0, 1, len(states))
    (left-target).square().mean().backward(); (right-target.cuda()).square().mean().backward()
    differences = []
    for before, after in zip(cpu.parameters(), cuda.parameters(), strict=True):
        torch.testing.assert_close(before.grad, after.grad.cpu(), rtol=2e-4, atol=2e-6)
        differences.append(float((before.grad-after.grad.cpu()).abs().max()))
    data.document(output, dict(kind='joint-state-gpu-synthetic-preflight-1', passed=True,
        newGames=0, actualFits=0, corpusOpened=False, resources=resources,
        maximumOutputDifference=float((left-right.cpu()).abs().max().detach()),
        maximumGradientDifference=max(differences), criticSha256=data.sha(protocol['critic']),
        dictionarySha256=data.sha(protocol['jointDictionary'])))


def completed_artifacts(output):
    """Mutable supervisor files and stdout logs are owned outside these completed outputs."""
    return [path for owner in ('cache', 'fits') for path in (output/owner).rglob('*') if path.is_file()]


def study(protocol, output):
    import torch
    import joint_state_value_fit as fit
    import purchase_value_benchmark as benchmark
    corpus = validate_inputs(protocol, corpus_required=True)
    gpu.verify_environment(data.read(protocol['gpuEnvironment']), variables=VARIABLES)
    benchmark.configure(threads=1); resource_preflight(protocol, corpus)
    data.build_cache(protocol['corpusRoot'], output/'cache', exp.remaining_seconds)
    cache = data.Cache(output/'cache')
    report = fit.fit(cache, corpus, output/'fits', data.config(protocol['banks']),
        exp.remaining_seconds, torch.device('cuda:0'),
        purchase_names=data.read(protocol['purchaseDictionary']),
        joint_names=data.read(protocol['jointDictionary'])['stateFeatureNames'],
        critic_path=protocol['critic'])
    return exp.stage_result(output, 'study', True,
        'Completed predictive state-value study; no action ranking or play-strength qualification.',
        report, completed_artifacts(output))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('synthetic-preflight', 'preflight', 'stage'))
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(); protocol = data.read(args.protocol)
    if args.mode == 'synthetic-preflight':
        synthetic_preflight(protocol, args.output)
    elif args.mode == 'preflight':
        import automata_campaign as controller
        import purchase_value_benchmark as benchmark
        corpus = validate_inputs(protocol, corpus_required=True)
        gpu.verify_environment(data.read(protocol['gpuEnvironment']), variables=VARIABLES)
        benchmark.configure(threads=1)
        data.require(not args.output.exists(), 'Preflight must precede the single fit attempt.')
        samples = controller.processes.sample_group(os.getpgrp())
        data.require(any(sample.pid == os.getpid() and sample.rss_bytes > 0 for sample in samples),
            'The actual numerical child requires external process sampling.')
        print(__import__('json').dumps(dict(passed=True, newGames=0, actualFits=0,
            externalPsSamplerPassed=True, resources=resource_preflight(protocol, corpus))))
    else:
        study(protocol, args.output)


if __name__ == '__main__': main()
