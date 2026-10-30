import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';

export async function checkGroupedReplacements(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'grouped replacement harness');
    const viewer = baseline.viewerId;
    const provinces = ['rome', 'genoa', 'venice'];
    let fixture = {...baseline, revision: 33000,
        prompt: {messageId: 'prompt.replace-event-token', players: [viewer]},
        legalChoices: {commands: ['replace-event-tokens none'], bidMin: null, bidMax: null},
        game: {...baseline.game, stage: 'PLAY_CARDS',
            players: baseline.game.players.map(player => player.id === viewer ? {...player, poolTokens: 2, stockTokens: 0, misery: 20} : player),
            activity: {...baseline.game.activity, decisionPlayer: viewer, choice: 'replace-event-token', decisionEntry: 33000},
            replacementToken: {player: viewer, provinces, event: 'BLACK_DEATH', poolTokens: 2, poolCost: 1,
                maxReplacements: 2, stockTokens: 0, misery: 20, civilWarLossPending: false}}};
    const show = async () => {
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`document.querySelector('[data-test-revision="${fixture.revision}"]')`, 'grouped replacement snapshot');
    };
    await show();
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-replacement-province]').length`), 3, 'Every province is offered together');
    const confirm = '.replacement-confirmation';
    const choose = (province, pool) => page.evaluate(`document.querySelector('[data-replacement-province="${province}"] button:${pool ? 'first' : 'last'}-child').click()`);
    assert.equal(await page.evaluate(`document.querySelector('${confirm}').disabled`), true, 'Every province needs a decision');
    const before = await page.evaluate('window.mapCommands.length');
    await choose('rome', true);
    await choose('genoa', true);
    assert.equal(await page.evaluate(`document.querySelector('[data-replacement-province="venice"] button:first-child').disabled`), true, 'Shared pool budget prevents overspending');
    await choose('venice', false);
    assert.equal(await page.evaluate('window.mapCommands.length'), before, 'Preparing all replacements submits nothing');
    assert.match(await page.evaluate(`document.querySelector('.replacement-review').textContent`), /Pool tokens: 2 → 0.*Place 1 pool token in Rome.*Place 1 pool token in Genoa.*Leave Venice empty/s);
    fixture = {...fixture, revision: fixture.revision + 1};
    await show();
    assert.equal(await page.evaluate(`document.querySelectorAll('.event-replacements [aria-pressed=true]').length`), 3, 'Polling preserves the complete group draft');
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-grouped-replacements-desktop.png'), 1280, 900);
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-grouped-replacements-phone.png'), 390, 844, true);
    assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
    await page.evaluate(`document.querySelector('${confirm}').click()`);
    assert.equal(await page.evaluate('window.mapCommands.at(-1)'), 'replace-event-tokens genoa rome', 'One atomic command confirms the selected subset');
    fixture = {...fixture, revision: fixture.revision + 1,
        game: {...fixture.game, activity: {...fixture.game.activity, decisionEntry: 33002}}};
    await show();
    assert.equal(await page.evaluate(`document.querySelectorAll('.event-replacements [aria-pressed=true]').length`), 0, 'A replacement decision starts with a fresh draft');
    await page.evaluate(`document.querySelector('.event-replacements > button.secondary').click()`);
    await page.evaluate(`document.querySelector('${confirm}').click()`);
    assert.equal(await page.evaluate('window.mapCommands.at(-1)'), 'replace-event-tokens none');
    console.log('Passed: simultaneous province choices, shared pool budget, review, polling preservation, phone layout and atomic confirmation.');
}

export async function checkLiveCivilWar(page, web) {
    let game;
    let player;
    for (let attempt = 0; attempt < 200 && !player; attempt++) {
        game = await createGame(3);
        for (const link of game.links) {
            if ((await game.snapshot(link)).game.hand.some(card => card.definition.id === 'civil-war')) {
                player = link;
                break;
            }
        }
    }
    assert(player, 'Find a real opening Civil War through normal creation');
    for (const link of game.links) await game.send(link, 'bid-capital 0');
    let before;
    for (let step = 0; step < 100; step++) {
        const state = await game.snapshot(player);
        if (state.game.stage === 'PLAY_CARDS' && state.prompt?.players.includes(player.id)) { before = state; break; }
        const actor = game.links.find(link => state.prompt?.players.includes(link.id));
        assert(actor, `Civil War setup has an actor at ${state.game.stage}`);
        const current = await game.snapshot(actor);
        const me = current.game.players.find(candidate => candidate.id === actor.id);
        const commands = current.legalChoices.commands;
        const command = current.game.stage === 'TOKEN_PURCHASE' ? `buy-tokens ${actor.id === player.id ? me.stockTokens : 0}`
            : commands.find(command => command.startsWith('choose-capital '))
                ?? commands.find(command => command.startsWith('choose-turn-position '))
                ?? commands.find(command => command === 'pass');
        assert(command, `Civil War setup has a command at ${current.game.stage}`);
        await game.send(actor, command);
    }
    assert(before, 'The opening card reaches its play window');
    const me = before.game.players.find(candidate => candidate.id === player.id);
    assert.equal(me.stockTokens, 0, 'Token purchase exhausted stock, requiring a capital replacement choice');
    const card = before.game.hand.find(card => card.definition.id === 'civil-war');
    const offer = before.game.cardOffers.find(offer => offer.reference === card.reference);
    assert(offer.choices.some(choice => choice.kind === 'PLAYER' && choice.value === player.id), 'The engine offers the real player target');
    await page.send('Page.navigate', {url: web + player.path});
    await page.wait(`document.querySelector('[data-hand-card="${card.reference}"]')`, 'live Civil War hand');
    await page.evaluate(`document.querySelector('[data-hand-card="${card.reference}"]').click()`);
    await page.wait(`document.querySelector('[data-player="${player.id}"].eligible-target')`, 'Civil War target picker');
    await page.evaluate(`document.querySelector('[data-player="${player.id}"] > summary').click()`);
    await page.evaluate(`document.querySelector('.decision-actions .play-card-action').click()`);
    await page.wait(`document.querySelector('[data-replacement-province="${me.capital}"]')`, 'live capital replacement');
    const pending = await game.snapshot(player);
    assert.deepEqual(pending.game.replacementToken.provinces, [me.capital]);
    assert.equal(pending.game.civilWarLoss, null, 'Loss choices wait for capital replacement');
    await page.evaluate(`document.querySelector('[data-replacement-province="${me.capital}"] button:first-child').click()`);
    await page.evaluate(`document.querySelector('.replacement-confirmation').click()`);
    await page.wait(`document.querySelector('.action-buttons button')?.textContent.includes('Lose $')`, 'live Civil War costs');
    const lossState = await game.snapshot(player);
    const loss = lossState.game.civilWarLoss;
    const pool = me.poolTokens - 1;
    assert.equal(loss.tokenCost, Math.ceil(pool / 2));
    assert.equal(loss.cashCost, Math.min(me.cash, Math.ceil(me.writtenCash / 2)));
    const expectedCash = `Lose $${loss.cashCost} cash · cash after: $${loss.cashAfter}`;
    const expectedTokens = `Lose ${loss.tokenCost} expansion tokens · pool after: ${loss.poolAfter}`;
    const labels = await page.evaluate(`[...document.querySelectorAll('.action-buttons button')].map(button=>button.textContent)`);
    assert(labels.includes(expectedCash), 'Cash option uses the authoritative payable loss');
    assert(labels.includes(expectedTokens), 'Token option uses the pool after capital replacement');
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-live-civil-war.png'), 1280, 900);
    await page.click(expectedTokens);
    assert.match(await page.evaluate(`document.querySelector('.selected-action-confirmation').textContent`), new RegExp(`Lose ${loss.tokenCost} expansion tokens`));
    await page.evaluate(`document.querySelector('.selected-action-confirmation').click()`);
    await page.wait(`!document.querySelector('.selected-action')`, 'Civil War loss committed');
    const after = await game.snapshot(player);
    const paid = after.game.players.find(candidate => candidate.id === player.id);
    assert.equal(paid.poolTokens, loss.poolAfter);
    assert.equal(paid.cash, me.cash, 'Choosing token loss does not spend cash');
    assert.equal(after.game.board.find(province => province.id === me.capital).tokens.find(holding => holding.owner === player.id)?.coloured, 1);
    console.log('Passed: real Civil War capital replacement and authoritative cash/token offers match the committed token loss.');
    return after;
}

if (process.argv[1]?.endsWith('/eventDecisions.browser.mjs')) {
    const page = await open('about:blank');
    try {
        const baseline = await checkLiveCivilWar(page, process.env.MAP_WEB_URL);
        await checkGroupedReplacements(page, process.env.MAP_WEB_URL, baseline);
    }
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
