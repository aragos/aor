import assert from 'node:assert/strict';
import {test} from 'node:test';
import {readFile} from 'node:fs/promises';
import ts from 'typescript';

const source = await readFile(new URL('../src/map/mapOrders.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, {compilerOptions: {module: ts.ModuleKind.ESNext}}).outputText;
const {placementOrder, provinceOrders} = await import('data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'));

function snapshot(commands, actors = ['p1']) {
    return {viewerId: 'p1', prompt: {players: actors}, legalChoices: {commands}};
}

test('only the prompted player receives direct province orders', () => {
    assert.deepEqual(provinceOrders(snapshot(['attack rome'], ['p2']), 'rome'), []);
});

test('orders are limited to the selected province and supported direct actions', () => {
    const state = snapshot(['place 1 rome', 'attack naples', 'reduce-marker rome', 'play hand-1 province rome', 'pass']);
    assert.deepEqual(provinceOrders(state, 'rome'), ['place 1 rome', 'reduce-marker rome']);
});

test('capital selection and event marker relocation retain their canonical commands', () => {
    assert.deepEqual(provinceOrders(snapshot(['choose-capital venice', 'move-marker genoa']), 'venice'), ['choose-capital venice']);
    assert.deepEqual(provinceOrders(snapshot(['move-marker genoa']), 'genoa'), ['move-marker genoa']);
});

test('placement generates a destination-only order with the requested count', () => {
    assert.equal(placementOrder('milan', '3', 5), 'place 3 milan');
});

test('placement rejects empty, fractional, nonpositive, and unavailable counts', () => {
    for (const count of ['', ' ', '0', '-1', '1.5', 'Infinity', '6']) {
        assert.equal(placementOrder('milan', count, 5), null, count);
    }
});
