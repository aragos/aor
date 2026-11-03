import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';
import {checkLiveCompetitionLifecycle} from './competitionLifecycle.browser.mjs';
import {checkSpectator} from './spectator.browser.mjs';

export async function checkTurnOrder(page, web) {
    const artifacts = process.env.AOR_BROWSER_ARTIFACTS;
    const help = '.turn-order-help [data-help-topic="turn-order"]';
    const popup = '#turn-order';
    const rows = () => page.evaluate(`[...document.querySelectorAll('${popup} tbody tr')].map(row =>
        [...row.querySelectorAll('th, td')].map(cell => cell.textContent))`);
    const openHelp = async selector => {
        await page.evaluate(`document.querySelector(${JSON.stringify(selector)}).focus(); document.querySelector(${JSON.stringify(selector)}).click()`);
        await page.wait(`document.querySelector('${popup} .order-track')`, 'turn order lookup');
    };
    const escape = async () => {
        await page.send('Page.bringToFront');
        await page.send('Input.dispatchKeyEvent', {type:'keyDown', key:'Escape', code:'Escape', windowsVirtualKeyCode:27, nativeVirtualKeyCode:27});
        await page.send('Input.dispatchKeyEvent', {type:'keyUp', key:'Escape', code:'Escape', windowsVirtualKeyCode:27, nativeVirtualKeyCode:27});
        await page.wait(`!document.querySelector('.reference-dialog')`, 'Escape closes reference');
    };
    async function tokenGame(count) {
        const game = await createGame(count);
        for (const [index, player] of game.links.entries()) await game.send(player, `bid-capital ${count - index}`);
        for (let step = 0; step < 30; step++) {
            const state = await game.snapshot(game.links[0]);
            if (state.game.stage === 'TOKEN_PURCHASE') return game;
            const actor = game.links.find(link => state.prompt?.players.includes(link.id));
            assert(actor, `Setup actor at ${state.game.stage}`);
            const current = await game.snapshot(actor);
            const command = current.legalChoices.commands.find(command => command.startsWith('choose-capital ')) ??
                current.legalChoices.commands.find(command => command.startsWith('return-card '));
            assert(command, `Setup command at ${state.game.stage}`);
            await game.send(actor, command);
        }
        assert.fail('Setup did not reach token purchase');
    }
    // Each game supplies its own printed track, including the distinct 4- and 5-player endings.
    const expected = {3:[2,4,6], 4:[1,3,5,6], 5:[1,2,3,4,5], 6:[1,2,3,4,5,6]};
    for (const count of [3,4,5,6]) {
        const game = await createGame(count);
        const state = await game.snapshot(game.links[0]);
        assert.deepEqual(state.game.catalog.orderTrack.map(box => box.number), expected[count]);
        await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
        await page.wait(`typeof window.setMapSnapshot === 'function'`, 'StrictMode table harness');
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(state)})`);
        await page.wait(`document.querySelector('[data-help-topic="income-track"]')`, 'table reference controls');
        await page.evaluate(`document.querySelector('[data-help-topic="income-track"]').click()`);
        await page.wait(`document.querySelector('.reference-popup > .reference-link')`, 'neighboring income reference');
        await page.evaluate(`document.querySelector('.reference-popup > .reference-link').click()`);
        await page.click('Turn order & green die');
        await page.wait(`document.querySelector('${popup} .order-track')`, 'order topic from chooser');
        assert.deepEqual((await rows()).map(row => Number(row[1])), expected[count]);
        assert.equal(await page.evaluate(`document.querySelectorAll('${popup} .order-track-player').length`), 0);
        assert.match(await page.evaluate(`document.querySelector('${popup}').textContent`), /order is pending/);
        assert.equal((await rows()).at(-1)[2], count === 5 ? '6' : 'No result');
        await escape();
    }

    const game = await tokenGame(4);
    const viewer = game.links[0];
    await page.send('Page.navigate', {url: web + viewer.path});
    await page.wait(`document.querySelector('#bid')`, 'live token purchase');
    await page.fill('#bid', '7');
    const originalLocation = await page.evaluate('location.href');
    await openHelp(help);
    assert.equal(await page.evaluate(`document.querySelectorAll('${popup} .order-track-player').length`), 0);
    assert.deepEqual(await rows(), [['1','1','2–6'],['2','3','4–6'],['3','5','6'],['4','6','No result']]);
    await game.send(game.links[1], 'buy-tokens 8');
    await page.wait(`document.querySelectorAll('.player-status').length === 1`, 'accepted polling while help is open');
    assert.equal(await page.evaluate('location.href'), originalLocation);
    assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7', 'Polling and help retain the unsaved bid');
    assert.equal(await page.evaluate(`document.querySelectorAll('${popup} .order-track-player').length`), 0, 'Other private bids cannot imply assignments');
    await page.screenshot(join(artifacts, 'turn-order-pending-desktop.png'), 1280, 900);
    await page.screenshot(join(artifacts, 'turn-order-pending-phone.png'), 390, 844, true);
    assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
    const dialogFits = await page.evaluate(`(() => { const dialog = document.querySelector('.reference-dialog'); return dialog.scrollWidth <= dialog.clientWidth; })()`);
    assert(dialogFits, 'Printed track fits the phone dialog without horizontal scrolling');
    await escape();
    assert.equal(await page.evaluate(`document.activeElement.matches('${help}')`), true, 'Escape restores the help trigger');
    assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7');
    await page.click('Buy tokens');
    await page.wait(`document.querySelector('.pending-submission')`, 'saved token purchase');
    await game.send(game.links[2], 'buy-tokens 9');
    await game.send(game.links[3], 'buy-tokens 10');
    await page.wait(`document.querySelector('.player-turn-order')`, 'resolved printed boxes');
    assert.match(await page.evaluate(`document.querySelector('.player-summary.is-viewer .player-turn-order').textContent`), /^Box 1/);
    assert.equal(await page.evaluate(`document.querySelector('.player-summary.is-viewer > summary').textContent.includes('Position')`), false,
        'Player boxes show the printed box without a redundant position');
    assert.equal(await page.evaluate(`document.querySelector('.player-summary.is-viewer .player-turn-order').parentElement.className`), 'player-status-row',
        'The printed box shares the pool/submission row');
    assert.match(await page.evaluate(`document.querySelector('.player-summary.is-viewer .player-identity').textContent`), /You/i);
    await page.evaluate(`document.querySelector('#tab-players')?.click()`);
    const viewerHelp = '.player-summary.is-viewer [data-help-topic="turn-order"]';
    await openHelp(viewerHelp);
    assert.equal(await page.evaluate(`document.querySelector('.player-summary.is-viewer').open`), false, 'Box help does not expand player details');
    assert.equal(await page.evaluate(`document.querySelectorAll('${popup} .order-track-player').length`), 4);
    assert.equal(await page.evaluate(`document.querySelectorAll('${popup} .order-track-player .identity-label svg').length`), 4, 'Every capital retains its symbol');
    assert.equal(await page.evaluate(`document.querySelector('${popup} .is-viewer').dataset.orderBox`), '1');
    await page.screenshot(join(artifacts, 'turn-order-current-phone.png'), 390, 844, true);
    await escape();

    // Reuse a real projection to verify live assignment updates without implementing rules in fixtures.
    const resolved = await game.snapshot(viewer);
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'current-order harness');
    await page.evaluate(`window.setMapSnapshot(${JSON.stringify(resolved)})`);
    await page.wait(`document.querySelector('.player-turn-order')`, 'resolved order fixture');
    await page.evaluate(`document.querySelector('#tab-players')?.click()`);
    await openHelp(viewerHelp);
    const swappedOrder = [...resolved.game.turnOrder].reverse();
    const swapped = {...resolved, revision: resolved.revision + 1, game: {...resolved.game,
        turnOrder: swappedOrder, orderBoxes: Object.fromEntries(swappedOrder.map((id, i) => [id, expected[4][i]]))}};
    await page.evaluate(`window.setMapSnapshot(${JSON.stringify(swapped)})`);
    await page.wait(`document.querySelector('${popup} .is-viewer')?.dataset.orderBox === '6'`, 'open lookup follows changed order');
    assert.match(await page.evaluate(`document.querySelector('.player-summary.is-viewer .player-turn-order').textContent`), /^Box 6/);
    await page.screenshot(join(artifacts, 'turn-order-current-desktop.png'), 1280, 900);
    await escape();
    assert.equal(await page.evaluate(`window.mapCommands.length`), 0, 'Reference never submits a game action');

    const tied = await tokenGame(3);
    for (const player of tied.links) await tied.send(player, 'buy-tokens 2');
    await page.send('Page.navigate', {url: web + tied.links[0].path});
    await page.wait(`document.querySelector('.turn-tie')`, 'live unresolved tie');
    await openHelp(help);
    assert.match(await page.evaluate(`document.querySelector('${popup}').textContent`), /order is pending/);
    assert.equal(await page.evaluate(`document.querySelectorAll('${popup} .order-track-player').length`), 0, 'An unresolved tie assigns no boxes');
    await escape();
    console.log('Passed: printed tracks for 3–6 players, pending/tied order, live assignments, preserved bid and attack drafts, reference chooser, focus and phone layout.');
    return resolved;
}

if (process.argv[1]?.endsWith('/turnOrder.browser.mjs')) {
    const page = await open('about:blank');
    try {
        const baseline = await checkTurnOrder(page, process.env.MAP_WEB_URL);
        await checkSpectator(page, process.env.MAP_WEB_URL, baseline);
        await checkLiveCompetitionLifecycle(page, process.env.MAP_WEB_URL);
    } finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
