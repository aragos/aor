import assert from 'node:assert/strict';
import {test} from 'node:test';
import {readFile} from 'node:fs/promises';
import ts from 'typescript';

const source = await readFile(new URL('../src/polling.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, {compilerOptions: {module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020}}).outputText;
const {startPolling} = await import('data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'));
const settle = async () => {for (let turn = 0; turn < 8; turn++) await Promise.resolve();};

function browser(context, hidden = true) {
    const originalDocument = globalThis.document, originalWindow = globalThis.window;
    const document = Object.assign(new EventTarget(), {hidden});
    const window = new EventTarget();
    globalThis.document = document;
    globalThis.window = window;
    context.mock.timers.enable({apis: ['setTimeout']});
    const stops = [];
    context.after(() => {
        stops.forEach(stop => stop());
        globalThis.document = originalDocument; globalThis.window = originalWindow;
    });
    return {document, window, start: options => {
        const stop = startPolling(options); stops.push(stop); return stop;
    }};
}

test('hidden pages continue polling every thirty seconds', async context => {
    const environment = browser(context);
    let reads = 0;
    environment.start({intervalMs: 1000, paused: () => false, poll: async () => {reads++;}, onError: assert.fail});
    await settle();

    context.mock.timers.tick(29999);
    assert.equal(reads, 1);
    context.mock.timers.tick(1);
    await settle();

    assert.equal(reads, 2);
});

test('repeated visibility changes cancel the old read and start only one replacement', async context => {
    const environment = browser(context, false);
    const {document} = environment;
    const reads = [];
    environment.start({intervalMs: 1000, paused: () => false, onError: assert.fail,
        poll: signal => new Promise((resolve, reject) => {
            reads.push({signal, resolve});
            signal.addEventListener('abort', () => reject(new Error('Canceled read')), {once: true});
        })});

    document.hidden = true;
    document.dispatchEvent(new Event('visibilitychange'));
    document.hidden = false;
    document.dispatchEvent(new Event('visibilitychange'));
    await settle();
    context.mock.timers.tick(60000);

    assert.equal(reads.length, 2);
    assert.equal(reads[0].signal.aborted, true);
    assert.equal(reads[1].signal.aborted, false);
});

test('a failed background read is retried on the next polling interval', async context => {
    const environment = browser(context);
    let reads = 0;
    const errors = [];
    const failure = new Error('Offline');
    environment.start({intervalMs: 1000, paused: () => false, onError: error => errors.push(error),
        poll: async () => {if (++reads === 1) throw failure;}});
    await settle();

    context.mock.timers.tick(30000);
    await settle();

    assert.equal(reads, 2);
    assert.deepEqual(errors, [failure]);
});

for (const [target, event] of [['document', 'resume'], ['window', 'online'], ['window', 'pageshow']]) {
    test(`${event} refreshes a hidden page immediately`, async context => {
        const environment = browser(context);
        let reads = 0;
        environment.start({intervalMs: 1000, paused: () => false, onError: assert.fail, poll: async () => {reads++;}});
            await settle();

        environment[target].dispatchEvent(new Event(event));
        await settle();

        assert.equal(reads, 2);
    });
}

test('cleanup aborts the current read and removes timers and wake listeners', async context => {
    const environment = browser(context);
    const reads = [];
    const stop = environment.start({intervalMs: 1000, paused: () => false, onError: assert.fail,
        poll: signal => new Promise((_, reject) => {
            reads.push(signal);
            signal.addEventListener('abort', () => reject(new Error('Canceled read')), {once: true});
        })});

    stop();
    for (const target of [environment.document, environment.window]) {
        for (const event of ['resume', 'online', 'pageshow', 'visibilitychange']) target.dispatchEvent(new Event(event));
    }
    await settle();
    context.mock.timers.tick(90000);

    assert.equal(reads.length, 1);
    assert.equal(reads[0].aborted, true);
});

test('a StrictMode cleanup and setup leave exactly one active polling loop', async context => {
    const environment = browser(context);
    let reads = 0;
    const options = {intervalMs: 1000, paused: () => false, onError: assert.fail, poll: async () => {reads++;}};
    environment.start(options)();
    environment.start(options);
    await settle();

    context.mock.timers.tick(30000);
    await settle();

    assert.equal(reads, 3);
});
