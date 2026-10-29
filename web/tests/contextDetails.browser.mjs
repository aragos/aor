import assert from 'node:assert/strict';
import {join} from 'node:path';

export async function checkCardAndPlayerDetails(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1280, height: 900, deviceScaleFactor: 1, mobile: false});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'card and player details harness');
    const viewer = baseline.viewerId;
    const other = baseline.game.players.find(player => player.id !== viewer);
    let revision = 31000;
    const famine = baseline.game.cardInspections.find(card => card.definition.id === 'famine').definition;
    const leader = baseline.game.cardInspections.find(card => card.definition.id === 'charlemagne').definition;
    let fixture;
    async function show(changes = {}) {
        fixture = {...baseline, ...changes, revision: ++revision, game: {...baseline.game, ...changes.game}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'details fixture');
    }
    await show({game: {currentEpoch: 2, deckRemaining: 22,
        players: baseline.game.players.map(player => player.id === viewer ? {...player, cash: 75, writtenCash: 40,
            ships: {fleet: 'OCEAN', level: 4}} : player),
        hand: [{reference: 'recurring', definition: famine}, {reference: 'once', definition: leader}],
        cardOffers: []}});
    assert.equal(await page.evaluate(`document.querySelector('.epoch-progress').textContent`), 'Epoch 2 (22 cards remaining)');
    assert.equal(await page.evaluate(`document.querySelector('.table-effects').textContent.includes('22 cards')`), false, 'Deck count has one home');
    assert.equal(await page.evaluate(`document.querySelectorAll('.hand-card .eyebrow').length`), 0, 'Card type headers are removed');
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-hand-card="recurring"] .recurring-badge').length`), 1);
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-hand-card="once"] .recurring-badge').length`), 0);
    await page.evaluate(`document.querySelector('[data-hand-card="recurring"]').click()`);
    await page.wait(`document.querySelector('.inspection-panel .card-decision')`, 'recurring card inspection');
    assert.equal(await page.evaluate(`document.querySelectorAll('.inspection-panel .card-decision > .eyebrow').length`), 0);
    assert.match(await page.evaluate(`document.querySelector('.inspection-panel .recurring-badge').textContent`), /Recurring/);
    await page.evaluate(`document.querySelector('[data-player="${viewer}"] > summary').click()`);
    assert.equal(await page.evaluate(`document.querySelector('[data-player="${viewer}"] .written-cash dd').textContent`), '$40');
    assert.equal(await page.evaluate(`[...document.querySelectorAll('[data-player="${viewer}"] .player-detail dt')].some(label=>label.textContent==='Income')`), false);
    assert.match(await page.evaluate(`document.querySelector('[data-player="${viewer}"] .player-money').textContent`), /\$75/);
    for (const topic of ['misery-track', 'shipping-track']) {
        const help = `[data-player="${viewer}"] .player-stats a[href="/help#${topic}"]`;
        assert.equal(await page.evaluate(`document.querySelector('${help}').textContent`), '?');
        assert.equal(await page.evaluate(`document.querySelector('${help}').target`), '_blank');
    }
    assert.equal(await page.evaluate(`document.querySelectorAll('.player-detail .reference-link').length`), 0, 'Standalone reference-track links are removed');
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-player-context-desktop.png'), 1280, 900);
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-player-context-phone.png'), 390, 844, true);
    assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
    assert.equal(await page.evaluate(`[...document.querySelectorAll('.player-summary > summary')].every(summary=>summary.scrollWidth<=summary.clientWidth)`), true, 'Track icons and long fleet names fit player boxes');
    await show({...fixture, game: {...fixture.game, currentEpoch: 3, deckRemaining: 4}});
    assert.equal(await page.evaluate(`document.querySelector('.epoch-progress').textContent`), 'Epoch 3 (4 cards remaining)', 'Epoch/deck counter follows snapshots');

    const civilWar = baseline.game.cardInspections.find(card => card.definition.id === 'civil-war').definition;
    const command = `play target player ${other.id}`;
    await show({prompt: {messageId: 'prompt.play-cards', players: [viewer]}, legalChoices: {commands: [command, 'pass']},
        game: {stage: 'PLAY_CARDS', hand: [{reference: 'target', definition: civilWar}],
            cardOffers: [{reference: 'target', playable: true, blockers: [], choices: [{kind: 'PLAYER', value: other.id, command}],
                commodities: [], leaderDiscounts: [], provinceTargets: [], requiredTargetCount: null, protectionMax: null}]}});
    await page.evaluate(`document.querySelector('[data-hand-card="target"]').click()`);
    await page.wait(`document.querySelector('[data-player="${other.id}"].eligible-target')`, 'eligible Civil War player');
    await page.evaluate(`(() => {const link=document.querySelector('[data-player="${other.id}"] a[href="/help#misery-track"]');link.addEventListener('click',event=>event.preventDefault(),{once:true});link.click();})()`);
    assert.equal(await page.evaluate(`document.querySelectorAll('.picked-target').length`), 0, 'Help does not choose a player target');
    assert.equal(await page.evaluate(`window.mapCommands.length`), 0);

    await page.send('Page.navigate', {url: web + '/help#cards'});
    await page.wait(`document.querySelector('#card-famine .recurring-badge')`, 'recurring reference labels');
    const reference = await page.evaluate(`document.querySelector('#cards').textContent`);
    assert(!/\b(circle|square)\b/i.test(reference), 'Card reference uses behavior rather than shape vocabulary');
    assert.equal(await page.evaluate(`document.querySelectorAll('#cards article .eyebrow').length`), 0);
    assert.equal(await page.evaluate(`!!document.querySelector('#misery-track') && !!document.querySelector('#shipping-track')`), true);
    console.log('Passed: recurring badges, concise card displays, live epoch counter, contextual track links, written cash and preserved target selection.');
}

export async function checkReplacementContext(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'replacement context harness');
    const viewer = baseline.viewerId;
    const other = baseline.game.players.find(player => player.id !== viewer);
    let revision = 32000;
    async function show(province, poolTokens, event = 'REBELLION') {
        const snapshot = {...baseline, revision: ++revision,
            prompt: {messageId: 'prompt.replace-event-token', players: [viewer]},
            legalChoices: {commands: [...(poolTokens ? ['replace-event-token pool'] : []), 'replace-event-token none']},
            game: {...baseline.game, stage: 'PLAY_CARDS',
                players: baseline.game.players.map(player => player.id === viewer ? {...player, poolTokens, stockTokens: 0, misery: 20} : player),
                activity: {actor: other.id, decisionPlayer: viewer, choice: 'replace-event-token', purchaseStep: null, startEntry: 31900, decisionEntry: revision},
                replacementToken: {player: viewer, province, event, poolTokens, poolCost: 1,
                    poolAfter: poolTokens ? poolTokens - 1 : null, stockTokens: 0, misery: 20, civilWarLossPending: event === 'CIVIL_WAR'}}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(snapshot)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'replacement fixture');
    }
    await show('rome', 1);
    const description = await page.evaluate(`document.querySelector('.replacement-token-context').textContent`);
    assert.match(description, /Rome.*Rebellion.*No stock tokens.*Pool tokens: 1 → 0.*colored token.*adds no misery/s);
    await page.evaluate(`(() => {const button=[...document.querySelectorAll('.action-buttons button')].find(button=>button.textContent==='Place 1 pool token in Rome');button.click();})()`);
    assert.match(await page.evaluate(`document.querySelector('.selected-action').textContent`), /Place 1 pool token in Rome/);
    await page.evaluate(`document.querySelector('.selected-action button:last-child').click()`);
    assert.equal(await page.evaluate(`window.mapCommands.at(-1)`), 'replace-event-token pool');
    await show('genoa', 0, 'CIVIL_WAR');
    const next = await page.evaluate(`document.querySelector('.replacement-token-context').textContent`);
    assert.match(next, /Genoa.*Civil War.*No pool tokens.*misery has already been applied.*cash or token loss/s);
    assert.equal(await page.evaluate(`[...document.querySelectorAll('.action-buttons button')].some(button=>button.textContent.includes('Place 1 pool token'))`), false);
    await page.evaluate(`(() => {const button=[...document.querySelectorAll('.action-buttons button')].find(button=>button.textContent==='Leave Genoa empty');button.click();})()`);
    await page.evaluate(`document.querySelector('.selected-action button:last-child').click()`);
    assert.equal(await page.evaluate(`window.mapCommands.at(-1)`), 'replace-event-token none');
    console.log('Passed: event/recipient/province context, exact pool cost, explicit replacement/empty choices and exhausted multi-province flow.');
}
