import assert from 'node:assert/strict';
import {test} from 'node:test';
import {decisionIdentity, targetSelection} from '../src/decision.ts';

const baseline = {gameId:'game',viewerId:'p1',revision:1,prompt:{messageId:'prompt.expand',players:['p1','p2']},
    game:{turnNumber:1,stage:'EXPANSION',activity:{actor:'p1',decisionPlayer:'p1',choice:'expand',decisionEntry:3}}};

test('private readiness and polling preserve the committed decision identity', () => {
    assert.equal(decisionIdentity({...baseline,revision:2,prompt:{...baseline.prompt,players:['p2']}}), decisionIdentity(baseline));
});

test('nested replacements open a new decision even with the same actor and prompt', () => {
    assert.notEqual(decisionIdentity({...baseline,game:{...baseline.game,activity:{...baseline.game.activity,decisionEntry:4}}}), decisionIdentity(baseline));
});

test('rapid target picks respect both total count and the combined overseas cap', () => {
    const eligible = ['genoa','rome','china','india'];
    const policy = targetSelection([],eligible,2,[{eligible:['china','india'],capacity:1}]);
    const first = policy.toggle('china',[]);
    const second = policy.toggle('india',first);
    const third = policy.toggle('genoa',second);
    assert.deepEqual(second,['china']);
    assert.deepEqual(policy.toggle('rome',third),['china','genoa']);
});

test('a lost target stays correctable and blocks submission', () => {
    const selected = ['genoa','china'];
    const policy = targetSelection(selected,['genoa','rome'],2);
    assert.equal(policy.invalid,true);
    assert.equal(policy.valid,false);
    assert.deepEqual(policy.toggle('china',selected),['genoa']);
});
