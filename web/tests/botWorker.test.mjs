import assert from 'node:assert/strict';
import {test} from 'node:test';
import {createBotWorkerClient} from '../src/ai/botWorkerClient.ts';

function fixture(context) {
    const original = globalThis.Worker;
    const workers = [];
    class FakeWorker extends EventTarget {
        constructor() {super(); workers.push(this);}
        terminate() {this.terminated = true;}
        postMessage(message) {this.message = message;}
        answer(command) {this.dispatchEvent(new MessageEvent('message', {data: {id: this.message.id, command}}));}
    }
    globalThis.Worker = FakeWorker;
    const client = createBotWorkerClient();
    context.after(() => {client.stop(); globalThis.Worker = original;});
    return {client, workers};
}

const job = {seat: 'p2', difficulty: 'HARD', seed: 19, decisionId: 'next', submissionVersion: 'private', observation: {playerId: 'p2', hand: ['own-card']}};

test('worker receives only policy input and never authentication or transport receipts', async context => {
    const {client, workers} = fixture(context);
    const result = client.think(job, new AbortController().signal);

    assert.deepEqual(Object.keys(workers[0].message).sort(), ['difficulty', 'id', 'observation', 'seed']);
    assert.deepEqual(workers[0].message.observation, job.observation);
    workers[0].answer('choose-capital genoa');

    assert.equal(await result, 'choose-capital genoa');
});

test('canceling a decision terminates synchronous work and discards its later answer', async context => {
    const {client, workers} = fixture(context);
    const signal = new AbortController();
    const result = client.think(job, signal.signal);
    const rejected = assert.rejects(result, /canceled/);

    signal.abort();
    workers[0].answer('obsolete answer');

    await rejected;
    assert.equal(workers[0].terminated, true);
});

test('one worker is reused for serial decisions', async context => {
    const {client, workers} = fixture(context);
    const signal = new AbortController().signal;
    const first = client.think(job, signal);
    workers[0].answer('first');
    await first;
    const second = client.think({...job, seat: 'p3'}, signal);
    workers[0].answer('second');

    assert.equal(await second, 'second');
    assert.equal(workers.length, 1);
});

test('worker receives the optional safe purchase projection without coordinator metadata', async context => {
    const {client, workers} = fixture(context);
    const purchase = {encoderVersion: 'aor-purchase-observation-1', stateFeatures: {}, actions: []};
    const result = client.think({...job, purchase}, new AbortController().signal);

    assert.deepEqual(Object.keys(workers[0].message).sort(), ['difficulty', 'id', 'observation', 'purchase', 'seed']);
    assert.deepEqual(workers[0].message.purchase, purchase);
    workers[0].answer('pass');

    assert.equal(await result, 'pass');
});

test('canceling an actor decision creates a fresh worker for the next request', async context => {
    const {client, workers} = fixture(context);
    const controller = new AbortController();
    const first = client.think({...job, purchase: {}}, controller.signal);
    const rejected = assert.rejects(first, /canceled/);
    controller.abort();
    await rejected;

    const second = client.think(job, new AbortController().signal);
    workers[0].answer('obsolete learned answer');
    workers[1].answer('current baseline');

    assert.equal(await second, 'current baseline');
    assert.equal(workers[0].terminated, true);
    assert.equal(workers.length, 2);
});

test('worker receives optional expansion facts without controller metadata', async context => {
    const {client, workers} = fixture(context);
    const expansion = {encoderVersion: 'aor-expansion-observation-2', stateFeatures: {}, actions: []};
    const result = client.think({...job, expansion}, new AbortController().signal);

    assert.deepEqual(Object.keys(workers[0].message).sort(), ['difficulty', 'expansion', 'id', 'observation', 'seed']);
    assert.deepEqual(workers[0].message.expansion, expansion);
    workers[0].answer('pass');

    assert.equal(await result, 'pass');
});
