import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import {
    evaluateTokenPolicy,
    evaluateTokenPolicySeeded,
} from '../ai/build/dist/js/productionLibrary/aor-ai.mjs';

// JVM-generated constructed inputs, with no saved models/worlds or browser assets.
const fixture = JSON.parse(readFileSync(new URL('../ai/build/test-fixtures/token-browser-parity.json', import.meta.url), 'utf8'));
const tolerance = 2e-12;
assert.equal(fixture.kind, 'token-browser-parity-1');
assert.equal(fixture.constructedOnly, true);
assert.equal(fixture.newGames, 0);
assert.equal(fixture.retainedModelsRead, 0);
assert.equal(fixture.tolerance, tolerance);
assert.equal(fixture.cases.length, 60);
assert.equal(fixture.cases.filter(row => row.botSeed !== null).length, 28);

for (const row of fixture.cases) {
    test(`production TOKEN2 matches native ${row.id}`, context => {
        const observation = JSON.stringify(row.observation);
        const model = JSON.stringify(row.model);
        const outputs = [{input: 'explicit-native-uniform', value: evaluateTokenPolicy(observation, model, row.uniform)}];
        if (row.botSeed !== null) {
            outputs.push({input: 'original-signed-bot-seed', value: evaluateTokenPolicySeeded(observation, model, row.botSeed)});
        }
        let maximumAbsoluteError = 0;
        for (const output of outputs) {
            const actual = JSON.parse(output.value);
            assert.deepEqual(Object.keys(actual).sort(), Object.keys(row.expected).sort());
            assert.equal(actual.index, row.expected.index);
            assert.equal(actual.command, row.expected.command);
            const compare = (value, expected, field) => {
                assert.equal(Number.isFinite(value), true, field);
                const error = Math.abs(value - expected);
                maximumAbsoluteError = Math.max(maximumAbsoluteError, error);
                assert.ok(error <= tolerance, `${output.input} ${field}: absolute error ${error} exceeds ${tolerance}`);
            };
            for (const name of ['logits', 'probabilities']) {
                assert.equal(actual[name].length, row.expected[name].length);
                actual[name].forEach((value, index) => compare(value, row.expected[name][index], `${name}[${index}]`));
            }
            compare(actual.value, row.expected.value, 'value');
            compare(actual.logProbability, row.expected.logProbability, 'logProbability');
        }
        context.diagnostic(JSON.stringify({botSeed: row.botSeed, actorSeed: row.actorSeed,
            uniform: row.uniform, maximumAbsoluteError, tolerance, exactCommand: true,
            selectionInputs: outputs.map(output => output.input)}));
    });
}
