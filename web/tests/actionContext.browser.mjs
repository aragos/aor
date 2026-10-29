import assert from 'node:assert/strict';
import {join} from 'node:path';

export async function checkActionContext(page, web, baseline) {
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1100, height: 900, deviceScaleFactor: 1, mobile: false});
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'action context harness');
    const viewer = baseline.viewerId;
    const other = baseline.game.players.find(player => player.id !== viewer);
    let revision = 19000;
    let fixture;
    async function show(changes = {}) {
        fixture = {...baseline, ...changes, revision: ++revision,
            game: {...baseline.game, ...changes.game}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'action context fixture');
    }
    const click = selector => page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
    const event = {...baseline.events.at(-1), id: 'old-result', kind: 'COMPETITION_RESULT', actor: viewer, turnNumber: baseline.game.turnNumber, stage: 'EXPANSION',
        provinces: ['rome'], card: null, commodity: null, dice: [], cashChanges: [], miseryChanges: [],
        competition: {attackerWon: false, defenders: [other.id]}, war: null};
    const offer = {destination: 'rome', primaryAction: 'ATTACK', requiredTokens: 3, availablePool: 10,
        resultingPool: 7, establishesMarker: true, uncertain: true, blockers: [], partialPlacements: [],
        shipping: null, incomeBefore: 15, incomeAfter: 18, reachable: true};
    await show({events: [event], legalChoices: {commands: ['attack rome', 'pass'], bidMax: null, bidMin: null},
        game: {stage: 'EXPANSION', expansionOffers: [offer], activity: {...baseline.game.activity, actor: viewer, decisionPlayer: viewer, choice: 'expand'}}});
    assert.equal(await page.evaluate(`document.querySelector('.available-resources, .stock-details')`), null);
    assert.equal(await page.evaluate(`document.querySelector('.action-cash')`), null, 'Expansion without a purchase needs no cash strip');
    assert.equal(await page.evaluate(`document.querySelector('.competition-notice, .war-notice')`), null, 'Historical results stay in activity');
    await page.evaluate(`document.querySelector('[data-map-province="rome"]').dispatchEvent(new KeyboardEvent('keydown', {key:'Enter',bubbles:true}))`);
    await click('.primary-expansion');
    await page.wait(`window.mapCommands.at(-1) === 'attack rome'`, 'local attack sent');
    await show({...fixture, game: {...fixture.game, activity: {...fixture.game.activity, choice: 'cathedral', decisionPlayer: other.id}},
        prompt: {messageId: 'prompt.cathedral', players: [other.id]}, legalChoices: {commands: [], bidMax: null}});
    assert.equal(await page.evaluate(`!!document.querySelector('.competition-notice')`), false, 'Attack waits through the Cathedral response');
    const result = {...event, id: 'local-result'};
    await show({...fixture, events: [event, result]});
    await page.wait(`document.querySelector('.competition-notice.lost')`, 'new local competition explicitly shown');
    await click('.competition-notice .close-detail');
    await show({...fixture});
    assert.equal(await page.evaluate(`document.querySelector('.competition-notice')`), null, 'Dismissed result stays dismissed during polling');
    await show({...fixture, events: [...fixture.events, {...event, id: 'remote-result', actor: other.id}]});
    assert.equal(await page.evaluate(`document.querySelector('.competition-notice')`), null, 'Remote competitions do not create an action notice');

    await show({...fixture, prompt: {messageId: 'prompt.cathedral', players: [viewer]}, legalChoices: {commands: ['cathedral', 'pass'], bidMax: null},
        game: {...fixture.game, activity: {...fixture.game.activity, choice: 'cathedral', decisionPlayer: viewer}}});
    assert.equal(await page.evaluate(`!!document.querySelector('.action-cash')`), false, 'Cathedral does not spend cash');
    await click('.decision-panel .action-buttons button');
    await show({...fixture, events: [...fixture.events, {...event, id: 'local-cathedral'}]});
    await page.wait(`document.querySelector('.competition-notice')`, 'local Cathedral explicitly shows its result');
    await click('.competition-notice .close-detail');

    await show({...fixture, legalChoices: {commands: ['pass'], bidMax: null}, game: {...fixture.game, stage: 'PLAY_CARDS',
        activeWar: {initiator: viewer, opponent: other.id, startedTurn: baseline.game.turnNumber, ties: 0},
        activity: {...fixture.game.activity, choice: 'war-preparation'}}});
    await click('.decision-panel .action-buttons button');
    const war = {...event, id: 'local-war', kind: 'WAR_RESULT', competition: null,
        war: {initiator: viewer, opponent: other.id, winner: other.id, totals: {[viewer]: 2, [other.id]: 5}, requiredTransfers: 3}};
    await show({...fixture, events: [...fixture.events, war]});
    await page.wait(`document.querySelector('.war-notice')`, 'new local War explicitly shown');
    await show({...fixture, events: fixture.events.filter(event => event.id !== war.id)});
    await page.wait(`!document.querySelector('.war-notice')`, 'History restoration removes its obsolete War receipt');

    const commodity = baseline.game.cardInspections.find(card => card.definition.id === 'stone-1');
    const payout = {commodity: 'STONE', adjustments: ['SHORTAGE'], owners: [
        {player: viewer, provinces: ['venice'], marketCount: 1, effectiveBox: 2, payout: 4, industryBonus: 0},
    ]};
    const cardEvent = {...event, id: 'history-commodity', kind: 'PLAY_CARD', card: 'stone-1', competition: null};
    await show({events: [cardEvent], game: {commodityPayouts: [payout]}});
    const beforeCommands = await page.evaluate('window.mapCommands.length');
    await click('.event-card');
    await page.wait(`document.querySelector('.inspection-panel .card-value')`, 'historical commodity opens in game');
    assert.match(await page.evaluate(`document.querySelector('.inspection-panel').textContent`), /Stone.*Shortage.*\$4/s);
    assert.equal(await page.evaluate('location.pathname'), '/tests/map-harness.html');
    await show({...fixture, game: {...fixture.game, commodityPayouts: [{...payout, adjustments: ['SURPLUS'], owners: [{...payout.owners[0], payout: 2}]}]}});
    assert.match(await page.evaluate(`document.querySelector('.inspection-panel').textContent`), /Surplus.*\$2/s, 'Historical commodity uses current payout projections');
    assert.equal(await page.evaluate('window.mapCommands.length'), beforeCommands);

    const card = {reference: 'draft', definition: {id: 'the-crusades', kind: 'event', messageId: 'card.the-crusades', shape: 'CIRCLE'}};
    await show({events: [cardEvent], prompt: {messageId: 'prompt.play-cards', players: [viewer]},
        legalChoices: {commands: ['play draft province jerusalem', 'pass'], bidMax: null}, game: {stage: 'PLAY_CARDS', hand: [card],
            cardOffers: [{reference: 'draft', playable: true, blockers: [], commodities: [], leaderDiscounts: [],
                choices: [{kind: 'PROVINCE', value: 'jerusalem', command: 'play draft province jerusalem'}], provinceTargets: [], requiredTargetCount: null, protectionMax: null}]}});
    await click('[data-hand-card="draft"]');
    await page.wait(`document.querySelector('[data-map-province="jerusalem"].eligible')`, 'draft map picker');
    await page.evaluate(`document.querySelector('[data-map-province="jerusalem"]').dispatchEvent(new KeyboardEvent('keydown', {key:'Enter',bubbles:true}))`);
    await click('.event-card');
    assert.equal(await page.evaluate(`document.querySelector('.decision-panel .card-decision form > button:last-child').disabled`), false, 'Activity inspection preserves a prepared card target');
    assert.match(await page.evaluate(`document.querySelector('.decision-panel .card-event-text').textContent`), /Area VI/);
    await click('.inspection-panel .close-detail');
    assert.equal(await page.evaluate(`!!document.querySelector('.inspection-panel')`), false);
    assert.equal(await page.evaluate(`document.querySelector('.decision-panel .card-decision form > button:last-child').disabled`), false, 'Closing inspection preserves the prepared target');
    await click('[data-hand-card="draft"]');
    await click('.inspection-panel .close-detail');
    assert.equal(await page.evaluate(`document.querySelector('.decision-panel .card-decision form > button:last-child').disabled`), false, 'Closing the active card description preserves its action too');

    const hand = Array.from({length: 12}, (_, index) => ({reference: `drag-${index}`, definition: commodity.definition}));
    await show({events: [], game: {hand, cardOffers: []}});
    await page.wait(`document.querySelector('.hand-cards[data-draggable="true"]')`, 'overflowing hand is draggable');
    await page.evaluate(`document.querySelector('.hand-cards').scrollIntoView({block:'center'})`);
    const bounds = await page.evaluate(`(() => {const b=document.querySelector('.hand-cards').getBoundingClientRect();return {x:b.x+250,y:b.y+45};})()`);
    const count = await page.evaluate('window.mapCommands.length');
    await page.send('Input.dispatchMouseEvent', {type: 'mouseMoved', ...bounds});
    await page.send('Input.dispatchMouseEvent', {type: 'mousePressed', ...bounds, button: 'left', buttons: 1, clickCount: 1});
    for (let distance = 20; distance <= 180; distance += 20) {
        await page.send('Input.dispatchMouseEvent', {type: 'mouseMoved', x: bounds.x - distance, y: bounds.y, button: 'left', buttons: 1});
    }
    await page.send('Input.dispatchMouseEvent', {type: 'mouseReleased', x: bounds.x - 180, y: bounds.y, button: 'left', buttons: 0, clickCount: 1});
    assert.equal(await page.evaluate(`document.querySelector('.hand-cards').scrollLeft > 100`), true, 'Dragging scrolls the hand');
    assert.equal(await page.evaluate(`document.querySelector('.hand-card[aria-pressed="true"]')`), null, 'A drag does not select a card');
    assert.equal(await page.evaluate('window.mapCommands.length'), count, 'Dragging cannot send an order');
    const scrolled = await page.evaluate(`document.querySelector('.hand-cards').scrollLeft`);
    await page.send('Input.dispatchMouseEvent', {type: 'mousePressed', ...bounds, button: 'left', buttons: 1, clickCount: 1});
    await page.send('Input.dispatchMouseEvent', {type: 'mouseMoved', x: bounds.x, y: bounds.y - 100, buttons: 1});
    await page.send('Input.dispatchMouseEvent', {type: 'mouseReleased', x: bounds.x, y: bounds.y - 100, button: 'left', buttons: 0, clickCount: 1});
    await page.send('Input.dispatchMouseEvent', {type: 'mouseMoved', x: bounds.x - 100, y: bounds.y, buttons: 0});
    assert.equal(await page.evaluate(`document.querySelector('.hand-cards').scrollLeft`), scrolled, 'Releasing outside the hand cannot leave a drag armed');
    await page.evaluate(`document.querySelector('[data-hand-card="drag-2"]').focus(); document.querySelector('[data-hand-card="drag-2"]').click()`);
    assert.equal(await page.evaluate(`document.querySelector('[data-hand-card="drag-2"]').getAttribute('aria-pressed')`), 'true', 'Keyboard/programmatic card activation still works after dragging');

    const advance = baseline.game.catalog.advances.find(candidate => candidate.id === 'NATIONALISM');
    await show({prompt: {messageId: 'prompt.purchase-advances', players: [viewer]}, legalChoices: {commands: [`buy-advance ${advance.letter}`, 'pass'], bidMax: null},
        game: {stage: 'PURCHASE', activity: {...baseline.game.activity, actor: viewer, purchaseStep: 'ADVANCES'},
            advanceOffers: [{advance: advance.id, price: 30, unavailableReason: null,
                leaderDiscounts: [{leaderCardId: 'charlemagne', baseAmount: 20, writtenRecordBonus: 10, amount: 30}],
                priceBreakdown: {advance: advance.id, baseCost: 60, categoryCredit: 0, advanceCredits: [], effectiveDiscount: 30,
                    institutionalResearchCredit: 0, leaders: [{leaderCardId: 'charlemagne', baseAmount: 20, writtenRecordBonus: 10, amount: 30}],
                    price: 30, miseryBefore: 0, miseryAfter: 0}}]}});
    await click(`[data-advance="${advance.id}"]`);
    assert.match(await page.evaluate(`document.querySelector('.action-cash').textContent`), /Cash available:.*\$/);
    assert.match(await page.evaluate(`document.querySelector('.decision-panel .advance-benefits').textContent`), /.+/);
    await click('.decision-panel .price-details > summary');
    assert.match(await page.evaluate(`document.querySelector('.decision-panel .leader-discount').textContent`), /−\$30.*Includes \$10 Written Record/);
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-action-context-desktop.png'), 1280, 900);
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-action-context-phone.png'), 390, 844, true);
    assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`), true, 'Descriptions and credit labels fit a phone');
    console.log('Passed: contextual cash, local battle receipts, historical live card inspection, draft preservation, hand dragging, inline rules and explicit Written Record credits.');
}
