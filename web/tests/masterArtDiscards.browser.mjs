import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';

const game = await createGame(3);
const owner = game.links[0];
let ready;
for (let step = 0; step < 600; step++) {
    const publicState = await game.snapshot(owner);
    const actor = game.links.find(link => publicState.prompt?.players.includes(link.id));
    assert(actor, 'Ordinary play reaches the Master Art opportunity before the game ends');
    const state = await game.snapshot(actor);
    const commands = state.legalChoices.commands;
    if (actor.id === owner.id && commands.some(command => command.startsWith('discard-card '))) {
        ready = state;
        break;
    }
    const player = state.game.players.find(player => player.id === actor.id);
    const placement = state.game.expansionOffers.filter(offer => offer.primaryAction === 'PLACE' &&
        offer.establishesMarker && offer.blockers.length === 0)
        .sort((first, second) => first.requiredTokens - second.requiredTokens)[0];
    let command;
    if (state.game.stage === 'BIDDING') command = `bid-capital ${actor.id === owner.id ? 3 : 0}`;
    else if (state.game.stage === 'TOKEN_PURCHASE') command = `buy-tokens ${actor.id === owner.id ? Math.min(6, player.cash, player.stockTokens) : 0}`;
    else if (actor.id === owner.id) command = ['N', 'O', 'P'].map(letter => `buy-advance ${letter}`).find(command => commands.includes(command));
    if (!command && actor.id === owner.id && placement) command = `place ${placement.requiredTokens} ${placement.destination}`;
    command ??= commands.find(command => command === 'stabilize cash')
        ?? commands.find(command => command === 'stabilize misery')
        ?? commands.find(command => command === 'pass') ?? commands[0];
    await game.send(actor, command);
}
assert(ready, 'Normal play offers a Master Art discard');
const command = ready.legalChoices.commands.find(command => command.startsWith('discard-card '));
const card = ready.game.hand.find(card => card.reference === command.split(' ')[1]);
const page = await open(process.env.MAP_WEB_URL + owner.path);
try {
    await page.wait(`document.querySelector('.discard-request')`, 'live Master Art selection');
    await page.evaluate(`document.querySelector('[data-hand-card="${card.reference}"]').click()`);
    await page.wait(`document.querySelector('.selected-action-confirmation:not([disabled])')`, 'discard confirmation');
    await page.evaluate(`document.querySelector('.selected-action-confirmation').click()`);
    await page.wait(`!document.querySelector('[data-hand-card="${card.reference}"]')`, 'successful discard clears the held card');
    for (const viewer of game.links) {
        const state = await game.snapshot(viewer);
        const event = state.events.findLast(event => event.kind === 'DISCARD_CARD');
        assert.equal(event.card, card.definition.id, 'Every player receives the discarded catalog identity');
        assert(state.history.includes(`${owner.id}: ${command} [${card.definition.id}]`));
        assert(!state.game.unplayedCards.some(candidate => candidate.id === card.definition.id));
        const draw = state.events.find(event => event.kind === 'DRAW_CARD' && event.actor !== viewer.id);
        assert.equal(draw.card, null, 'Other players’ earlier draws stay private');
        if (viewer.id === owner.id) continue;
        await page.send('Page.navigate', {url: process.env.MAP_WEB_URL + viewer.path});
        // Seat links share a pathname; reload so a fragment-only navigation remounts the app.
        await page.reload();
        const name = state.game.players.find(player => player.id === viewer.id).name;
        await page.wait(`document.querySelector('.private-seat-link summary')?.textContent.endsWith(${JSON.stringify(name)})`, 'observer seat identity');
        for (const [width, height, mobile] of [[1280, 900, false], [390, 844, true]]) {
            await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
            if (await page.evaluate(`document.querySelector('#tab-activity').getAttribute('aria-selected') !== 'true'`)) {
                await page.evaluate(`document.querySelector('#tab-activity').click()`);
            }
            const selector = `[data-activity-member="${event.id}"]`;
            await page.wait(`document.querySelector('${selector} .event-card')`, 'public discard card in activity');
            assert.match(await page.evaluate(`document.querySelector('${selector}').textContent`), /Discarded a card/);
            assert(await page.evaluate(`document.querySelector('${selector} .event-card').textContent.length > 2`));
            await page.evaluate(`document.querySelector('${selector}').scrollIntoView({block: 'center'})`);
            await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `master-art-public-${viewer.id}-${width}.png`));
            assert(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth + 1`));
        }
        await page.reload();
        await page.wait(`document.querySelector('[data-activity-member="${event.id}"] .event-card')`, 'discard identity survives observer reload');
    }
    console.log('Passed: live Master Art discard selection, public activity/history/counts, private draws, observer reload and desktop/phone layouts.');
} finally {
    page.ws.close();
    await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
}
