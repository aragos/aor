import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open, waitForMap} from './browser-support.mjs';
import {assertTextContrast} from './contrast-support.mjs';

const web = process.env.MAP_WEB_URL;
const game = await createGame(3);
for (const [index, link] of game.links.entries()) await game.send(link, `bid-capital ${index}`);
for (let step = 0; step < 12; step++) {
    const state = await game.snapshot(game.links[0]);
    if (state.game.stage === 'TOKEN_PURCHASE') break;
    const actor = game.links.find(link => state.prompt.players.includes(link.id));
    const choices = (await game.snapshot(actor)).legalChoices.commands;
    const command = choices.find(choice => choice.startsWith('choose-capital ')) ??
        choices.find(choice => choice === 'pass');
    assert(command, `Setup can progress at ${state.game.stage}`);
    await game.send(actor, command);
}
const viewer = game.links[0];
const baseline = await game.snapshot(viewer);
assert.equal(baseline.game.stage, 'TOKEN_PURCHASE');
const page = await open(web + viewer.path);
const click = selector => page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
try {
    await page.wait(`document.querySelector('#purchase-preview .resources')`, 'authoritative token quote');
    await waitForMap(page, baseline);
    await page.fill('#bid', '2');
    await page.wait(`document.querySelector('#purchase-preview .resources dd')?.textContent === '$2'`, 'edited token quote');
    for (const theme of ['light', 'dark']) {
        await page.evaluate(`localStorage.setItem('aor.setting.dark-mode', '${theme === 'dark'}'); window.dispatchEvent(new StorageEvent('storage', {key: 'aor.setting.dark-mode'}))`);
        await page.wait(`document.documentElement.dataset.theme === '${theme}'`, 'personal theme');
        for (const [width, height, mobile] of [[1440, 980, false], [390, 844, true]]) {
            await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
            await click('#tab-map');
            await assertTextContrast(page, `${theme} token quote at ${width}px`);
            assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '2');
            await page.evaluate(`document.querySelector('#purchase-preview').scrollIntoView({block: 'nearest'})`);
            assert(await page.evaluate(`(() => {
                const action = document.querySelector('.bid-action').getBoundingClientRect();
                return action.top >= 0 && action.bottom <= innerHeight;
            })()`), 'Confirmation stays reachable while reviewing the quote');
            await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `contrast-${theme}-${width}-purchase.png`));
            for (const tab of ['markets', 'advances', 'activity']) {
                await click(`#tab-${tab}`);
                await assertTextContrast(page, `${theme} ${tab} at ${width}px`);
                if (tab === 'advances') {
                    await click('[data-advance]');
                    await page.wait(`document.querySelector('.advance-inspection')`, 'advance price inspection');
                    await assertTextContrast(page, `${theme} advance details at ${width}px`);
                    await click('.advance-inspection .close-detail');
                }
            }
            await click('#tab-map');
            await click('[data-help-topic="misery-track"]');
            await page.wait(`document.querySelector('.reference-dialog #misery-track')`, 'misery reference');
            await assertTextContrast(page, `${theme} reference at ${width}px`, '.reference-dialog');
            await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `contrast-${theme}-${width}-reference.png`));
            await click('.reference-dialog .close-detail');
            await click('[data-help-topic="unplayed-cards"]');
            await page.wait(`document.querySelector('#unplayed-cards')`, 'remaining cards reference');
            await assertTextContrast(page, `${theme} remaining cards at ${width}px`, '.reference-dialog');
            for (const kind of ['commodity', 'event', 'leader']) {
                const card = baseline.game.unplayedCards.find(card => card.kind === kind);
                assert(card, `The initial deck contains a ${kind} card`);
                await click(`[data-unplayed-card="${card.id}"] button`);
                const detail = '.card-inspection-dialog[open]:not(:has(#unplayed-cards))';
                await page.wait(`document.querySelector('${detail}')`, `${kind} reference`);
                await assertTextContrast(page, `${theme} ${kind} reference at ${width}px`, detail);
                await click(`${detail} .close-detail`);
            }
            await click('.reference-dialog .close-detail');
            assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '2', 'Inspection preserves the draft');
        }
    }
    // Submit one simultaneous purchase: the saved private amount and the quote
    // coexist while the other two players have not submitted.
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 980, deviceScaleFactor: 1, mobile: false});
    await click('.bid-action');
    await page.wait(`document.querySelector('.pending-submission')`, 'saved private purchase');
    await assertTextContrast(page, 'dark saved purchase');
    // Continue with the authority's live offers to inspect shipping and advance prices.
    for (const link of game.links.slice(1)) await game.send(link, 'buy-tokens 0');
    for (let step = 0; step < 20; step++) {
        const state = await game.snapshot(viewer);
        if (state.game.shippingOffer) break;
        const actor = game.links.find(link => state.prompt.players.includes(link.id));
        const choices = (await game.snapshot(actor)).legalChoices.commands;
        const command = choices.find(choice => choice.startsWith('choose-turn-position ')) ??
            choices.find(choice => choice === 'pass') ?? choices.find(choice => choice === 'stabilize misery');
        assert(command, `Normal play can progress at ${state.game.stage}`);
        await game.send(actor, command);
    }
    const shipping = await game.snapshot(viewer);
    assert(shipping.game.shippingOffer, 'Normal play reaches a shipping offer');
    await page.reload();
    await page.wait(`document.querySelector('.shipping-action')`, 'live shipping price');
    await assertTextContrast(page, 'dark shipping preview');
    await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'contrast-dark-shipping.png'));
    console.log('Theme contrast passed: live token quotes, saved purchases, shipping, workspaces and reference at desktop/phone widths.');
} finally {
    page.ws.close();
    await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
}
