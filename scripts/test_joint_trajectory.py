"""Synthetic native-shaped shared origins, exact typed behavior and atomic pair publication."""
from dataclasses import replace
import gzip
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

import joint_trajectory as joint
from test_joint_economy import terminal

b = joint.backend


def put(path, value):
    b.atomic_json(path, value)


def trained_actor(root, phase):
    contract = joint.CONTRACTS[phase]
    schema = dict(encoderVersion=contract.encoder, stateFeatureNames=['state'], actionFeatureNames=['choice'])
    initial = b.initialize(schema, 19 if phase == 'card' else 31, contract=contract)
    folder = root/(phase+'-start'); folder.mkdir()
    put(folder/'initial.json', initial)
    actor = b.ResidualPolicy(initial, contract)
    optimizer = b.torch.optim.Adam(actor.parameters(), lr=.0003, foreach=False)
    for parameter in actor.parameters():
        parameter.grad = b.torch.ones_like(parameter) * (1 if phase == 'card' else 2)
    optimizer.step()
    put(folder/'policy.json', actor.artifact())
    identity = dict(schemaVersion=1, actorVersion=contract.actor,
        modelSha256=joint.sha(folder/'initial.json'), config=b.asdict(b.UpdateConfig()), deadlineEpochSeconds=1.)
    put(folder/'identity.json', identity)
    history = [dict(epoch=1, accepted=True, behaviorKl=.0001, priorKl=.0001, valueMse=.1)]
    payload = dict(schemaVersion=1, identitySha256=b.digest(identity), epoch=1,
        model=actor.artifact(), optimizer=b.encode_optimizer(optimizer), history=history,
        elapsedSeconds=.01, deadlineEpochSeconds=1., stopReason=None)
    b.save_checkpoint(folder/'checkpoint.json', payload)
    put(folder/'finished.json', dict(identitySha256=b.digest(identity),
        inputModelSha256=identity['modelSha256'], outputModelSha256=joint.sha(folder/'policy.json'),
        history=history, acceptedEpochs=1))
    return folder/'policy.json', folder/'checkpoint.json', actor


def phase_descriptor(path, phase):
    model = joint.read(path); contract = joint.CONTRACTS[phase]
    return dict(path=str(path), sha256=joint.sha(path), encoderVersion=contract.encoder,
        actorVersion=contract.actor, stateFeatureNames=model['stateFeatureNames'], actionFeatureNames=model['actionFeatureNames'])


def decision(actor, phase, index, number):
    commands = ['pass', 'play hand-1'] if phase == 'card' else ['buy-advance A', 'pass']
    observation = dict(encoderVersion=joint.CONTRACTS[phase].encoder, stateFeatures={'state': .2+index*.1},
        actions=[dict(command=command, features={'choice': float(i)}) for i, command in enumerate(commands)],
        baselineIndex=commands.index('pass'))
    with b.torch.no_grad():
        logps, values = actor([observation])
    seed = joint.actor_seed(-1000000000+13+index*8191, number, index%4, phase)
    selected = b.select(logps[0].exp().tolist(), b.selection_uniform(seed))
    return dict(number=number, actorSeed=seed, turn=1,
        decision='play-cards' if phase == 'card' else 'purchase-advances', observation=observation,
        selectedIndex=selected, behaviorLogProbability=float(logps[0][selected]), behaviorValue=float(values[0]))


def fixture(root):
    root = root.resolve(); put(root/'F.json', {'weights': {'f': 1.}})
    models, checkpoints, actors = {}, {}, {}
    for phase in joint.PHASES:
        models[phase], checkpoints[phase], actors[phase] = trained_actor(root, phase)
    raw = dict(schemaVersion=1, kind='joint-card-purchase-1', configuration=joint.CONFIGURATION, baselineSha256=joint.sha(root/'F.json'),
        **{phase: dict(path=str(models[phase].relative_to(root)), sha256=joint.sha(models[phase])) for phase in joint.PHASES})
    put(root/'bundle.json', raw)
    put(root/'rival.json', dict(raw, card=None))
    def descriptor(path, rival=False):
        return dict(path=str(path), sha256=joint.sha(path), kind=raw['kind'], configuration=joint.CONFIGURATION,
            baselineSha256=raw['baselineSha256'], card=None if rival else phase_descriptor(models['card'], 'card'),
            purchase=phase_descriptor(models['purchase'], 'purchase'))
    own, rival = descriptor(root/'bundle.json'), descriptor(root/'rival.json', True)
    source = dict(schemaVersion=1, kind='joint-collect', configuration=joint.CONFIGURATION, games=2, seed=13,
        baseline=str(root/'F.json'), bundle=str(root/'bundle.json'), opponents=[str(root/'rival.json')])
    put(root/'source.json', source)
    directory = root/'collection'; directory.mkdir()
    for original, name in [(root/'source.json', 'source.json'), (root/'F.json', 'baseline.json'),
                           (root/'bundle.json', 'bundle.json'), (root/'rival.json', 'opponent-0-bundle.json'),
                           (models['purchase'], 'opponent-0-purchase-policy.json'),
                           *[(models[phase], phase+'-policy.json') for phase in joint.PHASES]]:
        shutil.copyfile(original, directory/name)
    seeds = [-1000000000+13+i*8191 for i in range(2)]
    manifest = dict(schemaVersion=1, kind='joint-collect', mode='on-policy', signature='fixture-native',
        trajectoryEncoding='gzip-json-v1', source=str(root/'source.json'), sourceSha256=joint.sha(root/'source.json'),
        configuration=joint.CONFIGURATION, games=2, seed=13, seeds=seeds,
        baseline=dict(path=str(root/'F.json'), sha256=joint.sha(root/'F.json')), bundle=own, opponents=[rival])
    put(directory/'collection.json', manifest)
    put(directory/'state.json', dict(kind='joint-collect', signature='fixture-native', phase='complete', completedGames=2))
    for index in range(2):
        outcome = terminal(index, index == 0); outcome['gameDecisions'] = 4
        game = dict(schemaVersion=1, signature='fixture-native', index=index, configuration=joint.CONFIGURATION,
            gameSeed=seeds[index], controlledSeat=index, opponentIndices=[0]*4,
            bundleSha256=own['sha256'], cardModelSha256=own['card']['sha256'],
            purchaseModelSha256=own['purchase']['sha256'],
            cardDecisions=[decision(actors['card'], 'card', index, 0)] if index == 0 else [],
            purchaseDecisions=[decision(actors['purchase'], 'purchase', index, 1)] if index == 1 else [], terminal=outcome)
        (directory/f'game-{index}.json.gz').write_bytes(gzip.compress(b.canonical(game), mtime=0))
    finish(directory)
    return directory, root/'bundle.json', checkpoints


def finish(directory):
    put(directory/'finished.json', dict(kind='joint-collect', signature='fixture-native', games=2,
        gameSha256=[joint.sha(directory/f'game-{index}.json.gz') for index in range(2)]))


def change_game(directory, index, mutate):
    path = directory/f'game-{index}.json.gz'
    game = json.loads(gzip.decompress(path.read_bytes())); mutate(game)
    path.write_bytes(gzip.compress(b.canonical(game), mtime=0)); finish(directory)


class Reader(unittest.TestCase):
    def setUp(self):
        b.torch.set_num_threads(1)
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.directory, self.bundle, self.checkpoints = fixture(self.root)

    def test_both_views_keep_all_origins_and_only_their_real_terminal_labels(self):
        batch = joint.JointCollection(self.directory, self.bundle)
        self.assertEqual([1, 0], [row['cardDecisions'] for row in batch.origins])
        self.assertEqual([0, 1], [row['purchaseDecisions'] for row in batch.origins])
        self.assertEqual([0], [row['originIndex'] for row in batch.rows['card']])
        self.assertEqual([1], [row['originIndex'] for row in batch.rows['purchase']])
        provenance = []
        for phase in joint.PHASES:
            _, data = batch.phase_reader(self.directory, batch.bundle[phase]['path'], batch.models[phase],
                                         contract=joint.CONTRACTS[phase])
            provenance.append(data)
        for key in ('gameHashes', 'originCoverage', 'gameMeanReward', 'bundleSha256'):
            self.assertEqual(provenance[0][key], provenance[1][key])
        self.assertEqual(1., batch.rows['card'][0]['return'])
        self.assertNotEqual(1., batch.rows['purchase'][0]['return'])

    def test_missing_completion_never_decodes_any_game(self):
        (self.directory/'finished.json').unlink()
        with patch.object(b, 'read_game') as decode:
            with self.assertRaises(FileNotFoundError): joint.JointCollection(self.directory, self.bundle)
            decode.assert_not_called()

    def test_later_corrupt_hash_blocks_all_decoding(self):
        with (self.directory/'game-1.json.gz').open('ab') as stream: stream.write(b'changed')
        with patch.object(b, 'read_game') as decode:
            with self.assertRaisesRegex(ValueError, 'compressed joint'): joint.JointCollection(self.directory, self.bundle)
            decode.assert_not_called()

    def test_typed_actor_snapshot_cannot_be_replaced(self):
        put(self.directory/'purchase-policy.json', {'wrong': True})
        with self.assertRaisesRegex(ValueError, 'snapshot changed'): joint.JointCollection(self.directory, self.bundle)

    def test_shared_terminal_is_recomputed_from_public_scores(self):
        change_game(self.directory, 0, lambda game: game['terminal'].update(reward=0.))
        with self.assertRaisesRegex(ValueError, 'public scores'): joint.JointCollection(self.directory, self.bundle)

    def test_phase_decision_numbers_cannot_overlap(self):
        second = b.read_game(self.directory/'game-1.json.gz', joint.GZIP_CONTRACT)['purchaseDecisions'][0]
        second['number'] = 0
        change_game(self.directory, 0, lambda game: game['purchaseDecisions'].append(second))
        with self.assertRaisesRegex(ValueError, 'ordered and disjoint'): joint.JointCollection(self.directory, self.bundle)

    def test_recorded_actor_likelihood_cannot_be_relabelled(self):
        change_game(self.directory, 0, lambda game: game['cardDecisions'][0].update(behaviorLogProbability=-10.))
        with self.assertRaisesRegex(ValueError, 'likelihood/value'): joint.JointCollection(self.directory, self.bundle)

    def test_actor_seed_cannot_change_even_if_the_selected_action_would_match(self):
        change_game(self.directory, 0, lambda game: game['cardDecisions'][0].update(actorSeed=17))
        with self.assertRaisesRegex(ValueError, 'typed native sampling'): joint.JointCollection(self.directory, self.bundle)

    def test_merged_phase_turns_cannot_reverse_time(self):
        second = b.read_game(self.directory/'game-1.json.gz', joint.GZIP_CONTRACT)['purchaseDecisions'][0]
        second['actorSeed'] = joint.actor_seed(-1000000000+13, 1, 0, 'purchase')
        def reverse_turn(game):
            game['cardDecisions'][0]['turn'] = 2
            game['purchaseDecisions'].append(second)
        change_game(self.directory, 0, reverse_turn)
        with self.assertRaisesRegex(ValueError, 'nondecreasing game turns'): joint.JointCollection(self.directory, self.bundle)

    def test_other_player_profiles_are_explicitly_unsupported(self):
        path = self.directory/'collection.json'; manifest = joint.read(path)
        manifest['configuration'] = 'players-3/standard/no-crusades/deck-true/art-true'; put(path, manifest)
        with self.assertRaisesRegex(ValueError, 'qualified four-player'): joint.JointCollection(self.directory, self.bundle)

    def test_unknown_hidden_feature_cannot_enter_either_typed_view(self):
        change_game(self.directory, 0, lambda game: game['cardDecisions'][0]['observation']['stateFeatures'].update(hiddenDeck=1.))
        with self.assertRaisesRegex(ValueError, 'unknown feature'): joint.JointCollection(self.directory, self.bundle)

    def test_entirely_empty_phase_rejects_before_either_fit_or_publication(self):
        change_game(self.directory, 0, lambda game: game.update(cardDecisions=[]))
        with patch.object(b, 'update') as update:
            with self.assertRaisesRegex(ValueError, 'entirely empty'):
                joint.update_pair(self.directory, self.bundle, configs(), self.checkpoints, self.root/'output')
            update.assert_not_called()
        self.assertFalse((self.root/'output').exists())


def configs():
    config = b.UpdateConfig(epochs=1, maxBehaviorKl=.1, threads=1, maxSeconds=30)
    return {'card': replace(config, cardReferenceKlCoefficient=.1), 'purchase': config}


class PairUpdate(unittest.TestCase):
    def setUp(self):
        b.torch.set_num_threads(1)
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.directory, self.bundle, self.checkpoints = fixture(self.root)

    def call(self, output, resume=False):
        return joint.update_pair(self.directory, self.bundle, configs(), self.checkpoints, output,
                                 purchase_reference=b.PurchaseReferenceObjective(), resume=resume)

    def test_real_updates_bind_both_shared_views_and_distinct_adam(self):
        output = self.root/'pair'; report = self.call(output)
        self.assertEqual(2, len(report['commonOrigins']))
        self.assertEqual(set(joint.PHASES), set(report['phases']))
        for phase in joint.PHASES:
            identity = joint.read(output/phase/'identity.json')
            self.assertEqual(report['gameHashes'], identity['data']['gameHashes'])
            self.assertEqual(joint.sha(self.checkpoints[phase]), identity['optimizerFromSha256'])
            self.assertEqual(phase, identity['data']['phase'])
            self.assertEqual(joint.sha(joint.__file__), identity['data']['readerSha256'])
        self.assertNotIn('purchaseReferenceObjective', joint.read(output/'card/identity.json'))
        self.assertEqual(.1, joint.read(output/'purchase/identity.json')['purchaseReferenceObjective']['coefficient'])
        bundle, _ = joint.load_bundle(output/'bundle.json', joint.CONFIGURATION, joint.sha(self.root/'F.json'), {})
        self.assertEqual(report['phases']['card']['policySha256'], bundle['card']['sha256'])

    def test_second_fit_failure_publishes_no_pair_and_resume_preserves_exact_carry(self):
        output = self.root/'interrupted'; original = b.update
        def fail_second(*args, **kwargs):
            if kwargs['contract'].label == 'purchase': raise RuntimeError('synthetic interruption')
            return original(*args, **kwargs)
        with patch.object(b, 'update', side_effect=fail_second):
            with self.assertRaisesRegex(RuntimeError, 'synthetic interruption'): self.call(output)
        self.assertTrue((output/'card/finished.json').exists())
        self.assertFalse((output/'bundle.json').exists()); self.assertFalse((output/'finished.json').exists())
        resumed = self.call(output, resume=True)
        direct = self.root/'direct'; self.call(direct)
        for phase in joint.PHASES:
            resumed_checkpoint = b.read_checkpoint(output/phase/'checkpoint.json')
            direct_checkpoint = b.read_checkpoint(direct/phase/'checkpoint.json')
            self.assertEqual(direct_checkpoint['model'], resumed_checkpoint['model'])
            self.assertEqual(direct_checkpoint['optimizer'], resumed_checkpoint['optimizer'])
        self.assertTrue(resumed['allInputHashesUnchanged'])

    def test_completed_resume_takes_no_additional_optimizer_steps(self):
        output = self.root/'pair'; original = self.call(output)
        with patch.object(b, 'update') as update:
            self.assertEqual(original, self.call(output, resume=True))
            update.assert_not_called()

    def test_first_actor_mutation_during_second_fit_prevents_pair_publication(self):
        output = self.root/'pair'; original = b.update
        def mutate_first(*args, **kwargs):
            report = original(*args, **kwargs)
            if kwargs['contract'].label == 'purchase':
                path = output/'card/policy.json'; model = joint.read(path)
                model['valueBias'] += .001; put(path, model)
            return report
        with patch.object(b, 'update', side_effect=mutate_first):
            with self.assertRaisesRegex(ValueError, 'Frozen joint inputs changed'): self.call(output)
        self.assertTrue((output/'purchase/finished.json').exists())
        self.assertFalse((output/'bundle.json').exists())
        self.assertFalse((output/'finished.json').exists())

    def test_pretrained_carry_cannot_reset_all_adam_states(self):
        path = self.checkpoints['purchase']; payload = b.read_checkpoint(path)
        payload['optimizer']['state'] = {}; b.save_checkpoint(path, payload)
        with patch.object(b, 'update') as update:
            with self.assertRaisesRegex(ValueError, 'retain every parameter state'): self.call(self.root/'pair')
            update.assert_not_called()
        self.assertFalse((self.root/'pair').exists())

    def test_negative_second_moment_cannot_enter_joint_adam(self):
        path = self.checkpoints['card']; payload = b.read_checkpoint(path)
        state = next(iter(payload['optimizer']['state'].values()))
        state['exp_avg_sq']['tensor'][0][0] = -1.; b.save_checkpoint(path, payload)
        with self.assertRaisesRegex(ValueError, 'Adam tensors'): self.call(self.root/'pair')
        self.assertFalse((self.root/'pair').exists())

    def test_changed_resume_config_rejects_before_fit(self):
        output = self.root/'pair'; self.call(output)
        changed = configs(); changed['purchase'] = replace(changed['purchase'], seed=33)
        with patch.object(b, 'update') as update:
            with self.assertRaisesRegex(ValueError, 'identity or result changed'):
                joint.update_pair(self.directory, self.bundle, changed, self.checkpoints, output,
                    purchase_reference=b.PurchaseReferenceObjective(), resume=True)
            update.assert_not_called()

    def test_card_initializer_cannot_use_an_unqualified_substitute(self):
        with self.assertRaisesRegex(ValueError, 'exact vanilla32'):
            joint.initial_bundle(joint.CONFIGURATION, joint.sha(self.root/'F.json'),
                {phase: self.checkpoints[phase].parent/'policy.json' for phase in joint.PHASES}, self.checkpoints)


if __name__ == '__main__': unittest.main()
