import assert from 'node:assert/strict';
import {test} from 'node:test';
import {provinceOrders} from '../src/map/mapOrders.ts';

function snapshot(commands, actors = ['p1']) {
    return {viewerId: 'p1', prompt: {players: actors}, legalChoices: {commands}};
}

test('only the prompted player receives direct province orders', () => {
    assert.deepEqual(provinceOrders(snapshot(['move-marker rome'], ['p2']), 'rome'), []);
});

test('secondary orders exclude other provinces and actions with contextual controls', () => {
    const state = snapshot(['place 1 rome', 'attack rome', 'reduce-marker rome', 'move-marker naples', 'play hand-1 province rome', 'pass']);
    assert.deepEqual(provinceOrders(state, 'rome'), ['reduce-marker rome']);
});

test('capital selection and event marker relocation retain their canonical commands', () => {
    assert.deepEqual(provinceOrders(snapshot(['choose-capital venice', 'move-marker genoa']), 'venice'), ['choose-capital venice']);
    assert.deepEqual(provinceOrders(snapshot(['move-marker genoa']), 'genoa'), ['move-marker genoa']);
});
