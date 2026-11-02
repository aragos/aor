import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';

export async function checkCommodityMap(page, web, baseline) {
    const info = baseline.game.cardInspections.find(card =>
        card.definition.kind === 'commodity' && card.commodities.length > 1);
    assert(info, 'The public catalog includes a dual-commodity card');
    let revision = 62000;
    const reference = 'commodity-highlight';
    const commands = info.commodities.map(commodity => `play ${reference} commodity ${commodity.toLowerCase()}`);
    const fixture = {...baseline, prompt: {messageId: 'prompt.play-cards', players: [baseline.viewerId]},
        legalChoices: {commands: [...commands, 'pass'], bidMin: null, bidMax: null},
        game: {...baseline.game, stage: 'PLAY_CARDS', expansionOffers: [],
            hand: [{reference, definition: info.definition}],
            cardOffers: [{reference, playable: true, blockers: [], commodities: info.commodities,
                leaderDiscounts: [], choices: info.commodities.map((commodity, index) =>
                    ({kind: 'COMMODITY', value: commodity.toLowerCase(), command: commands[index]})),
                provinceTargets: [], requiredTargetCount: null, protectionMax: null}]}};
    const expected = fixture.game.catalog.provinces.filter(province =>
        province.commodities.some(commodity => info.commodities.includes(commodity))).map(province => province.id).sort();
    assert(expected.length, 'The selected commodities have provinces on this board');
    async function show(changes = {}) {
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify({...fixture, ...changes,
            revision: ++revision, game: {...fixture.game, ...changes.game}})})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'commodity map fixture');
    }
    async function highlighted() {
        return page.evaluate(`[...document.querySelectorAll('[data-commodity-highlight]')].map(node => node.dataset.mapProvince).sort()`);
    }
    for (const [width, height, mobile] of [[1440, 980, false], [390, 844, true]]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
        await page.wait(`typeof window.setMapSnapshot === 'function'`, 'commodity map harness');
        await show();
        await page.evaluate(`document.querySelector('#tab-markets').click(); document.querySelector('[data-hand-card="${reference}"]').click()`);
        await page.wait(`document.querySelector('#tab-map[aria-selected=true]') && document.querySelector('[data-commodity-highlight]')`, 'commodity click reveals highlighted map');
        assert.deepEqual(await highlighted(), expected, 'Every matching in-play province, including either half of a dual card, is highlighted');
        assert.equal(await page.evaluate(`document.querySelectorAll('[data-commodity-highlight].inactive').length`), 0, 'Unused provinces remain unhighlighted');
        assert.equal(await page.evaluate(`document.querySelectorAll('.card-inspection-dialog').length`), 0, 'A commodity click needs no duplicate inspector');
        assert.equal(await page.evaluate(`getComputedStyle(document.querySelector('.map-zoom')).display === 'none'`), mobile, 'Map buttons are absent on mobile');
        const draft = await page.evaluate(`document.querySelector('.decision-content .card-decision').textContent`);
        const iconExamples = [
            fixture.game.catalog.provinces.find(province => province.commodities.includes('STONE')),
            fixture.game.catalog.provinces.find(province => province.commodities.length > 1),
        ];
        assert(iconExamples.every(Boolean), 'Fixture includes Stone and a multi-commodity province');
        for (const province of iconExamples) {
            for (const commodity of province.commodities) {
                const selector = `[data-map-commodity="${province.id}:${commodity}"]`;
                await page.evaluate(`document.querySelector(${JSON.stringify(selector)}).focus()`);
                await page.evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
                const point = await page.evaluate(`(() => {
                    const icon = document.querySelector(${JSON.stringify(selector)});
                    const point = new DOMPoint(12, 12).matrixTransform(icon.getScreenCTM());
                    return {x: point.x, y: point.y};
                })()`);
                await page.send('Input.dispatchMouseEvent', {type: 'mousePressed', ...point, button: 'left', clickCount: 1});
                await page.send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...point, button: 'left', clickCount: 1});
                const matching = fixture.game.catalog.provinces.filter(candidate => candidate.commodities.includes(commodity))
                    .map(candidate => candidate.id).sort();
                assert.deepEqual(await highlighted(), matching, 'A province icon highlights exactly its own commodity');
                assert.equal(await page.evaluate(`document.querySelector('.decision-content .card-decision').textContent`), draft,
                    'Commodity inspection preserves the prepared card action');
                await show();
                assert.deepEqual(await highlighted(), matching, 'Polling preserves province commodity inspection');
            }
        }
        assert.equal(await page.evaluate(`document.querySelectorAll('[data-map-commodity][tabindex="0"]').length`), 1,
            'All commodity icons share one roving tab stop');
        await page.send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Home'});
        const firstIcon = await page.evaluate('document.activeElement.dataset.mapCommodity');
        await page.send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'ArrowRight'});
        assert.notEqual(await page.evaluate('document.activeElement.dataset.mapCommodity'), firstIcon,
            'Arrow keys traverse commodity icons');
        await page.send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Enter'});
        const keyboardCommodity = await page.evaluate(`document.activeElement.dataset.mapCommodity.split(':')[1]`);
        assert.deepEqual(await highlighted(), fixture.game.catalog.provinces.filter(province => province.commodities.includes(keyboardCommodity))
            .map(province => province.id).sort(), 'Keyboard activation highlights the focused commodity');
        await page.evaluate(`document.querySelector('[data-hand-card="${reference}"]').click()`);
        assert.deepEqual(await highlighted(), expected, 'A card can replace the province commodity inspection');
        const beforeZoom = await page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox')`);
        await page.evaluate(`document.querySelector('.world-map').dispatchEvent(new KeyboardEvent('keydown', {key: '+', bubbles: true}))`);
        await page.wait(`document.querySelector('.world-map').getAttribute('viewBox') !== ${JSON.stringify(beforeZoom)}`, 'keyboard zoom without map buttons');
        await show();
        assert.deepEqual(await highlighted(), expected, 'Polling preserves commodity inspection');
        await page.evaluate(`document.querySelector('[data-map-province="${expected[0]}"]').dispatchEvent(new MouseEvent('click', {bubbles:true}))`);
        assert.deepEqual(await highlighted(), [], 'Province inspection clears commodity highlighting');
        assert.equal(await page.evaluate(`document.querySelectorAll('.decision-content .card-decision').length`), 1, 'Province inspection preserves the prepared commodity action');
        await page.evaluate(`document.querySelector('[data-map-area-label]').dispatchEvent(new MouseEvent('click', {bubbles:true}))`);
        assert.deepEqual(await highlighted(), [], 'Area inspection clears commodity highlighting');
        await page.evaluate(`document.querySelector('[data-hand-card="${reference}"]').click()`);
        assert.deepEqual(await highlighted(), expected, 'Clicking the same commodity again restores its highlights after area inspection');
        assert.equal(await page.evaluate(`document.querySelectorAll('[data-area-dimming]').length`), 0, 'A renewed commodity inspection clears area dimming');
        await page.evaluate(`document.querySelector('.world-map').dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}))`);
        assert.deepEqual(await highlighted(), [], 'Usual map dismissal clears commodity highlighting');
        await show({legalChoices: {commands: ['pass'], bidMin: null, bidMax: null},
            game: {cardOffers: [{...fixture.game.cardOffers[0], playable: false, choices: [], blockers: ['NOT_CURRENT_PLAYER']}]}});
        await page.evaluate(`document.querySelector('[data-map-area-label]').dispatchEvent(new MouseEvent('click', {bubbles:true})); document.querySelector('[data-hand-card="${reference}"]').click()`);
        assert.equal(await page.evaluate(`document.querySelectorAll('[data-area-dimming]').length`), 0, 'Unavailable commodity inspection also clears the former area');
        assert.deepEqual(await highlighted(), expected, 'An unavailable commodity card still supports map inspection');
        assert.equal(await page.evaluate('window.mapCommands.length'), 0, 'Commodity inspection and map navigation never submit');
        if (process.env.AOR_BROWSER_ARTIFACTS) await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-commodity-map-${width}.png`));
    }
    console.log('Passed: commodity-card map highlights, dual commodities, in-play filtering, draft-safe dismissal/polling, unavailable cards and mobile map navigation.');
}

if (process.argv[1]?.endsWith('/commodityMap.browser.mjs')) {
    const game = await createGame(3);
    const baseline = await game.snapshot(game.links[0]);
    const page = await open('about:blank');
    try { await checkCommodityMap(page, process.env.MAP_WEB_URL, baseline); }
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
