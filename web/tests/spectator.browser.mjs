import assert from 'node:assert/strict';
import {join} from 'node:path';
import {waitForMap} from './browser-support.mjs';

// Server tests establish audience filtering. This fixture verifies the public table stays inspectable
// without creating a player's private controls, in the same StrictMode harness as normal play.
export async function checkSpectator(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1280, height: 900, deviceScaleFactor: 1, mobile: false});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'spectator table harness');
    const publicSnapshot = {...baseline, revision: 41000, viewerId: null, accountId: null,
        pendingBid: null, submitted: [], submissionReceipts: [], submissionVersion: null, history: [],
        events: baseline.events.map(event => ['DRAW_CARD', 'DISCARD_CARD', 'TRADE_CARD'].includes(event.kind)
            ? {...event, card: null} : event),
        legalChoices: {commands: [], bidMin: null, bidMax: null},
        undo: {...baseline.undo, available: false, fromEntry: null, actions: []},
        game: {...baseline.game, hand: [], cardOffers: [], advanceOffers: [],
            expansionOffers: [], expansionInspections: [], markerReductionOffers: [],
            tokenPurchaseOffers: [], shippingOffer: null, reliefOffers: [],
            cardPurchaseOffer: null, adjustmentOffers: [], patronageOffers: []}};
    await page.evaluate(`window.setMapSnapshot(${JSON.stringify(publicSnapshot)})`);
    await page.wait(`document.querySelector('.spectator-table')`, 'public spectator table');
    await waitForMap(page, publicSnapshot);
    assert.match(await page.evaluate('document.title'), /Spectating/);
    assert.equal(await page.evaluate(`document.querySelector('.spectator-status').textContent`), 'Spectating');
    assert.match(await page.evaluate(`document.querySelector('[aria-label="Spectator view"]').textContent`), /Public information only/);
    assert.equal(await page.evaluate(`document.querySelectorAll('.table-hand, .command-dock, .private-seat-link, .undo-action, .decision-actions button, .decision-panel form, .action-cash, .player-summary.is-viewer').length`), 0,
        'Spectators have no private hand, seat controls, commands, undo, action form or own-player marker');
    assert.equal(await page.evaluate(`document.querySelectorAll('.player-summary').length`), baseline.game.players.length,
        'All public player summaries remain visible');
    if (baseline.game.turnOrder.length) {
        await page.evaluate(`document.querySelector('.player-turn-order .help-link').click()`);
        await page.wait(`document.querySelector('#turn-order .order-track')`, 'spectator turn-order reference');
        assert.equal(await page.evaluate(`document.querySelectorAll('#turn-order .order-track-player').length`), baseline.game.players.length);
        assert.equal(await page.evaluate(`document.querySelectorAll('#turn-order .is-viewer').length`), 0,
            'Public turn-order reference has no own-player marker');
        await page.evaluate(`document.querySelector('.reference-dialog .close-detail').click()`);
    }
    const favicon = await page.evaluate(`document.querySelector('link[rel=icon]').href`);
    assert.match(await fetch(favicon).then(response => response.text()), /#777/, 'Spectator favicon remains grayscale');
    if (baseline.prompt) {
        assert.match(await page.evaluate(`document.querySelector('.decision-status').textContent`), /Waiting for/);
    }
    const click = selector => page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
    await click('#tab-markets');
    assert.equal(await page.evaluate(`document.querySelectorAll('.market-tile').length`), baseline.game.commodityPayouts.length);
    await click('#tab-advances');
    assert.equal(await page.evaluate(`document.querySelectorAll('.advance-category > h3 > small, #tab-advances > small').length`), 0,
        'Public advance browsing does not imply a spectator owns zero advances');
    const advance = baseline.game.catalog.advances[0];
    await click(`[data-advance="${advance.id}"]`);
    await page.wait(`document.querySelector('.advance-inspection')`, 'spectator advance inspection');
    assert.equal(await page.evaluate(`document.querySelector('.buy-advance-action')`), null,
        'Advance inspection offers no purchase action');
    await page.evaluate(`window.setMapSnapshot(${JSON.stringify({...publicSnapshot, revision: 41001})})`);
    await page.wait(`document.querySelector('[data-test-revision="41001"]')`, 'spectator refresh');
    assert.equal(await page.evaluate(`!!document.querySelector('.advance-inspection')`), true,
        'Polling preserves spectator inspection');
    await click('.advance-inspection .close-detail');
    const leader = await page.evaluate(`document.querySelector('.unplayed-leader')?.dataset.unplayedLeader`);
    if (leader) {
        await click(`[data-unplayed-leader="${leader}"]`);
        await page.wait(`document.querySelector('.card-inspection-dialog')`, 'spectator leader reference');
        assert.equal(await page.evaluate(`document.querySelector('.play-card-action')`), null);
        await click('.card-inspection-dialog .close-detail');
    }
    await click('#tab-map');
    const province = baseline.game.catalog.provinces.find(candidate =>
        baseline.game.catalog.landBorders.some(border => border.first === candidate.id || border.second === candidate.id));
    await page.evaluate(`document.querySelector('[data-map-province="${province.id}"]').dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', bubbles:true}))`);
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-province-outline][data-highlight-priority="1"]').length > 0`), true,
        'Spectators can inspect province connections using the map');
    await click('#tab-activity');
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-event-id]').length`), baseline.events.filter(event => event.kind !== 'PASS').length,
        'Public activity remains available');
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS ?? '/private/tmp', 'aor-spectator-desktop.png'), 1280, 900);
    await page.send('Emulation.setDeviceMetricsOverride', {width: 390, height: 844, deviceScaleFactor: 1, mobile: true});
    await page.wait(`document.querySelector('#tab-players')`, 'mobile spectator players tab');
    await click('#tab-players');
    await click('.player-summary > summary');
    await page.wait(`document.querySelector('.player-summary[open] .player-detail')`, 'public player details');
    const inspectedPlayer = await page.evaluate(`document.querySelector('.player-summary[open]').dataset.player`);
    assert.equal(await page.evaluate(`document.querySelector('.player-summary[open] .written-cash dd').textContent`),
        `$${baseline.game.players.find(player => player.id === inspectedPlayer).writtenCash}`);
    await click('.player-summary > summary');
    await click('#tab-map');
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS ?? '/private/tmp', 'aor-spectator-phone.png'), 390, 844, true);
    assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth + 1`), true,
        'The public table fits a phone');
    assert.deepEqual(await page.evaluate('window.mapCommands'), [], 'Spectator inspection never submits a command');
    console.log('Passed: public spectator table, player/map/advance/card inspection, polling, activity and phone layout.');
}
