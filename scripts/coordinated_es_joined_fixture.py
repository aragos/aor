"""CONSTRUCTED-only joined numerical proof; never a campaign or scientific input loader.

The handwritten factors/directions cancel all blocks except actor readout. Monotone
invented offers give an analytic common V1/V2 calibration interval without a search.
Native games, lifecycle and transport are explicitly supplied fixtures. Numerical
requests, CPU environment, forwards, calibration, archives, reward arithmetic,
fitness, update, diagnostics and coordinator subject consumption use real owners.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time
from types import SimpleNamespace
from unittest.mock import patch

import numpy as np
import coordinated_es_calibration_v2 as v2
import coordinated_es_campaign as campaign
import coordinated_es_math as es
import coordinated_es_numeric as numeric
import coordinated_es_numeric_stage as stage
import coordinated_es_plan as plan_owner
import coordinated_es_projection as projection
import coordinated_es_storage as storage
from test_coordinated_es_plan import protocol as protocol_fixture


def put(path, value, *, raw=False, lf=False):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    es.require(not path.exists(), 'A constructed proof subject is published once.')
    path.write_bytes(value if raw else es.canonical(value) + (b'\n' if lf else b''))
    return plan_owner.subject(path)


def models():
    """No random constructor: every coefficient is an explicit test constant."""
    result = {}
    for head in es.HEADS:
        contract = projection.contract(head)
        states, actions = ['unit.state'], ['unit.amount']
        if head is es.Head.TOKEN:
            states = sorted(states + list(projection.token_paired_policy.ORDER_NAMES))
            actions = sorted(actions + list(projection.token_paired_policy.AMOUNT_NAMES))
        action_row = [.02 if name == 'unit.amount' else 0. for name in actions]
        model = dict(schemaVersion=contract.schemaVersion, encoderVersion=contract.encoder,
            hiddenSize=64, baselineProbability=.97, stateFeatureNames=states,
            actionFeatureNames=actions, stateWeights=[0.] * (64 * len(states)),
            stateBias=[0.] * 64, actionWeights=action_row * 64, actionBias=[0.] * 64,
            actorWeights=[.03 if head in (es.Head.CARD, es.Head.PURCHASE) else 0.] * 64,
            valueWeights=[.07] * 64, valueBias=.23)
        raw = es.canonical(model)
        result[head] = projection.Original(head, raw, numeric.raw_sha(raw))
    return result


def factor_map(originals, bundle_sha):
    """Invented normalized factors, NOT claimed to be a sampled PCG64 realization."""
    heads = {}
    for head, original in originals.items():
        model = original.model
        states, actions = len(model['stateFeatureNames']), len(model['actionFeatureNames'])
        factors = {name: [[1.] * shape[1] for _ in range(64)]
            for name, shape in projection.shapes(states, actions).items()}
        factors['actorWeights'] = [[1. if row % 2 == 0 else -1.] * 64 for row in range(64)]
        heads[head.value] = dict(modelSha256=original.sha256,
            dictionarySha256=projection.dictionary_sha(model), stateCount=states,
            actionCount=actions, factors=factors)
    return projection.Projection.from_record(dict(kind='coordinated-es-projection-1',
        scopeSha256='2' * 64, originalBundleSha256=bundle_sha, recipe=projection.RECIPE,
        projectionSeed=101000, numpyVersion=np.__version__, heads=heads))


def directions(scope, generation):
    vector = tuple(1. if index % 2 == 0 else -1. for index in range(256))
    return es.DirectionTable(scope, generation, (vector,) * 16)


def panel(originals, protocol, bundle):
    return dict(kind='coordinated-es-public-panel-1', protocolSha256=protocol['sha256'],
        originalBundleSha256=bundle['sha256'], worlds=[dict(index=index,
            assignedEpoch=1 + (index // 4) % 3, heads={head.value: [dict(number=8,
                batchStartNumber=7, epoch=1 + (index // 4) % 3, referenceIndex=0,
                observation=dict(encoderVersion=originals[head].model['encoderVersion'],
                    baselineIndex=0, stateFeatures={}, actions=[
                        dict(command='unit-action-0', features={}),
                        dict(command='unit-action-1', features={'unit.amount': (index + 1) / 256.})]))]
                for head in (es.Head.EXPANSION, es.Head.TOKEN)}) for index in range(256)])


def terminal(seat, plus):
    """Invented scores determine every seat's normalized label through the real reader."""
    totals = [1 + ((index - seat - 1) % 4) for index in range(4)]
    totals[seat] = 4 if plus else 0
    maximum = max(totals)
    scores = [dict(player='p' + str(index + 1), cash=total, advancePoints=0, misery=0,
        total=total, winner=total == maximum) for index, total in enumerate(totals)]
    wins = [1. if total == maximum else 0. for total in totals]
    ranks = [sum(total > other for index, other in enumerate(totals) if index != seat_index) / 3.
        for seat_index, total in enumerate(totals)]
    return dict(scores=scores, wins=wins, ranks=ranks,
        rewards=[.8 * win + .2 * rank for win, rank in zip(wins, ranks)],
        decisions=9, elapsedMillis=0)


def archives(root, plan, subjects, population):
    """Eight full native-wire archives, admitted by the real pure native metadata owner."""
    receipts = []
    for chunk in range(8):
        folder = root / ('chunk-' + str(chunk))
        native = folder / 'native'
        request = plan.generation_source(1, chunk, 'POPULATION', subjects)
        source = put(native / 'source.json', request, lf=True)
        put(native / 'arena.json', Path(subjects['arena']['path']).read_bytes(), raw=True)
        signature = es.digest(dict(constructed=True, chunk=chunk, source=source['sha256']))
        common = dict(signature=signature, scopeSha256=plan.record['original']['scopeSha256'],
            scienceSha256=es.digest(dict(constructed=True, population=subjects['population']['sha256'])),
            sourceSha256=source['sha256'])
        dictionaries = {head: {key: row[key] for key in
            ('encoderVersion', 'stateFeatureNames', 'actionFeatureNames')}
            for head, row in ((h, json.loads(numeric.bundle(numeric.Inputs(), subjects['originalBundle'])[es.Head(h)]))
                for h in plan_owner.HEADS)}
        prepared = dict(kind=plan_owner.PREFIX + '-prepared-1', stage='POPULATION', generation=1,
            globalStart=chunk * 32, count=32, bank=plan.bank(1)['worldBank'],
            subjects={key: None if value is None else dict(value, originalPath=value['path'])
                for key, value in subjects.items()}, source=source,
            originalBundle=subjects['originalBundle'], arena=subjects['arena'], dictionaries=dictionaries,
            inputPins={value['path']: value['sha256'] for value in subjects.values() if value is not None},
            ecologySha256=plan.record['original']['ecologySha256'], **{k: common[k]
                for k in ('signature', 'scopeSha256', 'scienceSha256')})
        put(native / 'prepared.json', prepared)
        replay_indices = {row['globalIndex']: row['mutant']['index'] for row in plan_owner.replays(1, chunk)}
        for index in plan_owner.indices(chunk):
            for member in range(32):
                game = dict(kind=plan_owner.PREFIX + '-game-1', generation=1, globalIndex=index,
                    seed=plan.bank(1)['worldBank']['seeds'][index], seat=index % 4,
                    role=storage.role(member), bundleSha256=population['members'][member]['bundleSha256'],
                    originalBundleSha256=subjects['originalBundle']['sha256'], **common,
                    terminal=terminal(index % 4, member % 2 == 0),
                    canonical=dict(replayVerified=True, constructed=True)
                        if replay_indices.get(index) == member else None)
                put(native / storage.game_name(index, member), game)
        files = storage.inventory(native)
        finished = dict(kind=plan_owner.PREFIX + '-complete-1', **{key: prepared[key] for key in
            ('signature', 'scopeSha256', 'scienceSha256', 'stage', 'generation', 'globalStart', 'count')},
            attemptedGames=1024, completedGames=1024, expectedGames=1024,
            canonicalGames=len(replay_indices), files={key: row['sha256'] for key, row in files.items()})
        put(native / 'finished.json', finished)
        for index, member in replay_indices.items():
            name = storage.game_name(index, member)
            prefix = 'replay-' + storage.role(member) + '-' + str(index)
            actual = put(native / prefix / name, (native / name).read_bytes(), raw=True)
            put(native / prefix / (prefix + '.json'), dict(kind=plan_owner.PREFIX + '-reproduction-1',
                **{key: common[key] for key in ('signature', 'scienceSha256', 'sourceSha256')},
                globalIndex=index, role=storage.role(member), original=plan_owner.subject(native / name),
                actual=dict(file=name, sha256=actual['sha256']), terminalEqual=True, canonicalEqual=True,
                replayVerified=True, scientificTerminalExecutions=1, attemptedGames=1, completedGames=1))
        receipt = storage.verify_generation(plan, native, source['path'], 1, chunk, 'population')
        storage.archive_collection(native, folder / 'evidence.tar.gz', receipt)
        receipts.append(plan_owner.subject(folder / 'evidence.tar.gz.json'))
    return receipts


def source_owners():
    modules = (numeric, es, numeric.cal, projection, numeric.environment,
        projection.b, projection.card_policy, projection.expansion_mc, projection.token_paired_policy, v2)
    return {str(Path(module.__file__).absolute()): plan_owner.file_sha(module.__file__) for module in modules}


def constructed_plan(value, originals):
    """Confine toy-original admission to one test-only constructor and prove restoration."""
    before = dict(plan_owner.ORIGINAL_ACTORS)
    def rejected():
        try:
            plan_owner.Plan.from_record(value)
        except ValueError as failure:
            es.require('prescribed original' in str(failure), 'The toy must fail specifically at original admission.')
            return
        raise AssertionError('A toy model must never pass ordinary scientific-original admission.')
    rejected()
    with patch.dict(plan_owner.ORIGINAL_ACTORS, {head.value: row.sha256 for head, row in originals.items()}):
        plan = plan_owner.Plan.from_record(value)
    es.require(plan_owner.ORIGINAL_ACTORS == before, 'Test-only original expectations must restore immediately.')
    rejected()
    return plan


def run_child(profile, command, log):
    """Only external lifecycle is replaced; launch the exact coordinator-built CLI unchanged."""
    remaining = profile['_clock'].deadline - time.time()
    es.require(remaining > 0, 'The constructed joined proof deadline expired.')
    with Path(log).open('xb') as stream:
        result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=remaining,
            check=False)
    es.require(result.returncode == 0, 'Constructed numerical child failed; retain its exact log.')


def context_evidence(directory, consumed, expected):
    for key, subject in expected.items():
        es.require((consumed[key] is None) == (subject is None) and
            (subject is None or consumed[key]['sha256'] == subject['sha256']),
            'The coordinator must consume the exact produced ' + key + ' subject.')
    return dict(stage=plan_owner.subject(Path(directory) / 'stage.json'), consumed=consumed)


def verify_existing_consumption(root):
    """Pure supplement for a retained joined proof; no numerical child/model/forward."""
    root = Path(root).absolute()
    proof_subject = plan_owner.subject(root / 'joined-proof.json')
    proof = plan_owner.read(proof_subject['path'])
    es.require(proof['kind'] == 'coordinated-es-constructed-joined-proof-1' and proof['passed'] is True
        and proof['constructed'] is True, 'Only an already-completed constructed proof is eligible.')
    protocol = proof['protocol']
    value = plan_owner.read(plan_owner.checked_subject(protocol))
    originals = {es.Head(head): SimpleNamespace(sha256=row['actorSha256'])
        for head, row in value['original']['heads'].items()}
    plan = constructed_plan(value, originals)
    profile = dict(originalBundle=proof['originalBundle'], projection=proof['projection'],
        arena=plan_owner.subject(root / 'arena.json'))
    result = []
    for number in range(1, proof['coordinatorGenerationsConsumed'] + 1):
        directory = root / ('generation-' + str(number)) / 'portable'
        consumed = campaign.read_context(directory, plan, protocol['path'], profile, number, 'population')
        result.append(context_evidence(directory, consumed, dict(
            center=plan_owner.subject(root / ('center-0.json' if number == 1 else 'generation-1/update/center.json')),
            previousUpdate=None if number == 1 else plan_owner.subject(root / 'generation-1/update/update.json'),
            calibration=proof['calibration'], population=plan_owner.subject(directory / 'population.json'))))
    return dict(kind='coordinated-es-constructed-consumption-proof-1', constructed=True, passed=True,
        completedProof=proof_subject, contexts=result, numericalChildren=0, forwards=0,
        scientificGames=0, scientificUpdates=0)


def execute(root, version, environment):
    root = Path(root).absolute()
    root.mkdir(parents=True, exist_ok=False)
    original = models()
    original_out = numeric.Output(root / 'original')
    bundle_sha = original_out.bundle('', {head: row.raw for head, row in original.items()})
    bundle = dict(path=str(original_out.path / 'bundle.json'), sha256=bundle_sha)
    factors = factor_map(original, bundle_sha)
    projection_subject = put(root / 'projection.json', factors.wire, raw=True)
    value = protocol_fixture()
    value['calibration'] = plan_owner.calibration_recipe(version)
    value['original'].update(bundleSha256=bundle_sha, projectionSha256=factors.sha256,
        projectionRecipeSha256=es.digest(projection.RECIPE), heads={head.value:
            dict(actorSha256=row.sha256, dictionarySha256=projection.dictionary_sha(row.model))
            for head, row in original.items()})
    plan = constructed_plan(value, original)
    protocol = put(root / 'protocol.json', value, lf=True)
    profile = dict(numericalSourceOwners=source_owners(), numerical=environment,
        python=dict(executable=sys.executable), _clock=SimpleNamespace(deadline=time.time() + 240),
        originalBundle=bundle, projection=projection_subject,
        arena=put(root / 'arena.json', dict(constructed=True)),
        directions=[put(root / ('directions-' + str(i) + '.json'), directions(factors.scope, i).record)
            for i in range(1, 17)])
    center = put(root / 'center-0.json', es.Center(factors.scope, 0, (0.,) * 256).record)
    history = put(root / 'historical' / 'fixture.json', dict(constructed=True, actualGames=0))
    public = put(root / 'public-panel.json', panel(original, protocol, bundle))
    bank = stage.bank_subject(plan, 1, root / 'generation-1')
    common = dict(protocol=protocol, originalBundle=bundle, projection=projection_subject)
    stages = []
    contexts = []
    with patch.object(stage.execution, 'run_child', run_child):
        def invoke(verb, directory, fields):
            result = stage.invoke(profile, verb, directory, fields)
            es.require(result['complete'], 'The predeclared constructed positive fixture must complete.')
            stages.append(result['receipt'])
            return result
        first = root / 'generation-1'
        invoke('materialize', first / 'materialize', dict(common, center=center,
            directions=profile['directions'][0], worldBank=bank, worldIds=list(range(256)), calibration=None))
        invoke('calibrate', first / 'calibrate', dict(common, directions=profile['directions'][0],
            materialization=plan_owner.subject(first / 'materialize/materialization.json'), publicPanel=public))
        seal = plan_owner.subject(first / 'calibrate/calibration.json')
        population = stage.population_publication(first / 'materialize',
            plan_owner.subject(first / 'calibrate/population.json'), first / 'calibrate', first / 'portable')
        context = campaign.context(first / 'portable', plan, protocol['path'], profile,
            1, 'population', center, None, history, seal, population)
        subjects = campaign.read_context(context, plan, protocol['path'], profile, 1, 'population')
        contexts.append(context_evidence(context, subjects, dict(center=center,
            previousUpdate=None, calibration=seal, population=population)))
        receipts = archives(first / 'archives', plan, subjects, plan_owner.read(population['path']))
        fitness = stage.publish_numeric(first / 'fitness.json', storage.fitness(plan, protocol['sha256'],
            population, seal, receipts))
        if version == 2:
            invoke('update', first / 'update', dict(protocol=protocol, center=center,
                directions=profile['directions'][0], population=population, fitness=fitness))
            next_center = plan_owner.subject(first / 'update/center.json')
            update = plan_owner.subject(first / 'update/update.json')
            invoke('diagnose', first / 'diagnose', dict(common, center=next_center,
                calibration=seal, publicPanel=public))
            second = root / 'generation-2'
            invoke('materialize', second / 'materialize', dict(common, center=next_center,
                directions=profile['directions'][1], worldBank=stage.bank_subject(plan, 2, second),
                worldIds=list(range(256)), calibration=seal))
            next_population = stage.population_publication(second / 'materialize',
                plan_owner.subject(second / 'materialize/population.json'), first / 'calibrate', second / 'portable')
            context = campaign.context(second / 'portable', plan, protocol['path'], profile,
                2, 'population', next_center, update, history, seal, next_population)
            consumed = campaign.read_context(context, plan, protocol['path'], profile, 2, 'population')
            contexts.append(context_evidence(context, consumed, dict(center=next_center,
                previousUpdate=update, calibration=seal, population=next_population)))
    record = dict(kind='coordinated-es-constructed-joined-proof-1', constructed=True, passed=True,
        version=version, protocol=protocol, projection=projection_subject, originalBundle=bundle,
        numericalStages=stages, calibration=seal, population=population, fitness=fitness,
        archives=receipts, archiveCount=8, scoreValidatedGameRecords=8192,
        expectedMemberRewards=[1. if i % 2 == 0 else 0. for i in range(32)],
        numericalUpdates=1 if version == 2 else 0, coordinatorGenerationsConsumed=2 if version == 2 else 1,
        coordinatorContexts=contexts,
        substitutions=['handwritten models/factors/directions/public offers/terminal scores',
            'test-only Plan original actor constants', 'native game/canonical/reproduction evidence',
            'external peer lifecycle replaced by bounded real CLI subprocess'],
        nativeFinalAdmissionCovered=False, scientificGames=0, scientificUpdates=0,
        toyOriginalRejectedBeforeAndAfter=True, originalExpectationRestoredBeforeExecution=True,
        sourceOwners=source_owners())
    actual = plan_owner.read(fitness['path'])
    es.require(all(row['rewards'] == [expected] * 256 for row, expected in
        zip(actual['members'], record['expectedMemberRewards'])), 'Invented scores must produce exact known returns.')
    put(root / 'joined-proof.json', record)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    root = Path(args.output).absolute()
    root.mkdir(parents=True, exist_ok=False)
    environment = put(root / 'numerical-environment.json', numeric.environment.current())
    receipts = []
    for version in (2, 1):
        execute(root / ('v' + str(version)), version, environment)
        receipts.append(plan_owner.subject(root / ('v' + str(version)) / 'joined-proof.json'))
    proof = dict(kind='coordinated-es-constructed-joined-complete-1', passed=True,
        constructed=True, environment=environment, proofs=receipts,
        source=plan_owner.subject(__file__), scientificGames=0, scientificUpdates=0)
    put(root / 'finished.json', proof)
    print(json.dumps(proof, sort_keys=True))


if __name__ == '__main__':
    main()
