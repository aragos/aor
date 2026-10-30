import fs from 'node:fs';
import assert from 'node:assert/strict';

const api = process.env.MAP_API_URL ?? 'http://127.0.0.1:8095';
const cdp = process.env.MAP_CDP_URL ?? 'http://127.0.0.1:9351';

class CdpError extends Error {
    constructor(message, code, method) {
        super(message);
        this.name = 'CdpError';
        this.code = code;
        this.method = method;
    }
}

// A navigation may replace the document while a read predicate is being evaluated.
// Keep this restricted to protocol errors; a page exception with the same text is a real failure.
function isNavigationContextError(error) {
    return error instanceof CdpError && error.method === 'Runtime.evaluate' && error.code === -32000 &&
        /^(?:Inspected target navigated or closed|Execution context was destroyed(?:, most likely because of a navigation)?|Cannot find context with specified id|Cannot find default execution context)\.?$/.test(error.message);
}

export class Page {
    constructor(ws) {
        this.ws = ws; this.next = 0; this.pending = new Map();
        ws.addEventListener('message', event => {
            const message = JSON.parse(event.data);
            if (message.id) {
                const pending = this.pending.get(message.id);
                this.pending.delete(message.id);
                if (!pending) return;
                if (message.error) pending.reject(new CdpError(message.error.message, message.error.code, pending.method)); else pending.resolve(message.result);
            }
        });
    }
    static async connect(url) {
        const ws = new WebSocket(url);
        await new Promise((resolve, reject) => { ws.addEventListener('open', resolve); ws.addEventListener('error', reject); });
        const page = new Page(ws);
        await page.send('Runtime.enable');
        return page;
    }
    send(method, params = {}) {
        return new Promise((resolve, reject) => {
            const id = ++this.next;
            const timeout = setTimeout(() => { this.pending.delete(id); reject(new Error(`CDP timed out: ${method}`)); }, 15000);
            this.pending.set(id, {method, resolve: value => {clearTimeout(timeout); resolve(value);}, reject: error => {clearTimeout(timeout); reject(error);}});
            this.ws.send(JSON.stringify({id, method, params}));
        });
    }
    async evaluate(expression) {
        const result = await this.send('Runtime.evaluate', {expression, awaitPromise: true, returnByValue: true});
        if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text);
        return result.result.value;
    }
    async wait(expression, description) {
        let navigationError;
        for (let attempt = 0; attempt < 100; attempt++) {
            try {
                if (await this.evaluate(`Boolean(${expression})`)) return;
            } catch (error) {
                if (!isNavigationContextError(error)) throw error;
                navigationError = error;
            }
            await new Promise(resolve => setTimeout(resolve, 100));
        }
        throw new Error(`Timed out: ${description}`, {cause: navigationError});
    }
    async reload() {
        const previous = await this.evaluate('performance.timeOrigin');
        await this.send('Page.reload');
        await this.wait(`performance.timeOrigin !== ${previous} && document.readyState === 'complete'`, 'new document after reload');
    }
    async click(text) {
        const expression = `(() => {const button = [...document.querySelectorAll('button')].find(b => b.textContent === ${JSON.stringify(text)}); if (!button || button.disabled) return false; button.click(); return true;})()`;
        assert.equal(await this.evaluate(expression), true, `Button unavailable: ${text}`);
    }
    async fill(selector, value) {
        await this.evaluate(`(() => { const input = document.querySelector(${JSON.stringify(selector)}); Object.getOwnPropertyDescriptor(Object.getPrototypeOf(input), 'value').set.call(input, ${JSON.stringify(value)}); input.dispatchEvent(new Event('input', {bubbles:true})); input.dispatchEvent(new Event('change', {bubbles:true})); })()`);
    }
    async snapshot() {
        return this.evaluate(`fetch('/api' + location.pathname, {headers:{Authorization:'Bearer ' + new URLSearchParams(location.hash.slice(1)).get('token')}}).then(r=>r.json())`);
    }
    async viewportScreenshot(path) {
        const result = await this.send('Page.captureScreenshot', {format:'png', captureBeyondViewport:false});
        fs.writeFileSync(path, Buffer.from(result.data, 'base64'));
    }
    async screenshot(path, width = 1280, height = 1000, mobile = false) {
        // Hidden player tabs suspend animation frames; focus before waiting for layout.
        await this.send('Page.bringToFront');
        await this.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor:1, mobile});
        await this.evaluate('document.fonts.ready.then(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))');
        const result = await this.send('Page.captureScreenshot', {format:'png', captureBeyondViewport:true});
        fs.writeFileSync(path, Buffer.from(result.data, 'base64'));
    }
}
export async function open(url) {
    const target = await fetch(cdp + '/json/new?' + encodeURIComponent(url), {method:'PUT'}).then(r=>r.json());
    const page = await Page.connect(target.webSocketDebuggerUrl);
    page.target = target;
    return page;
}



export async function createGame(count, options = {}) {
    const created = await fetch(api + '/api/games', {method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({players: ['Ada', 'Bruno', 'Clara', 'Dario', 'Eva', 'Farid'].slice(0, count), ...options})}).then(r => r.json());
    const links = created.links.map(link => ({...link, token: new URLSearchParams(link.path.split('#')[1]).get('token')}));
    const snapshot = link => fetch(api + '/api/games/' + created.gameId, {headers: {Authorization: 'Bearer ' + link.token}}).then(r => r.json());
    async function send(link, command) {
        const state = await snapshot(link);
        const response = await fetch(api + '/api/games/' + created.gameId + '/commands', {method: 'POST',
            headers: {'Content-Type': 'application/json', Authorization: 'Bearer ' + link.token},
            body: JSON.stringify({expectedRevision: state.revision, expectedSubmissionVersion: state.submissionVersion, command})});
        assert(response.ok, `Engine rejected fixture command ${command}: ${await response.clone().text()}`);
        return response.json();
    }
    return {created, links, snapshot, send};
}
