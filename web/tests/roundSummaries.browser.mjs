import assert from 'node:assert/strict';
import {createGame} from './browser-support.mjs';

export async function checkAdvanceCategoryGroups(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'grouped player details harness');
    const viewer = baseline.viewerId;
    const acquisitions = ['NEW_WORLD', 'HUMAN_BODY', 'PATRONAGE', 'THE_HEAVENS', 'CATHEDRAL', 'WRITTEN_RECORD', 'OCEAN_NAVIGATION'];
    const fixture = {...baseline, revision: 29000, game: {...baseline.game,
        players: baseline.game.players.map(player => player.id === viewer ? {...player, advances: acquisitions} : player)}};
    await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
    await page.wait(`document.querySelector('[data-test-revision="29000"]')`, 'grouped details fixture');
    await page.evaluate(`document.querySelector('[data-player="${viewer}"] > summary').click()`);
    const groups = await page.evaluate(`[...document.querySelectorAll('[data-player="${viewer}"] .player-advance-category')].map(group => ({category:group.querySelector('h5').textContent,advances:[...group.querySelectorAll('li')].map(item=>item.textContent)}))`);
    assert.deepEqual(groups, [
        {category: 'Science', advances: ['The Heavens', 'Human Body']},
        {category: 'Religion', advances: ['Patronage', 'Cathedral']},
        {category: 'Communication', advances: ['Written Record']},
        {category: 'Exploration', advances: ['Ocean Navigation', 'New World']},
    ], 'Player advances follow printed categories and letters rather than purchase order');
    await page.evaluate(`window.setMapSnapshot(${JSON.stringify({...fixture, revision: 29001})})`);
    assert.equal(await page.evaluate(`document.querySelector('[data-player="${viewer}"]').open`), true, 'Polling preserves expanded details');
    assert.equal(await page.evaluate(`window.mapCommands.length`), 0);
    console.log('Passed: expanded player advances grouped by printed category and letter, with stable inspection.');
}

export async function checkTokenPurchaseRound(page, web) {
    const game = await createGame(3);
    for (const [index, player] of game.links.entries()) await game.send(player, `bid-capital ${3 - index}`);
    const viewer = game.links[0];
    for (let step = 0; step < 15; step++) {
        const current = await game.snapshot(viewer);
        if (current.game.stage === 'TOKEN_PURCHASE') break;
        const player = game.links.find(link => current.prompt?.players.includes(link.id));
        assert(player, `Setup has an actor at ${current.game.stage}`);
        const state = await game.snapshot(player);
        const command = state.legalChoices.commands.find(choice => choice.startsWith('choose-capital ')) ??
            state.legalChoices.commands.find(choice => choice.startsWith('return-card '));
        assert(command, `Setup has a choice at ${state.game.stage}`);
        await game.send(player, command);
    }
    assert.equal((await game.snapshot(viewer)).game.stage, 'TOKEN_PURCHASE');
    await page.send('Page.bringToFront');
    await page.send('Page.navigate', {url: web + viewer.path});
    await page.wait(`document.querySelector('#bid')`, 'live token purchase round');
    await game.send(game.links[0], 'buy-tokens 0');
    await game.send(game.links[1], 'buy-tokens -3');
    const privateState = await game.snapshot(viewer);
    assert.equal(privateState.events.filter(event => event.kind === 'BUY_TOKENS' || event.kind === 'TOKEN_PURCHASES').length, 0,
        'Unrevealed submissions never enter activity');
    assert.equal(await page.evaluate(`document.querySelectorAll('.token-purchase-round').length`), 0);
    await game.send(game.links[2], 'buy-tokens 8');
    await page.wait(`document.querySelectorAll('.token-purchase-row').length === 3`, 'revealed purchase batch');
    const revealed = await game.snapshot(viewer);
    const batches = revealed.events.filter(event => event.kind === 'TOKEN_PURCHASES');
    assert.equal(batches.length, 1);
    assert.equal(revealed.events.filter(event => event.kind === 'BUY_TOKENS').length, 0, 'Individual purchase entries are replaced by one round entry');
    assert.deepEqual(batches[0].tokenPurchases.map(({player, amount, cashCost, tokensReceived}) => ({player, amount, cashCost, tokensReceived})), [
        {player: game.links[0].id, amount: 0, cashCost: 0, tokensReceived: 0},
        {player: game.links[1].id, amount: -3, cashCost: 3, tokensReceived: 0},
        {player: game.links[2].id, amount: 8, cashCost: 8, tokensReceived: 8},
    ]);
    assert.equal(await page.evaluate(`document.querySelectorAll('.token-purchase-round').length`), 1);
    for (const [index, player] of game.links.entries()) {
        const text = await page.evaluate(`document.querySelector('[data-token-purchase-player="${player.id}"]').textContent`);
        assert(text.includes(['$0', '−$3', '−$8'][index]));
        assert.equal(await page.evaluate(`document.querySelectorAll('[data-token-purchase-player="${player.id}"] .event-cash-change').length`), 1,
            'Each purchase cash cost appears once');
    }
    const html = await page.evaluate(`document.querySelector('.token-purchase-round').innerHTML`);
    const actor = game.links.find(link => revealed.prompt?.players.includes(link.id));
    assert(actor && (await game.snapshot(actor)).legalChoices.commands.includes('pass'));
    await game.send(actor, 'pass');
    await page.wait(`document.querySelector('.game-header')`, 'later action accepted');
    await page.reload();
    await page.wait(`document.querySelectorAll('.token-purchase-round').length === 1`, 'one batch after reload');
    assert.equal(await page.evaluate(`document.querySelector('.token-purchase-round').innerHTML`), html,
        'Later actions and replay preserve the existing round entry');
    console.log('Passed: live sealed token purchases become one immutable round entry with zero/negative purchases and exact cash costs.');
}

export async function checkSharedMarketCancellation(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'shared cancellation harness');
    const viewer = baseline.viewerId;
    const fixture = {...baseline, revision: 29100, prompt: {messageId: 'prompt.cancel-adjustment', players: [viewer]},
        legalChoices: {commands: ['cancel-adjustment gold surplus', 'pass'], bidMin: null, bidMax: null},
        game: {...baseline.game, stage: 'DRAW_CARD', adjustments: {GOLD: ['SURPLUS'], IVORY: ['SURPLUS']},
            activity: {...baseline.game.activity, actor: viewer, decisionPlayer: viewer, choice: 'cancel-adjustment'}}};
    await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
    await page.wait(`document.querySelector('[data-test-revision="29100"]')`, 'one shared cancellation fixture');
    const choice = `.game-actions .action-buttons button[aria-pressed]`;
    assert.equal(await page.evaluate(`document.querySelectorAll('${choice}').length`), 1);
    assert.match(await page.evaluate(`document.querySelector('${choice}').textContent`), /Gold \/ Ivory.*Surplus.*\$10/);
    await page.evaluate(`document.querySelector('${choice}').click()`);
    assert.match(await page.evaluate(`document.querySelector('.selected-action-confirmation').textContent`), /Gold \/ Ivory.*Surplus.*\$10/);
    await page.evaluate(`document.querySelector('.selected-action-confirmation').click()`);
    assert.equal(await page.evaluate(`window.mapCommands.at(-1)`), 'cancel-adjustment gold surplus');
    await page.evaluate(`window.setMapSnapshot(${JSON.stringify({...fixture, revision: 29101, game: {...fixture.game,
        adjustments: {GOLD: ['SURPLUS'], IVORY: []}}})})`);
    await page.wait(`document.querySelector('[data-test-revision="29101"]')`, 'unpaired remaining Gold marker');
    const remaining = await page.evaluate(`document.querySelector('${choice}').textContent`);
    assert.match(remaining, /Gold.*Surplus.*\$10/);
    assert(!remaining.includes('Ivory'), 'A remaining singleton is labeled as its own commodity');
    console.log('Passed: one priced Gold/Ivory cancellation, confirmation and canonical command, with honest singleton labels.');
}
