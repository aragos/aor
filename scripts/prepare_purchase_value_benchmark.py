"""Freeze a purchase-value benchmark without opening outcomes or fitting models."""
from pathlib import Path
import argparse
from dataclasses import asdict
import shutil

import purchase_value_data as data

SCRIPTS = ('purchase_value_data.py', 'purchase_value_benchmark.py', 'purchase_value_campaign.py',
           'test_purchase_value_benchmark.py', 'prepare_purchase_value_benchmark.py')
INVENTORY = '73c88838c437ac010cfe2102241c38c88d097a7e9f08386ed53f40e6519aa44e'
CONFIG = asdict(data.Config())


def prepare(inventory_directory, c64, output):
    inventory_directory, c64, output = map(Path, (inventory_directory, c64, output))
    data.require(data.sha(inventory_directory/'report.json') == INVENTORY,
                 'The independently verified retained-data inventory changed.')
    inventory = data.read(inventory_directory/'report.json')
    data.require(data.sha(inventory_directory/'games.json') == inventory['gamesSha256'],
                 'The exact deduplicated whole-game inventory changed.')
    data.require(data.sha(c64) == data.C64 and not output.exists(), 'Use exact C64 and a new preparation root.')
    corpus = data.declaration(inventory, data.read(inventory_directory/'games.json'))
    output.mkdir(parents=True)
    (output/'scripts').mkdir(); (output/'inputs').mkdir(); (output/'evidence').mkdir()
    shutil.copyfile(c64, output/'inputs/c64.json')
    for name in SCRIPTS:
        shutil.copyfile(Path(__file__).parent/name, output/'scripts'/name)
    for name in ('report.json', 'games.json', 'inventory.py'):
        shutil.copyfile(inventory_directory/name, output/'evidence'/('inventory-'+name))
    for batch in corpus['batches']:
        key = Path('corpus')/batch['dataset']/batch['batch']
        directory = output/key
        directory.mkdir(parents=True)
        archive, metadata = Path(batch['archive']), Path(batch['metadataPath'])
        digest = inventory['filePins'][str(archive)]
        data.require(data.sha(archive) == digest and data.sha(metadata) == batch['metadataSha256'],
                     'A retained source changed before packaging.')
        shutil.copyfile(archive, directory/'games.tar.gz')
        shutil.copyfile(metadata, directory/'collection.json')
        shutil.copyfile(metadata.parent/'finished.json', directory/'finished.json')
        batch.update(packageArchive=str(key/'games.tar.gz'), packageMetadata=str(key/'collection.json'),
                     archiveSha256=digest)
    data.document(output/'corpus.json', corpus)
    protocol = dict(schemaVersion=1, kind='purchase-state-value-benchmark-1', config=CONFIG,
        models={'small': dict(widths=[64], parameters=227713),
                'large': dict(widths=[256, 256], parameters=976641)},
        comparators=['frozen-c64-state-critic', 'equal-training-game-mean-constant'],
        inputFeatures='Only exact sparse purchase encoder-v1 stateFeatures, 3556 columns.',
        normalization='Existing encoder units; no fitted centering/scaling; Float64 CSR, FP32 fresh models.',
        objective='Equal whole-game MSE; equal decision weights within each game.',
        selection='Best validation epoch separately per architecture, strict improvement, earlier exact ties.',
        finalTest='Entire 2048-game32m stronger ecology; opened after both selections are frozen.',
        primaryComparison='Test equal-game MSE, large minus small; negative favors larger model.',
        intervals='2000 paired whole-game percentile bootstrap replicates, fixed seed, marginal95%.',
        calibration='Ten fixed-width prediction bins; report counts and equal-game weights.',
        grouping=['purchase-stage', 'epoch', 'ecology'],
        c64Exposure='Secondary comparator: C64 was trained on its retained ancestry.',
        splitCounts=corpus['splitCounts'], corpusSha256=data.sha(output/'corpus.json'),
        maximumSeconds=3600, maximumAttempts=1, cpuThreads=2, maximumDataBytes=6*data.GIB,
        maximumEnvironmentBytes=12*data.GIB, minimumFreeBytes=8*data.GIB,
        automaticNext=False, productionActivation=False, qualificationPassed=False,
        actorTraining=False, actionQ=False, humanStrengthVerified=False,
        fitDataInspectedDuringPreparation=False, testUsedForSelection=False,
        randomNamespace='purchase-value-learning-and-bootstrap-1; not simulation world seeds')
    data.document(output/'protocol.json', protocol)
    data.document(output/'transport-manifest.json', dict(kind='purchase-value-preparation-1',
        files={str(p.relative_to(output)): data.sha(p) for p in output.rglob('*') if p.is_file()},
        noActualFits=True, noNewGames=True))
    return protocol


def freeze(root):
    root = Path(root)
    data.require((root/'environment.json').exists() and not (root/'manifest.json').exists(),
                 'Host freeze requires one pinned numerical environment and a new manifest.')
    return data.document(root/'manifest.json', dict(kind='purchase-value-frozen-package-1',
        files={str(p.relative_to(root)): data.sha(p) for p in root.rglob('*') if p.is_file()
               and p.name not in ('manifest.json', 'synthetic-preflight.json')
               and '__pycache__' not in p.parts}, noActualFits=True, noNewGames=True))


def finalize(root):
    """Bind the successful zero-fit proof; preserve the exact preflight manifest."""
    root = Path(root)
    proof = data.read(root/'synthetic-preflight.json')
    data.require(proof['passed'] and proof['actualModelFits'] == proof['retainedLabelsOpened'] == 0
                 and proof['bindings'] == dict(manifestSha256=data.sha(root/'manifest.json'),
                    protocolSha256=data.sha(root/'protocol.json'),
                    environmentSha256=data.sha(root/'environment.json')),
                 'Final freeze requires the exact successful synthetic preflight.')
    previous = data.read(root/'manifest.json')
    data.require(all(data.sha(root/name) == digest for name, digest in previous['files'].items()),
                 'A scientific input changed after preflight.')
    data.require(not (root/'preflight-manifest.json').exists(), 'Preserve any prior finalization.')
    (root/'manifest.json').rename(root/'preflight-manifest.json')
    data.document(root/'manifest.json', dict(kind='purchase-value-frozen-package-1',
        files={str(p.relative_to(root)): data.sha(p) for p in root.rglob('*') if p.is_file()
               and p.name != 'manifest.json' and '__pycache__' not in p.parts},
        noActualFits=True, noNewGames=True))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('prepare', 'freeze', 'finalize'))
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--inventory', type=Path)
    parser.add_argument('--c64', type=Path)
    options = parser.parse_args()
    if options.mode == 'prepare':
        prepare(options.inventory, options.c64, options.root)
    elif options.mode == 'freeze':
        freeze(options.root)
    else:
        finalize(options.root)
