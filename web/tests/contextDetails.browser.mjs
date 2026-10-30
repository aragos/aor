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
    await page.wait(`document.querySelector('.card-inspection-dialog .card-decision')`, 'recurring card inspection');
    assert.equal(await page.evaluate(`document.querySelectorAll('.card-inspection-dialog .card-decision > .eyebrow').length`), 0);
    assert.match(await page.evaluate(`document.querySelector('.card-inspection-dialog .recurring-badge').textContent`), /Recurring/);
    await page.evaluate(`document.querySelector('.card-inspection-dialog .close-detail')?.click()`);
    await page.evaluate(`document.querySelector('[data-player="${viewer}"] > summary').click()`);
    assert.equal(await page.evaluate(`document.querySelector('[data-player="${viewer}"] .written-cash dd').textContent`), '$40');
    assert.equal(await page.evaluate(`[...document.querySelectorAll('[data-player="${viewer}"] .player-detail dt')].some(label=>label.textContent==='Income')`), false);
    assert.match(await page.evaluate(`document.querySelector('[data-player="${viewer}"] .player-money').textContent`), /\$75/);
    for (const topic of ['misery-track', 'shipping-track']) {
        const help = `[data-player="${viewer}"] .player-stats button[data-help-topic="${topic}"]`;
        assert.equal(await page.evaluate(`document.querySelector('${help}').textContent`), '?');
        assert.equal(await page.evaluate(`document.querySelector('${help}').getAttribute('aria-haspopup')`), 'dialog');
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
    await page.evaluate(`(() => {const link=document.querySelector('[data-player="${other.id}"] button[data-help-topic="misery-track"]');link.addEventListener('click',event=>event.preventDefault(),{once:true});link.click();})()`);
    assert.equal(await page.evaluate(`document.querySelectorAll('.picked-target').length`), 0, 'Help does not choose a player target');
    assert.equal(await page.evaluate(`window.mapCommands.length`), 0);

    await page.wait(`document.querySelector('.reference-dialog #misery-track')`, 'Target help popup');
    assert.equal(await page.evaluate(`document.querySelector('#card-charlemagne, #advances')`), null, 'Contextual track lookup omits duplicate card and advance catalogs');
    await page.evaluate(`document.querySelector('.reference-dialog .close-detail').click()`);
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
            legalChoices: {commands: ['replace-event-tokens none']},
            game: {...baseline.game, stage: 'PLAY_CARDS',
                players: baseline.game.players.map(player => player.id === viewer ? {...player, poolTokens, stockTokens: 0, misery: 20} : player),
                activity: {actor: other.id, decisionPlayer: viewer, choice: 'replace-event-token', purchaseStep: null, startEntry: 31900, decisionEntry: revision},
                replacementToken: {player: viewer, provinces: [province], event, poolTokens, poolCost: 1,
                    maxReplacements: poolTokens ? 1 : 0, stockTokens: 0, misery: 20, civilWarLossPending: event === 'CIVIL_WAR'}}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(snapshot)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'replacement fixture');
    }
    await show('rome', 1);
    const description = await page.evaluate(`document.querySelector('.replacement-token-context').textContent`);
    assert.match(description, /Rebellion.*Rome.*No stock tokens.*colored pool token.*adds no misery/s);
    await page.evaluate(`document.querySelector('[data-replacement-province="rome"] button:first-child').click()`);
    assert.match(await page.evaluate(`document.querySelector('.replacement-review').textContent`), /Pool tokens: 1 → 0.*Place 1 pool token in Rome/s);
    await page.evaluate(`document.querySelector('.replacement-confirmation').click()`);
    assert.equal(await page.evaluate(`window.mapCommands.at(-1)`), 'replace-event-tokens rome');
    await show('genoa', 0, 'CIVIL_WAR');
    const next = await page.evaluate(`document.querySelector('.replacement-token-context').textContent`);
    assert.match(next, /Civil War.*Genoa.*No pool tokens.*misery has already been applied.*cash or token loss/s);
    assert.equal(await page.evaluate(`document.querySelector('[data-replacement-province="genoa"] button:first-child').disabled`), true);
    await page.evaluate(`document.querySelector('[data-replacement-province="genoa"] button:last-child').click()`);
    await page.evaluate(`document.querySelector('.replacement-confirmation').click()`);
    assert.equal(await page.evaluate(`window.mapCommands.at(-1)`), 'replace-event-tokens none');
    console.log('Passed: event/recipient/province context, exact pool cost, explicit replacement/empty choices and exhausted multi-province flow.');
}
