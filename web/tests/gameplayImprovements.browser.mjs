import assert from 'node:assert/strict';

export async function checkGameplayImprovements(page, web, baseline) {
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false});
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'gameplay improvements harness');
    let revision = 14000;
    const viewer = baseline.viewerId;
    const me = baseline.game.players.find(player => player.id === viewer);
    const other = baseline.game.players.find(player => player.id !== viewer);
    async function show(changes = {}) {
        const fixture = {...baseline, ...changes, revision: ++revision,
            game: {...baseline.game, ...changes.game}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'gameplay fixture');
    }
    const click = selector => page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
    await show();
    assert.match(await page.evaluate('document.title'), /^AoR · /);
    assert.equal(await page.evaluate(`document.querySelector('.game-header h1 .app-logo').alt`), 'Age of Renaissance');
    assert.equal(await page.evaluate(`document.querySelector('.game-header').getBoundingClientRect().height <= 68`), true);
    assert.equal(await page.evaluate(`document.querySelector('.board-panel')`), null);
    assert.equal(await page.evaluate(`document.querySelector('#tab-activity').textContent`), 'Activity', 'Activity has a workspace tab');
    assert.equal(await page.evaluate(`document.querySelector('.workspace-side .table-activity')`), null, 'Activity does not crowd desktop actions');

    for (const [id, province] of [['the-crusades', 'jerusalem'], ['rebellion', 'rome']]) {
        const command = `play target province ${province}`;
        await show({prompt: {messageId: 'prompt.play-cards', players: [viewer]},
            legalChoices: {commands: [command, 'pass']}, game: {stage: 'PLAY_CARDS',
                hand: [{reference: 'target', definition: {id, kind: 'event', messageId: `card.${id}`, shape: 'SQUARE'}}],
                cardOffers: [{reference: 'target', playable: true, blockers: [], commodities: [], leaderDiscounts: [],
                    choices: [{kind: 'PROVINCE', value: province, command}], provinceTargets: [], requiredTargetCount: null, protectionMax: null}]}});
        await click('#tab-markets');
        await click('[data-hand-card="target"]');
        await page.wait(`document.querySelector('#tab-map[aria-selected=true]') && document.querySelector('[data-map-province="${province}"].eligible')`, `${id} activates its map target`);
        assert.match(await page.evaluate(`document.querySelector('.decision-panel .card-event-text').textContent`), /.+/, 'Event descriptions accompany card actions');
        assert.equal(await page.evaluate(`document.querySelector('.card-inspection-dialog button[data-help-topic^="card-"]')`), null);
        assert.equal(await page.evaluate(`document.querySelector('.map-pick-banner').textContent`), 'Pick a target');
        const count = await page.evaluate('window.mapCommands.length');
        await page.evaluate(`document.querySelector('[data-map-province="${province}"]').dispatchEvent(new KeyboardEvent('keydown', {key:'Enter',bubbles:true}))`);
        assert.equal(await page.evaluate('window.mapCommands.length'), count, 'Map choice only prepares the order');
        if (id === 'rebellion') {
            await click('#tab-advances');
            await click('.advance-tile');
            assert.equal(await page.evaluate(`!!document.querySelector('.inspection-panel .advance-decision')`), true);
            assert.equal(await page.evaluate(`document.querySelector('.decision-actions .play-card-action').disabled`), false, 'Advance inspection preserves the prepared card target');
            await click('#tab-map');
            assert.equal(await page.evaluate(`!!document.querySelector('[data-map-province="rome"].eligible')`), true, 'Inspection keeps the active map picker');
        }
        await click('.decision-actions .play-card-action');
        assert.equal(await page.evaluate('window.mapCommands.at(-1)'), command);
        assert.equal(await page.evaluate(`document.querySelector('.card-decision')`), null, 'Successful card play closes both surfaces');
    }

    await show({prompt: {messageId: 'prompt.play-cards', players: [viewer]}, legalChoices: {commands: ['play pirates', 'pass']},
        game: {stage: 'PLAY_CARDS', hand: [{reference: 'pirates', definition: {id: 'pirates-vikings', kind: 'event', messageId: 'card.pirates-vikings', shape: 'SQUARE'}}],
            cardOffers: [{reference: 'pirates', playable: true, blockers: [], commodities: [], leaderDiscounts: [],
                choices: [{kind: 'PROVINCES', value: null, command: null}], provinceTargets: ['genoa', 'rome'], requiredTargetCount: 2, protectionMax: null}]}});
    await click('#tab-markets');
    await click('[data-hand-card="pirates"]');
    await page.wait(`document.querySelector('#tab-map[aria-selected=true]') && document.querySelector('[data-map-province="genoa"].eligible')`, 'Pirates activates map targets');
    assert.equal(await page.evaluate(`document.querySelector('.map-pick-banner').textContent`), 'Pick 2 targets');
    for (const province of ['genoa', 'rome']) {
        await page.evaluate(`document.querySelector('[data-map-province="${province}"]').dispatchEvent(new KeyboardEvent('keydown', {key:'Enter',bubbles:true}))`);
    }
    await click('.decision-actions .play-card-action');
    assert.equal(await page.evaluate('window.mapCommands.at(-1)'), 'play pirates provinces genoa rome');

    await show({prompt: {messageId: 'prompt.cancel-adjustment', players: [viewer]},
        legalChoices: {commands: ['cancel-adjustment grain shortage', 'pass']}, game: {stage: 'DRAW_CARD'}});
    const base = baseline.game.catalog.commodities.find(commodity => commodity.id === 'GRAIN').baseValue;
    const cancel = '.game-actions .action-buttons button[aria-pressed]';
    assert.match(await page.evaluate(`document.querySelector('${cancel}').textContent`), new RegExp(`\\$${base}`));
    await click(cancel);
    assert.match(await page.evaluate(`document.querySelector('.selected-action-confirmation').textContent`), new RegExp(`\\$${base}`));

    await show({prompt: {messageId: 'prompt.purchase-stabilization', players: [viewer]},
        legalChoices: {commands: ['stabilize misery']}, game: {stage: 'PURCHASE', stabilizationCost: 8, stabilizationMisery: 20,
            activity: {...baseline.game.activity, actor: viewer, decisionPlayer: viewer, purchaseStep: 'STABILIZATION'}}});
    assert.match(await page.evaluate(`document.querySelector('.decision-actions .simple-action').textContent`), /misery.*20/i);
    await show({prompt: {messageId: 'prompt.purchase-relief', players: [viewer]}, legalChoices: {commands: ['pass']},
        game: {stage: 'PURCHASE', reliefOffers: [{spaces: 1, cashCost: 20, ratingSpent: 1, affordable: false}],
            activity: {...baseline.game.activity, actor: viewer, decisionPlayer: viewer, purchaseStep: 'RELIEF'}}});
    assert.equal(await page.evaluate(`document.querySelector('#relief-spaces button').disabled`), true);
    assert.match(await page.evaluate(`document.querySelector('.game-actions .guided-form .blocker').textContent`), /afford/i);
    assert.equal(await page.evaluate(`document.querySelector('.decision-actions .pass-action').disabled`), false, 'Unaffordable relief retains Pass');

    // Durable private planning is covered through live offers in planning.browser.mjs.
    assert.equal(await page.evaluate(`document.querySelectorAll('.decision-panel .province-inspector, .decision-panel .province-details').length`), 0);
    assert(me.capital && (await page.evaluate('document.title')).toLowerCase().includes(me.capital), 'Page title identifies the capital');
    assert.equal(await page.evaluate(`[...document.querySelectorAll('button')].some(button => /inspect/i.test(button.textContent))`), false, 'Objects do not have parallel Inspect controls');
    assert.equal(await page.evaluate(`document.querySelector('.player-stats .help-link').getBoundingClientRect().width <= 14`), true, 'Track help looks like a footnote');

    const stone = baseline.game.cardInspections.find(card => card.definition.id === 'stone-1').definition;
    const cashChanges = [{player: viewer, before: 40, after: 42}];
    await show({prompt: {messageId: 'prompt.play-cards', players: [viewer]}, legalChoices: {commands: ['play stone commodity stone', 'pass']},
        game: {stage: 'PLAY_CARDS', hand: [{reference: 'stone', definition: stone}],
            cardOffers: [{reference: 'stone', playable: true, blockers: [], commodities: ['STONE'], leaderDiscounts: [],
                choices: [{kind: 'COMMODITY', value: 'stone', command: 'play stone commodity stone', cashChanges}],
                provinceTargets: [], requiredTargetCount: null, protectionMax: null}]}});
    await click('[data-hand-card="stone"]');
    assert.equal(await page.evaluate(`document.querySelector('.card-inspection-dialog[open], .inspection-panel')`), null, 'A commodity has no inspection box');
    assert.equal(await page.evaluate(`!!document.querySelector('.cash-preview .identity-label svg')`), true, 'Commodity cash previews retain the capital symbol');
    assert.match(await page.evaluate(`document.querySelector('.cash-preview').textContent`), /\$40 → \$42/);
    assert.equal(await page.evaluate(`document.querySelector('.table-hand button[data-help-topic="commodity-stone"]').textContent`), '?');
    const commandsBeforeHelp = await page.evaluate('window.mapCommands.length');
    await page.evaluate(`(() => {const link=document.querySelector('.table-hand button[data-help-topic="commodity-stone"]');link.addEventListener('click',event=>event.preventDefault(),{once:true});link.click();})()`);
    await page.wait(`document.querySelector('.reference-dialog #commodity-stone .payout-box')`, 'Payout popup loads');
    assert.equal(await page.evaluate('window.mapCommands.length'), commandsBeforeHelp, 'Payout help never plays the card');
    assert.equal(await page.evaluate(`document.querySelector('.decision-actions .play-card-action').disabled`), false, 'Payout help preserves the prepared play');

    await click('.reference-dialog .close-detail');
    await show({prompt: {messageId: 'prompt.purchase-shipping', players: [viewer]}, legalChoices: {commands: ['upgrade-ships', 'pass']},
        game: {stage: 'PURCHASE', activity: {...baseline.game.activity, actor: viewer, decisionPlayer: viewer, purchaseStep: 'SHIPPING'},
            players: baseline.game.players.map(player => player.id === viewer ? {...player, ships: {fleet: 'GALLEY', level: 2}} : player),
            shippingOffer: {cost: 10, ships: {fleet: 'GALLEY', level: 4}, cashAfter: 30, blockers: []}}});
    assert.match(await page.evaluate(`document.querySelector('.game-actions .purchase-preview').textContent`), /Galley 2 → Galley 4/);
    console.log('Passed: compact capital title, separate inspections, automatic Crusades/Rebellion/Pirates map targeting, priced actions, relief blockers and relief blockers.');
}
