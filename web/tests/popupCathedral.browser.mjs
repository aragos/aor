import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open, waitForMap} from './browser-support.mjs';

export async function checkPopupCathedral(page, web) {
    const game = await createGame(3);
    const baseline = await game.snapshot(game.links[0]);
    const settle = () => page.evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
    const click = selector => page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
    const layout = () => page.evaluate(`(() => {
        const bounds = selector => {
            const {x, y, width, height} = document.querySelector(selector).getBoundingClientRect();
            return {x, y, width, height};
        };
        return {scrollX, scrollY, height: document.documentElement.scrollHeight,
            shell: bounds('.game-shell'), overview: bounds('.table-overview'),
            workspace: bounds('.workspace-main'), decision: bounds('.decision-panel'),
            map: document.querySelector('.world-map').getAttribute('viewBox')};
    })()`);

    for (const [width, height, mobile] of [[1280, 900, false], [390, 844, true]]) {
        await page.send('Page.bringToFront');
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
        await page.wait(`typeof window.setMapSnapshot === 'function'`, 'fresh StrictMode harness');
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(baseline)})`);
        await waitForMap(page, baseline);
        await page.fill('#bid', '7');
        await settle();
        const before = await layout();
        let paused;
        const intercept = event => {
            const message = JSON.parse(event.data);
            if (message.method === 'Fetch.requestPaused') paused = message.params.requestId;
        };
        page.ws.addEventListener('message', intercept);
        await page.send('Fetch.enable', {patterns: [{urlPattern: '*ReferencePopup.tsx*', requestStage: 'Request'}]});
        try {
            await page.evaluate(`document.querySelector('.game-header [data-help-topic="unplayed-cards"]').focus(); document.activeElement.click()`);
            await page.wait(`document.querySelector('.reference-dialog[open] [role=status]')`, 'loading stays in a modal');
            assert(paused, 'The first reference module download is held while checking layout');
            await settle();
            assert.deepEqual(await layout(), before, 'Lazy popup loading cannot resize or scroll the page');
            await page.send('Fetch.continueRequest', {requestId: paused});
            paused = undefined;
            await page.wait(`document.querySelector('.reference-popup')`, 'loaded reference');
            await settle();
            assert.deepEqual(await layout(), before, 'Loaded popup keeps the table and map stable');
            await click('.reference-popup .close-detail');
            await page.wait(`!document.querySelector('.reference-dialog')`, 'popup closes');
            assert.equal(await page.evaluate(`document.activeElement.matches('.game-header [data-help-topic="unplayed-cards"]')`), true,
                'Focus returns to the opener even after delayed loading');
            assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7', 'Reference preserves the live bid draft');
            assert.deepEqual(await layout(), before, 'Closing the first popup keeps layout stable');
        } finally {
            if (paused) await page.send('Fetch.continueRequest', {requestId: paused});
            await page.send('Fetch.disable');
            page.ws.removeEventListener('message', intercept);
        }

        const [owner, opponent, otherOwner] = baseline.game.players;
        const pairs = [{owner: owner.id, opponent: opponent.id, used: true},
            {owner: otherOwner.id, opponent: opponent.id, used: false}];
        const fixture = {...baseline, revision: baseline.revision + 1000, game: {...baseline.game,
            players: baseline.game.players.map(player => ({...player,
                advances: player.id === opponent.id ? [] : ['CATHEDRAL']})), cathedralPairs: pairs}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
        await click('#tab-advances');
        await click('[data-advance="CATHEDRAL"]');
        await page.wait(`document.querySelector('.cathedral-usage')`, 'Cathedral inspection');
        const cell = ownerId => `[data-cathedral-owner="${ownerId}"][data-cathedral-opponent="${opponent.id}"]`;
        assert.equal(await page.evaluate(`document.querySelector(${JSON.stringify(cell(owner.id))}).textContent`), 'Used');
        assert.equal(await page.evaluate(`document.querySelector(${JSON.stringify(cell(otherOwner.id))}).textContent`), 'Unused');
        assert.equal(await page.evaluate(`document.querySelectorAll('.cathedral-usage tbody td').length`), 2,
            'Only owner/non-owner pairs appear');
        assert.equal(await page.evaluate(`document.querySelectorAll('.cathedral-usage .identity-label svg').length`), 0,
            'Setup players have handles instead of invented capitals');
        const assigned = {...fixture, revision: fixture.revision + 1, game: {...fixture.game,
            players: fixture.game.players.map((player, index) => ({...player, capital: ['venice', 'genoa', 'barcelona'][index]}))}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(assigned)})`);
        await page.wait(`document.querySelectorAll('.cathedral-usage .identity-label svg').length === 3`, 'Capital symbols on all labels');
        assert.equal(await page.evaluate(`(() => {
            const table = document.querySelector('.cathedral-usage table').getBoundingClientRect();
            const panel = document.querySelector('.inspection-panel').getBoundingClientRect();
            return table.top >= panel.top && table.bottom <= panel.bottom;
        })()`), true, 'The complete usage table is visible when Cathedral details open');
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-cathedral-${width}.png`));
        assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`), true, 'Tracker fits the page');
        const reset = {...assigned, revision: assigned.revision + 1, game: {...assigned.game,
            cathedralPairs: pairs.map(pair => ({...pair, used: false}))}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(reset)})`);
        await page.wait(`document.querySelectorAll('[data-cathedral-used="false"]').length === 2`, 'Open tracker follows accepted turn reset or rewind');
        await click('.advance-inspection .close-detail');
        await click('#tab-map');
        assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7', 'Advance inspection and polling preserve the draft');
    }
    console.log('Passed: delayed first popup preserves layout, focus, map and live bid draft at desktop and phone widths; Cathedral tracking shows directed owner/non-owner usage, retains capital symbols and follows public updates.');
}

if (process.argv[1]?.endsWith('/popupCathedral.browser.mjs')) {
    const page = await open('about:blank');
    try {
        await checkPopupCathedral(page, process.env.MAP_WEB_URL);
    } finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
