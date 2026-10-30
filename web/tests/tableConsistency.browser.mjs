import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';
import {checkTable} from './table.browser.mjs';
import {checkMarketInsights} from './marketInsights.browser.mjs';
import {checkBottomHand} from './bottomHand.browser.mjs';
import {checkBidWithdrawal} from './purchaseClarity.browser.mjs';

export async function checkTableConsistency(page, web, baseline) {
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false});
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'table consistency harness');
    let revision = 31000;
    const viewer = baseline.viewerId;
    async function show(changes = {}) {
        const fixture = {...baseline, ...changes, revision: ++revision,
            game: {...baseline.game, ...changes.game}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'table consistency fixture');
    }
    const click = selector => page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
    const offer = baseline.game.expansionOffers.find(offer => offer.resultingPool !== null && offer.primaryAction !== 'INSPECT');
    assert(offer, 'Live expansion offers a full commitment');
    await show({undo: {...baseline.undo, available: true}});
    await page.evaluate(`document.querySelector('[data-map-province="${offer.destination}"]').dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true}))`);
    assert.equal(await page.evaluate(`document.querySelectorAll('.decision-panel .pool-summary').length`), 1, 'Expansion has one pool summary');
    assert.equal(await page.evaluate(`document.querySelector('[data-pool-after]').textContent`), String(offer.resultingPool));
    assert.equal(await page.evaluate(`document.querySelectorAll('.decision-panel .pass-action, .decision-panel .undo-action').length`), 2);
    assert.equal(await page.evaluate(`document.querySelectorAll('.decision-content .pass-action, .decision-content .undo-action').length`), 0);
    const assertToolbar = async () => assert.equal(await page.evaluate(`(() => {
        const pass=document.querySelector('.decision-toolbar .pass-action');
        const undo=document.querySelector('.decision-toolbar .undo-action');
        const p=pass.getBoundingClientRect(), u=undo.getBoundingClientRect();
        return p.right<=u.left && Math.abs((p.top+p.bottom)/2-(u.top+u.bottom)/2)<1 &&
            !pass.classList.contains('secondary') && p.height>=38 &&
            getComputedStyle(pass).backgroundColor!==getComputedStyle(undo).backgroundColor;
    })()`), true, 'Pass is prominent and stays to the left of Undo on one row');
    await assertToolbar();
    assert.equal(await page.evaluate(`(() => {
        const row=document.querySelector('.player-summary.is-viewer .player-status-row');
        const pool=row.querySelector('.player-pool').getBoundingClientRect();
        const status=row.querySelector('.player-status').getBoundingClientRect();
        return Math.abs((pool.top+pool.bottom)/2-(status.top+status.bottom)/2)<1;
    })()`), true, 'Pool and status share a centered row when space permits');
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-compact-expansion.png'), 1440, 1000);
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-compact-expansion-phone.png'), 390, 844, true);
    assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
    await assertToolbar();
    assert.equal(await page.evaluate(`document.querySelectorAll('.decision-panel .pool-summary').length`), 1);
    assert.equal(await page.evaluate(`(() => {
        const heading=document.querySelector('.decision-heading').getBoundingClientRect();
        const toolbar=document.querySelector('.decision-toolbar').getBoundingClientRect();
        return Math.abs(heading.top-toolbar.top)<1 && toolbar.left>=heading.right;
    })()`), true, 'Phone expansion toolbar shares the short decision heading row');
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false});

    const definition = baseline.game.cardInspections.find(card => card.definition.id === 'revolutionary-uprisings').definition;
    const changes = baseline.game.players.map((player, index) => ({player: player.id, fromValue: 100, toValue: index === 0 ? 125 : 100}));
    const card = {reference: 'uprisings', definition};
    const cardOffer = {reference: card.reference, playable: true, blockers: [], commodities: [], leaderDiscounts: [],
        choices: [{kind: 'NONE', value: null, command: 'play uprisings', miseryChanges: changes}],
        provinceTargets: [], requiredTargetCount: null, protectionMax: null, miseryChanges: changes};
    await show({prompt: {messageId: 'prompt.play-cards', players: [viewer]}, undo: {...baseline.undo, available: true},
        legalChoices: {commands: ['play uprisings', 'pass']}, game: {stage: 'PLAY_CARDS', hand: [card], cardOffers: [cardOffer]}});
    await click('[data-hand-card="uprisings"]');
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-misery-preview-player]').length`), changes.length, 'Group misery shows every active player including unchanged boxes');
    assert.equal(await page.evaluate(`document.querySelector('[data-hand-card]').textContent.includes('Playable') || document.querySelector('[data-hand-card]').textContent.includes('Selected')`), false);
    assert.equal(await page.evaluate(`document.querySelector('[data-hand-card]').getAttribute('aria-pressed')`), 'true');
    assert.equal(await page.evaluate(`document.querySelector('.card-confirmation > button').disabled`), false);
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-group-misery-preview.png'), 1440, 1000);
    await click('.decision-toolbar .pass-action');
    assert.equal(await page.evaluate('window.mapCommands.at(-1)'), 'pass');
    assert.equal(await page.evaluate(`document.querySelector('.decision-panel .card-decision')`), null, 'Pass closes the prepared card');

    const event = {...baseline.events.at(-1), id: 'uprising-receipt', kind: 'PLAY_CARD', actor: viewer,
        participants: baseline.game.players.map(({id, name, capital}) => ({id, name, capital})),
        card: definition.id, commodity: null, provinces: [], dice: [], competition: null, war: null,
        amount: null, cashChanges: [], tokenChanges: [],
        miseryChanges: changes.map(change => ({...change, spaces: change.toValue === 125 ? 1 : 0,
            reasons: [{kind: 'CARD_EFFECT', card: definition.id}]}))};
    await show({events: [event]});
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-event-id="uprising-receipt"] .event-misery-change').length`), changes.length);
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-event-id="uprising-receipt"] .event-misery-change .identity-label svg').length`), changes.length, 'All group results retain entry-time identities');
    assert.match(await page.evaluate(`document.querySelector('[data-event-id="uprising-receipt"]').textContent`), /\+0 misery.*100 → 100/);

    const other = baseline.game.players.find(player => player.id !== viewer);
    await show({prompt: {messageId: 'prompt.turn-order-tie', players: [viewer]},
        legalChoices: {commands: ['choose-turn-position 1', 'choose-turn-position 2']},
        game: {stage: 'TURN_ORDER_TIE', provisionalTieOrder: [other.id]}});
    assert.equal(await page.evaluate(`(() => {
        const buttons=[...document.querySelectorAll('.turn-tie .capital-choices > button[aria-pressed]')];
        return buttons.length===2 && buttons.every(button=>{
            const b=button.getBoundingClientRect(), text=button.querySelector('.label-message').getBoundingClientRect();
            const style=getComputedStyle(button);
            return style.justifyContent==='center' && style.alignItems==='center' &&
                Math.abs((b.top+b.bottom)/2-(text.top+text.bottom)/2)<1 &&
                Math.abs((b.left+b.right)/2-(text.left+text.right)/2)<1;
        });
    })()`), true, 'Before and After sentences center as complete units');
    const stone = baseline.game.cardInspections.find(card => card.definition.id === 'stone-1').definition;
    await show({prompt: {messageId: 'prompt.play-cards', players: [viewer]}, undo: {...baseline.undo, available: true},
        legalChoices: {commands: ['play stone commodity stone', 'pass']},
        game: {stage: 'PLAY_CARDS', hand: [{reference: 'stone', definition: stone}], cardOffers: [{...cardOffer,
            reference: 'stone', commodities: ['STONE'], miseryChanges: [], choices: [{kind: 'COMMODITY', value: 'stone',
                command: 'play stone commodity stone', cashChanges: [{player: viewer, before: 40, after: 42}]}]}]}});
    await click('[data-hand-card="stone"]');
    for (const [width, height, mobile] of [[1440, 1000, false], [390, 844, true]]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        await assertToolbar();
        assert.equal(await page.evaluate(`(() => {
            const message=document.querySelector('.cash-preview .label-message');
            const icon=message.querySelector('svg').getBoundingClientRect();
            const text=[...message.querySelectorAll('.label-message-text')].find(node=>node.textContent.includes('receives'));
            const range=document.createRange(); range.selectNodeContents(text);
            const rect=range.getBoundingClientRect();
            return Math.abs((icon.top+icon.bottom)/2-(rect.top+rect.bottom)/2)<2;
        })()`), true, 'Capital symbols and commodity receipt text align on desktop and phone');
        await page.evaluate(`document.querySelector('.cash-preview').scrollIntoView({block: 'center'})`);
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-commodity-receipt-${width}.png`));
    }
    console.log('Passed: compact expansion pool, shared status row, action toolbar, unlabeled card state, per-player group misery and centered turn-order buttons.');
}

if (process.argv[1]?.endsWith('/tableConsistency.browser.mjs')) {
    const web = process.env.MAP_WEB_URL;
    const page = await open('about:blank');
    try {
        await checkBidWithdrawal(page, web);
        const game = await createGame(6);
        for (const link of game.links) await game.send(link, 'bid-capital 0');
        let baseline;
        for (let step = 0; step < 100; step++) {
            const state = await game.snapshot(game.links[0]);
            const actor = game.links.find(link => state.prompt?.players.includes(link.id));
            assert(actor);
            const current = await game.snapshot(actor);
            if (current.game.stage === 'EXPANSION') { baseline = current; break; }
            const commands = current.legalChoices.commands;
            const command = current.game.stage === 'TOKEN_PURCHASE' ? `buy-tokens ${8 + game.links.indexOf(actor)}`
                : commands.find(command => command.startsWith('choose-capital '))
                    ?? commands.find(command => command.startsWith('choose-turn-position '))
                    ?? commands.find(command => command === 'pass')
                    ?? commands.find(command => command === 'stabilize cash');
            assert(command, `Setup progresses from ${current.game.stage}`);
            await game.send(actor, command);
        }
        assert(baseline);
        await checkTable(page, web, baseline);
        await checkTableConsistency(page, web, baseline);
        await checkMarketInsights(page, web, baseline);
        await checkBottomHand(page, web, baseline);
    } finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
