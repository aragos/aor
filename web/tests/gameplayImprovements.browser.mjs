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
    assert.equal(await page.evaluate(`document.querySelector('.game-header h1').textContent`), 'AoR');
    assert.equal(await page.evaluate(`document.querySelector('.game-header').getBoundingClientRect().height <= 68`), true);
    assert.equal(await page.evaluate(`document.querySelector('.board-panel')`), null);
    assert.equal(await page.evaluate(`document.querySelector('.activity-toggle').getAttribute('aria-expanded')`), 'true', 'Activity is visible by default');
    assert.equal(await page.evaluate(`document.querySelector('.table-activity').getBoundingClientRect().top < innerHeight`), true, 'Desktop activity starts in view beside the board');

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
        assert.equal(await page.evaluate(`document.querySelector('.inspection-panel a[href="/help#card-${id}"]').target`), '_blank');
        const count = await page.evaluate('window.mapCommands.length');
        await page.evaluate(`document.querySelector('[data-map-province="${province}"]').dispatchEvent(new KeyboardEvent('keydown', {key:'Enter',bubbles:true}))`);
        assert.equal(await page.evaluate('window.mapCommands.length'), count, 'Map choice only prepares the order');
        if (id === 'rebellion') {
            await click('#tab-advances');
            await click('.advance-tile');
            assert.equal(await page.evaluate(`!!document.querySelector('.inspection-panel .advance-decision')`), true);
            assert.equal(await page.evaluate(`document.querySelector('.decision-panel .card-decision form > button:last-child').disabled`), false, 'Advance inspection preserves the prepared card target');
            await click('#tab-map');
            assert.equal(await page.evaluate(`!!document.querySelector('[data-map-province="rome"].eligible')`), true, 'Inspection keeps the active map picker');
        }
        await click('.decision-panel .card-decision form > button:last-child');
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
    for (const province of ['genoa', 'rome']) {
        await page.evaluate(`document.querySelector('[data-map-province="${province}"]').dispatchEvent(new KeyboardEvent('keydown', {key:'Enter',bubbles:true}))`);
    }
    await click('.decision-panel .card-decision form > button:last-child');
    assert.equal(await page.evaluate('window.mapCommands.at(-1)'), 'play pirates provinces genoa rome');

    await show({prompt: {messageId: 'prompt.cancel-adjustment', players: [viewer]},
        legalChoices: {commands: ['cancel-adjustment grain shortage', 'pass']}, game: {stage: 'DRAW_CARD'}});
    const base = baseline.game.catalog.commodities.find(commodity => commodity.id === 'GRAIN').baseValue;
    const cancel = '.game-actions .action-buttons button[aria-pressed]';
    assert.match(await page.evaluate(`document.querySelector('${cancel}').textContent`), new RegExp(`\\$${base}`));
    await click(cancel);
    assert.match(await page.evaluate(`document.querySelector('.game-actions .selected-action').textContent`), new RegExp(`\\$${base}`));

    await show({prompt: {messageId: 'prompt.purchase-stabilization', players: [viewer]},
        legalChoices: {commands: ['stabilize misery']}, game: {stage: 'PURCHASE', stabilizationCost: 8, stabilizationMisery: 20,
            activity: {...baseline.game.activity, actor: viewer, decisionPlayer: viewer, purchaseStep: 'STABILIZATION'}}});
    assert.match(await page.evaluate(`document.querySelector('.game-actions .action-buttons button').textContent`), /misery.*20/i);
    await show({prompt: {messageId: 'prompt.purchase-relief', players: [viewer]}, legalChoices: {commands: ['pass']},
        game: {stage: 'PURCHASE', reliefOffers: [{spaces: 1, cashCost: 20, ratingSpent: 1, affordable: false}],
            activity: {...baseline.game.activity, actor: viewer, decisionPlayer: viewer, purchaseStep: 'RELIEF'}}});
    assert.equal(await page.evaluate(`document.querySelector('#relief-spaces button').disabled`), true);
    assert.match(await page.evaluate(`document.querySelector('.game-actions .guided-form .blocker').textContent`), /afford/i);
    assert.equal(await page.evaluate(`document.querySelector('.game-actions .action-buttons button').disabled`), false, 'Unaffordable relief retains Pass');

    const order = [other.id, viewer, ...baseline.game.turnOrder.filter(id => id !== viewer && id !== other.id)];
    const players = baseline.game.players.map(player => player.id === viewer ? {...player, cash: 20, advances: [...player.advances, 'URBAN_ASCENDANCY']} : player);
    const waiting = {stage: 'BUY_CARD', players, turnOrder: order,
        activity: {...baseline.game.activity, actor: other.id, decisionPlayer: other.id}};
    await show({prompt: {messageId: 'prompt.buy-card', players: [other.id]}, legalChoices: {commands: []}, game: waiting});
    assert.match(await page.evaluate(`document.querySelector('.urban-ascendancy-queue').textContent`), /Cash available: \$20/);
    const count = await page.evaluate('window.mapCommands.length');
    await click('.urban-ascendancy-queue button');
    assert.equal(await page.evaluate('window.mapCommands.length'), count, 'Preregistration waits for the authoritative turn');
    await show({prompt: {messageId: 'prompt.buy-card', players: [viewer]}, legalChoices: {commands: ['buy-card', 'pass']},
        game: {...waiting, activity: {...waiting.activity, actor: viewer, decisionPlayer: viewer}}});
    await page.wait(`window.mapCommands.length === ${count + 1}`, 'Queued Urban Ascendancy purchase');
    assert.equal(await page.evaluate('window.mapCommands.at(-1)'), 'buy-card');
    await show({prompt: {messageId: 'prompt.buy-card', players: [viewer]}, legalChoices: {commands: ['buy-card', 'pass']},
        game: {...waiting, activity: {...waiting.activity, actor: viewer, decisionPlayer: viewer}}});
    assert.equal(await page.evaluate('window.mapCommands.length'), count + 1, 'Polling cannot repeat a queued purchase');
    // History replacement invalidates preauthorization even when game, viewer and turn stay equal.
    await show({prompt: {messageId: 'prompt.buy-card', players: [other.id]}, legalChoices: {commands: []}, game: waiting});
    await click('.urban-ascendancy-queue button');
    const afterFirstQueue = await page.evaluate('window.mapCommands.length');
    await show({undo: {...baseline.undo, editRevision: (baseline.undo?.editRevision ?? 0) + 1},
        prompt: {messageId: 'prompt.buy-card', players: [other.id]}, legalChoices: {commands: []}, game: waiting});
    await show({prompt: {messageId: 'prompt.buy-card', players: [viewer]}, legalChoices: {commands: ['buy-card', 'pass']},
        game: {...waiting, activity: {...waiting.activity, actor: viewer, decisionPlayer: viewer}}});
    assert.equal(await page.evaluate('window.mapCommands.length'), afterFirstQueue, 'An edited history requires fresh purchase authorization');
    assert.equal(await page.evaluate(`document.querySelectorAll('.decision-panel .province-inspector, .decision-panel .province-details').length`), 0);
    assert(me.capital && (await page.evaluate('document.title')).toLowerCase().includes(me.capital), 'Page title identifies the capital');
    console.log('Passed: compact capital title, separate inspections, automatic Crusades/Rebellion/Pirates map targeting, priced actions, relief blockers and queued Urban Ascendancy purchase.');
}
