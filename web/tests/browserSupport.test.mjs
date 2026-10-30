import assert from 'node:assert/strict';
import {test} from 'node:test';
import {Page} from './browser-support.mjs';

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

test('read waits survive document replacement and then evaluate the new document', async () => {
    const socket = new FakeSocket([
        navigation('Inspected target navigated or closed'),
        value(false),
        value(true),
    ]);
    const page = new Page(socket);
    await page.wait('document.readyState === "complete"', 'document ready');
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

test('reload is sent once and waits for a different complete document', async () => {
    const socket = new FakeSocket([
        value(100),
        {result: {}},
        navigation('Execution context was destroyed, most likely because of a navigation.'),
        value(false),
        value(true),
    ]);
    const page = new Page(socket);
    await page.reload();
    assert.equal(socket.sent.filter(command => command.method === 'Page.reload').length, 1);
    assert(socket.sent.slice(2).every(command => command.params.expression ===
        'Boolean(performance.timeOrigin !== 100 && document.readyState === \'complete\')'));
});
