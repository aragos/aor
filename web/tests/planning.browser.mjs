import assert from 'node:assert/strict';
import {join} from 'node:path';
import {pathToFileURL} from 'node:url';
import {open, createGame} from './browser-support.mjs';
import {checkPurchaseClarity} from './purchaseClarity.browser.mjs';
import {checkReferenceUx} from './referenceUx.browser.mjs';
import {checkBottomHand} from './bottomHand.browser.mjs';
import {checkTable} from './table.browser.mjs';

async function purchaseGame() {
    const game = await createGame(3, {undoPolicy: 'competitive'});
    for (let seat = 0; seat < 3; seat++) await game.send(game.links[seat], `bid-capital ${3 - seat}`);
    for (let step = 0; step < 30; step++) {
        const state = await game.snapshot(game.links[0]);
        if (state.game.stage === 'PURCHASE') break;
        const actor = game.links.find(link => state.prompt?.players.includes(link.id));
        assert(actor, `Expected a setup actor at ${state.game.stage}`);
        const own = await game.snapshot(actor);
        const command = own.game.stage === 'TOKEN_PURCHASE' ? `buy-tokens ${game.links.indexOf(actor) - 2}`
            : own.legalChoices.commands.find(command => command.startsWith('choose-capital '))
            ?? own.legalChoices.commands.find(command => command.startsWith('choose-turn-position '))
            ?? own.legalChoices.commands.find(command => command === 'pass');
        assert(command, `Expected setup command at ${own.game.stage}`);
        await game.send(actor, command);
    }
    return game;
}

async function checkPlanningTurnArrival(page, web) {
    const game = await purchaseGame();
    async function visit(seat) {
        const previous = await page.evaluate('performance.timeOrigin');
        const url = new URL(game.links[seat].path, web);
        url.searchParams.set('turn-arrival-check', String(seat));
        await page.send('Page.navigate', {url: url.href});
        await page.wait(`performance.timeOrigin !== ${previous} && document.querySelector('.game-table')`,
            'turn arrival player view');
    }
    await visit(0);
    await page.wait(`document.querySelector('.decision-status.is-ready')`, 'active purchase');
    assert.equal(await page.evaluate(`document.querySelector('.activity-planner')`), null,
        'The active player is not offered a new plan');
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-active-purchase-desktop.png'), 1280, 900);
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-active-purchase-phone.png'), 390, 844, true);
    assert.equal(await page.evaluate(`document.querySelector('.activity-planner')`), null,
        'The active player is not offered a new plan on a phone');
    assert(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`));
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1280, height: 900, deviceScaleFactor: 1, mobile: false});
    await visit(1);
    await page.wait(`document.querySelector('.activity-planner')`, 'waiting player can prepare');
    await page.click('Prepare a plan');
    await page.wait(`document.querySelector('.pass-action')?.textContent.includes('Add to plan')`, 'unsaved preview');
    for (const command of ['pass', 'pass', 'stabilize cash']) await game.send(game.links[0], command);
    await page.wait(`document.querySelector('.decision-status.is-ready') && !document.querySelector('.activity-planner')`,
        'turn arrival closes the unsaved preview');
    assert.doesNotMatch(await page.evaluate(`document.querySelector('.pass-action').textContent`), /Add to plan/,
        'Turn arrival restores live purchase controls');
    assert.equal((await game.snapshot(game.links[1])).planning.saved, null,
        'Closing a browser preview does not save or execute it');
}

export async function checkParallelPlanning(page, web) {
    const game = await purchaseGame();
    const initial = await game.snapshot(game.links[1]);
    assert.equal(initial.game.stage, 'PURCHASE');
    let navigation = 0;
    async function visit(seat) {
        const previous = await page.evaluate('performance.timeOrigin');
        const url = new URL(game.links[seat].path, web);
        url.searchParams.set('planning-check', String(++navigation));
        await page.send('Page.navigate', {url: url.href});
        await page.wait(`performance.timeOrigin !== ${previous} && document.querySelector('.activity-planner')`, 'live planner');
    }
    async function waitPlan(count) {
        await page.wait(`document.querySelectorAll('.plan-actions li').length === ${count} && [...document.querySelectorAll('.decision-actions button')].some(b=>b.textContent === 'Save for review' && !b.disabled) && !document.querySelector('.activity-planner .error')`, `plan with ${count} actions`);
    }
    async function enabled(selector) {
        await page.wait(`document.querySelector(${JSON.stringify(selector)}) && !document.querySelector(${JSON.stringify(selector)}).disabled`, selector);
    }
    async function assertCompactPlan() {
        const panel = await page.evaluate(`document.querySelector('.activity-planner').textContent`);
        assert.equal(await page.evaluate(`document.querySelector('.activity-planner h3')`), null, 'Plan controls need no separate heading');
        assert.doesNotMatch(panel, /Prepare now|Nothing will execute|Preview assumes|This plan finishes|remaining decisions for review|stops after drawing/, 'Planning explanations live in Help');
        const entries = await page.evaluate(`[...document.querySelectorAll('.plan-actions li strong')].map(entry => entry.textContent)`);
        assert(entries.every(entry => !/Continue|Finish purchasing|^Pass$/.test(entry)), 'Only changing actions appear in the plan');
    }
    async function prepareAdvance() {
        await page.click('Prepare a plan');
        await enabled('.pass-action');
        await assertCompactPlan();
        await page.click('Add to plan · Continue to advances');
        await enabled('[data-advance="URBAN_ASCENDANCY"].available');
        await page.evaluate(`document.querySelector('[data-advance="URBAN_ASCENDANCY"]').click()`);
        await enabled('.buy-advance-action');
        await page.evaluate(`document.querySelector('.buy-advance-action').click()`);
        await waitPlan(1);
        await enabled('.pass-action');
        await page.click('Add to plan · Continue to stabilization');
        await page.wait(`[...document.querySelectorAll('.simple-action')].some(b => b.textContent.includes('Pay cash') && !b.disabled)`, 'planned stabilization');
        await page.evaluate(`[...document.querySelectorAll('.simple-action')].find(b=>b.textContent.includes('Pay cash')).click()`);
        await waitPlan(2);
        await assertCompactPlan();
        const beforeHelp = await game.snapshot(game.links[1]);
        await page.evaluate(`document.querySelector('.decision-actions .button-with-help [data-help-topic="plans"]').click()`);
        await page.wait(`document.querySelector('[data-help-content="plans"]')`, 'Save help opens from its button');
        await page.evaluate(`document.querySelector('.reference-dialog .close-detail').click()`);
        await page.wait(`!document.querySelector('.reference-dialog')`, 'Plan help closes');
        assert.deepEqual((await game.snapshot(game.links[1])).planning.saved, beforeHelp.planning.saved,
            'Opening action help does not save or execute the draft');
        assert.equal(await page.evaluate(`document.querySelectorAll('.plan-actions li').length`), 2,
            'Action help preserves the local plan');
        assert.equal(await page.evaluate(`document.querySelectorAll('.plan-actions li strong')[1].textContent`),
            'Stabilization: pay cash', 'The draft identifies the stabilization step');
        await page.click('Save for review');
        await page.wait(`document.querySelector('.activity-planner [role="status"]').textContent === 'Saved for review'`, 'durable review plan');
        assert.equal(await page.evaluate(`document.querySelectorAll('.plan-actions li strong')[1].textContent`),
            'Stabilization: pay cash', 'The saved summary identifies the stabilization step');
    }

    await visit(1);
    await prepareAdvance();
    const savedPlan = await game.snapshot(game.links[1]);
    assert.equal(savedPlan.revision, initial.revision, 'Saving has no gameplay effect');
    assert.deepEqual(savedPlan.planning.saved.actions.map(action => action.command), ['pass', 'buy-advance V', 'pass', 'stabilize cash'], 'Hidden continuations remain in the saved execution sequence');
    const purchaseEffect = savedPlan.planning.preview.effects[1];
    assert(purchaseEffect.cashChanges.length > 0, 'The purchase has a quoted cash consequence');
    const firstEntry = await page.evaluate(`document.querySelector('.plan-actions li').textContent`);
    assert.match(firstEntry, /Urban Ascendancy/);
    for (const change of purchaseEffect.cashChanges) {
        assert(firstEntry.includes(`$${change.before} → $${change.after}`), 'The first visible purchase retains the effect of the second saved action');
    }
    assert.equal((await game.snapshot(game.links[0])).planning.saved, null, 'Another seat cannot see the saved plan');
    await page.click('Return to the table');
    await page.reload();
    await page.wait(`document.querySelectorAll('.plan-actions li').length === 2`, 'saved plan after reload');
    await page.click('Edit plan');
    await page.evaluate(`document.querySelectorAll('.plan-actions li')[0].querySelector('button').click()`);
    await waitPlan(0);
    await enabled('[data-advance="URBAN_ASCENDANCY"].available');
    assert.match(await page.evaluate(`document.querySelector('.pass-action').textContent`), /Continue to stabilization/, 'Removing the first visible step preserves the preceding hidden continuation');
    const concurrent = await game.snapshot(game.links[1]);
    const replaced = await fetch(`${process.env.MAP_API_URL}/api/games/${game.created.gameId}/plans`, {
        method: 'POST', headers: {'Content-Type': 'application/json', Authorization: `Bearer ${game.links[1].token}`},
        body: JSON.stringify({expectedRevision: concurrent.revision, expectedVersion: concurrent.planning.version,
            operationId: crypto.randomUUID(), operation: 'SAVE', actions: concurrent.planning.saved.actions}),
    });
    assert(replaced.ok, 'Another device can replace the saved plan');
    await replaced.json();
    await page.wait(`[...document.querySelectorAll('.activity-planner .blocker button')].some(button => button.textContent === 'Load saved version')`, 'stale local draft warning');
    assert.equal(await page.evaluate(`document.querySelectorAll('.plan-actions li').length`), 0, 'Polling retains the unsaved local draft');
    assert(await page.evaluate(`[...document.querySelectorAll('.decision-actions button')].find(b=>b.textContent === 'Save for review').disabled`), 'A stale draft cannot overwrite the other device');
    await page.click('Load saved version');
    await waitPlan(2);
    await page.evaluate(`document.querySelector('.decision-actions [data-help-topic="plans"]').click()`);
    await page.wait(`document.querySelector('[data-help-content="plans"]')`, 'private planning help');
    const help = await page.evaluate(`document.querySelector('[data-help-content="plans"]').textContent`);
    assert.match(help, /earlier players/);
    assert.match(help, /draw pauses for card review/);
    await page.evaluate(`document.querySelector('.reference-dialog .close-detail').click()`);
    assert.equal(await page.evaluate('document.querySelectorAll(".plan-actions li").length'), 2, 'Help inspection preserves the draft');
    // Hold a save after its receipt is captured, then change only the server receipt elsewhere.
    await page.evaluate(`(() => {
        const original = window.fetch.bind(window);
        window.planOriginalFetch = original;
        window.planHoldSave = true;
        window.fetch = async (input, init) => {
            if (window.planHoldSave && String(input).endsWith('/plans') && init?.body && JSON.parse(init.body).operation === 'SAVE') {
                window.planHoldSave = false;
                await new Promise(resolve => {window.releasePlanSave = resolve;});
            }
            return original(input, init);
        };
    })()`);
    await page.click('Save for review');
    await page.wait(`typeof window.releasePlanSave === 'function'`, 'save waiting before publication');
    const racing = await game.snapshot(game.links[1]);
    const raced = await fetch(`${process.env.MAP_API_URL}/api/games/${game.created.gameId}/plans`, {
        method: 'POST', headers: {'Content-Type': 'application/json', Authorization: `Bearer ${game.links[1].token}`},
        body: JSON.stringify({expectedRevision: racing.revision, expectedVersion: racing.planning.version,
            operationId: crypto.randomUUID(), operation: 'SAVE', actions: racing.planning.saved.actions}),
    });
    assert(raced.ok, 'Concurrent identical save changes its receipt');
    await raced.json();
    await page.evaluate('window.releasePlanSave()');
    await page.wait(`[...document.querySelectorAll('.activity-planner .blocker button')].some(button => button.textContent === 'Load saved version')`, 'rejected save preserves its original receipt');
    assert(await page.evaluate(`[...document.querySelectorAll('.decision-actions button')].find(b=>b.textContent === 'Save for review').disabled`), 'A rejected save cannot acknowledge another device’s identical write');
    await page.evaluate('window.fetch = window.planOriginalFetch');
    await page.click('Load saved version');
    await waitPlan(2);
    await page.click('Return to the table');
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-planning-desktop.png'), 1280, 900);
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-planning-phone.png'), 390, 844, true);
    assert(await page.evaluate('document.documentElement.scrollWidth <= innerWidth'), 'Phone planning fits the viewport');
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1280, height: 900, deviceScaleFactor: 1, mobile: false});

    await visit(2);
    await prepareAdvance();
    await page.click('Execute automatically on my turn');
    await page.wait(`document.querySelector('.activity-planner [role="status"]').textContent === 'Automatic execution enabled'`, 'server authorization');
    await page.click('Edit plan');
    await page.wait(`document.querySelector('.activity-planner[data-editing="true"]')`, 'confirmed disarm before editing');
    assert.equal((await game.snapshot(game.links[2])).planning.saved.armed, false);
    await page.click('Return to the table');
    await page.click('Execute automatically on my turn');
    await page.wait(`document.querySelector('.activity-planner [role="status"]').textContent === 'Automatic execution enabled'`, 'authorization after editing');
    await page.click('Cancel automatic execution');
    await page.wait(`document.querySelector('.activity-planner [role="status"]').textContent === 'Saved for review'`, 'durable cancellation');
    await page.click('Execute automatically on my turn');
    await page.wait(`document.querySelector('.activity-planner [role="status"]').textContent === 'Automatic execution enabled'`, 'fresh authorization');

    // An earlier purchase supplies new strategic information without committing a review plan.
    for (const command of ['pass', 'buy-advance V', 'pass', 'stabilize cash']) await game.send(game.links[0], command);
    assert.equal((await game.snapshot(game.links[1])).game.activity.actor, game.links[1].id);
    assert(!(await game.snapshot(game.links[1])).game.players[1].advances.includes('URBAN_ASCENDANCY'));
    await visit(1);
    await page.click('Edit plan');
    await page.wait(`document.querySelector('.activity-planner[data-editing="true"]')`, 'review editing');
    await page.evaluate(`document.querySelectorAll('.plan-actions li')[0].querySelector('button').click()`);
    await waitPlan(0);
    await enabled('.pass-action');
    await page.click('Add to plan · Continue to stabilization');
    await page.wait(`[...document.querySelectorAll('.simple-action')].some(b => b.textContent.includes('Pay cash') && !b.disabled)`, 'revised stabilization');
    await page.evaluate(`[...document.querySelectorAll('.simple-action')].find(b=>b.textContent.includes('Pay cash')).click()`);
    await waitPlan(1);
    await page.click('Save for review');
    await page.wait(`[...document.querySelectorAll('button')].some(b => b.textContent === 'Execute now' && !b.disabled)`, 'explicit confirmation');
    await page.click('Execute now');
    await page.wait(`!document.querySelector('.activity-planner')`, 'review purchase committed');
    const after = await game.snapshot(game.links[2]);
    assert.equal(after.game.stage, 'EXPANSION');
    assert(after.game.players[2].advances.includes('URBAN_ASCENDANCY'), 'The next authorized player executed offline');
    assert(!after.game.players[1].advances.includes('URBAN_ASCENDANCY'), 'Review changes replaced the original plan');
    assert.equal(after.history.filter(line => line === `${game.links[2].id}: buy-advance V`).length, 1);

    for (const link of game.links) await game.send(link, 'pass');
    for (let seat = 0; seat < 3; seat++) await game.send(game.links[seat], `buy-tokens ${seat - 2}`);
    if ((await game.snapshot(game.links[0])).game.stage === 'DRAW_CARD') await game.send(game.links[0], 'pass');
    await visit(2);
    await page.click('Prepare a plan');
    await page.wait(`[...document.querySelectorAll('.simple-action')].some(b => b.textContent.includes('Buy card') && !b.disabled)`, 'planned card offer');
    await page.evaluate(`[...document.querySelectorAll('.simple-action')].find(b=>b.textContent.includes('Buy card')).click()`);
    await waitPlan(1);
    await assertCompactPlan();
    await page.click('Save for review');
    await page.wait(`[...document.querySelectorAll('button')].some(b => b.textContent === 'Execute automatically on my turn' && !b.disabled)`, 'card authorization');
    await page.click('Execute automatically on my turn');
    const beforeDraw = await game.snapshot(game.links[2]);
    await page.send('Page.navigate', {url: 'about:blank'});
    await game.send(game.links[0], 'pass');
    const afterDraw = await game.snapshot(game.links[2]);
    assert.equal(afterDraw.game.hand.length, beforeDraw.game.hand.length + 1);
    assert.equal(afterDraw.history.filter(line => line === `${game.links[2].id}: buy-card`).length, 1);
    console.log('Passed: live private planning, reload, phone layout, cancellation, reconsideration under Competitive undo, offline ordered execution and card draw boundary.');
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
    const web = process.env.MAP_WEB_URL;
    const page = await open(web);
    try {
        await checkPlanningTurnArrival(page, web);
        await checkParallelPlanning(page, web);
        const game = await createGame(6);
        for (let seat = 0; seat < 6; seat++) await game.send(game.links[seat], `bid-capital ${6 - seat}`);
        for (let step = 0; step < 100; step++) {
            const state = await game.snapshot(game.links[0]);
            if (state.game.stage === 'EXPANSION') break;
            const actor = game.links.find(link => state.prompt?.players.includes(link.id));
            assert(actor, `Expected an actor at ${state.game.stage}`);
            const own = await game.snapshot(actor);
            const command = own.game.stage === 'TOKEN_PURCHASE' ? `buy-tokens ${6 + game.links.indexOf(actor)}`
                : own.legalChoices.commands.find(command => command.startsWith('choose-capital '))
                ?? own.legalChoices.commands.find(command => command.startsWith('choose-turn-position '))
                ?? own.legalChoices.commands.find(command => command === 'pass')
                ?? own.legalChoices.commands.find(command => command === 'stabilize cash');
            assert(command, `Expected a progression command at ${own.game.stage}`);
            await game.send(actor, command);
        }
        const baseline = await game.snapshot(game.links[0]);
        assert.equal(baseline.game.stage, 'EXPANSION');
        await page.send('Emulation.setDeviceMetricsOverride', {width: 1280, height: 900, deviceScaleFactor: 1, mobile: false});
        for (const check of [checkTable, checkPurchaseClarity, checkReferenceUx, checkBottomHand]) {
            await page.send('Page.navigate', {url: 'about:blank'});
            await page.wait(`location.href === 'about:blank' && document.readyState === 'complete'`, 'fresh fixture document');
            await check(page, web, baseline);
        }
    }
    finally {page.ws.close(); await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);}
}
