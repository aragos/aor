import assert from 'node:assert/strict';
import {createGame, open} from './browser-support.mjs';

export async function checkLiveCompetitionLifecycle(page, web) {
    const game = await createGame(3);
    for (const player of game.links) await game.send(player, 'bid-capital 0');
    const capitals = ['genoa', 'venice', 'barcelona'];
    let state;
    for (let step = 0; step < 100; step++) {
        state = await game.snapshot(game.links[0]);
        if (state.game.stage === 'EXPANSION') break;
        const actor = game.links.find(player => state.prompt?.players.includes(player.id));
        assert(actor, `Competition setup has an actor at ${state.game.stage}`);
        const current = await game.snapshot(actor);
        const commands = current.legalChoices.commands;
        const command = current.game.stage === 'TOKEN_PURCHASE' ? `buy-tokens ${18 + game.links.indexOf(actor)}`
            : capitals.map(capital => `choose-capital ${capital}`).find(command => commands.includes(command))
                ?? commands.find(command => command.startsWith('choose-turn-position '))
                ?? commands.find(command => command === 'pass')
                ?? commands.find(command => command === 'stabilize cash')
                ?? commands.find(command => command === 'stabilize misery');
        assert(command, `Competition setup has a command at ${current.game.stage}`);
        await game.send(actor, command);
    }
    assert.equal(state.game.stage, 'EXPANSION');
    let attacker;
    let target;
    for (let step = 0; step < game.links.length; step++) {
        const actor = game.links.find(player => state.prompt?.players.includes(player.id));
        assert(actor, 'Expansion has an actor');
        const current = await game.snapshot(actor);
        target = current.game.expansionOffers.find(offer => offer.primaryAction === 'ATTACK' &&
            !offer.blockers.length && current.legalChoices.commands.includes(`attack ${offer.destination}`));
        if (target) { attacker = actor; state = current; break; }
        state = await game.send(actor, 'pass');
    }
    assert(attacker && target, 'Adjacent real capitals offer a legal first-round competition');
    const turn = state.game.turnNumber;
    await page.send('Page.navigate', {url: web + attacker.path});
    await page.wait(`document.querySelector('.expansion-player')`, 'live competition actor');
    await page.evaluate(`document.querySelector('[data-map-province="${target.destination}"]').dispatchEvent(new KeyboardEvent('keydown', {key:'Enter',bubbles:true}))`);
    await page.wait(`document.querySelector('.primary-expansion:not([disabled])')`, 'live authoritative attack');
    await page.evaluate(`document.querySelector('.primary-expansion').click()`);
    await page.wait(`document.querySelector('.competition-notice')`, 'live competition result');
    const resolved = await game.snapshot(attacker);
    const outcome = resolved.events.findLast(event => event.competition);
    assert(outcome, 'The resolved competition remains in activity');
    state = resolved;
    for (let step = 0; step < game.links.length; step++) {
        if (state.game.stage !== 'EXPANSION') break;
        const actor = game.links.find(player => state.prompt?.players.includes(player.id));
        assert(actor, 'Remaining expansion has an actor');
        state = await game.send(actor, 'pass');
    }
    assert.notEqual(state.game.stage, 'EXPANSION', 'All players finished expansion');
    await page.wait(`!document.querySelector('.header-stage strong')?.textContent.includes('Expansion')`, 'live expansion phase completed');
    assert.equal(await page.evaluate(`document.querySelector('.competition-notice')`), null, 'An expansion-ending server poll clears the popup');
    assert.equal((await game.snapshot(attacker)).events.some(event => event.id === outcome.id), true, 'The activity outcome survives popup expiry');

    for (let step = 0; step < 100; step++) {
        state = await game.snapshot(attacker);
        if (state.game.stage === 'EXPANSION') break;
        const actor = game.links.find(player => state.prompt?.players.includes(player.id));
        assert(actor, `Next round has an actor at ${state.game.stage}`);
        const current = await game.snapshot(actor);
        const commands = current.legalChoices.commands;
        const command = current.game.stage === 'TOKEN_PURCHASE' ? `buy-tokens ${1 + game.links.indexOf(actor)}`
            : commands.find(command => command === 'pass')
                ?? commands.find(command => command === 'stabilize cash')
                ?? commands.find(command => command === 'stabilize misery');
        assert(command, `Next round has a command at ${current.game.stage}`);
        await game.send(actor, command);
    }
    assert.equal(state.game.stage, 'EXPANSION');
    assert(state.game.turnNumber > turn, 'A new expansion belongs to a later turn');
    await page.wait(`document.querySelector('.header-stage strong')?.textContent.includes('Expansion')`, 'next live expansion');
    assert.equal(await page.evaluate(`document.querySelector('.competition-notice')`), null, 'The next expansion cannot restore a completed receipt');
    console.log('Passed: live competition receipt expires at expansion end, retains activity, and stays cleared in the next expansion.');
}

if (process.argv[1]?.endsWith('/competitionLifecycle.browser.mjs')) {
    const page = await open('about:blank');
    try { await checkLiveCompetitionLifecycle(page, process.env.MAP_WEB_URL); }
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
