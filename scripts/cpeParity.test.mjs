import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import {evaluateCpePolicy, evaluateCpePolicySeeded}
    from '../ai/build/dist/js/productionLibrary/aor-ai.mjs';

// Handwritten JVM inputs only; no shipped assets, learned actors or world records.
const fixture = JSON.parse(readFileSync(new URL('../ai/build/test-fixtures/cpe-browser-parity.json', import.meta.url), 'utf8'));
const tolerance = 2e-12;
assert.equal(fixture.kind, 'cpe-browser-parity-1');
assert.equal(fixture.constructedOnly, true);
assert.equal(fixture.newGames, 0);
assert.equal(fixture.retainedModelsRead, 0);
assert.equal(fixture.tolerance, tolerance);
assert.equal(fixture.selectionScope, 'native-uniform-and-signed-bot-seed');
assert.equal(fixture.cases.length, 180);
for (const head of ['CARD', 'PURCHASE', 'EXPANSION']) {
    assert.equal(fixture.cases.filter(row => row.head === head).length, 60);
    assert.equal(fixture.cases.filter(row => row.head === head && row.botSeed !== null).length, 28);
    assert.ok(fixture.cases.some(row => row.head === head && row.referenceDiffersFromPrior));
}

for (const row of fixture.cases) {
    test(`production CPE matches native ${row.id}`, context => {
        const observation = JSON.stringify(row.observation);
        const model = JSON.stringify(row.model);
        const outputs = [evaluateCpePolicy(row.head, observation, model, row.uniform)];
        if (row.botSeed !== null) outputs.push(evaluateCpePolicySeeded(row.head, observation, model, row.botSeed));
        let maximumAbsoluteError = 0;
        for (const output of outputs) {
            const actual = JSON.parse(output);
            assert.deepEqual(Object.keys(actual).sort(), Object.keys(row.expected).sort());
            assert.equal(actual.index, row.expected.index);
            assert.equal(actual.command, row.expected.command);
            assert.equal(row.referenceIndex, actual.index);
            assert.equal(row.referenceDiffersFromPrior, row.referenceIndex !== row.observation.baselineIndex);
            const compare = (value, expected, field) => {
                assert.ok(Number.isFinite(value), field);
                const error = Math.abs(value - expected);
                maximumAbsoluteError = Math.max(maximumAbsoluteError, error);
                assert.ok(error <= tolerance, `${field}: ${error} exceeds ${tolerance}`);
            };
            for (const name of ['logits', 'probabilities']) {
                assert.equal(actual[name].length, row.expected[name].length);
                actual[name].forEach((value, index) => compare(value, row.expected[name][index], `${name}[${index}]`));
            }
            compare(actual.value, row.expected.value, 'value');
            compare(actual.logProbability, row.expected.logProbability, 'logProbability');
        }
        context.diagnostic(JSON.stringify({head: row.head, botSeed: row.botSeed, actorSeed: row.actorSeed,
            uniform: row.uniform, maximumAbsoluteError, tolerance, exactCommand: true,
            selectionInputs: row.botSeed === null ? ['native-uniform'] : ['native-uniform', 'signed-bot-seed']}));
    });
}

test('CPE evaluator rejects hidden observation fields', () => {
    const row = fixture.cases[0];
    assert.throws(() => evaluateCpePolicy(row.head,
        JSON.stringify({...row.observation, hiddenDeck: ['unit']}), JSON.stringify(row.model), .5));
});

test('CPE evaluator rejects undeclared action fields', () => {
    const row = fixture.cases[0];
    const observation = structuredClone(row.observation);
    observation.actions[0].terminalReward = 1;
    assert.throws(() => evaluateCpePolicy(row.head, JSON.stringify(observation), JSON.stringify(row.model), .5));
});

test('CPE expansion evaluator requires schema2', () => {
    const row = fixture.cases.find(value => value.head === 'EXPANSION');
    assert.throws(() => evaluateCpePolicy(row.head, JSON.stringify(row.observation),
        JSON.stringify({...row.model, schemaVersion: 1}), .5));
});

test('CPE evaluator requires an explicit supported head', () => {
    const row = fixture.cases[0];
    assert.throws(() => evaluateCpePolicy('TOKEN', JSON.stringify(row.observation), JSON.stringify(row.model), .5));
});
