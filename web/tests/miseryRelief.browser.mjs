import assert from 'node:assert/strict';
import {join} from 'node:path';
import {pathToFileURL} from 'node:url';
import {open, createGame} from './browser-support.mjs';

export async function checkLiveMiseryRelief(page, web) {
    const game = await createGame(3);
    const target = game.links[0];
    const tier = ['THE_HEAVENS', 'PATRONAGE', 'CARAVAN', 'WRITTEN_RECORD', 'OVERLAND_EAST', 'URBAN_ASCENDANCY'];
    for (const link of game.links) await game.send(link, 'bid-capital 0');
    let checkedPurchase = false;
    let checkedBlocked = false;
    let checkedRating = false;
    for (let step = 0; step < 550 && !(checkedPurchase && checkedBlocked && checkedRating); step++) {
        const publicState = await game.snapshot(target);
        const actor = game.links.find(link => publicState.prompt?.players.includes(link.id));
        assert(actor, `An actor can continue the live relief game at ${publicState.game.stage}, turn ${publicState.game.turnNumber}: ${JSON.stringify(publicState.game.players.map(player => ({id: player.id, cash: player.cash, misery: player.misery, advances: player.advances})))}`);
        const state = await game.snapshot(actor);
        const player = state.game.players.find(player => player.id === actor.id);
        const commands = state.legalChoices.commands;
        if (actor.id === target.id && state.game.activity?.purchaseStep === 'RELIEF') {
            const affordable = state.game.reliefOffers.find(offer => offer.affordable);
            const previousDocument = await page.evaluate('performance.timeOrigin');
            const url = new URL(target.path, web);
            url.searchParams.set('relief-check', String(state.revision));
            await page.send('Page.navigate', {url: url.href});
            await page.wait(`performance.timeOrigin !== ${previousDocument} && document.querySelector('.relief-rating-summary')`, 'live relief decision');
            assert.equal(await page.evaluate(`document.querySelector('#decision-title').textContent`), 'Purchase · Misery relief');
            assert.equal(await page.evaluate(`document.querySelector('.pass-action').textContent`), 'Finish purchase');
            assert.equal(await page.evaluate(`document.querySelector('.relief-rating-summary strong').textContent`), `$${state.game.reliefRatingAvailable}`);
            checkedRating ||= state.game.reliefRatingAvailable > 0;
            if (!affordable && state.game.reliefOffers.length > 0 && !checkedBlocked) {
                assert.equal(await page.evaluate(`document.querySelector('.relief-action').disabled`), true);
                assert.equal(await page.evaluate(`document.querySelector('.pass-action').disabled`), false);
                const response = await fetch(`${process.env.MAP_API_URL}/api/games/${game.created.gameId}/commands`, {
                    method: 'POST', headers: {'Content-Type': 'application/json', Authorization: 'Bearer ' + actor.token},
                    body: JSON.stringify({expectedRevision: state.revision, command: 'relieve-misery 1'}),
                });
                assert.equal(response.status, 422);
                assert.equal((await response.json()).code, 'INSUFFICIENT_CASH');
                assert.equal((await game.snapshot(actor)).revision, state.revision, 'Rejected relief never advances');
                await page.evaluate(`new Promise(resolve => setTimeout(resolve, 1200))`);
                assert.equal((await game.snapshot(actor)).revision, state.revision, 'Polling never auto-passes blocked relief');
                await page.evaluate(`document.querySelector('.pass-action').click()`);
                await page.wait(`!document.querySelector('.relief-rating-summary')`, 'explicit relief pass');
                checkedBlocked = true;
                continue;
            }
            if (affordable && !checkedPurchase) {
                const saved = await fetch(`${process.env.MAP_API_URL}/api/games/${game.created.gameId}/plans`, {
                    method: 'POST', headers: {'Content-Type': 'application/json', Authorization: `Bearer ${actor.token}`},
                    body: JSON.stringify({expectedRevision: state.revision, expectedVersion: state.planning.version,
                        operationId: crypto.randomUUID(), operation: 'SAVE', actions: []}),
                });
                assert(saved.ok, 'An empty review plan may be edited on the active relief decision');
                await page.reload();
                await page.wait(`document.querySelector('.activity-planner')`, 'saved relief plan');
                await page.click('Edit plan');
                await page.wait(`document.querySelector('.activity-planner[data-editing="true"]') && document.querySelector('#relief-spaces button:not(:disabled)')`, 'planned relief quote');
                await page.evaluate(`document.querySelector('#relief-spaces button:not(:disabled)').click()`);
                assert.equal((await game.snapshot(actor)).revision, state.revision, 'Choosing spaces only edits the draft');
                await page.evaluate(`new Promise(resolve => setTimeout(resolve, 1200))`);
                assert.equal(await page.evaluate(`document.querySelector('#relief-spaces button[aria-pressed="true"]').textContent.includes('${affordable.spaces}')`), true, 'Polling retains the selected purchase');
                await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-relief-purchase-desktop.png'), 1280, 900);
                await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-relief-purchase-phone.png'), 390, 844, true);
                assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
                await page.evaluate(`document.querySelector('.relief-action').click()`);
                await page.wait(`[...document.querySelectorAll('.decision-actions button')].some(button => button.textContent === 'Save and execute plan' && !button.disabled)`, 'explicit relief plan application');
                assert.equal((await game.snapshot(actor)).revision, state.revision, 'Adding relief to a plan has no game effect');
                assert.match(await page.evaluate(`document.querySelector('.plan-actions').textContent`), /Buy misery relief/);
                await page.click('Save and execute plan');
                await page.wait(`!document.querySelector('.relief-rating-summary') && !document.querySelector('.activity-planner[data-editing="true"]')`, 'confirmed relief finishes purchasing');
                const after = await game.snapshot(actor);
                assert.equal(after.revision, state.revision + 1, 'Purchase and progression use one command');
                assert.equal(after.history.at(-1), `${actor.id}: relieve-misery ${affordable.spaces}`);
                assert.equal(after.game.players.find(player => player.id === actor.id).cash, affordable.cashAfter);
                assert.equal(after.game.players.find(player => player.id === actor.id).misery, affordable.miseryAfter);
                assert(after.game.stage !== 'PURCHASE' || after.game.activity.actor !== actor.id, 'Purchaser no longer owns the purchase step');
                checkedPurchase = true;
                continue;
            }
        }
        const advance = actor.id === target.id && state.game.activity?.purchaseStep === 'ADVANCES'
            ? state.game.advanceOffers.find(offer => tier.includes(offer.advance) && !offer.unavailableReason)
            : null;
        const letter = advance && state.game.catalog.advances.find(item => item.id === advance.advance).letter;
        const safePlay = state.game.stage === 'PLAY_CARDS' && state.game.hand
            .filter(card => ['commodity', 'leader'].includes(card.definition.kind))
            .flatMap(card => commands.filter(command => command === `play ${card.reference}` || command.startsWith(`play ${card.reference} `)))[0];
        const command = state.game.stage === 'TOKEN_PURCHASE'
            ? `buy-tokens ${actor.id === target.id && checkedPurchase && !checkedBlocked ? player.cash : 0}`
            : safePlay ? safePlay
            : letter && commands.includes(`buy-advance ${letter}`) ? `buy-advance ${letter}`
            : state.game.activity?.purchaseStep === 'STABILIZATION' && actor.id === target.id ? 'stabilize misery'
            : commands.find(command => command.startsWith('choose-capital '))
                ?? commands.find(command => command.startsWith('choose-turn-position '))
                ?? commands.find(command => command === 'pass')
                ?? commands.find(command => command === 'stabilize cash')
                ?? commands.find(command => command === 'stabilize misery');
        assert(command, `A live setup action is available at ${state.game.stage}`);
        if (actor.id === target.id && command.startsWith('buy-advance ')) {
            const previousDocument = await page.evaluate('performance.timeOrigin');
            const url = new URL(target.path, web);
            url.searchParams.set('advance-check', String(state.revision));
            await page.send('Page.navigate', {url: url.href});
            await page.wait(`performance.timeOrigin !== ${previousDocument} && document.querySelector('.advance-relief-rating')`, 'live accumulated MR before advance purchase');
            assert.equal(await page.evaluate(`document.querySelector('#decision-title').textContent`), 'Purchase · Advances');
            assert.equal(await page.evaluate(`document.querySelector('.pass-action').textContent`), 'Continue to stabilization');
            assert.equal(await page.evaluate(`document.querySelector('.advance-relief-rating strong').textContent`), `$${state.game.reliefRatingAvailable}`);
            await page.evaluate(`document.querySelector('[data-advance="${advance.advance}"]').click()`);
            assert.equal(await page.evaluate(`document.querySelectorAll('.advance-relief-rating').length`), 0, 'Selected review replaces the generic MR summary');
            assert.match(await page.evaluate(`document.querySelector('.advance-purchase-benefits .advance-relief dd').textContent`), new RegExp('\\$' + state.game.reliefRatingAvailable));
            const definition = state.game.catalog.advances.find(item => item.id === advance.advance);
            const expected = state.game.reliefRatingAvailable + definition.miseryRelief;
            await page.evaluate(`document.querySelector('.buy-advance-action').click()`);
            await page.wait(`!document.querySelector('.buy-advance-action') && document.querySelector('.advance-relief-rating strong')?.textContent === '$${expected}'`, 'accumulated MR after confirmed advance purchase');
            assert.equal((await game.snapshot(actor)).game.reliefRatingAvailable, expected);
            await page.reload();
            await page.wait(`document.querySelector('.advance-relief-rating strong')?.textContent === '$${expected}'`, 'accumulated MR after reload');
            if (expected > 0) {
                await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-advance-mr-desktop.png'), 1280, 900);
                await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-advance-mr-phone.png'), 390, 844, true);
                assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
            }
        } else {
            await game.send(actor, command);
        }
    }
    assert(checkedRating, 'A live purchase decision displays MR from advances acquired this turn');
    assert(checkedPurchase, 'A live confirmed purchase finishes purchasing automatically');
    assert(checkedBlocked, 'Unaffordable live relief requires explicit Pass');
    console.log('Passed: live accumulated MR during advances, current-turn relief display, draft/polling preservation, one-command relief completion and explicit unaffordable Pass.');
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
    const page = await open(process.env.MAP_WEB_URL);
    try { await checkLiveMiseryRelief(page, process.env.MAP_WEB_URL); }
    finally { page.ws.close(); await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`); }
}
