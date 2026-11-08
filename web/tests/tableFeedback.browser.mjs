import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open, waitForMap} from './browser-support.mjs';

// These presentation fixtures carry explicit projected quotes. Engine tests verify their rules;
// deliberately distinctive probabilities ensure the browser displays the projection unchanged.
const game = await createGame(3);
const baseline = await game.snapshot(game.links[0]);
const viewer = baseline.viewerId;
const other = baseline.game.players.find(player => player.id !== viewer).id;
const players = baseline.game.players.map((player, index) => ({...player,
    capital: ['venice', 'genoa', 'barcelona'][index], cash: 100, misery: 20,
    poolTokens: 20, advances: player.id === viewer ? ['PRINTED_WORD', 'NATIONALISM', 'INDUSTRY'] : [],
}));
const leader = baseline.game.cardInspections.find(card => card.definition.id === 'charlemagne');
assert(leader, 'Reference projection contains Charlemagne');
const discount = {...leader.leaderDiscounts.find(discount => discount.advance === 'NATIONALISM'), rebate: 20};
assert.equal(discount.advance, 'NATIONALISM');
const fur = baseline.game.catalog.commodities.find(commodity => commodity.id === 'FUR');
const furProvince = baseline.game.catalog.provinces.find(province => province.commodities.includes('FUR'));
const province = baseline.game.catalog.provinces.find(province => province.id === 'florence');
assert(fur && furProvince && province, 'Public catalog supplies the tested provinces and commodity');
let revision = 93000;
let decisionEntry = 93000;
const page = await open('about:blank');
const web = process.env.MAP_WEB_URL;
const click = selector => page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);

async function show(choice, gameChanges = {}, commands = ['pass'], replaceDecision = true) {
    if (replaceDecision) decisionEntry++;
    const snapshot = {...baseline, revision: ++revision, planning: null,
        prompt: {messageId: `prompt.${choice}`, players: [viewer]},
        legalChoices: {commands, bidMin: null, bidMax: null},
        game: {...baseline.game, stage: 'PLAY_CARDS', turnNumber: 2, players,
            turnOrder: players.map(player => player.id), hand: [], cardOffers: [],
            shippingOffer: null, reliefOffers: [], expansionOffers: [], expansionInspections: [],
            windmillOffer: null,
            activity: {...baseline.game.activity, actor: viewer, decisionPlayer: viewer,
                choice, decisionEntry, purchaseStep: null},
            ...gameChanges}};
    await page.evaluate(`window.setMapSnapshot(${JSON.stringify(snapshot)})`);
    await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'table feedback projection');
    await waitForMap(page, snapshot);
    return snapshot;
}

async function capture(name) {
    await page.evaluate('document.fonts.ready.then(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))');
    assert(await page.evaluate('document.documentElement.scrollWidth <= innerWidth'), `${name} fits viewport`);
    await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `table-feedback-${name}.png`));
}

try {
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'StrictMode table feedback harness');
    for (const [width, height, mobile] of [[1280, 900, false], [390, 844, true]]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        const offer = {reference: 'rebate-leader', playable: true, blockers: [], commodities: [],
            leaderDiscounts: [discount], provinceTargets: [], requiredTargetCount: null, protectionMax: 100,
            choices: [{kind: 'NONE', value: null, command: 'play rebate-leader'},
                {kind: 'PROTECTION', value: null, command: null}]};
        const leaderChanges = {hand: [{reference: offer.reference, definition: leader.definition}],
            cardOffers: [offer], cardInspections: baseline.game.cardInspections.map(card =>
                card.definition.id === leader.definition.id ? {...card, leaderDiscounts: [discount]} : card)};
        await show('play-cards', leaderChanges, ['play rebate-leader', 'pass']);
        await click('#tab-map');
        const title = await page.evaluate(`document.querySelector('[data-map-province="${furProvince.id}"] > title').textContent`);
        assert.match(title, /Fur/, 'Province hover text includes its commodity');
        assert.match(await page.evaluate(`document.querySelector('[data-map-province="${furProvince.id}"]').getAttribute('aria-label')`),
            /Fur/, 'The accessible province name retains its commodity');
        assert.match(await page.evaluate(`document.querySelector('[data-hand-card="rebate-leader"] .leader-discount.has-rebate').textContent`),
            /Nationalism.*\+\$20 rebate/, 'An owned advance rebate is highlighted on its leader');
        await click('[data-hand-card="rebate-leader"]');
        await page.wait(`document.querySelector('.decision-panel .card-decision input')`, 'leader protection field');
        assert.match(await page.evaluate(`document.querySelector('.decision-panel .leader-discount-detail summary .leader-rebate').textContent`),
            /\+\$20 rebate/, 'The same rebate appears in leader details');
        await page.fill('.decision-panel .card-decision input', '7');
        assert.equal(await page.evaluate(`document.querySelector('.decision-panel .card-decision input').value`), '7');
        assert.equal(await page.evaluate(`[...document.querySelectorAll('.decision-panel .card-decision label')].filter(label => label.textContent.includes('Protection')).length`),
            1, 'Protection has one editable label');
        assert.doesNotMatch(await page.evaluate(`document.querySelector('.decision-panel .card-play-effects').textContent`),
            /Protection/, 'A separate protection amount does not duplicate the input');
        await page.evaluate(`window.protectionDraft = document.querySelector('.decision-panel .card-decision input'); true`);
        await show('play-cards', leaderChanges, ['play rebate-leader', 'pass'], false);
        assert(await page.evaluate(`document.querySelector('.decision-panel .card-decision input') === window.protectionDraft`),
            'Polling keeps the mounted protection draft');
        assert.equal(await page.evaluate(`window.protectionDraft.value`), '7');
        await capture(`leader-${width}`);

        const noRebate = {...discount, rebate: 0};
        await show('play-cards', {...leaderChanges, cardOffers: [{...offer, leaderDiscounts: [noRebate]}],
            cardInspections: baseline.game.cardInspections.map(card => card.definition.id === leader.definition.id
                ? {...card, leaderDiscounts: [noRebate]} : card)}, ['play rebate-leader', 'pass'], false);
        assert.equal(await page.evaluate(`document.querySelector('[data-hand-card="rebate-leader"] .leader-rebate')`), null,
            'A projected zero rebate has no money highlight');

        await show('play-cards', {commodityPayouts: [{commodity: 'FUR', adjustments: [], owners: [{
            player: viewer, provinces: [furProvince.id], marketCount: 1, effectiveBox: 2,
            payout: fur.baseValue * 4, industryBonus: 1}]}]});
        await click('#tab-markets');
        await click('.market-tile button[data-help-topic="commodity-fur"]');
        await page.wait(`document.querySelector('.reference-dialog .commodity-industry')`, 'Industry in commodity popup');
        assert.match(await page.evaluate(`document.querySelector('.commodity-industry').textContent`),
            /Venice.*Industry.*extra payout box/s, 'The commodity popup identifies the Industry owner and effect');
        assert.equal(await page.evaluate(`document.querySelectorAll('.commodity-industry').length`), 1,
            'Only the Industry owner receives the indicator');
        assert(await page.evaluate(`document.querySelector('#commodity-fur [data-track-box="2"] [data-track-player="${viewer}"]') !== null`),
            'The popup retains the authoritative Industry-adjusted payout position');
        await capture(`commodity-${width}`);
        await click('.reference-dialog .close-detail');
        await page.wait(`!document.querySelector('.reference-dialog')`, 'commodity popup closes');

        const competition = {destination: province.id, primaryAction: 'ATTACK', requiredTokens: 6,
            availablePool: 20, resultingPool: 14, establishesMarker: true, uncertain: true,
            blockers: [], partialPlacements: [], shipping: null, incomeBefore: 18, incomeAfter: null,
            reachable: true, greenDieWins: [5, 6], competitionWinProbability: 0.625};
        await show('expand', {stage: 'EXPANSION', board: [{id: province.id,
            marker: {owner: other, side: 'COLOURED'}, tokens: []}],
            expansionOffers: [competition], expansionInspections: [competition]}, [`attack ${province.id}`, 'pass']);
        await click('#tab-map');
        await page.evaluate(`document.querySelector('[data-map-province="${province.id}"]').dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true}))`);
        await page.wait(`document.querySelector('.competition-odds')`, 'competition probability beside attack');
        assert.equal(await page.evaluate(`document.querySelector('.competition-odds').textContent`),
            '62.5% chance of winning on dice, before Cathedral choices', 'Attack odds use the projected probability');
        assert.equal(await page.evaluate(`document.querySelector('.primary-expansion').disabled`), false);
        await capture(`competition-${width}`);

        await show('windmill', {stage: 'EXPANSION', windmillOffer: {player: viewer, victim: other,
            province: province.id, tokenCost: 3, successProbability: 0.5}}, ['windmill', 'pass']);
        await page.wait(`document.querySelector('.windmill-preview')`, 'Wind/Watermill quote');
        assert.match(await page.evaluate(`document.querySelector('.windmill-preview').textContent`),
            /Florence.*3 pool tokens.*50% chance of success/s, 'The optional trade shows token cost and success odds');
        assert.match(await page.evaluate(`document.querySelector('.simple-action').textContent`),
            /Try Wind\/Watermill trade.*3 tokens/s, 'The Wind/Watermill action includes its token cost');
        assert.equal(await page.evaluate(`document.querySelector('.simple-action').disabled`), false);
        await capture(`windmill-${width}`);
    }
    assert.deepEqual(await page.evaluate('window.mapCommands'), [], 'Inspection and quotes never roll dice or commit an action');
    await click('.simple-action');
    assert.deepEqual(await page.evaluate('window.mapCommands'), ['windmill'], 'Explicit confirmation submits the canonical trade command');
    console.log('Passed: province commodity hover text, projected leader rebates, editable-only protection amount, Industry popup and dice odds/costs at desktop and phone widths.');
} finally {
    page.ws.close();
    await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
}
