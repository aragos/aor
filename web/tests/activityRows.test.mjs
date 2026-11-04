import assert from 'node:assert/strict';
import {test} from 'node:test';
import {activityRows} from '../src/activityRows.ts';

const first = {id: '1:0', kind: 'ROLL', detail: 'SHORTAGE', turnNumber: 2, stage: 'INCOME',
    dice: [{color: 'WHITE', value: 1}, {color: 'BLACK', value: 1}, {color: 'GREEN', value: 2}],
    marketRoll: {total: 2, commodities: ['STONE'], adjustment: 'SHORTAGE', cancelled: []}};
const second = {...first, id: '1:1', cashChanges: [{player: 'p1', amount: -2}],
    marketRoll: {...first.marketRoll, adjustment: 'SURPLUS', cancelled: ['STONE']}};

test('neighboring market rolls share one activity row with their recorded facts', () => {
    const rows = activityRows([first, second]);
    assert.deepEqual(rows, [[first, second]]);
    assert.equal(rows[0][0], first);
    assert.equal(rows[0][1], second);
});

test('appending a second roll preserves the original event and row identity', () => {
    const earlier = activityRows([first]);
    const later = activityRows([first, second]);
    assert.equal(later[0][0], earlier[0][0]);
    assert.equal(later[0][0].id, earlier[0][0].id);
});

test('market rolls from different turns keep separate activity rows', () => {
    const later = {...second, turnNumber: first.turnNumber + 1};
    assert.deepEqual(activityRows([first, later]), [[first], [later]]);
});

test('market rolls from different stages keep separate activity rows', () => {
    const later = {...second, stage: 'DRAW_CARD'};
    assert.deepEqual(activityRows([first, later]), [[first], [later]]);
});

test('an intervening action prevents grouping even when it is hidden in Activity', () => {
    const pass = {id: '1:2', kind: 'PASS'};
    assert.deepEqual(activityRows([first, pass, second]), [[first], [pass], [second]]);
});

test('other rolls retain their individual activity rows', () => {
    const competition = {...second, marketRoll: null, detail: 'COMPETITION'};
    assert.deepEqual(activityRows([first, competition]), [[first], [competition]]);
});
