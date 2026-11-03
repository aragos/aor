import assert from 'node:assert/strict';
import {test} from 'node:test';
import {startBotRunner} from '../src/ai/botRunner.ts';

const settle = async () => {for (let turn = 0; turn < 30; turn++) await Promise.resolve();};
const job = seat => ({seat, difficulty: 'HARD', seed: 7, decisionId: `decision-${seat}`, submissionVersion: null, observation: {playerId: seat}});
const work = (observations = [], extra = {}) => ({revision: 2, epoch: 3, leaseUntil: Date.now() + 30000, paused: false, observations, operationReceipts: [], ...extra});
const failure = code => Object.assign(new Error(code), {code});
function deferred() {
    let resolve;
    const promise = new Promise(complete => {resolve = complete;});
    return {promise, resolve};
}
function fixture(context, overrides = {}) {
    context.mock.timers.enable({apis: ['setTimeout', 'Date']});
    const states = [], submitted = [], leases = [];
    let nextId = 0;
    const dependencies = {
        runner: async operation => {leases.push(operation); return work();},
        work: async () => work(),
        submit: async (state, decision, command, operationId) => {submitted.push({state, decision, command, operationId}); return work();},
        think: async decision => `answer ${decision.seat}`,
        changed: async () => {}, suspended: () => false,
        status: state => states.push(state), uuid: () => `operation-${++nextId}`,
        ...overrides,
    };
    const runner = startBotRunner(3, dependencies);
    context.after(async () => {runner.stop(); await runner.done;});
    return {runner, states, submitted, leases};
}

test('one runner computes and submits multiple bot seats serially', async context => {
    const order = [];
    let computations = 0;
    const first = job('p2'), second = job('p3');
    const {submitted} = fixture(context, {
        runner: async () => work([first, second]),
        think: async decision => {
            assert.equal(++computations, 1);
            order.push(decision.seat);
            await Promise.resolve(); --computations;
            return `answer ${decision.seat}`;
        },
        submit: async (state, decision, command) => {
            submitted.push({decision, command});
            return work(decision.seat === 'p2' ? [second] : [], {revision: state.revision + 1});
        },
    });
    await settle();

    assert.deepEqual(order, ['p2', 'p3']);
    assert.deepEqual(submitted.map(entry => entry.command), ['answer p2', 'answer p3']);
});

test('a stopped StrictMode instance cannot publish or submit a late lease response', async context => {
    const pending = deferred();
    const {runner, states, submitted} = fixture(context, {runner: () => pending.promise});

    runner.stop();
    pending.resolve(work([job('p2')]));
    await runner.done;

    assert.deepEqual(states, ['starting']);
    assert.equal(submitted.length, 0);
});

test('human mutations discard a decision computed while the human is busy', async context => {
    const pending = deferred();
    let busy = false;
    const {submitted, runner} = fixture(context, {
        runner: async () => work([job('p2')]), think: () => pending.promise,
        suspended: () => busy,
    });
    await settle();
    busy = true;
    pending.resolve('old answer');
    await settle();

    assert.equal(submitted.length, 0);
    runner.stop();
});

test('a canceled worker result never becomes a command', async context => {
    const pending = deferred();
    const {runner, submitted} = fixture(context, {
        runner: async () => work([job('p2')]), think: () => pending.promise,
    });
    await settle();
    runner.stop();
    pending.resolve('obsolete command');
    await runner.done;

    assert.equal(submitted.length, 0);
});

test('a lost response reconciles the operation receipt without resubmitting', async context => {
    let submissions = 0;
    const {states} = fixture(context, {
        runner: async () => work([job('p2')]),
        submit: async () => {submissions++; throw failure('OUTCOME_UNKNOWN');},
        work: async () => work([], {operationReceipts: ['operation-1']}),
    });
    await settle();

    assert.equal(submissions, 1);
    assert.equal(states.at(-1), 'waiting');
});

test('an unconfirmed operation stops for explicit resumption', async context => {
    let submissions = 0;
    const {runner, states} = fixture(context, {
        runner: async () => work([job('p2')]),
        submit: async () => {submissions++; throw failure('OUTCOME_UNKNOWN');},
        work: async () => work([job('p2')]),
    });
    await runner.done;

    assert.equal(submissions, 1);
    assert.equal(states.at(-1), 'uncertain');
});

test('a stale command obtains a fresh observation before computing again', async context => {
    const old = job('p2'), fresh = {...job('p2'), decisionId: 'replacement'};
    const decisions = [];
    let submissions = 0;
    fixture(context, {
        runner: async () => work([old]),
        work: async () => work([fresh], {revision: 8}),
        think: async decision => {decisions.push(decision.decisionId); return decision.decisionId;},
        submit: async state => {
            if (++submissions === 1) throw failure('STALE_REVISION');
            assert.equal(state.revision, 8);
            return work();
        },
    });
    await settle();

    assert.deepEqual(decisions, ['decision-p2', 'replacement']);
    assert.equal(submissions, 2);
});

test('a history-paused response stops the runner without thinking', async context => {
    let thoughts = 0;
    const {runner, states} = fixture(context, {
        runner: async () => work([job('p2')], {paused: true, epoch: 4}),
        think: async () => {thoughts++; return 'pass';},
    });
    await runner.done;

    assert.equal(thoughts, 0);
    assert.equal(states.at(-1), 'paused');
});

test('a competing tab uses bounded lease retries', async context => {
    let claims = 0;
    const {states} = fixture(context, {
        runner: async () => {claims++; throw failure('BOT_RUNNER_BUSY');},
    });
    await settle();
    context.mock.timers.tick(9999);
    await settle();
    assert.equal(claims, 1);

    context.mock.timers.tick(1);
    await settle();

    assert.equal(claims, 2);
    assert.equal(states.at(-1), 'other');
});

test('an idle runner renews its lease before it expires', async context => {
    const {leases} = fixture(context);
    await settle();
    context.mock.timers.tick(20000);
    await settle();

    assert.deepEqual(leases, ['claim', 'renew']);
});

test('a suspended browser reclaims an expired lease before asking for another decision', async context => {
    const operations = [];
    let reads = 0;
    fixture(context, {
        runner: async operation => {operations.push(operation); return work();},
        work: async () => {if (++reads === 1) throw failure('BOT_RUNNER_EXPIRED'); return work();},
    });
    await settle();
    context.mock.timers.tick(2000);
    await settle();

    assert.deepEqual(operations, ['claim', 'claim']);
});

test('a failed table refresh does not retry an already acknowledged command', async context => {
    let submissions = 0;
    const {states} = fixture(context, {
        runner: async () => work([job('p2')]),
        submit: async () => {submissions++; return work();},
        changed: async () => {throw failure('NETWORK');},
    });
    await settle();

    assert.equal(submissions, 1);
    assert.equal(states.at(-1), 'waiting');
});
