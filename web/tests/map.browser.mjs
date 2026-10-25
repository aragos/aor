import fs from 'node:fs';
import assert from 'node:assert/strict';

const web = process.env.MAP_WEB_URL ?? 'http://127.0.0.1:5191';
const api = process.env.MAP_API_URL ?? 'http://127.0.0.1:8095';
const cdp = process.env.MAP_CDP_URL ?? 'http://127.0.0.1:9351';
class Page {
    constructor(ws) {
        this.ws = ws; this.next = 0; this.pending = new Map();
        ws.addEventListener('message', event => {
            const message = JSON.parse(event.data);
            if (message.id) {
                const pending = this.pending.get(message.id);
                this.pending.delete(message.id);
                if (message.error) pending.reject(new Error(message.error.message)); else pending.resolve(message.result);
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
        return new Promise((resolve, reject) => { const id = ++this.next; this.pending.set(id, {resolve, reject}); this.ws.send(JSON.stringify({id, method, params})); });
    }
    async evaluate(expression) {
        const result = await this.send('Runtime.evaluate', {expression, awaitPromise: true, returnByValue: true});
        if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text);
        return result.result.value;
    }
    async wait(expression, description) {
        for (let attempt = 0; attempt < 100; attempt++) {
            if (await this.evaluate(`Boolean(${expression})`)) return;
            await new Promise(resolve => setTimeout(resolve, 100));
        }
        throw new Error(`Timed out: ${description}`);
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
    async screenshot(path, width = 1280) {
        await this.send('Emulation.setDeviceMetricsOverride', {width, height:1000, deviceScaleFactor:1, mobile:false});
        const result = await this.send('Page.captureScreenshot', {format:'png', captureBeyondViewport:true});
        fs.writeFileSync(path, Buffer.from(result.data, 'base64'));
    }
}
async function open(url) {
    const target = await fetch(cdp + '/json/new?' + encodeURIComponent(url), {method:'PUT'}).then(r=>r.json());
    const page = await Page.connect(target.webSocketDebuggerUrl);
    page.target = target;
    return page;
}



async function createGame(count) {
    const created = await fetch(api + '/api/games', {method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({players: ['Ada', 'Bruno', 'Clara', 'Dario', 'Eva', 'Farid'].slice(0, count)})}).then(r => r.json());
    const links = created.links.map(link => ({...link, token: new URLSearchParams(link.path.split('#')[1]).get('token')}));
    const snapshot = link => fetch(api + '/api/games/' + created.gameId, {headers: {Authorization: 'Bearer ' + link.token}}).then(r => r.json());
    async function send(link, command) {
        const state = await snapshot(link);
        const response = await fetch(api + '/api/games/' + created.gameId + '/commands', {method: 'POST',
            headers: {'Content-Type': 'application/json', Authorization: 'Bearer ' + link.token},
            body: JSON.stringify({expectedRevision: state.revision, command})});
        assert(response.ok, `Engine rejected fixture command ${command}: ${await response.clone().text()}`);
        return response.json();
    }
    return {created, links, snapshot, send};
}

const game = await createGame(6);
await game.send(game.links[0], 'start');
for (const link of game.links) await game.send(link, 'bid-capital 0');
for (let step = 0; step < 100; step++) {
    const first = await game.snapshot(game.links[0]);
    if (first.game.stage === 'EXPANSION') break;
    const actor = game.links.find(link => first.prompt?.players.includes(link.id));
    assert(actor, `No actor during ${first.game.stage}`);
    const state = await game.snapshot(actor);
    const commands = state.legalChoices.commands;
    const command = state.game.stage === 'TOKEN_PURCHASE' ? 'buy-tokens ' + (8 + game.links.indexOf(actor))
        : state.game.stage === 'CAPITAL_CHOICE' ? commands[0]
        : state.game.activity?.purchaseStep === 'SHIPPING' && commands.includes('upgrade-ships') ? 'upgrade-ships'
        : commands.find(c => c === 'pass') ?? commands.find(c => c === 'stabilize cash') ?? commands[0];
    assert(command, 'Fixture must be able to advance');
    await game.send(actor, command);
}
const first = await game.snapshot(game.links[0]);
const actor = game.links.find(link => first.prompt?.players.includes(link.id));
const page = await open(web + actor.path);
try {
    await page.wait(`document.querySelectorAll('[data-map-province]').length === 108`, 'full map');
    const before = await page.snapshot();
    // Hit the new continental extensions, away from labels, with real pointer input.
    for (const [province, x, y] of [
        ['bergen', 1150, -20], ['kiev', 2450, 720], ['sarai', 3240, 850],
        ['cairo', 2430, 2050], ['tripoli', 1510, 2000], ['west-africa', 130, 2020],
    ]) {
        const point = await page.evaluate(`(() => {
            const svg = document.querySelector('.world-map');
            svg.scrollIntoView({block:'center', behavior:'instant'});
            const point = new DOMPoint(${x}, ${y}).matrixTransform(svg.getScreenCTM());
            return {x: point.x, y: point.y};
        })()`);
        await page.send('Input.dispatchMouseEvent', {type:'mousePressed', ...point, button:'left', clickCount:1});
        await page.send('Input.dispatchMouseEvent', {type:'mouseReleased', ...point, button:'left', clickCount:1});
        await page.wait(`document.querySelector('[data-map-province="${province}"][aria-pressed="true"]')`, `${province} frame selection`);
    }
    assert.equal((await page.snapshot()).revision, before.revision, 'Frame selection cannot submit');
    const target = before.legalChoices.commands.find(c => c.startsWith('place 1 ') && before.game.catalog.provinces.some(p => p.id === c.split(' ')[2] && p.satellite)).split(' ')[2];
    const pick = id => page.evaluate(`document.querySelector('[data-map-province="${id}"]').dispatchEvent(new MouseEvent('click',{bubbles:true}))`);
    await pick(target);
    assert.equal((await page.snapshot()).revision, before.revision, 'Inspection cannot submit');
    await page.fill('.map-orders input', '2');
    await page.evaluate(`document.querySelector('.map-orders form button').click()`);
    await page.wait(`document.querySelector('.interactive-board .error')`, 'rejected placement feedback');
    assert.equal((await page.snapshot()).revision, before.revision, 'Rejected order cannot spend pieces');
    assert.equal(await page.evaluate(`document.querySelector('.map-orders input').value`), '2', 'Keep rejected draft');
    await page.fill('.map-orders input', '1');
    await page.evaluate(`document.querySelector('.map-orders form button').click()`);
    await page.wait(`[...document.querySelectorAll('.save-status')].some(e => e.textContent.includes('Saved'))`, 'successful placement');
    const after = await page.snapshot();
    assert.equal(after.revision, before.revision + 1);
    assert(after.game.board.some(p => p.id === target && p.tokens.some(t => t.owner === after.viewerId && t.white === 1)));
    await page.evaluate(`document.querySelector('.expansion-form .map-picker').click()`);
    await page.wait(`document.querySelector('.map-pick-banner')`, 'target picker');
    const next = after.legalChoices.commands.find(c => c.startsWith('place 1 ')).split(' ')[2];
    await page.evaluate(`document.querySelector('[data-map-province="${next}"]').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))`);
    await page.wait(`document.querySelector('.expansion-form .action-fields label:nth-child(2) select').value === '${next}'`, 'keyboard choice reaches form');
    assert.equal((await page.snapshot()).revision, after.revision, 'Picking cannot submit');
    await page.evaluate(`document.querySelector('.map-pick-banner button').click()`);
    await page.fill('.map-toolbar input', 'ivory');
    assert.equal(await page.evaluate(`document.querySelectorAll('.map-province.active:not(.map-filtered)').length`), 4);
    await page.fill('.map-toolbar input', '');
    await page.evaluate(`document.querySelector('[aria-label="Zoom in"]').click()`);
    const zoomed = await page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox')`);
    await page.click('Fit map');
    assert.notEqual(await page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox')`), zoomed);
    await page.send('Emulation.setDeviceMetricsOverride', {width: 390, height: 844, deviceScaleFactor: 1, mobile: true});
    assert.equal(await page.evaluate('document.documentElement.scrollWidth <= innerWidth'), true, 'No mobile overflow');
    await page.screenshot('/private/tmp/aor-map-mobile.png', 390);
    await page.screenshot('/private/tmp/aor-map-desktop.png', 1440);

    for (const [count, expected] of [[3, 67], [4, 83], [5, 96]]) {
        const reduced = await createGame(count);
        await page.send('Page.navigate', {url: web + reduced.links[0].path});
        await page.wait(`document.querySelectorAll('.map-province.active').length === ${expected}`, `${count}-player board`);
        assert.equal(await page.evaluate(`document.querySelectorAll('.map-province.inactive[tabindex]').length`), 0);
    }

    // Use the same components with deterministic interruptions and mixed piece ages.
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'component harness');
    let revision = 500;
    async function show(changes) {
        const fixture = {...after, revision: ++revision, prompt: {messageId: 'prompt.test', players: [after.viewerId]}, ...changes,
            game: {...after.game, ...changes.game}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'fixture render');
        return fixture;
    }
    await show({legalChoices: {commands: ['play hand-pirates']}, game: {stage: 'PLAY_CARDS',
        hand: [{reference: 'hand-pirates', definition: {id: 'pirates-vikings', kind: 'event', messageId: 'card.pirates-vikings', shape: 'SQUARE'}}],
        pirateTargets: {requiredCount: 2, eligibleProvinces: ['barcelona', 'genoa', 'rome']}}});
    await page.fill('#play-card', 'hand-pirates');
    await page.evaluate(`document.querySelector('.play-card-form .map-picker').click()`);
    await pick('barcelona'); await pick('genoa'); await pick('rome');
    assert.equal(await page.evaluate(`document.querySelectorAll('.province-checks input:checked').length`), 2);
    assert.equal(await page.evaluate('window.mapCommands.length'), 0);
    await page.evaluate(`document.querySelector('.play-card-form > button:last-child').click()`);
    assert.equal(await page.evaluate('window.mapCommands.at(-1)'), 'play hand-pirates provinces barcelona genoa');

    await show({legalChoices: {commands: ['cede-war']}, game: {warCession: {winner: 'p2', requiredCount: 2,
        eligibleProvinces: ['genoa', 'china', 'india'], overseasCapacity: 1}}});
    await page.evaluate(`document.querySelector('.decision .map-picker').click()`);
    await page.evaluate(`['china','india','genoa'].forEach(id => document.querySelector('[data-map-province="'+id+'"]').dispatchEvent(new MouseEvent('click',{bubbles:true})))`);
    assert.equal(await page.evaluate(`document.querySelectorAll('.province-checks input:checked').length`), 2);
    await page.evaluate(`document.querySelector('.decision form > button:last-child').click()`);
    assert.equal(await page.evaluate('window.mapCommands.at(-1)'), 'cede-war china genoa');

    await show({legalChoices: {commands: []}, game: {board: [{id: 'toledo', marker: null,
        tokens: [{owner: after.game.players[0].id, coloured: 1, white: 1}, {owner: after.game.players[1].id, coloured: 0, white: 1}]}]}});
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-province-label="toledo"] [data-piece-kind="tokens"]').length`), 3);
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-province-label="toledo"] [data-piece-age="new"]').length`), 2);
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-province-label="toledo"] [data-piece-age="established"]').length`), 1);
    console.log('Passed: continental frame selection, live placement and rejection, draft preservation, inspection, keyboard picking, search, zoom, mobile layout, 3–6 player boards, pirate limits, War overseas limits, mixed owners and ages.');
} finally {page.ws.close();}
