import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import {evaluateCoordinatedPolicySeeded}
    from '../ai/build/dist/js/productionLibrary/aor-ai.mjs';

// Literal public tensors and offers only; never shipped models, world records or assets.
const fixture = JSON.parse(readFileSync(new URL(
    '../training/build/test-fixtures/coordinated-es-browser-parity.json', import.meta.url), 'utf8'));
const tolerance = 2e-12;
assert.equal(fixture.kind, 'coordinated-es-browser-parity-1');
assert.equal(fixture.constructedOnly, true);
assert.equal(fixture.newGames, 0);
assert.equal(fixture.retainedModelsRead, 0);
assert.equal(fixture.tolerance, tolerance);
assert.equal(fixture.cases.length, 224);
assert.equal(fixture.projection.coordinates.length, 256);

const evaluate = row => JSON.parse(evaluateCoordinatedPolicySeeded(row.head,
    JSON.stringify(row.observation), JSON.stringify(row.model), JSON.stringify(row.original),
    row.scale, row.botSeed));

for (const row of fixture.cases) {
    test(`production coordinated selector matches native ${row.id}`, context => {
        const actual = evaluate(row);
        assert.deepEqual(Object.keys(actual).sort(), Object.keys(row.expected).sort());
        assert.equal(actual.index, row.expected.index);
        assert.equal(actual.command, row.expected.command);
        let maximumAbsoluteError = 0;
        const compare = (value, expected, field) => {
            if (expected === null) return assert.equal(value, null, field);
            assert.ok(Number.isFinite(value), field);
            const error = Math.abs(value - expected);
            maximumAbsoluteError = Math.max(maximumAbsoluteError, error);
            assert.ok(error <= tolerance, `${field}: ${error} exceeds ${tolerance}`);
        };
        if (row.head === 'CARD' || row.head === 'PURCHASE') {
            for (const name of ['logits', 'probabilities']) {
                assert.equal(actual[name].length, row.expected[name].length);
                actual[name].forEach((value, index) => compare(value, row.expected[name][index], `${name}[${index}]`));
            }
            compare(actual.value, row.expected.value, 'value');
            compare(actual.logProbability, row.expected.logProbability, 'logProbability');
        } else {
            for (const name of ['residualDifferences', 'scores']) {
                assert.equal(actual[name].length, row.expected[name].length);
                actual[name].forEach((value, index) => compare(value, row.expected[name][index], `${name}[${index}]`));
            }
            compare(actual.strongestGap, row.expected.strongestGap, 'strongestGap');
            if (row.case === 'zero') {
                assert.ok(actual.residualDifferences.every(value => value === 0));
                assert.equal(actual.index, row.observation.baselineIndex);
            }
        }
        context.diagnostic(JSON.stringify({head: row.head, botSeed: row.botSeed,
            actorSeed: row.actorSeed, scale: row.scale, maximumAbsoluteError, tolerance,
            exactCommand: true, deterministicMargin: row.actorSeed === null}));
    });
}

test('coordinated selector rejects hidden observations', () => {
    const row = structuredClone(fixture.cases.find(value => value.head === 'TOKEN'));
    row.observation.hiddenDeck = ['unit'];
    assert.throws(() => evaluate(row));
});

test('coordinated selector preserves frozen value arrays', () => {
    const row = structuredClone(fixture.cases.find(value => value.head === 'EXPANSION'));
    row.model.valueWeights[0] += .01;
    assert.throws(() => evaluate(row));
});

test('card sampler rejects an E/T calibration scale', () => {
    const row = {...fixture.cases.find(value => value.head === 'CARD'), scale: 2};
    assert.throws(() => evaluate(row));
});

test('deterministic territory output carries no fictitious likelihood', () => {
    const row = fixture.cases.find(value => value.head === 'TOKEN');
    const actual = evaluate(row);
    assert.equal(Object.hasOwn(actual, 'probabilities'), false);
    assert.equal(Object.hasOwn(actual, 'logProbability'), false);
});
