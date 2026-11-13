import assert from 'node:assert/strict';
import {test} from 'node:test';
import {createHash} from 'node:crypto';
import {createBotPolicyRunner} from '../src/ai/botPolicy.ts';

const job = {observation: {playerId: 'p2'}, purchase: {stateFeatures: {ownCash: 1}}, difficulty: 'HARD', seed: 19};
const baseline = {weights: {cash: 1}};

test('one immutable actor is parsed for all serial worker jobs', async () => {
    const constructions = [];
    const calls = [];
    let loads = 0;
    class Selector {
        constructor(...args) {constructions.push(args);}
        choose(...args) {calls.push(args); return 'pass';}
    }
    const run = createBotPolicyRunner(async () => {
        loads++;
        return {chooseBotMove: () => 'baseline', PurchaseBotSelector: Selector};
    }, baseline, {model: 'frozen-actor'});

    assert.equal(await run(job), 'pass');
    assert.equal(await run({...job, seed: -1}), 'pass');
    assert.equal(loads, 1);
    assert.deepEqual(constructions, [[JSON.stringify(baseline), '{"model":"frozen-actor"}']]);
    assert.deepEqual(calls[0], [JSON.stringify(job.observation), JSON.stringify(job.purchase), 'HARD', 19]);
    assert.equal(calls[1][3], -1);
});

test('unavailable optional selector preserves the ordinary policy', async () => {
    const calls = [];
    const run = createBotPolicyRunner(async () => ({chooseBotMove: (...args) => {
        calls.push(args);
        return 'baseline';
    }}), baseline);

    assert.equal(await run(job), 'baseline');
    assert.deepEqual(calls, [[JSON.stringify(job.observation), JSON.stringify(baseline), 'HARD', 19]]);
});

test('optional actor construction failure is cached and does not stop subsequent bots', async () => {
    let attempts = 0;
    class BrokenActor {constructor() {attempts++; throw new Error('Invalid optional asset');}}
    const run = createBotPolicyRunner(async () => ({chooseBotMove: () => 'baseline', PurchaseBotSelector: BrokenActor}), baseline, {});

    assert.equal(await run(job), 'baseline');
    assert.equal(await run({...job, seed: 20}), 'baseline');
    assert.equal(attempts, 1);
});

test('optional per-decision failures fall back without disabling later compatible input', async () => {
    class Selector {
        choose(_observation, purchase) {if (!purchase) throw new Error('Missing projection'); return 'learned';}
    }
    const run = createBotPolicyRunner(async () => ({chooseBotMove: () => 'baseline', PurchaseBotSelector: Selector}), baseline, {});

    assert.equal(await run({...job, purchase: undefined}), 'baseline');
    assert.equal(await run(job), 'learned');
});

test('a malformed optional projection is omitted while the ordinary input stays usable', async () => {
    const circular = {}; circular.self = circular;
    const run = createBotPolicyRunner(async () => ({chooseBotMove: () => 'baseline', PurchaseBotSelector: class {
        choose(_observation, purchase) {assert.equal(purchase, null); return 'baseline';}
    }}), baseline, null);

    assert.equal(await run({...job, purchase: circular}), 'baseline');
});

test('joint runner passes only optional actor projections and constructs once', async () => {
    const calls = [];
    let constructions = 0;
    class Joint {
        constructor(...inputs) {constructions++; assert.equal(inputs[2], '{"joint":true}');}
        choose(...inputs) {calls.push(inputs); return 'play card';}
    }
    const run = createBotPolicyRunner(async () => ({chooseBotMove: () => 'ordinary', JointBotSelector: Joint}), baseline, {}, {joint: true});
    const card = {stateFeatures: {ownCard: 1}};

    assert.equal(await run({...job, card}), 'play card');
    assert.equal(await run({...job, card, seed: -1}), 'play card');
    assert.equal(constructions, 1);
    assert.deepEqual(calls[0], [JSON.stringify(job.observation), JSON.stringify(job.purchase), JSON.stringify(card), 'HARD', 19]);
});

test('joint construction failure retains the qualified incumbent purchase actor', async () => {
    const run = createBotPolicyRunner(async () => ({
        chooseBotMove: () => 'ordinary',
        PurchaseBotSelector: class {choose() {return 'incumbent';}},
        JointBotSelector: class {constructor() {throw new Error('Invalid joint bundle');}},
    }), baseline, {}, {});

    assert.equal(await run(job), 'incumbent');
});

test('joint decision failure retains the qualified incumbent purchase actor', async () => {
    const run = createBotPolicyRunner(async () => ({
        chooseBotMove: () => 'ordinary',
        PurchaseBotSelector: class {choose() {return 'incumbent';}},
        JointBotSelector: class {choose() {throw new Error('Invalid projection');}},
    }), baseline, {}, {});

    assert.equal(await run(job), 'incumbent');
});

test('inactive joint asset does not construct a joint selector', async () => {
    const run = createBotPolicyRunner(async () => ({
        chooseBotMove: () => 'ordinary',
        JointBotSelector: class {constructor() {assert.fail('Inactive joint actor constructed');}},
    }), baseline, null, null);

    assert.equal(await run(job), 'ordinary');
});

function expansionAsset(ordinary, purchase, joint) {
    const hash = value => createHash('sha256').update(JSON.stringify(value), 'utf8').digest('hex');
    return {reference: {kind: 'browser-policy-reference-1', ordinarySha256: hash(ordinary),
        purchaseSha256: hash(purchase), jointSha256: hash(joint), supportProfile: null}};
}

test('optional expansion wraps the same cached joint selector only after exact wire binding', async () => {
    const purchase = {purchase: true};
    const joint = {joint: true};
    const calls = [];
    let original;
    let constructions = 0;
    const asset = expansionAsset(baseline, purchase, joint);
    const run = createBotPolicyRunner(async () => ({
        chooseBotMove: () => 'ordinary',
        JointBotSelector: class {constructor() {original = this;} choose() {return 'reference';}},
        ExpansionBotSelector: class {
            constructor(incumbent, serialized) {
                constructions++;
                assert.equal(incumbent, original);
                assert.equal(serialized, JSON.stringify(asset));
            }
            choose(...args) {calls.push(args); return 'place 1 genoa';}
        },
    }), baseline, purchase, joint, asset);
    const expansion = {encoderVersion: 'aor-expansion-observation-2'};

    assert.equal(await run({...job, expansion}), 'place 1 genoa');
    assert.equal(await run({...job, expansion, seed: -1}), 'place 1 genoa');
    assert.equal(constructions, 1);
    assert.deepEqual(calls[0], [JSON.stringify(job.observation), JSON.stringify(job.purchase), null,
        JSON.stringify(expansion), 'HARD', 19]);
});

for (const field of ['ordinarySha256', 'purchaseSha256', 'jointSha256']) {
    test(`mismatched ${field} prevents expansion construction`, async () => {
        const asset = expansionAsset(baseline, {}, {});
        asset.reference[field] = '0'.repeat(64);
        let constructions = 0;
        const run = createBotPolicyRunner(async () => ({
            chooseBotMove: () => 'ordinary',
            JointBotSelector: class {choose() {return 'reference';}},
            ExpansionBotSelector: class {constructor() {constructions++;} choose() {return 'wrong';}},
        }), baseline, {}, {}, asset);

        assert.equal(await run(job), 'reference');
        assert.equal(constructions, 0);
    });
}

test('absent expansion asset preserves the old selector and avoids optional construction', async () => {
    let constructions = 0;
    const run = createBotPolicyRunner(async () => ({
        chooseBotMove: () => 'ordinary',
        JointBotSelector: class {choose() {return 'reference';}},
        ExpansionBotSelector: class {constructor() {constructions++;} choose() {return 'wrong';}},
    }), baseline, {}, {});

    assert.equal(await run({...job, expansion: {}}), 'reference');
    assert.equal(constructions, 0);
});

test('malformed or unrecognized reference preserves the complete joint controller', async () => {
    for (const reference of [null, {}, {kind: 'future'}, {...expansionAsset(baseline, {}, {}).reference, hidden: true}]) {
        let constructions = 0;
        const run = createBotPolicyRunner(async () => ({
            chooseBotMove: () => 'ordinary',
            JointBotSelector: class {choose() {return 'reference';}},
            ExpansionBotSelector: class {constructor() {constructions++;} choose() {return 'wrong';}},
        }), baseline, {}, {}, {reference});

        assert.equal(await run(job), 'reference');
        assert.equal(constructions, 0);
    }
});

test('optional expansion decision failure preserves the existing controller', async () => {
    const run = createBotPolicyRunner(async () => ({
        chooseBotMove: () => 'ordinary',
        JointBotSelector: class {choose() {return 'reference';}},
        ExpansionBotSelector: class {choose() {throw new Error('Bad expansion facts');}},
    }), baseline, {}, {}, expansionAsset(baseline, {}, {}));

    assert.equal(await run(job), 'reference');
});
