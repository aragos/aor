import assert from 'node:assert/strict';
import {test} from 'node:test';
import {setImmediate} from 'node:timers/promises';
import {Page, resetBrowserSession} from './browser-support.mjs';

class FakeSocket extends EventTarget {
    sent = [];

    constructor(replies) {
        super();
        this.replies = [...replies];
    }

    send(data) {
        const command = JSON.parse(data);
        this.sent.push(command);
        const reply = this.replies.shift();
        assert(reply, `Unexpected CDP command: ${command.method}`);
        queueMicrotask(() => this.dispatchEvent(new MessageEvent('message', {
            data: JSON.stringify({id: command.id, ...reply}),
        })));
    }
}

const value = result => ({result: {result: {value: result}}});
const navigation = message => ({error: {code: -32000, message}});

test('read waits survive document replacement and then evaluate the new document', async context => {
    context.mock.timers.enable({apis: ['setTimeout']});
    const socket = new FakeSocket([
        navigation('Inspected target navigated or closed'),
        value(false),
        value(true),
    ]);
    const page = new Page(socket);
    const waiting = page.wait('document.readyState === "complete"', 'document ready');
    for (let attempt = 0; attempt < 2; attempt++) {
        await setImmediate();
        context.mock.timers.tick(100);
    }
    await waiting;
    assert.equal(socket.sent.length, 3);
    assert(socket.sent.every(command => command.method === 'Runtime.evaluate'));
});

test('read waits report unrelated protocol failures without retrying them', async () => {
    const socket = new FakeSocket([{error: {code: -32601, message: 'Runtime.evaluate is unavailable'}}]);
    const page = new Page(socket);
    await assert.rejects(page.wait('true', 'ready'), /Runtime\.evaluate is unavailable/);
    assert.equal(socket.sent.length, 1);
});

test('read waits do not swallow page exceptions resembling navigation errors', async () => {
    const socket = new FakeSocket([{result: {exceptionDetails: {
        exception: {description: 'Inspected target navigated or closed'},
    }}}]);
    const page = new Page(socket);
    await assert.rejects(page.wait('true', 'ready'), /Inspected target navigated or closed/);
    assert.equal(socket.sent.length, 1);
});

test('a navigation error during an action is not retried', async () => {
    const socket = new FakeSocket([navigation('Execution context was destroyed.')]);
    const page = new Page(socket);
    await assert.rejects(page.click('Confirm'), /Execution context was destroyed/);
    assert.equal(socket.sent.length, 1);
});

test('reload is sent once and waits for a different complete document', async context => {
    context.mock.timers.enable({apis: ['setTimeout']});
    const socket = new FakeSocket([
        value(100),
        {result: {}},
        navigation('Execution context was destroyed, most likely because of a navigation.'),
        value(false),
        value(true),
    ]);
    const page = new Page(socket);
    const reloading = page.reload();
    for (let attempt = 0; attempt < 2; attempt++) {
        await setImmediate();
        context.mock.timers.tick(100);
    }
    await reloading;
    assert.equal(socket.sent.filter(command => command.method === 'Page.reload').length, 1);
    assert(socket.sent.slice(2).every(command => command.params.expression ===
        'Boolean(performance.timeOrigin !== 100 && document.readyState === \'complete\')'));
});

test('closing a socket rejects every pending command immediately', async () => {
    const socket = new EventTarget();
    socket.send = () => {};
    const page = new Page(socket);
    const first = assert.rejects(page.send('Runtime.evaluate'), /CDP connection closed/);
    const second = assert.rejects(page.send('Page.reload'), /CDP connection closed/);
    socket.dispatchEvent(new Event('close'));
    await Promise.all([first, second]);
    assert.equal(page.pending.size, 0);
});

test('a socket error rejects commands without waiting for their timeout', async () => {
    const socket = new EventTarget();
    socket.send = () => {};
    const page = new Page(socket);
    const result = assert.rejects(page.send('Page.reload'), /CDP connection failed/);
    socket.dispatchEvent(new Event('error'));
    await result;
    assert.equal(page.pending.size, 0);
});

test('commands after disconnect fail before trying to send on the closed socket', async () => {
    const socket = new EventTarget();
    socket.send = () => assert.fail('Disconnected socket must not receive commands');
    const page = new Page(socket);
    socket.dispatchEvent(new Event('close'));
    await assert.rejects(page.send('Page.reload'), /CDP connection closed/);
    assert.equal(page.pending.size, 0);
});

test('a synchronous send failure clears the pending command and its timer', async () => {
    const socket = new EventTarget();
    socket.send = () => { throw new Error('Socket is not open'); };
    const page = new Page(socket);
    await assert.rejects(page.send('Page.reload'), /Socket is not open/);
    assert.equal(page.pending.size, 0);
});

test('a connection that never opens times out and closes its socket', async context => {
    context.mock.timers.enable({apis: ['setTimeout']});
    let closed = false;
    const original = globalThis.WebSocket;
    context.after(() => {globalThis.WebSocket = original;});
    globalThis.WebSocket = class extends EventTarget {
        close() {closed = true;}
    };
    const result = assert.rejects(Page.connect('ws://silent'), /CDP connection timed out/);
    context.mock.timers.tick(15000);
    await result;
    assert.equal(closed, true);
});

test('closing a connection before opening fails immediately', async context => {
    let socket;
    let closed = false;
    const original = globalThis.WebSocket;
    context.after(() => {globalThis.WebSocket = original;});
    globalThis.WebSocket = class extends EventTarget {
        constructor() {super(); socket = this;}
        close() {closed = true;}
    };
    const result = assert.rejects(Page.connect('ws://closed'), /CDP connection closed before opening/);
    socket.dispatchEvent(new Event('close'));
    await result;
    assert.equal(closed, true);
});

test('suite reset clears origin storage and closes leftover pages', async context => {
    const requests = [];
    const events = [];
    context.mock.method(globalThis, 'fetch', async url => {
        requests.push(url);
        events.push(url);
        if (url.endsWith('/json/new?about:blank')) {
            return {ok: true, json: async () => ({id: 'reset', webSocketDebuggerUrl: 'ws://page'})};
        }
        return {ok: true, json: async () => [
            {type: 'page', id: 'reset', webSocketDebuggerUrl: 'ws://page'},
            {type: 'page', id: 'one'},
            {type: 'page', id: 'two'},
            {type: 'service_worker', id: 'worker'},
        ]};
    });
    const commands = [];
    let closed = false;
    context.mock.method(Page, 'connect', async url => {
        assert.equal(url, 'ws://page');
        return {send: async (...args) => {commands.push(args); events.push(args[0]);},
            ws: {close: () => {closed = true;}}};
    });
    await resetBrowserSession('http://chrome', ['http://web', 'http://api']);
    assert.deepEqual(commands, [
        ['Storage.clearDataForOrigin', {origin: 'http://web', storageTypes: 'all'}],
        ['Storage.clearDataForOrigin', {origin: 'http://api', storageTypes: 'all'}],
    ]);
    assert.equal(closed, true);
    assert.deepEqual(requests, [
        'http://chrome/json/new?about:blank', 'http://chrome/json/list',
        'http://chrome/json/close/one', 'http://chrome/json/close/two',
    ]);
    assert(events.indexOf('http://chrome/json/close/two') < events.indexOf('Storage.clearDataForOrigin'),
        'Old pages close before storage is cleared, preventing unload and polling writes');
});

test('suite reset closes the CDP socket when clearing storage fails', async context => {
    context.mock.method(globalThis, 'fetch', async url => ({ok: true, json: async () =>
        url.endsWith('/json/list') ? [] : ({id: 'reset', webSocketDebuggerUrl: 'ws://page'})}));
    let closed = false;
    context.mock.method(Page, 'connect', async () => ({
        send: async () => {throw new Error('Storage unavailable');},
        ws: {close: () => {closed = true;}},
    }));
    await assert.rejects(resetBrowserSession('http://chrome', ['http://web']), /Storage unavailable/);
    assert.equal(closed, true);
});

for (const path of ['/json/new?about:blank', '/json/list', '/json/close/old']) {
    test(`suite reset bounds a stalled Chrome HTTP request to ${path}`, async context => {
        context.mock.timers.enable({apis: ['setTimeout']});
        context.mock.method(AbortSignal, 'timeout', milliseconds => {
            assert.equal(milliseconds, 15000);
            const controller = new AbortController();
            setTimeout(() => controller.abort(new DOMException('Chrome HTTP timed out', 'TimeoutError')),
                milliseconds);
            return controller.signal;
        });
        let stalled;
        const started = new Promise(resolve => {stalled = resolve;});
        context.mock.method(globalThis, 'fetch', async (url, options) => {
            if (url.endsWith(path)) {
                return new Promise((_, reject) => {
                    options.signal.addEventListener('abort', () => reject(options.signal.reason), {once: true});
                    stalled();
                });
            }
            return {ok: true, json: async () => url.endsWith('/json/list')
                ? [{type: 'page', id: 'old'}]
                : {id: 'reset', webSocketDebuggerUrl: 'ws://reset'}};
        });
        const rejected = assert.rejects(resetBrowserSession('http://chrome', ['http://web']),
            error => error.name === 'TimeoutError');
        await started;
        context.mock.timers.tick(15000);
        await rejected;
    });
}
