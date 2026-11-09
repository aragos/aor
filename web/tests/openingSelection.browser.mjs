import assert from 'node:assert/strict';
import {join} from 'node:path';
import {open, waitForMap} from './browser-support.mjs';

const api = process.env.MAP_API_URL;
const web = process.env.MAP_WEB_URL;
const artifacts = process.env.AOR_BROWSER_ARTIFACTS;
const page = await open(web + '/play');
try {
    await page.wait(`document.querySelector('#names')`, 'creation form');
    await page.evaluate(`localStorage.setItem('aor.setting.variants', JSON.stringify({
        'deck-modification':'enabled', 'initial-card':'no-events',
        'immediate-master-art-discard':'disabled', 'map':'european'}))`);
    await page.reload();
    await page.wait(`document.querySelector('#variant-draw-three-keep-two')`, 'new variant');
    await page.evaluate(`document.querySelector('.creation-settings > summary').click()`);
    assert.equal(await page.evaluate(`document.querySelector('#variant-initial-card [aria-pressed=true]').textContent`), 'No events in the initial deal');
    assert.equal(await page.evaluate(`document.querySelector('#variant-map [aria-pressed=true]').textContent`), 'European map');
    assert.equal(await page.evaluate(`document.querySelector('#variant-draw-three-keep-two [aria-pressed=true]').textContent`), 'Disabled');
    await page.evaluate(`document.querySelector('#variant-draw-three-keep-two button:last-child').click()`);
    await page.fill('#game-name', 'Opening choice');
    await page.click('Create game');
    await page.wait(`document.querySelectorAll('.seat-links a').length === 3`, 'created seats');
    const links = await page.evaluate(`[...document.querySelectorAll('.seat-links a')].map(a => ({path:a.getAttribute('href'), token:new URLSearchParams(a.hash.slice(1)).get('token')}))`);
    const id = links[0].path.split('/')[2].split('#')[0];
    const snapshot = link => fetch(`${api}/api/games/${id}`, {headers:{Authorization:`Bearer ${link.token}`}}).then(r => r.json());
    const send = async (link, command) => {
        const state = await snapshot(link);
        const response = await fetch(`${api}/api/games/${id}/commands`, {method:'POST',
            headers:{'Content-Type':'application/json', Authorization:`Bearer ${link.token}`},
            body:JSON.stringify({expectedRevision:state.revision, command})});
        assert(response.ok, await response.clone().text());
        return response.json();
    };
    await page.send('Page.navigate', {url:web + links[0].path});
    const opening = await snapshot(links[0]);
    assert.equal(opening.game.stage, 'SETUP_RETURN');
    assert(opening.game.hand.every(card => card.definition.kind !== 'event'));
    await page.wait(`document.querySelectorAll('[data-hand-card]').length === 3`, 'three opening cards');
    await waitForMap(page, opening);
    const reference = opening.game.hand[0].reference;
    await page.evaluate(`document.querySelector('[data-hand-card="${reference}"]').click()`);
    await page.wait(`document.querySelector('.selected-action-confirmation')`, 'separate return confirmation');
    assert.equal((await snapshot(links[0])).revision, opening.revision, 'Selection never commits');
    assert.equal(await page.evaluate(`document.querySelectorAll('.action-buttons button').length`), 0, 'The hand is the sole card selection surface');
    assert.match(await page.evaluate(`document.querySelector('.opening-keep-review').textContent`), /Keep:/);
    await page.evaluate(`document.querySelector('[data-help-topic="unplayed-cards"]').focus(); document.querySelector('[data-help-topic="unplayed-cards"]').click()`);
    await page.wait(`document.querySelector('.reference-popup')`, 'reference dialog');
    await page.evaluate(`document.querySelector('.reference-popup .close-detail').click()`);
    await page.wait(`!document.querySelector('.reference-popup')`, 'reference closed');
    assert.equal(await page.evaluate(`document.activeElement?.getAttribute('data-help-topic')`), 'unplayed-cards');
    assert.equal(await page.evaluate(`document.querySelector('[data-hand-card="${reference}"]').getAttribute('aria-pressed')`), 'true');
    await page.screenshot(join(artifacts, 'opening-desktop.png'), 1280, 900);
    await page.screenshot(join(artifacts, 'opening-phone.png'), 390, 844, true);
    await page.evaluate(`document.querySelector('.selected-action-confirmation').scrollIntoView({block:'nearest'})`);
    assert(await page.evaluate(`(() => {const r = document.querySelector('.selected-action-confirmation').getBoundingClientRect(); return r.top >= 0 && r.bottom <= innerHeight;})()`), 'Phone confirmation is reachable');
    // Exercise a real request rejection without changing the live offers or accepted state.
    await page.evaluate(`window.openingFetch = window.fetch; window.openingPolls = 0;
        window.rejectOpening = true;
        window.fetch = async (...args) => {
            const [url, init] = args;
            if (String(url).includes('/commands') && init?.method === 'POST' && window.rejectOpening) {
                window.rejectOpening = false;
                return new Response(JSON.stringify({code:'INVALID_CARD_RETURN'}), {status:422, headers:{'Content-Type':'application/json'}});
            }
            if (String(url).includes('/api/games/') && (!init?.method || init.method === 'GET')) window.openingPolls++;
            return window.openingFetch(...args);
        }`);
    await page.evaluate(`document.querySelector('.selected-action-confirmation').click()`);
    await page.wait(`document.querySelector('.error')`, 'correctable rejection');
    assert.equal((await snapshot(links[0])).revision, opening.revision);
    assert.equal(await page.evaluate(`document.querySelector('[data-hand-card="${reference}"]').getAttribute('aria-pressed')`), 'true', 'Rejection preserves selection');
    await page.evaluate(`window.dispatchEvent(new Event('pageshow'))`);
    await page.wait(`window.openingPolls > 0`, 'live polling');
    assert.equal(await page.evaluate(`document.querySelector('[data-hand-card="${reference}"]').getAttribute('aria-pressed')`), 'true', 'Polling preserves selection');
    await page.evaluate(`document.querySelector('.selected-action-confirmation').click()`);
    await page.wait(`document.querySelectorAll('[data-hand-card]').length === 2 && !document.querySelector('.selected-action-confirmation')`, 'kept hand and selection cleanup');
    const returned = await snapshot(links[0]);
    const observer = await snapshot(links[1]);
    assert(observer.history.includes('p1: return-card <hidden>'));
    assert.equal(observer.events.find(event => event.kind === 'RETURN_CARD').card, null);
    await page.reload();
    await page.wait(`document.querySelectorAll('[data-hand-card]').length === 2`, 'retained hand after reload');
    assert.equal((await snapshot(links[0])).revision, returned.revision);
    for (const link of links.slice(1)) {
        const own = await snapshot(link);
        await send(link, `return-card ${own.game.hand[0].reference}`);
    }
    await page.wait(`document.querySelector('#bid')`, 'neighboring capital bid');
    await page.fill('#bid', '7');
    await page.evaluate(`document.querySelector('.bid-action').click()`);
    await page.wait(`document.querySelector('.pending-submission')?.textContent.includes('$7')`, 'capital bid after keeping cards');
    await page.screenshot(join(artifacts, 'opening-capital-bid-phone.png'), 390, 844, true);

    const rejected = await fetch(`${api}/api/games`, {method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({players:['A','B','C','D','E','F'],options:{'draw-three-keep-two':'enabled','initial-card':'commodity-only'}})});
    assert.equal(rejected.status, 422);
    assert.equal((await rejected.json()).code, 'INSUFFICIENT_INITIAL_CARDS');
    console.log('Passed: preserved filters, five-option creation, private opening choice, desktop/phone confirmation, reference/focus, rejection/polling preservation, commit cleanup, reload, capital bidding and six-player guard.');
} finally {
    page.ws.close();
    await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
}
