"""Prospective whole-world ownership for one fixed-G2 state-value study.

The native collection stays unchanged. Corpus construction reads only completed, verified games;
state inputs preserve the original 3,556 encoder units and never include actions or hidden state.
"""
from pathlib import Path

import purchase_value_data as data
import purchase_strong_data as strong

KIND = 'purchase-strong-state-value-1'
SPLITS = {'training': 3072, 'validation': 512, 'test': 512}


def split(batch):
    data.require(type(batch) is int and 1 <= batch <= 16, 'Exactly sixteen fresh batches are declared.')
    return 'training' if batch <= 12 else 'validation' if batch <= 14 else 'test'


def bank_name(index):
    part = split(index)
    return f'{part}-{index if index <= 12 else index-12 if index <= 14 else index-14}'


def config(banks):
    """Only audited RNG identities differ from the original benchmark recipe."""
    seeds = (banks['initWarm']['seeds'] + banks['initFresh']['seeds']
             + banks['epochOrder']['seeds'] + banks['bootstrap']['seeds'])
    data.require(len(seeds) == 23 and seeds == list(range(seeds[0], seeds[0]+23)),
                 'Two initialization streams, twenty shared shuffles and one bootstrap are reserved.')
    return data.Config(smallSeed=seeds[0], largeSeed=seeds[1], orderSeed=seeds[2], bootstrapSeed=seeds[22])


def verify_declaration(corpus):
    games = corpus['games']
    data.require(corpus['kind'] == KIND and len(games) == 4096
                 and corpus['splitCounts'] == SPLITS
                 and len({g['gameSeed'] for g in games}) == len(games)
                 and len({g['sha256'] for g in games}) == len(games),
                 'The complete prospective whole-game split must have 4,096 unique worlds and records.')
    for index, game in enumerate(games):
        batch, offset = divmod(index, 256)
        data.require(game['id'] == index and game['index'] == offset
                     and game['batch'] == f'batch-{batch+1}' and game['split'] == split(batch+1)
                     and game['dataset'] == KIND and game['decisions'] > 0,
                     'Whole-world split/order and nonempty purchase coverage cannot change after collection.')
    data.require(len(corpus['batches']) == 16 and corpus['rows'] == sum(g['decisions'] for g in games),
                 'The corpus must retain all sixteen complete collections.')


def batch_declaration(directory, index, bank):
    """Snapshot counts/identity before lossless archiving; no outcome-based filtering."""
    directory = Path(directory)
    metadata = data.read(directory/'collection/collection.json')
    games, nonzeros = [], 0
    for number in range(256):
        path = directory/f'collection/game-{number}.json'; game = data.read(path)
        data.require(game['index'] == number and game['gameSeed'] == bank['seeds'][number]
                     and game['signature'] == metadata['signature'], 'A collected world changed its reserved identity.')
        rows = game['decisions']
        data.require(rows, 'A zero-decision game cannot silently receive an invented training target.')
        nonzeros += sum(len(row['observation']['stateFeatures']) for row in rows)
        games.append(dict(dataset=KIND,batch=f'batch-{index}',index=number,
            id=(index-1)*256+number,split=split(index),sha256=data.sha(path),
            gameSeed=game['gameSeed'],seat=game['controlledSeat'],decisions=len(rows)))
    return games, nonzeros


def corpus(batches):
    value = dict(kind=KIND,games=[g for b in batches for g in b['games']],
        batches=[b['archive'] for b in batches],splitCounts=SPLITS,
        rows=sum(g['decisions'] for b in batches for g in b['games']),
        nonzeros=sum(b['nonzeros'] for b in batches),noRowSplit=True,
        fixedPurchaseActorSha256=strong.G2,selection='Frozen batch membership, never observed reward.')
    verify_declaration(value)
    return value
