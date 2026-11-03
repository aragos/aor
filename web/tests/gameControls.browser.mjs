import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';

const api = process.env.MAP_API_URL;
const web = process.env.MAP_WEB_URL;
const game = await createGame(3, {undoPolicy: 'linear-game'});
const link = game.links[0];
const page = await open(web + link.path);
const click = selector => page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
async function vote(seat, operation) {
    const state = await game.snapshot(game.links[seat]);
    const response = await fetch(`${api}/api/games/${game.created.gameId}/abandonment`, {
        method: 'POST', headers: {'Content-Type': 'application/json', Authorization: 'Bearer ' + game.links[seat].token},
        body: JSON.stringify({operation, operationId: crypto.randomUUID(), expectedRevision: state.revision,
            ...(operation === 'propose' ? {} : {requestId: state.abandonment.requestId})}),
    });
    assert(response.ok, await response.clone().text());
    return response.json();
}
try {
    await page.send('Page.bringToFront');
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 980, deviceScaleFactor: 1, mobile: false});
    await page.wait(`document.querySelector('#bid')`, 'live setup');
    await click('[data-help-topic="misery-track"]');
    await page.wait(`document.querySelector('#misery-track')`, 'setup track lookup');
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-track-player]').length`), 0, 'Tracks omit player positions before capitals');
    await click('.reference-dialog .close-detail');
    await page.fill('#bid', '7');
    for (const [width, height, mobile] of [[1440, 980, false], [390, 844, true]]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        if (mobile) {
            await page.wait(`document.querySelector('#tab-players')`, 'Phone Players tab mounts after resizing');
            await click('#tab-players');
        }
        assert.equal(await page.evaluate(`document.querySelector('.abandon-game').checkVisibility()`), false,
            'Abandonment takes no space while Game options is closed');
        assert.equal(await page.evaluate(`document.querySelector('.abandon-game').closest('.table-configuration') !== null`), true);
        await click('.table-configuration > summary');
        await page.evaluate(`document.querySelector('.abandon-game').focus(); document.activeElement.click()`);
        await page.wait(`document.querySelector('dialog[open]')?.textContent.includes('Request abandonment')`, 'Abandonment confirmation opens through Game options');
        assert.equal((await game.snapshot(link)).abandonment, null, 'Opening confirmation does not propose abandonment');
        await page.click('Back');
        await page.wait(`!document.querySelector('dialog[open]')`, 'Abandonment confirmation closes');
        assert.equal(await page.evaluate(`document.activeElement.matches('.abandon-game')`), true, 'Closing confirmation restores focus to its opener');
        await click('.table-configuration > summary');
        if (mobile) await click('#tab-map');
        assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7', 'Reviewing abandonment preserves the bid draft');
    }
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 980, deviceScaleFactor: 1, mobile: false});
    await page.wait(`!document.querySelector('#tab-players')`, 'Desktop tool placement returns');
    await vote(1, 'propose');
    await page.wait(`document.querySelector('.abandonment-status')`, 'live consent offer');
    assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7', 'Consent polling preserves private browser draft');
    await page.click('Agree to abandon');
    await page.wait(`document.querySelector('.abandonment-status').textContent.includes('You agreed')`, 'nonfinal agreement');
    assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7', 'Nonfinal success preserves draft');
    await page.click('Withdraw agreement');
    await page.wait(`!document.querySelector('.abandonment-status')`, 'withdrawal closes request');
    assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7');

    for (let seat = 0; seat < 3; seat++) await game.send(game.links[seat], `bid-capital ${3 - seat}`);
    let reviewedAdvance = false;
    for (let step = 0; step < 160; step++) {
        const state = await game.snapshot(link);
        if (state.game.stage === 'EXPANSION') break;
        const actor = game.links.find(candidate => state.prompt?.players.includes(candidate.id));
        assert(actor, 'A live setup actor exists');
        const current = await game.snapshot(actor);
        const choices = current.legalChoices.commands;
        if (!reviewedAdvance && actor.id === link.id && current.game.activity?.purchaseStep === 'ADVANCES') {
            const command = choices.find(command => command.startsWith('buy-advance '));
            if (command) {
                const advance = current.game.catalog.advances.find(advance => advance.letter === command.split(' ')[1]);
                await page.wait(`document.querySelector('.command-dock summary small').textContent.endsWith('${current.revision}')`, 'live purchase polling');
                await click(`[data-advance="${advance.id}"]`);
                await page.wait(`document.querySelector('.advance-purchase-benefits')`, 'compact purchase review');
                assert.equal(await page.evaluate(`document.querySelectorAll('.advance-relief-rating').length`), 0, 'Selected purchase replaces generic MR summary');
                assert.equal(await page.evaluate(`document.querySelectorAll('.advance-decision .advance-prerequisites').length`), 0, 'Satisfied prerequisites are omitted');
                assert(await page.evaluate(`document.querySelector('.price-breakdown') !== null`), 'Named quote details are visible');
                const benefit = await page.evaluate(`document.querySelector('.advance-purchase-benefits').textContent`);
                if (advance.credit > 0) assert(benefit.includes('from next turn'));
                assert(benefit.includes('$' + current.game.reliefRatingAvailable));
                await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'advance-purchase-review.png'));
                await page.send('Emulation.setDeviceMetricsOverride', {width: 390, height: 844, deviceScaleFactor: 1, mobile: true});
                await page.wait(`document.querySelector('.buy-advance-action')`, 'phone purchase confirmation');
                assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth + 1`), true);
                await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'advance-purchase-review-phone.png'));
                await page.send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 980, deviceScaleFactor: 1, mobile: false});
                reviewedAdvance = true;
            }
        }
        await game.send(actor, current.game.stage === 'TOKEN_PURCHASE' ? `buy-tokens ${8 + game.links.indexOf(actor)}`
            : choices.includes('stabilize cash') ? 'stabilize cash' : choices.includes('pass') ? 'pass' : choices[0]);
    }
    assert(reviewedAdvance, 'Exercise an authoritative purchasable advance');
    let state = await game.snapshot(link);
    assert.equal(state.game.stage, 'EXPANSION');
    assert.equal(state.game.activity.actor, link.id);
    await page.wait(`document.querySelector('.command-dock summary small').textContent.endsWith('${state.revision}')`, 'expansion polling');
    await click('.pass-action');
    await page.wait(`document.querySelector('dialog[open]')?.textContent.includes('Pass anyway')`, 'pass review for reachable marker');
    assert.equal((await game.snapshot(link)).revision, state.revision, 'Opening pass review does not commit');
    await page.click('Back');
    await page.wait(`!document.querySelector('dialog[open]')`, 'dismiss pass review');
    assert.equal((await game.snapshot(link)).revision, state.revision);

    const drawn = await game.send(link, 'buy-expansion-card');
    await page.wait(`document.querySelector('.command-dock summary small').textContent.endsWith('${drawn.revision}') && document.querySelector('.undo-action:not([disabled])')`, 'linear undo after random draw');
    await page.click('Undo');
    await page.wait(`document.querySelector('dialog[open]')?.textContent.includes('This removes random outcomes')`, 'rewind consequence review');
    assert.equal((await game.snapshot(link)).revision, drawn.revision);
    await page.click('Rewind to here');
    await page.wait(`!document.querySelector('dialog[open]')`, 'rewind accepted');
    const undone = await game.snapshot(link);
    assert.equal(undone.revision, drawn.revision + 1);
    assert.deepEqual(undone.game.board, state.game.board);
    assert.deepEqual(undone.game.hand, state.game.hand);
    assert.equal(await page.evaluate(`document.querySelectorAll('.history-editor').length`), 0, 'Linear games expose no suffix editor');
    assert.equal(undone.undo.capability, 'PREFIX_REWIND');

    await vote(1, 'propose');
    await vote(0, 'agree');
    const ended = await vote(2, 'agree');
    await page.wait(`document.querySelector('.ending-notice')?.textContent.includes('Abandoned by agreement')`, 'terminal consensus notice');
    assert.equal(ended.status, 'ABANDONED');
    assert.equal(await page.evaluate(`document.querySelectorAll('.undo-action, .pass-action, .capital-confirmation, .abandonment-status').length`), 0, 'Terminal cleanup removes executable decisions');
    assert.equal(await page.evaluate(`document.querySelectorAll('.final-scores').length`), 0, 'Abandonment does not score the game');
    await page.send('Emulation.setDeviceMetricsOverride', {width: 390, height: 844, deviceScaleFactor: 1, mobile: true});
    await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'unanimously-abandoned-phone.png'));
    console.log('Live game controls passed: setup tracks, draft-safe consent, advance review, pass confirmation, random rewind, unanimous terminal cleanup.');
} finally {page.ws.close();}
