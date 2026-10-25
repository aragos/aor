import {join} from 'node:path';

const artifacts = process.env.AOR_BROWSER_ARTIFACTS ?? '/private/tmp';
import assert from 'node:assert/strict';
import {open, createGame} from './browser-support.mjs';
import {checkTable, checkLiveTokenPurchase, checkTokenContinuity, checkDecisionFamilies} from './table.browser.mjs';

const web = process.env.MAP_WEB_URL ?? 'http://127.0.0.1:5191';
const cdp = process.env.MAP_CDP_URL ?? 'http://127.0.0.1:9351';

let tokenFixture;
const game = await createGame(6);
const page = await open(web + game.links[0].path);
try {
await page.wait(`document.querySelector('#setup-rebuilt-marker-bonus')`, 'configuration controls');
await page.click('Start setup');
await page.wait(`document.querySelector('#bid')`, 'capital bidding');
await page.fill('#bid', '7');
await page.click('Seal bid');
await page.wait(`[...document.querySelectorAll('button')].some(button => button.textContent === 'Replace sealed bid')`, 'sealed submission');
const sealed = await game.snapshot(game.links[0]);
assert.equal(sealed.pendingBid, 7);
assert.equal((await game.snapshot(game.links[1])).pendingBid, null, 'Other seats cannot see sealed bid');
assert.deepEqual((await game.snapshot(game.links[1])).game.revealedBids, {}, 'Sealed bids remain unrevealed');
await page.fill('#bid', '0');
await page.click('Replace sealed bid');
await page.wait(`document.querySelector('.sealed')?.textContent.includes('$0')`, 'replaced bid');
assert.equal((await game.snapshot(game.links[0])).revision, sealed.revision, 'Sealed replacement cannot advance committed revision');
for (const link of game.links.slice(1)) await game.send(link, 'bid-capital 0');
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
    if (state.game.stage === 'TOKEN_PURCHASE' && !tokenFixture) {
        tokenFixture = await checkLiveTokenPurchase(page, game, actor, web, 8 + game.links.indexOf(actor));
    } else {
        await game.send(actor, command);
    }
}
const first = await game.snapshot(game.links[0]);
const actor = game.links.find(link => first.prompt?.players.includes(link.id));
await page.send('Page.navigate', {url: web + actor.path});
    await page.wait(`location.hash === ${JSON.stringify(actor.path.slice(actor.path.indexOf('#')))}`, 'expansion actor navigation');
    await page.wait(`document.querySelectorAll('[data-map-province]').length === 108`, 'full map');
    await page.wait(`document.querySelector('.expansion-form, .pool-summary')`, 'expansion decision ready');
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
    await page.wait(`document.querySelector('.map-orders .primary-expansion')`, 'selected satellite action');
    assert.equal(await page.evaluate(`document.querySelector('.map-orders .primary-expansion').disabled`), false);
    await page.evaluate(`document.querySelector('.map-orders .primary-expansion').click()`);
    await page.wait(`[...document.querySelectorAll('.save-status')].some(e => e.textContent.includes('Saved'))`, 'successful placement');
    let after = await page.snapshot();
    assert.equal(after.revision, before.revision + 1);
    assert(after.game.board.some(p => p.id === target && p.tokens.some(t => t.owner === after.viewerId && t.white === 1)));
    const market = after.game.expansionOffers.find(offer => offer.primaryAction === 'PLACE' && offer.establishesMarker && !offer.blockers.length && offer.partialPlacements.some(choice => choice.tokens === 1));
    assert(market, 'Fixture must support legal partial occupation');
    await pick(market.destination);
    await page.evaluate(`document.querySelector('.partial-placement').open = true`);
    await page.fill('#partial-count', '1');
    assert.equal((await page.snapshot()).revision, after.revision, 'Partial amount selection cannot submit');
    await page.evaluate(`document.querySelector('.partial-placement form button').click()`);
    await page.wait(`document.querySelector('.primary-expansion')?.textContent.includes('${market.requiredTokens - 1}')`, 'remaining marker commitment after partial occupation');
    const partialState = await page.snapshot();
    assert.equal(partialState.revision, after.revision + 1);
    assert.equal(partialState.game.expansionOffers.find(offer => offer.destination === market.destination).requiredTokens, market.requiredTokens - 1, 'Existing friendly token reduces marker commitment');
    await page.evaluate(`document.querySelector('.primary-expansion').click()`);
    await page.wait(`!document.querySelector('.primary-expansion')`, 'marker established');
    after = await page.snapshot();
    assert.equal(after.game.board.find(province => province.id === market.destination).marker.owner, after.viewerId);
    console.log('Passed: live satellite placement, partial-market draft and remaining marker establishment.');
    await page.fill('.map-toolbar input', 'ivory');
    assert.equal(await page.evaluate(`document.querySelectorAll('.map-province.active:not(.map-filtered)').length`), 4);
    await page.fill('.map-toolbar input', '');
    await page.evaluate(`document.querySelector('[aria-label="Zoom in"]').click()`);
    const zoomed = await page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox')`);
    await page.click('Fit map');
    assert.notEqual(await page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox')`), zoomed);
    const screenshotOffer = after.game.expansionOffers.find(offer => offer.establishesMarker && !offer.blockers.length);
    assert(screenshotOffer, 'Screenshot fixture needs a legal marker draft');
    await pick(screenshotOffer.destination);
    await page.send('Emulation.setDeviceMetricsOverride', {width: 390, height: 844, deviceScaleFactor: 1, mobile: true});
    assert.equal(await page.evaluate('document.documentElement.scrollWidth <= innerWidth'), true, 'No mobile overflow');
    await page.evaluate(`document.querySelector('.primary-expansion').scrollIntoView({block:'nearest'})`);
    assert.equal(await page.evaluate(`(() => {const button=document.querySelector('.primary-expansion');const rect=button.getBoundingClientRect();return rect.top>=0 && rect.bottom<=innerHeight && document.elementFromPoint(rect.x+rect.width/2,rect.y+rect.height/2)===button;})()`), true, 'Phone marker submit is reachable and unobstructed');
    await page.screenshot(join(artifacts, 'aor-map-mobile.png'), 390, 844, true);
    await page.evaluate(`document.querySelector('.sheet-toggle').click()`);
    assert.equal(await page.evaluate(`document.querySelector('.sheet-toggle').getAttribute('aria-expanded')`), 'false', 'Phone sheet minimizes');
    await page.send('Emulation.setEmulatedMedia', {features: [{name: 'prefers-reduced-motion', value: 'reduce'}]});
    assert.equal(await page.evaluate(`matchMedia('(prefers-reduced-motion: reduce)').matches`), true);
    const touchPoint = await page.evaluate(`(() => {const control = document.querySelector('[aria-label="Zoom in"]'); control.scrollIntoView({block:'center'}); const rect=control.getBoundingClientRect();return {x:rect.x+rect.width/2,y:rect.y+rect.height/2};})()`);
    const beforeTouch = await page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox')`);
    await page.send('Input.dispatchTouchEvent', {type:'touchStart', touchPoints:[touchPoint]});
    await page.send('Input.dispatchTouchEvent', {type:'touchEnd', touchPoints:[]});
    await page.wait(`document.querySelector('.world-map').getAttribute('viewBox') !== ${JSON.stringify(beforeTouch)}`, 'touch zoom');
    await page.evaluate(`document.querySelector('.sheet-toggle').click()`);
    assert.equal(await page.evaluate(`document.querySelector('.sheet-toggle').getAttribute('aria-expanded')`), 'true', 'Phone sheet reopens with draft retained');
    await page.screenshot(join(artifacts, 'aor-map-phone-tall.png'), 430, 1100, true);
    assert.equal(await page.evaluate('document.documentElement.scrollWidth <= innerWidth'), true, 'No tall phone overflow');
    await page.screenshot(join(artifacts, 'aor-map-desktop.png'), 1440);

    for (const [count, expected] of [[3, 67], [4, 83], [5, 96]]) {
        const reduced = await createGame(count);
        await page.send('Page.navigate', {url: web + reduced.links[0].path});
        await page.wait(`document.querySelectorAll('.map-province.active').length === ${expected}`, `${count}-player board`);
        assert.equal(await page.evaluate(`document.querySelectorAll('.map-province.inactive[tabindex]').length`), 0);
    }

    await checkTable(page, web, after);
    await checkTokenContinuity(page, web, tokenFixture);
    await checkDecisionFamilies(page, web, after);

    // Use the same components with deterministic interruptions and mixed piece ages.
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'component harness');
    let revision = 500;
    async function show(changes) {
        const fixture = {...after, prompt: {messageId: 'prompt.test', players: [after.viewerId]}, ...changes, revision: ++revision,
            game: {...after.game, ...changes.game}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'fixture render');
        return fixture;
    }
    const pirateFixture = await show({legalChoices: {commands: ['play hand-pirates']}, game: {stage: 'PLAY_CARDS',
        hand: [{reference: 'hand-pirates', definition: {id: 'pirates-vikings', kind: 'event', messageId: 'card.pirates-vikings', shape: 'SQUARE'}}],
        pirateTargets: {requiredCount: 2, eligibleProvinces: ['barcelona', 'genoa', 'rome']},
        cardOffers: [{reference:'hand-pirates', playable:true, blockers:[], choices:[{kind:'PROVINCES',value:null,command:null}], commodities:[],leaderDiscounts:[],provinceTargets:['barcelona','genoa','rome'],requiredTargetCount:2,protectionMax:null}]}});
    await page.evaluate(`document.querySelector('[data-hand-card="hand-pirates"]').click()`);
    await page.evaluate(`document.querySelector('.card-decision .map-picker').click()`);
    await pick('barcelona'); await pick('genoa'); await pick('rome');
    assert.equal(await page.evaluate(`document.querySelectorAll('.card-decision .target-choices button:not(.map-picker)[aria-pressed="true"]').length`), 2);
    assert.equal(await page.evaluate('window.mapCommands.length'), 0);
    await show({...pirateFixture, game:{...pirateFixture.game,
        pirateTargets:{requiredCount:2,eligibleProvinces:['genoa','rome']},
        cardOffers:pirateFixture.game.cardOffers.map(offer=>({...offer,provinceTargets:['genoa','rome']}))}});
    await page.wait(`document.querySelector('.card-decision .clear-targets')`, 'lost pirate target explained');
    assert.equal(await page.evaluate(`document.querySelector('.card-decision form > button:last-child').disabled`), true, 'Lost target disables card submission');
    await page.evaluate(`document.querySelector('.card-decision .clear-targets').click()`);
    assert.equal(await page.evaluate('window.mapCommands.length'), 0, 'Clearing invalid targets never submits');
    await pick('genoa'); await pick('rome');
    await page.evaluate(`document.querySelector('.card-decision form > button:last-child').click()`);
    assert.equal(await page.evaluate('window.mapCommands.at(-1)'), 'play hand-pirates provinces genoa rome');

    await page.evaluate(`document.querySelector('.card-decision .close-detail')?.click()`);
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
} finally {
    page.ws.close();
    await fetch(cdp + '/json/close/' + page.target.id);
}
