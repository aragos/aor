import assert from 'node:assert/strict';
import {join} from 'node:path';

export async function checkCompactPresentation(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.send('Page.bringToFront');
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'compact presentation harness');
    const viewer = baseline.viewerId;
    const commodity = baseline.game.cardInspections.find(card => card.definition.id === 'cloth-wine');
    const leader = baseline.game.cardInspections.find(card => card.definition.id === 'charlemagne');
    const card = {reference: 'compact-choice', definition: commodity.definition};
    const offer = {reference: card.reference, playable: true, blockers: [], commodities: commodity.commodities,
        leaderDiscounts: [], provinceTargets: [], requiredTargetCount: null, protectionMax: null,
        choices: commodity.commodities.map(value => ({kind: 'COMMODITY', value: value.toLowerCase(),
            command: `play ${card.reference} commodity ${value.toLowerCase()}`}))};
    let revision = 37000;
    let fixture;
    async function show(changes = {}) {
        fixture = {...baseline, prompt: {messageId: 'prompt.play-cards', players: [viewer]},
            legalChoices: {commands: [...offer.choices.map(choice => choice.command), 'pass']},
            undo: {...baseline.undo, available: true}, ...changes, revision: ++revision,
            game: {...baseline.game, stage: 'PLAY_CARDS',
                hand: [card, {reference: 'compact-leader', definition: leader.definition}],
                cardOffers: [offer], ...changes.game}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'compact fixture render');
        await page.send('Page.bringToFront');
        await page.evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
    }
    const click = selector => page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
    const dimensions = selector => page.evaluate(`(() => {const r=document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect();return {width:r.width,height:r.height};})()`);
    for (const [width, height, mobile] of [[1280, 900, false], [390, 844, true]]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        console.log('Compact presentation:', width, 'initial layout');
        await show();
        const undoSize = await dimensions('.undo-action');
        assert.deepEqual(undoSize, {width: 70, height: 38});
        assert((await dimensions('.pass-action')).height >= 44, 'Final actions retain a touch-sized target');
        if (mobile) {
            assert.equal(await page.evaluate(`[...document.querySelectorAll('.hand-card > :not(h3), .card-track-links')].some(node => node.checkVisibility())`), false, 'Compact phone cards show names only');
            assert(await page.evaluate(`document.querySelector('.table-hand').getBoundingClientRect().height < 65`), 'Name-only hand minimizes its height');
            await click('.hand-toggle');
            assert.equal(await page.evaluate(`document.querySelector('.hand-card .card-commodity').checkVisibility()`), true, 'Expanding reveals commodity content');
            await click('.hand-toggle');
        }
        console.log('Compact presentation:', width, 'player details');
        if (mobile) await click('#tab-players');
        const overviewBefore = await dimensions('.table-overview');
        const mapBefore = await dimensions('.world-map');
        await click(`[data-player="${viewer}"] > summary`);
        await page.wait(`document.querySelector('[data-player="${viewer}"] .player-detail:popover-open')`, 'Player details float over the table');
        assert.deepEqual(await dimensions('.table-overview'), overviewBefore, 'Expanded details keep the overview height');
        assert.deepEqual(await dimensions('.world-map'), mapBefore, 'Expanded details keep the map dimensions');
        assert.equal(await page.evaluate(`document.querySelector('[data-player="${viewer}"] .player-detail > h3')`), null, 'Player details omit the repeated name');
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-floating-player-${width}.png`));
        assert.equal(await page.evaluate(`document.querySelector('[data-player="${viewer}"] .player-detail .close-detail')`), null, 'Expanded player text has no overlapping close button');
        await page.send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27, nativeVirtualKeyCode: 27});
        await page.send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27, nativeVirtualKeyCode: 27});
        await page.wait(`!document.querySelector('.player-detail:popover-open')`, 'Player detail closes');
        if (mobile) await click('#tab-map');
        console.log('Compact presentation:', width, 'commodity choice');
        await click('[data-hand-card="compact-choice"]');
        assert.deepEqual(await dimensions('.undo-action'), undoSize, 'Object review keeps Undo in its fixed toolbar');
        assert.equal(await page.evaluate(`document.querySelectorAll('.card-decision .target-choices .commodity-name svg').length`), 2, 'Both commodity choices carry their symbols');
        await click('.card-decision .target-choices button:last-child');
        const choice = await page.evaluate(`document.querySelector('.card-decision .target-choices [aria-pressed=true]').textContent`);
        if (mobile) await click('.hand-toggle');
        console.log('Compact presentation:', width, 'reference popup');
        const help = '.table-hand [data-help-topic="commodity-cloth"]';
        const location = await page.evaluate('location.href');
        const title = await page.evaluate('document.title');
        await page.evaluate(`document.querySelector(${JSON.stringify(help)}).focus(); document.activeElement.click()`);
        await page.wait(`document.querySelector('.reference-dialog #commodity-cloth .payout-box')`, 'Commodity reference popup loads');
        assert.equal(await page.evaluate('location.href'), location, 'Help never navigates away');
        assert.equal(await page.evaluate('document.title'), title, 'Help preserves the game title');
        await show({revision: revision + 1});
        assert.equal(await page.evaluate(`document.querySelector('.card-decision .target-choices [aria-pressed=true]').textContent`), choice, 'Reference polling preserves the commodity draft');
        assert.equal(await page.evaluate(`document.querySelectorAll('.reference-dialog .payout-box').length`), 10, 'Only the requested commodity track is shown');
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-reference-popup-${width}.png`));
        await page.send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27, nativeVirtualKeyCode: 27});
        await page.send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27, nativeVirtualKeyCode: 27});
        await page.wait(`!document.querySelector('.reference-dialog')`, 'Reference Escape closes');
        assert.equal(await page.evaluate(`document.activeElement.dataset.helpTopic`), 'commodity-cloth', 'Reference restores focus');
        if (mobile) await click('.hand-toggle');
        assert.equal(await page.evaluate(`document.querySelector('.workspace-side .table-activity')`), null, 'The action sidebar has its own full height');
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-compact-table-${width}.png`));
        await show({prompt: {messageId: 'prompt.play-cards', players: []}, legalChoices: {commands: []}});
        assert.equal(await page.evaluate(`document.querySelector('.pass-action')`), null);
        assert.deepEqual(await dimensions('.undo-action'), undoSize, 'Undo stays the same size when Pass is absent');
    }
    console.log('Passed: name-only phone cards, expanded detail, floating player boxes without resize, fixed action buttons, separate activity workspace, commodity symbols and reference popup draft/focus preservation.');
}
