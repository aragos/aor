import assert from 'node:assert/strict';
import {join} from 'node:path';

export async function checkLiveCommodityValues(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/ui-harness.html'});
    await page.wait(`typeof window.setUiSnapshot === 'function'`, 'live-value polling harness');
    const viewer = baseline.viewerId;
    const other = baseline.game.players.find(player => player.id !== viewer);
    const province = baseline.game.catalog.provinces.find(province => province.commodities.includes('STONE') && !province.satellite);
    const card = baseline.game.cardInspections.find(card => card.definition.id === 'stone-1');
    let revision = 26000;
    async function show(owner, side) {
        const snapshot = {...baseline, revision: ++revision,
            prompt: {messageId: 'prompt.expand', players: [other.id]}, legalChoices: {commands: [], bidMin: null, bidMax: null},
            game: {...baseline.game, stage: 'EXPANSION', hand: [{reference: 'live-stone', definition: card.definition}],
                activity: {...baseline.game.activity, actor: other.id, decisionPlayer: other.id, choice: 'expand'},
                cardOffers: [{reference: 'live-stone', playable: false, blockers: ['WRONG_PHASE'], choices: [],
                    commodities: ['STONE'], leaderDiscounts: [], provinceTargets: [], requiredTargetCount: null, protectionMax: null}],
                board: [...baseline.game.board.filter(entry => entry.id !== province.id), {id: province.id, marker: {owner, side}, tokens: []}],
                commodityPayouts: [{commodity: 'STONE', adjustments: [], owners: [
                    {player: owner, provinces: [province.id], marketCount: 1, effectiveBox: 1, payout: 2, industryBonus: 0}]}]}};
        const requests = await page.evaluate('window.uiRequests');
        await page.evaluate(`window.setUiSnapshot(${JSON.stringify(snapshot)})`);
        await page.wait(`window.uiRequests > ${requests}`, 'App polls changed marker ownership');
        await page.wait(`document.querySelector('[data-player="${owner}"] .player-labels strong')`, 'player identity rendered');
        const name = await page.evaluate(`document.querySelector('[data-player="${owner}"] .player-labels strong')?.textContent`);
        await page.wait(`document.querySelector('[data-hand-card="live-stone"] .owner-payout')?.title === ${JSON.stringify(name + ': $2')}`, 'hand value follows accepted snapshot');
        return name;
    }
    const original = await show(viewer, 'COLOURED');
    await page.evaluate(`document.querySelector('[data-hand-card="live-stone"]').click()`);
    assert.equal(await page.evaluate(`document.querySelector('.inspection-panel .card-decision')`), null, 'Commodity card values stay on the card');
    assert.equal(await page.evaluate(`document.querySelector('.table-hand a[href="/help#commodity-stone"]').target`), '_blank');
    assert.equal(await page.evaluate(`document.querySelector('.hand-card .owner-payout').title`), original + ': $2');
    const newOwner = await show(other.id, 'WHITE');
    for (const selector of ['.hand-card .owner-payout', '[data-commodity="STONE"] .owner-payout']) {
        assert.equal(await page.evaluate(`document.querySelector(${JSON.stringify(selector)}).title`), newOwner + ': $2', 'All commodity surfaces use the new white-marker owner');
    }
    assert.equal(await page.evaluate(`document.querySelectorAll('.hand-card .owner-payout').length`), 1, 'The defeated owner is removed');
    await page.evaluate(`document.querySelector('#tab-markets').click()`);
    assert.match(await page.evaluate(`document.querySelector('.markets-workspace .commodity-value-note').textContent`), /including white markers/);
    const reclaimed = await show(viewer, 'WHITE');
    assert.equal(await page.evaluate(`document.querySelector('.hand-card .owner-payout').title`), reclaimed + ': $2', 'Reclaiming changes live values again without a new hand');
    assert.equal(await page.evaluate(`window.uiCommands.length`), 0, 'Polling, inspection and value updates send no orders');
    console.log('Passed: App polling updates hand and markets for white-marker ownership changes and reclamation.');
}

export async function checkClearAdvancePrices(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1280, height: 900, deviceScaleFactor: 1, mobile: false});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'clear-prices harness');
    const viewer = baseline.viewerId;
    let revision = 27000;
    async function show(game) {
        const snapshot = {...baseline, revision: ++revision, prompt: {messageId: 'prompt.purchase-advances', players: [viewer]},
            legalChoices: {commands: ['pass'], bidMin: null, bidMax: null}, game: {...baseline.game, ...game}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(snapshot)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'price fixture');
    }
    await show({stage: 'BIDDING', turnNumber: null, advanceOffers: []});
    await page.evaluate(`document.querySelector('#tab-advances').click()`);
    for (const advance of baseline.game.catalog.advances) {
        assert.equal(await page.evaluate(`document.querySelector('[data-advance="${advance.id}"] .advance-price-compact strong').textContent`), '$' + advance.cost, 'Every advance has a printed price before quotes exist');
    }
    const quote = {advance: 'OCEAN_NAVIGATION', baseCost: 120, categoryCredit: 20,
        advanceCredits: [{advance: 'OVERLAND_EAST', amount: 20}], institutionalResearchCredit: 10,
        leaders: [{leaderCardId: 'christopher-columbus', baseAmount: 30, writtenRecordBonus: 10, amount: 40}],
        price: 50, effectiveDiscount: 70, miseryBefore: 20, miseryAfter: 20};
    await show({stage: 'PURCHASE', activity: {...baseline.game.activity, actor: viewer, decisionPlayer: viewer, purchaseStep: 'ADVANCES'},
        players: baseline.game.players.map(player => player.id === viewer ? {...player, cash: 200,
            advances: ['OVERLAND_EAST', 'WRITTEN_RECORD', 'INSTITUTIONAL_RESEARCH']} : player),
        advanceOffers: [{advance: 'OCEAN_NAVIGATION', price: 50, unavailableReason: 'MISSING_ADVANCE_PREREQUISITE',
            leaderDiscounts: quote.leaders, priceBreakdown: quote, miseryChanges: []}]});
    const tile = '[data-advance="OCEAN_NAVIGATION"]';
    assert.equal(await page.evaluate(`document.querySelector('${tile} .advance-price-compact strong').textContent`), '$50');
    assert.match(await page.evaluate(`document.querySelector('${tile} .advance-price-compact').textContent`), /−\$70 credits/);
    assert.equal(await page.evaluate(`!!document.querySelector('${tile} .price-breakdown, ${tile} .advance-discount')`), false, 'Tiles keep detailed credit rows out of the compact summary');
    assert.equal(await page.evaluate(`document.querySelector('[data-advance="OVERLAND_EAST"] .advance-price-compact strong').textContent`), '$40', 'Owned advances retain a printed cost');
    await page.evaluate(`document.querySelector('${tile}').click()`);
    await page.wait(`document.querySelector('.inspection-panel .price-details')`, 'blocked advance remains inspectable');
    assert.equal(await page.evaluate(`document.querySelector('.inspection-panel .price-details').open`), false, 'Detailed credits start collapsed');
    assert.equal(await page.evaluate(`!!document.querySelector('.decision-panel .advance-decision')`), false, 'A price estimate does not authorize purchase');
    await page.evaluate(`document.querySelector('.inspection-panel .price-details > summary').click()`);
    assert.match(await page.evaluate(`document.querySelector('[data-credit-advance="OVERLAND_EAST"]').textContent`), /Overland East−\$20/);
    const explanation = await page.evaluate(`document.querySelector('.inspection-panel .price-breakdown').textContent`);
    assert.match(explanation, /Printed cost\$120.*Overland East−\$20.*Institutional Research−\$10.*Christopher Columbus.*−\$40.*Includes \$10 Written Record.*Price\$50/);
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-clear-advance-prices.png'), 1280, 900);
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-clear-advance-prices-phone.png'), 390, 844, true);
    assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
    assert.equal(await page.evaluate(`window.mapCommands.length`), 0, 'Credit disclosure and blocked inspection submit nothing');
    console.log('Passed: all advance prices, compact summaries, named advance credits, optional detail, blocked purchase safety and phone layout.');
}
