import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import {evaluateExpansionPolicy} from '../../ai/build/dist/js/productionLibrary/aor-ai.mjs';

// JVM-generated, constructed inputs only. The original native uniform is passed
// explicitly: this compares forward/selection, not the separate seed-transform owner.
const fixture = JSON.parse(readFileSync(new URL('../../ai/build/test-fixtures/expansion-browser-parity.json', import.meta.url), 'utf8'));
const tolerance = 2e-12;
assert.equal(fixture.kind, 'expansion-browser-parity-1');
assert.equal(fixture.constructedOnly, true);
assert.equal(fixture.newGames, 0);
assert.equal(fixture.retainedModelsRead, 0);
assert.equal(fixture.tolerance, tolerance);
assert.equal(fixture.cases.length, 60);

for (const row of fixture.cases) {
    test(`production expansion matches native ${row.id}`, context => {
        const actual = JSON.parse(evaluateExpansionPolicy(JSON.stringify(row.projection), JSON.stringify(row.model), row.baselineCommand, row.uniform));
        assert.deepEqual(Object.keys(actual).sort(), Object.keys(row.expected).sort());
        assert.equal(actual.index, row.expected.index);
        assert.equal(actual.command, row.expected.command);
        let maximumAbsoluteError = 0;
        const compare = (value, expected, field) => {
            assert.equal(Number.isFinite(value), true, field);
            const error = Math.abs(value - expected);
            maximumAbsoluteError = Math.max(maximumAbsoluteError, error);
            assert.ok(error <= tolerance, `${field}: absolute error ${error} exceeds ${tolerance}`);
        };
        for (const name of ['logits', 'probabilities']) {
            assert.equal(actual[name].length, row.expected[name].length);
            actual[name].forEach((value, index) => compare(value, row.expected[name][index], `${name}[${index}]`));
        }
        compare(actual.value, row.expected.value, 'value');
        compare(actual.logProbability, row.expected.logProbability, 'logProbability');
        context.diagnostic(JSON.stringify({seed: row.seed, actorSeed: row.actorSeed, uniform: row.uniform,
            maximumAbsoluteError, tolerance, exactCommand: true, selectionInput: 'same-native-uniform'}));
    });
}
