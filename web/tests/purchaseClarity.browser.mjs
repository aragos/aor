import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame} from './browser-support.mjs';

export async function checkPurchaseClarity(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1280, height: 900, deviceScaleFactor: 1, mobile: false});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'purchase clarity harness');
    let revision = 22000;
    let fixture = baseline;
    async function show(changes = {}) {
        fixture = {...baseline, ...changes, revision: ++revision, game: {...baseline.game, ...changes.game}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'purchase clarity fixture');
    }
    const commodity = baseline.game.cardInspections.find(card => card.definition.id === 'cloth-wine');
    const hand = Array.from({length: 8}, (_, index) => ({reference: `compact-${index}`, definition: commodity.definition}));
    const players = baseline.game.players.map((player, index) => ({...player,
        advances: baseline.game.catalog.advances.slice(0, index + 2).map(advance => advance.id)}));
    await show({game: {players, hand, cardOffers: hand.map(card => ({reference: card.reference, playable: false,
        commodities: commodity.commodities, leaderDiscounts: [], choices: [], blockers: [], provinceTargets: [], requiredTargetCount: null, protectionMax: null}))}});
    const counts = await page.evaluate(`[...document.querySelectorAll('[data-player]')].map(box => ({id:box.dataset.player, count:Number(box.querySelector('.player-advance-count b').textContent),label:box.querySelector('.player-advance-count').getAttribute('aria-label')}))`);
    for (const {id, count, label} of counts) {
        assert.equal(count, players.find(player => player.id === id).advances.length);
        assert.match(label, /Advances: \d+/);
    }
    const density = await page.evaluate(`(() => {const hand=document.querySelector('.hand-cards'), card=hand.querySelector('.hand-card');return {width:card.getBoundingClientRect().width,visible:Math.floor(hand.clientWidth/(card.getBoundingClientRect().width+8))};})()`);
    assert(density.width <= 205 && density.visible >= 4, 'Compact hand displays at least four cards at desktop width');
    await page.evaluate(`document.querySelector('.table-hand').scrollIntoView({block:'center'})`);
    await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-compact-hand.png'));
    await show({game: {players: players.map((player, index) => index === 0 ? {...player, advances: [...player.advances, 'NATIONALISM']} : player)}});
    assert.equal(await page.evaluate(`Number(document.querySelector('[data-player="${players[0].id}"] .player-advance-count b').textContent)`), players[0].advances.length + 1, 'Advance count follows live player state');
    const viewer = baseline.viewerId;
    const others = baseline.game.players.filter(player => player.id !== viewer);
    const click = selector => page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
    await show({legalChoices: {commands: ['buy-expansion-card', 'pass'], bidMin: null, bidMax: null},
        prompt: {messageId: 'prompt.expand', players: [viewer]}, game: {stage: 'EXPANSION', expansionCardCost: 9,
            activity: {...baseline.game.activity, actor: viewer, decisionPlayer: viewer, choice: 'expand'}}});
    assert.match(await page.evaluate(`[...document.querySelectorAll('.action-buttons button')].find(button => button.textContent.includes('Buy card with tokens')).textContent`), /9.*tokens/);
    assert.equal(await page.evaluate(`!!document.querySelector('.action-cash')`), false, 'A token-priced card does not imply a cash purchase');

    const famine = baseline.game.cardInspections.find(card => card.definition.id === 'famine').definition;
    const changes = baseline.game.players.map((player, index) => ({player: player.id, fromValue: 20, toValue: [60, 50, null][index]}));
    const simpleOffer = {reference: 'misery', playable: true, blockers: [], commodities: [], leaderDiscounts: [],
        provinceTargets: [], requiredTargetCount: null, protectionMax: null, miseryChanges: changes, miseryUncertainPlayers: [],
        choices: [{kind: 'NONE', value: null, command: 'play misery', miseryChanges: changes, miseryUncertainPlayers: []}]};
    await show({prompt: {messageId: 'prompt.play-cards', players: [viewer]}, legalChoices: {commands: ['play misery', 'pass']},
        game: {stage: 'PLAY_CARDS', hand: [{reference: 'misery', definition: famine}], cardOffers: [simpleOffer]}});
    await click('[data-hand-card="misery"]');
    const preview = await page.evaluate(`[...document.querySelectorAll('.decision-panel [data-misery-preview-player]')].map(row => ({player: row.dataset.miseryPreviewPlayer, boxes: [...row.querySelectorAll('[data-misery-box]')].map(box => box.dataset.miseryBox)}))`);
    assert.deepEqual(preview, changes.map(change => ({player: change.player, boxes: ['20', String(change.toValue ?? 'chaos')]})), 'Card preview shows each affected player and Chaos');
    const civilWar = baseline.game.cardInspections.find(card => card.definition.id === 'civil-war').definition;
    await show({prompt: {messageId: 'prompt.play-cards', players: [viewer]}, game: {stage: 'PLAY_CARDS',
        hand: [{reference: 'victim', definition: civilWar}], cardOffers: [{...simpleOffer, reference: 'victim', miseryChanges: [],
            choices: others.map((player, index) => ({kind: 'PLAYER', value: player.id, command: `play victim player ${player.id}`,
                miseryChanges: [{player: player.id, fromValue: 20, toValue: index ? 50 : 40}], miseryUncertainPlayers: []}))}]}});
    await click('[data-hand-card="victim"]');
    assert.equal(await page.evaluate(`document.querySelectorAll('.decision-panel [data-misery-preview-player]').length`), 0, 'No victim is invented before selection');
    await click(`[data-player="${others[0].id}"] > summary`);
    assert.equal(await page.evaluate(`document.querySelector('.decision-panel [data-misery-preview-player]').dataset.miseryPreviewPlayer`), others[0].id);
    await click(`[data-player="${others[1].id}"] > summary`);
    assert.equal(await page.evaluate(`document.querySelector('.decision-panel [data-misery-preview-player]').dataset.miseryPreviewPlayer`), others[1].id, 'Changing target replaces the preview');
    assert.equal(await page.evaluate(`document.querySelector('.decision-panel [data-misery-preview-player] [data-misery-box="50"]').textContent`), '50');

    const advance = baseline.game.catalog.advances.find(advance => advance.id === 'NATIONALISM');
    const discount = {leaderCardId: 'charlemagne', baseAmount: 20, writtenRecordBonus: 10, amount: 30};
    const offer = {advance: advance.id, price: 40, unavailableReason: null, leaderDiscounts: [discount],
        priceBreakdown: {advance: advance.id, baseCost: 100, categoryCredit: 20, institutionalResearchCredit: 10,
            leaders: [discount], price: 40, miseryBefore: 20, miseryAfter: 30},
        miseryChanges: [{player: viewer, fromValue: 20, toValue: 30}]};
    await show({prompt: {messageId: 'prompt.purchase-advances', players: [viewer]}, legalChoices: {commands: [`buy-advance ${advance.letter}`, 'pass']},
        game: {stage: 'PURCHASE', activity: {...baseline.game.activity, actor: viewer, decisionPlayer: viewer, purchaseStep: 'ADVANCES'},
            advanceOffers: [offer]}});
    const tileText = await page.evaluate(`document.querySelector('[data-advance="${advance.id}"]').textContent`);
    assert.match(tileText, /Printed cost: \$100/);
    assert.match(tileText, /Category credit: −\$20/);
    assert.match(tileText, /Institutional Research: −\$10/);
    assert.match(tileText, /Charlemagne.*−\$30.*Includes \$10 Written Record/);
    await click(`[data-advance="${advance.id}"]`);
    assert.match(await page.evaluate(`document.querySelector('.decision-panel .price-breakdown').textContent`), /Printed cost\$100.*Category credit−\$20.*Institutional Research−\$10.*Price\$40/);
    assert.deepEqual(await page.evaluate(`[...document.querySelectorAll('.decision-panel [data-misery-preview-player] [data-misery-box]')].map(box => box.dataset.miseryBox)`), ['20', '30']);
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-purchase-preview-desktop.png'), 1280, 900);
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-purchase-preview-phone.png'), 390, 844, true);
    assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`), true, 'Quote and misery previews fit a phone');

    const source = baseline.game.catalog.advances.find(advance => advance.miseryRelief > 0);
    const spent = Math.min(5, source.miseryRelief), cash = 10 - spent;
    const event = {...baseline.events.at(-1), id: 'relief-breakdown', kind: 'RELIEVE_MISERY', actor: viewer,
        participants: baseline.game.players.map(({id, name, capital}) => ({id, name, capital})),
        card: null, commodity: null, provinces: [], dice: [], war: null, competition: null, amount: 1,
        drawReason: null, marketRoll: null, protectionCash: null, leaderRebate: null,
        reliefPayment: {cashCost: cash, ratingSpent: spent, ratingAvailableBefore: source.miseryRelief,
            ratingSources: [{advance: source.id, amount: source.miseryRelief}]},
        tokenChanges: [], cashChanges: [{player: viewer, amount: -cash}],
        miseryChanges: [{player: viewer, spaces: -1, fromValue: 30, toValue: 20}]};
    await show({events: [event]});
    const receipt = await page.evaluate(`document.querySelector('[data-event-id="relief-breakdown"]').textContent`);
    assert(receipt.includes(`Paid $${cash} cash + ${spent} MR`) && receipt.includes('MR earned this turn'));
    assert.equal((receipt.match(new RegExp('\\$'+cash+'(?![0-9])','g')) ?? []).length, 1, 'Cash component appears once');
    const holy = {...event, id: 'holy-tokens', kind: 'HOLY_INDULGENCE', actor: null, amount: null, reliefPayment: null,
        cashChanges: [], miseryChanges: [], tokenChanges: [
            {player: viewer, poolDelta: 4, stockDelta: -4}, {player: others[0].id, poolDelta: -2, stockDelta: 2}]};
    await show({events: [holy]});
    const tokenReceipt = await page.evaluate(`document.querySelector('[data-event-id="holy-tokens"]').textContent`);
    assert.match(tokenReceipt, /\+4 pool tokens.*−4 stock tokens/);
    assert.match(tokenReceipt, /−2 pool tokens.*\+2 stock tokens/);
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-event-id="holy-tokens"] .event-token-change').length`), 2);
    console.log('Passed: compact cards, advance counts, explicit token costs, target-aware misery previews, complete advance discounts and itemized relief/Holy Indulgence activity.');
}

export async function checkBidWithdrawal(page, web) {
    const game = await createGame(3);
    const player = game.links[0];
    await page.send('Page.navigate', {url: web + player.path});
    await page.wait(`document.querySelector('#bid')`, 'live capital bid');
    const before = await game.snapshot(player);
    await page.fill('#bid', '7');
    await page.evaluate(`document.querySelector('#bid').closest('form').requestSubmit()`);
    await page.wait(`document.querySelector('.withdraw-bid:not([disabled])')`, 'saved bid can be withdrawn');
    assert.equal((await game.snapshot(player)).pendingBid, 7);
    await page.evaluate(`document.querySelector('.withdraw-bid').click()`);
    await page.wait(`!document.querySelector('.withdraw-bid') && !document.querySelector('.pending-submission')`, 'bid withdrawn');
    assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7', 'Withdrawal preserves the local amount as an unsaved draft');
    const withdrawn = await game.snapshot(player);
    assert.equal(withdrawn.pendingBid, null);
    assert.equal(withdrawn.revision, before.revision, 'Unrevealed changes do not add committed actions');
    assert.deepEqual(withdrawn.history, before.history);
    assert(!withdrawn.submitted.includes(player.id));
    assert(withdrawn.prompt.players.includes(player.id), 'Withdrawal restores the player action');
    await page.reload();
    await page.wait(`document.querySelector('#bid') && !document.querySelector('.withdraw-bid')`, 'withdrawal persists after reload');
    await page.fill('#bid', '8');
    await page.evaluate(`document.querySelector('#bid').closest('form').requestSubmit()`);
    await page.wait(`document.querySelector('.withdraw-bid:not([disabled])')`, 'bid resubmitted');
    await game.send(game.links[1], 'bid-capital 4');
    await game.send(game.links[2], 'bid-capital 1');
    for (let step = 0; step < 15; step++) {
        const current = await game.snapshot(player);
        if (current.game.stage === 'TOKEN_PURCHASE') break;
        const actor = game.links.find(link => current.prompt?.players.includes(link.id));
        assert(actor, `Setup has an actor at ${current.game.stage}`);
        const state = await game.snapshot(actor);
        const command = state.legalChoices.commands.find(choice => choice.startsWith('choose-capital ')) ??
            state.legalChoices.commands.find(choice => choice.startsWith('return-card '));
        assert(command, `Setup has a choice at ${state.game.stage}`);
        await game.send(actor, command);
    }
    assert.equal((await game.snapshot(player)).game.stage, 'TOKEN_PURCHASE');
    await page.wait(`document.querySelector('#bid')?.min === '-32' && !document.querySelector('.withdraw-bid')`, 'revealed capital bids no longer withdrawable');
    await page.fill('#bid', '6');
    await page.wait(`!document.querySelector('#bid').closest('form').querySelector('button').disabled`, 'token quote ready');
    await page.evaluate(`document.querySelector('#bid').closest('form').requestSubmit()`);
    await page.wait(`document.querySelector('.withdraw-bid:not([disabled])')`, 'saved token purchase can be withdrawn');
    await page.evaluate(`document.querySelector('.withdraw-bid').click()`);
    await page.wait(`!document.querySelector('.withdraw-bid') && !document.querySelector('.pending-submission')`, 'token purchase withdrawn');
    assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '6');
    const tokens = await game.snapshot(player);
    assert.equal(tokens.pendingBid, null);
    assert.equal(tokens.game.players.find(candidate => candidate.id === player.id).cash, 32, 'Withdrawal spends no cash');
    await page.reload();
    await page.wait(`document.querySelector('#bid') && !document.querySelector('.withdraw-bid')`, 'token withdrawal persists');
    console.log('Passed: live capital and token bid withdrawal, editable drafts, readiness, reload persistence and resubmission.');
}
