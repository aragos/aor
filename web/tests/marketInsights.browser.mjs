import {waitForMap} from './browser-support.mjs';
import assert from 'node:assert/strict';
import {join} from 'node:path';

export async function checkMarketInsights(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'market insights harness');
    const viewer = baseline.viewerId;
    const availability = baseline.game.unplayedAdvanceLeaders.find(availability =>
        availability.epochs.some(epoch => epoch.leaders.length > 1));
    assert(availability, 'Live projection supplies matching leader identities');
    const advance = baseline.game.catalog.advances.find(advance => advance.id === availability.advance);
    const expected = availability.epochs.flatMap(epoch => epoch.leaders.map(leader => ({
        leader, epoch: ['I', 'II', 'III'][epoch.epoch - 1],
    })));
    const fur = baseline.game.catalog.commodities.find(commodity => commodity.id === 'FUR');
    let revision = 12000;
    const fixture = {...baseline, prompt: {messageId: 'prompt.play-cards', players: [viewer]},
        legalChoices: {commands: ['play fur commodity fur', 'pass']}, game: {...baseline.game,
            stage: 'PLAY_CARDS',
            hand: [{reference: 'fur', definition: {id: 'fur', kind: 'commodity', messageId: 'card.fur', shape: 'CIRCLE'}}],
            cardOffers: [{reference: 'fur', playable: true, blockers: [], commodities: ['FUR'], leaderDiscounts: [],
                choices: [{kind: 'COMMODITY', value: 'fur', command: 'play fur commodity fur'}],
                provinceTargets: [], requiredTargetCount: null, protectionMax: null}],
            commodityPayouts: [{commodity: 'FUR', adjustments: ['SHORTAGE', 'SHORTAGE', 'SURPLUS'],
                owners: [{player: viewer, provinces: ['novgorod'], marketCount: 1, effectiveBox: 2, payout: fur.printedPayouts[1], industryBonus: 0}]}],
        }};
    async function show(changes = {}) {
        const snapshot = {...fixture, ...changes, revision: ++revision, game: {...fixture.game, ...changes.game}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(snapshot)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'market fixture render');
        await waitForMap(page, snapshot);
    }
    await show();
    assert.match(await page.evaluate(`document.querySelector('[data-hand-card="fur"] .adjustment-badge.SHORTAGE').textContent`), /Shortage.*×2/i);
    assert.match(await page.evaluate(`document.querySelector('[data-hand-card="fur"] .adjustment-badge.SURPLUS').textContent`), /Surplus.*×1/i);
    await page.evaluate(`document.querySelector('#tab-markets').click()`);
    assert.equal(await page.evaluate(`document.querySelector('.market-tile button[data-help-topic="commodity-fur"]').getAttribute('aria-haspopup')`), 'dialog', 'Full market boxes open as reference');
    assert.equal(await page.evaluate(`document.querySelector('.market-tile .payout-box')`), null, 'Static boxes leave the game view');
    await page.evaluate(`document.querySelector('[data-hand-card="fur"]').click()`);
    await page.wait(`document.querySelector('.table-hand button[data-help-topic="commodity-fur"]')`, 'Selected commodity reference');
    assert.equal(await page.evaluate(`document.querySelector('.table-hand button[data-help-topic="commodity-fur"]').getAttribute('aria-haspopup')`), 'dialog');
    assert.match(await page.evaluate(`document.querySelector('.hand-card .adjustment-badge.SHORTAGE').textContent`), /Shortage/i);
    assert.equal(await page.evaluate(`document.querySelector('.card-inspection-dialog .card-decision')`), null, 'Commodity cards do not create an inspection box');
    await page.evaluate(`document.querySelector('#tab-advances').click()`);
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false});
    await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-unplayed-leader-circles.png'));
    const circles = await page.evaluate(`[...document.querySelector('[data-advance="${advance.id}"]').parentElement.querySelectorAll('.unplayed-leader')].map(button => ({leader: button.dataset.unplayedLeader, epoch: button.textContent.trim()}))`);
    assert.deepEqual(circles, expected, 'One circle per authoritative leader, labeled with its printed epoch');
    assert.equal(await page.evaluate(`document.querySelector('.advance-tile .unplayed-leader')`), null, 'Leader inspection controls are separate from the advance selection button');
    const owners = baseline.game.players.map(player => ({...player, advances: [advance.id]}));
    const unowned = baseline.game.catalog.advances.find(candidate => candidate.id !== advance.id);
    for (const [width, height, mobile] of [[1280, 900, false], [390, 844, true]]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        await show({game: {players: owners}});
        const symbols = `[data-advance="${advance.id}"] .advance-owner-symbols`;
        assert.deepEqual(await page.evaluate(`[...document.querySelectorAll('${symbols} [data-piece-owner]')].map(symbol => symbol.dataset.pieceOwner)`),
            owners.map(player => player.capital), 'Every owner has their capital symbol, including other players');
        assert.deepEqual(await page.evaluate(`[...document.querySelectorAll('${symbols} > [title]')].map(owner => ({title: owner.title, text: owner.textContent.trim()}))`),
            owners.map(player => ({title: player.capital.replace(/\b\w/g, letter => letter.toUpperCase()),
                text: player.capital.replace(/\b\w/g, letter => letter.toUpperCase())})),
            'Owner symbols retain hover names and accessible text');
        assert.equal(await page.evaluate(`document.querySelector('[data-advance="${unowned.id}"] .advance-owner-symbols')`), null,
            'Unowned advances have no owner symbols');
        await page.evaluate(`document.querySelector('[data-advance="${advance.id}"]').scrollIntoView({block: 'center'})`);
        assert(await page.evaluate(`(() => {
            const tile = document.querySelector('[data-advance="${advance.id}"]');
            const bounds = tile.getBoundingClientRect();
            return [...tile.querySelectorAll('.advance-owner-symbols svg')].every(symbol => {
                const rect = symbol.getBoundingClientRect();
                return rect.width === 15 && rect.height === 15 && rect.left >= bounds.left && rect.right <= bounds.right;
            }) && tile.scrollWidth <= tile.clientWidth && document.documentElement.scrollWidth <= innerWidth;
        })()`), `All six owner symbols fit the advance tile at ${width}px`);
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-advance-owners-${width}.png`));
        await show({game: {players: owners.map((player, index) => ({...player, advances: index ? [advance.id] : []}))}});
        assert.equal(await page.evaluate(`document.querySelectorAll('${symbols} svg').length`), owners.length - 1,
            'Ownership symbols follow public updates without changing the prepared card');
        assert.equal(await page.evaluate(`document.querySelector('.decision-actions .play-card-action').disabled`), false,
            'Ownership updates preserve the prepared commodity play');
    }
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false});
    const leaderName = await page.evaluate(`document.querySelector('[data-advance="${advance.id}"]').parentElement.querySelector('.unplayed-leader').getAttribute('aria-label').split(' · ')[0]`);
    await page.evaluate(`document.querySelector('[data-advance="${advance.id}"]').parentElement.querySelector('.unplayed-leader').focus(); document.activeElement.click()`);
    await page.wait(`document.querySelector('.card-inspection-dialog[open]')`, 'Unplayed leader popup');
    assert.equal(await page.evaluate(`document.querySelector('.card-inspection-dialog .card-decision').textContent.includes('$')`), true, 'Leader popup shows the credit detail');
    await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-unplayed-leader-popup.png'));
    assert.equal(await page.evaluate(`document.querySelector('.card-inspection-dialog h2').textContent`), leaderName);
    assert.equal(await page.evaluate(`document.querySelectorAll('.decision-content .card-decision').length`), 1, 'Reference inspection preserves the prepared commodity play');
    await page.send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27, nativeVirtualKeyCode: 27});
    await page.send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27, nativeVirtualKeyCode: 27});
    await page.wait(`!document.querySelector('.card-inspection-dialog')`, 'Leader popup closes');
    assert.equal(await page.evaluate(`document.activeElement.dataset.unplayedLeader`), expected[0].leader, 'Popup returns keyboard focus to the circle');
    await page.evaluate(`document.querySelector('[data-advance="${advance.id}"]').click()`);
    assert.equal(await page.evaluate(`document.querySelector('.advance-decision .unplayed-leader')`), null, 'Advance inspection does not duplicate the tile leader links');
    await show({game: {stage: 'EXPANSION', board: [{id: 'florence', marker: {owner: viewer, side: 'WHITE'}, tokens: []}]}});
    assert.equal(await page.evaluate(`document.querySelector('[data-player="${viewer}"] .player-white-markers b').textContent`), '1');
    const other = baseline.game.players.find(player => player.id !== viewer);
    assert.equal(await page.evaluate(`document.querySelector('[data-player="${other.id}"] .player-white-markers b').textContent`), '0');
    console.log('Passed: optional market/card payout boxes, commodity adjustment badges, public per-leader epoch circles, focus restoration and expansion white marker counts.');
}
