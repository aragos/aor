import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';

// Compare text boxes minus each font's descent, including rows with mixed font sizes.
// Container centers can agree while the text does not; superscript help controls are separate.
export async function assertTextAlignment(page, selector, description) {
    const failures = await page.evaluate(`(() => {
        const failures = [];
        const rows = document.querySelectorAll(${JSON.stringify(selector)});
        if (!rows.length) throw new Error('No text rows matched the alignment check');
        const context = document.createElement('canvas').getContext('2d');
        for (const row of rows) {
            const walker = document.createTreeWalker(row, NodeFilter.SHOW_TEXT);
            const boxes = [];
            while (walker.nextNode()) {
                const node = walker.currentNode;
                if (!node.textContent.trim() || node.parentElement.closest('svg, .sr-only, .help-link, sup, sub')) continue;
                const style = getComputedStyle(node.parentElement);
                context.font = style.fontStyle + ' ' + style.fontWeight + ' ' + style.fontSize + ' ' + style.fontFamily;
                const descent = context.measureText(node.textContent.trim()).fontBoundingBoxDescent;
                const range = document.createRange();
                range.selectNodeContents(node);
                for (const rect of range.getClientRects()) {
                    if (rect.width && rect.height) boxes.push({text: node.textContent.trim(),
                        top: rect.top, baseline: rect.bottom - descent, height: rect.height});
                }
            }
            for (const [index, box] of boxes.entries()) {
                for (const other of boxes.slice(index + 1)) {
                    // Ignore separate wrapped lines, but allow enough overlap to catch shifted text.
                    if (Math.abs(box.top - other.top) >= Math.min(box.height, other.height) / 2) continue;
                    if (Math.abs(box.baseline - other.baseline) > 1) failures.push({
                        row: row.dataset.alignmentCase ?? row.className, box, other});
                }
            }
        }
        return failures;
    })()`);
    assert.deepEqual(failures, [], description);
}

export async function checkIdentityAlignment(page, web) {
    for (const [width, height, mobile] of [[1280, 900, false], [390, 844, true], [320, 740, true]]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        await page.send('Page.navigate', {url: web + '/tests/identity-harness.html'});
        await page.wait(`document.querySelectorAll('[data-alignment-case]').length === 74`, 'identity examples');
        await page.send('Page.bringToFront');
        for (const fontSize of [14, 18]) {
            await page.evaluate(`document.documentElement.style.fontSize = '${fontSize}px'; document.fonts.ready`);
            await assertTextAlignment(page, '[data-alignment-case]', `Capital text shares the surrounding baseline at ${width}px / ${fontSize}px font`);
            assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`), true, 'Identity examples fit the viewport');
        }
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `identity-typography-${width}.png`));
    }

    // Use live assignments and offers to cover the actual waiting status and its separators.
    const game = await createGame(6);
    for (const player of game.links) await game.send(player, 'bid-capital 0');
    let state;
    for (let step = 0; step < 30; step++) {
        state = await game.snapshot(game.links[0]);
        if (state.game.stage === 'TOKEN_PURCHASE') break;
        const actor = game.links.find(player => state.prompt?.players.includes(player.id));
        assert(actor, 'Capital assignment has an actor');
        const offer = await game.snapshot(actor);
        const command = offer.legalChoices.commands.find(command => command.startsWith('choose-capital '));
        assert(command, 'Live capital assignment offers a choice');
        await game.send(actor, command);
    }
    assert.equal(state.game.stage, 'TOKEN_PURCHASE');
    const viewer = game.links[0];
    await game.send(viewer, 'buy-tokens 1');
    for (const pending of [5, 1]) {
        if (pending === 1) {
            for (const player of game.links.slice(1, -1)) await game.send(player, 'buy-tokens 1');
            await page.wait(`document.querySelectorAll('.waiting-players .identity-label').length === 1`, 'polling updates the waiting list');
        }
        for (const [width, height, mobile] of [[1280, 900, false], [390, 844, true]]) {
            await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
            await page.send('Page.navigate', {url: web + viewer.path});
            await page.wait(`document.querySelectorAll('.waiting-players .identity-label').length === ${pending}`, `live waiting for ${pending} capitals`);
            await assertTextAlignment(page, '.decision-status', `Live waiting status text aligns at ${width}px with ${pending} pending`);
            await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `identity-waiting-${pending}-${width}.png`));
        }
    }
    await page.send('Emulation.clearDeviceMetricsOverride');
    console.log('Passed: capital baselines in sentences, translations, headings, buttons, small print, wrapping and live waiting lists at desktop/phone widths and enlarged text.');
}

if (process.argv[1]?.endsWith('/identityAlignment.browser.mjs')) {
    const page = await open('about:blank');
    try { await checkIdentityAlignment(page, process.env.MAP_WEB_URL); }
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
