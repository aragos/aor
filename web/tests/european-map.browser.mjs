import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';

export async function checkEuropeanMap(page, web) {
    await page.send('Page.navigate', {url: web + '/'});
    await page.wait(`document.querySelector('#variant-map')`, 'map selection on creation');
    await page.fill('#names', 'Ada\nBruno\nClara\nDario\nEva\nFarid');
    await page.evaluate(`document.querySelector('#variant-map button:last-child').click()`);
    await page.reload();
    await page.wait(`document.querySelector('#variant-map button:last-child[aria-pressed=true]')`, 'European map preference survives reload');
    await page.fill('#names', 'Ada\nBruno\nClara\nDario\nEva\nFarid');
    await page.click('Create game');
    await page.wait(`document.querySelectorAll('.seat-links a').length === 6`, 'six European seats');
    const links = await page.evaluate(`[...document.querySelectorAll('.seat-links a')].map((link, index) => ({
        id: 'p' + (index + 1), path: link.getAttribute('href'),
        token: new URLSearchParams(link.hash.slice(1)).get('token'),
    }))`);
    const id = links[0].path.split('/')[2].split('#')[0];
    const endpoint = process.env.MAP_API_URL + '/api/games/' + id;
    const snapshot = link => fetch(endpoint, {headers: {Authorization: 'Bearer ' + link.token}}).then(response => response.json());
    const send = async (link, command) => {
        const state = await snapshot(link);
        const response = await fetch(endpoint + '/commands', {method: 'POST',
            headers: {'Content-Type': 'application/json', Authorization: 'Bearer ' + link.token},
            body: JSON.stringify({expectedRevision: state.revision, command})});
        assert(response.ok, await response.clone().text());
    };
    assert.equal((await snapshot(links[0])).game.rules.map, 'EUROPEAN');
    await page.send('Page.navigate', {url: web + links[0].path});
    await page.wait(`document.querySelector('.game-table')`, 'European game table');
    await page.click('Start setup');
    await page.wait(`document.querySelector('#bid')`, 'European capital bids');
    for (const [index, link] of links.entries()) await send(link, 'bid-capital ' + (6 - index));
    const capitals = ['paris', 'london', 'hamburg', 'venice', 'genoa', 'barcelona'];
    for (let step = 0; step < 100; step++) {
        const first = await snapshot(links[0]);
        if (first.game.stage === 'EXPANSION') break;
        const actor = links.find(link => first.prompt?.players.includes(link.id));
        assert(actor, `European game must have an actor during ${first.game.stage}`);
        const state = await snapshot(actor);
        const commands = state.legalChoices.commands;
        const index = links.indexOf(actor);
        const command = state.game.stage === 'CAPITAL_CHOICE' ? 'choose-capital ' + capitals[index]
            : state.game.stage === 'TOKEN_PURCHASE' ? 'buy-tokens ' + (8 + index)
            : commands.find(command => command === 'pass') ?? commands.find(command => command === 'stabilize cash') ?? commands[0];
        assert(command, 'European game must advance through ordinary commands');
        await send(actor, command);
    }
    const before = await snapshot(links[0]);
    assert.equal(before.game.stage, 'EXPANSION');
    assert(before.prompt.players.includes(links[0].id), 'Paris expands first');
    assert(before.game.expansionOffers.some(offer => offer.destination === 'loire' && offer.reachable));
    await page.reload();
    await page.wait(`document.querySelectorAll('[data-map-province]').length === 108`, 'European board renders');
    assert.equal(await page.evaluate(`!!document.querySelector('[data-map-province="smolensk"]')`), false);
    await page.evaluate(`document.querySelector('[data-map-province="loire"]').dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true}))`);
    await page.wait(`document.querySelector('.province-inspector h2')?.textContent === 'Loire'`, 'Loire keyboard inspection');
    assert.match(await page.evaluate(`document.querySelector('.province-inspector .map-facts').textContent`), /Wine.*3.*III/);
    assert.equal(await page.evaluate(`document.querySelector('.province-inspector .primary-expansion').disabled`), false);
    await page.evaluate(`document.querySelector('.province-inspector .primary-expansion').click()`);
    await page.wait(`document.querySelector('[data-province-label="loire"] [data-piece-kind="marker"]')`, 'live Loire marker placement');
    const placed = await snapshot(links[0]);
    assert.equal(placed.game.board.find(province => province.id === 'loire')?.marker?.owner, links[0].id);
    assert.equal(placed.game.players[0].poolTokens, before.game.players[0].poolTokens - 3);
    await page.reload();
    await page.wait(`document.querySelector('[data-province-label="loire"] [data-piece-kind="marker"]')`, 'Loire placement survives reload');
    await page.evaluate(`document.querySelector('[data-map-province="crete"]').dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true}))`);
    await page.wait(`document.querySelectorAll('[data-connected-sea]').length === 1`, 'European Crete sea inspection');
    assert.deepEqual(await page.evaluate(`[...document.querySelectorAll('[data-connected-sea]')].map(sea => sea.dataset.connectedSea).sort()`),
        ['CENTRAL_MEDITERRANEAN']);
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-sea]').length`), 10);
    assert.equal(await page.evaluate(`document.querySelector('[data-sea="AEGEAN_SEA"]') !== null`), false);
    await page.evaluate(`document.querySelector('[data-map-province="smyrna"]').dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true}))`);
    await page.wait(`document.querySelectorAll('[data-connected-sea]').length === 2`, 'Smyrna inspection after sea merge');
    assert.deepEqual(await page.evaluate(`[...document.querySelectorAll('[data-connected-sea]')].map(sea => sea.dataset.connectedSea).sort()`),
        ['CENTRAL_MEDITERRANEAN', 'EASTERN_MEDITERRANEAN']);
    if (process.env.AOR_BROWSER_ARTIFACTS) {
        await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-european-map.png'), 1440);
        await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-european-map-phone.png'), 390, 844, true);
    }
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'map switching harness');
    for (const count of [3, 4, 5, 6]) {
        const game = await createGame(count, {options: {map: 'european'}});
        const state = await game.snapshot(game.links[0]);
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(state)})`);
        await page.wait(`document.querySelectorAll('[data-map-province].active').length === ${state.game.catalog.provinces.length}`, 'European player cutoff ' + count);
        assert.equal(await page.evaluate(`!!document.querySelector('[data-map-province="loire"].active')`), count >= 4);
        assert.equal(await page.evaluate(`!!document.querySelector('[data-map-province="smolensk"]')`), false);
    }
    const standard = await createGame(6);
    await page.evaluate(`window.setMapSnapshot(${JSON.stringify(await standard.snapshot(standard.links[0]))})`);
    await page.wait(`document.querySelector('[data-map-province="smolensk"]')`, 'standard geometry after European game');
    assert.equal(await page.evaluate(`!!document.querySelector('[data-map-province="loire"]')`), false);
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-sea]').length`), 11);
    assert.equal(await page.evaluate(`document.querySelector('[data-sea="AEGEAN_SEA"]') !== null`), true);
    console.log('Passed: European creation, saved selection, setup, live Loire placement/reload, changed seas, 3–6 player boards, and standard map isolation.');
}

if (process.argv[1]?.endsWith('/european-map.browser.mjs')) {
    const web = process.env.MAP_WEB_URL;
    const page = await open(web);
    try { await checkEuropeanMap(page, web); }
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
