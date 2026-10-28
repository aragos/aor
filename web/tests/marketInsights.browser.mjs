import assert from 'node:assert/strict';

export async function checkMarketInsights(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'market insights harness');
    const viewer = baseline.viewerId;
    const advance = baseline.game.catalog.advances[0];
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
            unplayedAdvanceLeaders: [{advance: advance.id, epochs: [{epoch: 1, count: 2}, {epoch: 2, count: 1}, {epoch: 3, count: 0}]}],
        }};
    async function show(changes = {}) {
        const snapshot = {...fixture, ...changes, revision: ++revision, game: {...fixture.game, ...changes.game}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(snapshot)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'market fixture render');
    }
    await show();
    assert.match(await page.evaluate(`document.querySelector('[data-hand-card="fur"] .adjustment-badge.SHORTAGE').textContent`), /Shortage.*×2/i);
    assert.match(await page.evaluate(`document.querySelector('[data-hand-card="fur"] .adjustment-badge.SURPLUS').textContent`), /Surplus.*×1/i);
    await page.evaluate(`document.querySelector('#tab-markets').click(); document.querySelector('.market-tile[data-commodity="FUR"]').open = true`);
    assert.equal(await page.evaluate(`document.querySelector('.market-tile .payout-reference').open`), false, 'Full market payouts are optional');
    await page.evaluate(`document.querySelector('.market-tile .payout-reference > summary').click()`);
    assert.equal(await page.evaluate(`document.querySelectorAll('.market-tile .payout-reference[open] .payout-box').length`), 10);
    assert.equal(await page.evaluate(`document.querySelector('.market-tile .payout-box.is-current > span').textContent`), '2', 'Current effective payout box is marked');
    await page.evaluate(`document.querySelector('[data-hand-card="fur"]').click()`);
    await page.wait(`document.querySelector('.card-decision .payout-reference')`, 'selected commodity reference');
    assert.equal(await page.evaluate(`document.querySelector('.card-decision .payout-reference').open`), false, 'Selected commodity reference also defaults closed');
    await page.evaluate(`document.querySelector('.card-decision .payout-reference > summary').click()`);
    assert.equal(await page.evaluate(`document.querySelectorAll('.card-decision .payout-reference[open] .payout-box').length`), 10);
    assert.match(await page.evaluate(`document.querySelector('.card-decision .adjustment-badge.SHORTAGE').textContent`), /Shortage/i);
    await page.evaluate(`document.querySelector('#tab-advances').click()`);
    assert.match(await page.evaluate(`document.querySelector('[data-advance="${advance.id}"] .unplayed-leaders').textContent`), /I: 2.*II: 1.*III: 0/);
    await page.evaluate(`document.querySelector('[data-advance="${advance.id}"]').click()`);
    assert.match(await page.evaluate(`document.querySelector('.advance-decision .unplayed-leaders').textContent`), /I: 2.*II: 1.*III: 0/);
    await show({game: {stage: 'EXPANSION', board: [{id: 'florence', marker: {owner: viewer, side: 'WHITE'}, tokens: []}]}});
    assert.equal(await page.evaluate(`document.querySelector('[data-player="${viewer}"] .player-white-markers b').textContent`), '1');
    const other = baseline.game.players.find(player => player.id !== viewer);
    assert.equal(await page.evaluate(`document.querySelector('[data-player="${other.id}"] .player-white-markers b').textContent`), '0');
    console.log('Passed: optional market/card payout boxes, commodity adjustment badges, public leader epoch counts and expansion white marker counts.');
}
