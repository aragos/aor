import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {test} from 'node:test';
import {hardCheckpoint} from '../src/ai/hardCheckpoints.ts';
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

    assert.deepEqual(Object.keys(workers[0].message).sort(), ['difficulty', 'finish', 'id', 'initiative', 'observation', 'policyVersion', 'seed']);
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

const original = 'aor-hard-2026-11-20-1';
const market = 'aor-hard-2026-11-21-1';
const expansion = 'aor-hard-2026-11-22-1';
const transfer = 'aor-hard-2026-11-22-2';

test('only the four-player expansion seat loads the qualified model', async () => {
    const model = hardCheckpoint(expansion, 4);
    const bytes = await readFile(new URL('../public' + model.url, import.meta.url));
    assert.equal(model.market, true);
    assert.equal(createHash('sha256').update(bytes).digest('hex'), model.sha256);
    assert.equal(model.sha256, '06308b32b3ef40bf12c83f9383ed0a306b7bf475f1bb163e6c4f063e429f48eb');
    for (const count of [3, 5, 6]) {
        assert.deepEqual(hardCheckpoint(expansion, count), hardCheckpoint(market, count));
    }
});

test('saved four-player seats select their original market settings and asset', () => {
    const previous = hardCheckpoint(original, 4);
    assert.equal(previous.market, false);
    assert.equal(previous.sha256, '956b39695f744ce835a72af6452e33d061b74479a8b13938e3b968f18ce4556e');
    assert.deepEqual(hardCheckpoint(market, 4), {...previous, market: true});
});

test('the three-player transfer seat loads the qualified model', async () => {
    const model = hardCheckpoint(transfer, 3);
    const bytes = await readFile(new URL('../public' + model.url, import.meta.url));
    assert.equal(model.market, true);
    assert.equal(createHash('sha256').update(bytes).digest('hex'), model.sha256);
    assert.equal(model.sha256, '353af76e3c9207ca7a93297189d3ea9d4be5c6e547cce657ef07215e3c9bb821');
});

test('the five-player transfer seat loads the qualified model', async () => {
    const model = hardCheckpoint(transfer, 5);
    const bytes = await readFile(new URL('../public' + model.url, import.meta.url));
    assert.equal(model.market, true);
    assert.equal(createHash('sha256').update(bytes).digest('hex'), model.sha256);
    assert.equal(model.sha256, 'fb6c21344940db84976161a4f252f6ea8dee36029159f4befed1c61acb721b11');
});

test('transfer version selects the existing four- and six-player checkpoints', () => {
    for (const count of [4, 6]) {
        assert.deepEqual(hardCheckpoint(transfer, count), hardCheckpoint(expansion, count));
    }
});
