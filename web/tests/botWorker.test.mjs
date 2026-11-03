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
