"""Optional fit capacity preserves the portable 64-unit version-two contract."""
from dataclasses import replace
import math
from pathlib import Path
import random
import tempfile
import unittest
from unittest.mock import patch
import torch
import card_advantage as a
import card_advantage_train as fit
import card_advantage_toy as toy
import purchase_policy as common
from test_card_advantage import SCHEMA, group
from test_card_advantage_data import fixture

class WidthChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(1)
        torch.use_deterministic_algorithms(True)

    def test_active_width_is_a_bounded_integer(self):
        for width in (0, 65, 8.0, True):
            with self.subTest(width=width), self.assertRaisesRegex(ValueError, 'Active hidden width'):
                replace(a.CONFIG, activeHidden=width)

    def test_default_initializer_keeps_the_original_random_draws(self):
        rng = random.Random(84501)
        def weights(columns):
            return [rng.uniform(-1/math.sqrt(columns), 1/math.sqrt(columns)) for _ in range(64*columns)]
        expected = {'stateWeights': weights(2), 'stateBias': [0.0]*64,
                    'actionWeights': weights(3), 'actionBias': [0.0]*64,
                    'outputWeights': [0.0]*64, 'outputBias': 0.0}
        self.assertEqual(a.initialize(SCHEMA, 84501), expected)
        self.assertEqual(a.initialize(SCHEMA, 84501, 64), expected)

    def test_narrow_initializer_preserves_active_draws_and_zeros_all_padding(self):
        full, narrow = a.initialize(SCHEMA, 84501), a.initialize(SCHEMA, 84501, 8)
        for name, width in [('stateWeights', 2), ('actionWeights', 3), ('stateBias', 1),
                            ('actionBias', 1), ('outputWeights', 1)]:
            self.assertEqual(narrow[name][:8*width], full[name][:8*width])
            self.assertEqual(narrow[name][8*width:], [0.0]*(56*width))

    def test_padded_forward_and_choice_equal_a_genuinely_narrow_network(self):
        member = a.initialize(SCHEMA, 84501, 8)
        member['outputWeights'][:8] = [.3, -.2, .5, -.4, .1, .2, -.3, .4]
        member['stateBias'][:8] = [.03]*8
        member['actionBias'][:8] = [-.02]*8
        member['outputBias'] = .11
        network = a.Network(SCHEMA, member)
        for context in (-1, 1):
            g = group(context=context); actual = network([g])[0].detach()
            h = torch.tanh(g.state @ network.stateWeights[:8].T + network.stateBias[:8])
            actions = torch.tanh(g.actions @ network.actionWeights[:8].T + network.actionBias[:8])
            expected = torch.sigmoid((h*actions) @ network.outputWeights[:8] + network.outputBias).detach()
            torch.testing.assert_close(actual, expected, rtol=0., atol=1e-14)
            self.assertEqual(a.choose_values(g, [actual]*3)['index'], a.choose_values(g, [expected]*3)['index'])
        artifact = a.bundle(SCHEMA, [member]*3)
        self.assertEqual(artifact['hiddenSize'], 64)
        self.assertEqual(artifact['selectorVersion'], 'aor-card-advantage-2')

    def test_optimizer_updates_preserve_exact_zero_padding(self):
        config = replace(a.CONFIG, activeHidden=8, epochs=3, checkpoints=(1, 3))
        origins = [[group(1., 1)], [group(-1., -1)]]
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)/'member'
            selected, _ = fit.fit_member(SCHEMA, origins, origins, 84501, config, output, {}, math.inf)
            final = common.read_checkpoint(output/'checkpoint.json')['network']
            for member in (selected, final): a.validate_padding(member, SCHEMA, 8)
            self.assertNotEqual(final['outputWeights'][:8], [0.0]*8)

    def test_interrupted_compact_update_resumes_exactly(self):
        config = replace(a.CONFIG, activeHidden=8, epochs=2, checkpoints=(1, 2))
        origins = [[group(1., 1)], [group(-1., -1)]]; original = fit.epoch
        with tempfile.TemporaryDirectory() as tmp:
            first, second = Path(tmp)/'first', Path(tmp)/'second'
            expected, _ = fit.fit_member(SCHEMA, origins, origins, 84501, config, first, {}, math.inf)
            def interrupt(*args):
                original(*args)
                raise RuntimeError('Interrupted after mutable work')
            with patch.object(fit, 'epoch', side_effect=interrupt), self.assertRaises(RuntimeError):
                fit.fit_member(SCHEMA, origins, origins, 84501, config, second, {}, math.inf)
            actual, _ = fit.fit_member(SCHEMA, origins, origins, 84501, config, second, {}, math.inf)
            self.assertEqual(actual, expected)
            self.assertEqual(common.read_checkpoint(first/'checkpoint.json'), common.read_checkpoint(second/'checkpoint.json'))

    def test_nonzero_padding_in_checkpoint_is_rejected_without_overwriting(self):
        config = replace(a.CONFIG, activeHidden=8, epochs=1, checkpoints=(1,)); origins = [[group()]]
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)/'fit'
            fit.fit_member(SCHEMA, origins, origins, 1, config, output, {}, math.inf)
            path = output/'checkpoint.json'; checkpoint = common.read_checkpoint(path)
            checkpoint['network']['stateBias'][8] = .1
            common.save_checkpoint(path, checkpoint); before = path.read_bytes()
            with self.assertRaisesRegex(ValueError, 'Inactive hidden units'):
                fit.fit_member(SCHEMA, origins, origins, 1, config, output, {}, math.inf)
            self.assertEqual(before, path.read_bytes())

    def test_training_identity_binds_width_and_rejects_changed_resume(self):
        config = replace(a.CONFIG, activeHidden=8, epochs=1, checkpoints=(1,), maxSeconds=30)
        with tempfile.TemporaryDirectory() as tmp:
            source, output = Path(tmp)/'source', Path(tmp)/'fit'
            source.mkdir(); fixture(source)
            fit.train(source, output, config=config)
            self.assertEqual(common.read_json(output/'identity.json')['config']['activeHidden'], 8)
            for name in ('full', 'action-priority'):
                for member in common.read_json(output/(name+'.json'))['members']: a.validate_padding(member, SCHEMA, 8)
            with self.assertRaisesRegex(ValueError, 'Resume inputs/config/runtime differ'):
                fit.train(source, output, resume=True, config=replace(config, activeHidden=64))

    def test_toy_passes_immutable_config_to_every_fit(self):
        config = replace(a.CONFIG, activeHidden=8)
        artifact = a.bundle(toy.SCHEMA, [a.initialize(toy.SCHEMA, seed, 8) for seed in a.SEEDS])
        with tempfile.TemporaryDirectory() as tmp, patch.object(fit, 'fit_ensemble', return_value=(artifact, [])) as mocked, patch('builtins.print'):
            output = Path(tmp)/'toy'; toy.run(output, config=config)
            self.assertEqual(mocked.call_count, 4)
            self.assertTrue(all(call.kwargs['config'] is config for call in mocked.call_args_list))
            self.assertEqual(common.read_json(output/'protocol.json')['config']['activeHidden'], 8)
        self.assertEqual(a.CONFIG.activeHidden, 64)

if __name__ == '__main__': unittest.main()
