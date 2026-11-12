import assert from 'node:assert/strict';
import {join} from 'node:path';
import {pathToFileURL} from 'node:url';
import {createGame, open} from './browser-support.mjs';

const sizes = [[1280, 900, false], [390, 844, true]];
const click = (page, selector) => page.evaluate(`(() => {
    const button = document.querySelector(${JSON.stringify(selector)});
    button.focus(); button.click();
})()`);

async function visit(page, web, actor, revision) {
    await page.send('Page.navigate', {url: web + actor.path});
    await page.wait(`document.querySelector('#tool-tab-commands small')?.textContent.trim() === 'Revision ${revision}'`,
        'live purchase decision');
}

async function rejectNextCommand(page) {
    await page.evaluate(`(() => {
        const original = window.fetch.bind(window);
        window.fetch = async (input, init) => {
            if (String(input).endsWith('/commands') && init?.method === 'POST') {
                window.fetch = original;
                return new Response(JSON.stringify({code: 'INSUFFICIENT_CASH'}), {status: 422});
            }
            return original(input, init);
        };
    })()`);
}

async function checkPatronage(page, web, game, actor, state) {
    await visit(page, web, actor, state.revision);
    const offer = state.game.patronageOffers[0];
    const leader = offer.command.split(' ')[1].toUpperCase().replace(/-/g, '_');
    const selector = `[data-played-leader="${leader}"] .patronage-action`;
    await page.wait(`document.querySelector(${JSON.stringify(selector)})`, 'direct Patronage action');
    for (const [width, height, mobile] of sizes) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        const payment = await page.evaluate(`document.querySelector(${JSON.stringify(selector)}).parentElement.textContent`);
        assert(payment.includes(`$${offer.payerCashAfter}`) && payment.includes(`$${offer.recipientCashAfter}`),
            'Both authoritative cash endpoints appear beside the claim');
        assert.match(await page.evaluate(`document.querySelector(${JSON.stringify(selector)}).textContent`),
            new RegExp(`Patronize .+ · \\$${offer.cost}$`));
        assert.equal(await page.evaluate(`document.querySelectorAll('.patronage-action').length`), state.game.patronageOffers.length,
            'Each eligible leader has one claim action');
        assert.equal(await page.evaluate(`document.querySelector('.game-actions .action-buttons, .selected-action-confirmation')`), null,
            'Patronage has no duplicate selector or confirmation');
        await page.evaluate(`document.querySelector(${JSON.stringify(selector)}).scrollIntoView({block: 'center'})`);
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `patronage-direct-${width}.png`));
        assert(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`));
    }
    await click(page, `[data-played-leader="${leader}"] .map-link`);
    await page.wait(`document.querySelector('.card-inspection-dialog')?.open`, 'leader reference');
    await page.send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
    await page.wait(`!document.querySelector('.card-inspection-dialog')`, 'leader reference closed');
    assert(await page.evaluate(`document.activeElement.matches('[data-played-leader="${leader}"] .map-link')`),
        'Leader inspection restores focus');
    await rejectNextCommand(page);
    await click(page, selector);
    await page.wait(`document.querySelector('.decision-panel [role="alert"]') && !document.querySelector(${JSON.stringify(selector)})?.disabled`,
        'rejected claim remains correctable');
    assert.equal((await game.snapshot(actor)).revision, state.revision, 'Rejected claim has no game effect');
    // A real poll and a reference visit must not turn the claim into a selected, two-step action.
    await page.reload();
    await page.wait(`document.querySelector(${JSON.stringify(selector)}) && !document.querySelector(${JSON.stringify(selector)}).disabled`,
        'live claim after reload');
    await click(page, selector);
    await page.wait(`!document.querySelector(${JSON.stringify(selector)})`, 'one click commits the claim');
    const after = await game.snapshot(actor);
    assert.equal(after.history.filter(line => line === `${actor.id}: ${offer.command}`).length, 1);
    assert.equal(after.game.players.find(player => player.id === offer.payer).cash, offer.payerCashAfter);
    assert.equal(after.game.players.find(player => player.id === offer.recipient).cash, offer.recipientCashAfter);
}

async function checkRenaissance(page, web, game, actor, state) {
    await visit(page, web, actor, state.revision);
    const scheduled = command => `.card-purchase-choices button${command === 'buy-card' ? ':first-child' : ':last-child'}`;
    await page.wait(`document.querySelector('.card-purchase-choices button')`, 'card choice during Renaissance');
    for (const [width, height, mobile] of sizes) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        assert.equal(await page.evaluate(`document.querySelector('.pass-action').textContent`), 'Keep turn order');
        assert.equal(await page.evaluate(`document.querySelectorAll('.decision-actions button').length`), 1,
            'Only the current Renaissance commitment occupies the footer');
        assert.equal(await page.evaluate(`document.querySelector(${JSON.stringify(scheduled('pass'))}).textContent`), 'Pass on my turn');
        assert.match(await page.evaluate(`document.querySelector('.card-purchase-planner').textContent`), /wait until Renaissance is resolved/);
        await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `renaissance-card-choice-${width}.png`), width, height, mobile);
        assert(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`));
    }
    await click(page, scheduled('pass'));
    await page.wait(`document.querySelector(${JSON.stringify(scheduled('pass'))})?.getAttribute('aria-pressed') === 'true'`, 'card Pass scheduled');
    assert.equal((await game.snapshot(actor)).revision, state.revision, 'Scheduling never passes Renaissance');
    assert.equal(await page.evaluate(`document.querySelector('.pass-action').disabled`), false,
        'A scheduled card Pass leaves Renaissance enabled');
    await page.reload();
    await page.wait(`document.querySelector(${JSON.stringify(scheduled('pass'))})?.getAttribute('aria-pressed') === 'true'`, 'scheduled choice survives polling and reload');
    assert.equal(await page.evaluate(`document.querySelector('.pass-action').disabled`), false);
    await page.click('Cancel choice');
    await page.wait(`!document.querySelector('.card-purchase-planner [role="status"]')`, 'scheduled Pass cancelled');
    await rejectNextCommand(page);
    await page.click('Keep turn order');
    await page.wait(`document.querySelector('.decision-panel [role="alert"]') && !document.querySelector('.pass-action').disabled`,
        'rejected Renaissance can be retried');
    await page.click('Keep turn order');
    await page.wait(`document.querySelector('#decision-title')?.textContent === 'Card purchase'`, 'Keep turn order resolves only Renaissance');
    const kept = await game.snapshot(actor);
    assert.equal(kept.game.stage, 'BUY_CARD');
    assert.equal(kept.prompt.messageId, 'prompt.buy-card');
    assert.deepEqual(kept.game.turnOrder, state.game.turnOrder);
    assert.equal(await page.evaluate(`document.querySelector('.card-purchase-planner')`), null,
        'The active card buyer uses ordinary Buy/Pass');
    assert.equal(await page.evaluate(`document.querySelector('.pass-action').textContent`), 'Pass');

    // Restore the same live window to check the other Renaissance commitment independently.
    const restored = await game.send(actor, 'undo');
    await page.wait(`document.querySelector('#tool-tab-commands small')?.textContent.trim() === 'Revision ${restored.revision}' && document.querySelector('.card-purchase-choices button')`,
        'Renaissance window restored');
    await click(page, scheduled('pass'));
    await page.wait(`document.querySelector(${JSON.stringify(scheduled('pass'))})?.getAttribute('aria-pressed') === 'true'`,
        'card Pass rescheduled for Keep turn order');
    await page.click('Keep turn order');
    await page.wait(`document.querySelector('#tool-tab-commands small')?.textContent.trim() !== 'Revision ${restored.revision}'`,
        'Keep preserves and executes the scheduled card Pass');
    const passed = await game.snapshot(actor);
    assert.equal(passed.history.filter(line => line === `${actor.id}: pass`).length,
        restored.history.filter(line => line === `${actor.id}: pass`).length + 2,
        'Keep resolves Renaissance and executes exactly the scheduled card Pass');
    const swapWindow = await game.send(actor, `undo ${restored.undo.entryCount}`);
    await page.wait(`document.querySelector('#tool-tab-commands small')?.textContent.trim() === 'Revision ${swapWindow.revision}' && document.querySelector('.card-purchase-choices button')`,
        'independent swap window restored');
    await click(page, scheduled('buy-card'));
    await page.wait(`document.querySelector(${JSON.stringify(scheduled('buy-card'))})?.getAttribute('aria-pressed') === 'true'`, 'card Buy scheduled');
    await click(page, '.game-actions .action-buttons button');
    await page.wait(`document.querySelector('.selected-action-confirmation:not([disabled])')`, 'scheduled Buy permits preparing an order swap');
    assert.equal((await game.snapshot(actor)).revision, swapWindow.revision, 'Preparing the swap does not submit it');
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'renaissance-scheduled-swap-phone.png'), 390, 844, true);
    const widths = await page.evaluate(`['.selected-action-confirmation', '.renaissance-pass']
        .map(selector => document.querySelector(selector).getBoundingClientRect().width)`);
    assert(Math.abs(widths[0] - widths[1]) <= 1, 'Both Renaissance commitments stay readable on a phone');
    const command = swapWindow.legalChoices.commands.find(command => command.startsWith('swap-order '));
    await click(page, '.selected-action-confirmation');
    await page.wait(`document.querySelector('#tool-tab-commands small')?.textContent.trim() !== 'Revision ${swapWindow.revision}'`, 'swap commits with scheduled Buy intact');
    let after = await game.snapshot(actor);
    assert(after.history.includes(`${actor.id}: ${command}`));
    assert.notDeepEqual(after.game.turnOrder, state.game.turnOrder);
    for (let step = 0; after.game.stage === 'BUY_CARD' && step < 12; step++) {
        const next = game.links.find(link => after.prompt?.players.includes(link.id));
        assert(next, 'An actor can finish the card purchases');
        const own = await game.snapshot(next);
        const continuation = own.legalChoices.commands.find(command => command.startsWith('discard-card ')) ?? 'pass';
        await game.send(next, continuation);
        after = await game.snapshot(actor);
    }
    assert.equal(after.history.filter(line => line === `${actor.id}: buy-card`).length, 1,
        `The scheduled card Buy executes exactly once after Renaissance: ${JSON.stringify({saved: after.planning?.saved,
            executions: after.planning?.executions, history: after.history.slice(-15)})}`);
}

/** Reach the offers through ordinary play; do not fabricate legality or projected prices. */
export async function checkLivePurchaseDecisions(page, web) {
    let patronage = false, renaissance = false;
    for (let attempt = 0; attempt < 4 && (!patronage || !renaissance); attempt++) {
        const game = await createGame(3, {undoPolicy: 'linear-game'});
        for (let step = 0; step < 850; step++) {
            const publicState = await game.snapshot(game.links[0]);
            if (publicState.game.stage === 'FINISHED') break;
            const actor = game.links.find(link => publicState.prompt?.players.includes(link.id));
            assert(actor, 'Normal play has an actor');
            const state = await game.snapshot(actor);
            const commands = state.legalChoices.commands;
            if (!patronage && state.game.patronageOffers.length > 0) {
                await checkPatronage(page, web, game, actor, state);
                patronage = true;
                continue;
            }
            if (!renaissance && state.game.stage === 'BUY_CARD' && state.prompt.messageId === 'prompt.renaissance' &&
                state.game.cardPurchaseOffer?.blockers.length === 0) {
                await checkRenaissance(page, web, game, actor, state);
                renaissance = true;
                continue;
            }
            if (patronage && renaissance) break;
            let command;
            if (state.game.stage === 'BIDDING') command = `bid-capital ${3 - game.links.indexOf(actor)}`;
            else if (commands.includes('buy-tokens')) {
                const me = state.game.players.find(player => player.id === actor.id);
                const tokens = actor.id === game.links[0].id ? Math.min(6, me.cash, me.stockTokens) : game.links.indexOf(actor);
                command = `buy-tokens ${tokens}`;
            }
            else if (actor.id === game.links[0].id) command = ['V', 'E', 'N', 'O', 'P', 'Q']
                .map(letter => `buy-advance ${letter}`).find(command => commands.includes(command));
            if (!command && actor.id === game.links[0].id && state.game.stage === 'EXPANSION') {
                const placement = state.game.expansionOffers.filter(offer => offer.primaryAction === 'PLACE' &&
                    offer.establishesMarker && offer.blockers.length === 0 && offer.resultingPool !== null)
                    .sort((first, second) => first.requiredTokens - second.requiredTokens)[0];
                if (placement) command = `place ${placement.requiredTokens} ${placement.destination}`;
            }
            command ??= commands.find(command => command.startsWith('play ') && ['leader', 'commodity']
                .includes(state.game.hand.find(card => card.reference === command.split(' ')[1])?.definition.kind));
            if (!command && actor.id !== game.links[0].id && commands.includes('buy-card')) command = 'buy-card';
            command ??= commands.find(command => /^(choose-capital|choose-turn-position|return-card|discard-card) /.test(command))
                ?? commands.find(command => command === 'stabilize cash')
                ?? commands.find(command => command === 'stabilize misery')
                ?? commands.find(command => command === 'pass') ?? commands[0];
            assert(command, `Normal play can continue at ${state.prompt?.messageId}`);
            await game.send(actor, command);
        }
    }
    assert(patronage && renaissance, 'Normal play reaches live Patronage and Renaissance card-purchase decisions');
    console.log('Passed: single-click live Patronage, payment endpoints, rejection and references; independent Renaissance/card choices, reload, cancellation, Keep/swap and scheduled execution.');
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
    const web = process.env.MAP_WEB_URL;
    const page = await open(web);
    try {await checkLivePurchaseDecisions(page, web);}
    finally {page.ws.close(); await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);}
}
