import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';
import {checkActivityPayments} from './activityPayments.browser.mjs';
import {checkTokenPurchaseRound} from './roundSummaries.browser.mjs';
import {assertTextContrast} from './contrast-support.mjs';

const game = await createGame(3);
const baseline = await game.snapshot(game.links[0]);
const page = await open('about:blank');
const web = process.env.MAP_WEB_URL;
try {
    await checkActivityPayments(page, web, baseline);
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'StrictMode activity grouping fixture');
    const [actor, defender, secondDefender] = baseline.game.players.map(player => player.id);
    const participants = [
        {id: actor, name: 'Recorded attacker', capital: 'venice'},
        {id: defender, name: 'Recorded defender', capital: 'genoa'},
        {id: secondDefender, name: 'Other defender', capital: 'barcelona'},
    ];
    const event = {...baseline.events[0], turnNumber: 2, stage: 'EXPANSION', actor, participants,
        provinces: [], card: null, commodity: null, advance: null, amount: null, detail: null,
        target: null, competition: null, war: null, dice: [], cashChanges: [], miseryChanges: [],
        tokenChanges: [], tokenPurchases: [], positionChanges: [], reliefPayment: null,
        marketRoll: null, protectionCash: null, leaderRebate: null, drawReason: null};
    const purchase = {...event, id: '100:0', kind: 'BUY_EXPANSION_CARD', cashChanges: [{player: actor, amount: -5}]};
    const draw = {...event, id: '100:1', kind: 'DRAW_CARD', drawReason: 'EXPANSION_PURCHASE', card: 'stone-1'};
    const attack = {...event, id: '101:0', kind: 'PLACE', provinces: ['florence'], amount: 8};
    const result = {...attack, id: '101:1', kind: 'COMPETITION_RESULT', detail: 'COMPETITION',
        competition: {attackerWon: false, defenders: [defender, secondDefender]},
        dice: [{player: null, value: 6, color: 'WHITE'}, {player: null, value: 1, color: 'BLACK'},
            {player: null, value: 1, color: 'GREEN'}]};
    let events = [purchase, draw, attack, result];
    let revision = 50000;
    async function show(players = baseline.game.players, viewerId = baseline.viewerId) {
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify({...baseline, viewerId, revision: ++revision, events,
            game: {...baseline.game, players}})})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'Grouped activity render');
    }
    await show();
    await page.evaluate(`document.querySelector('#tab-activity').click()`);
    assert.equal(await page.evaluate(`document.querySelectorAll('.activity-list > li').length`), 2);
    assert.match(await page.evaluate(`document.querySelector('[data-event-id="100:0"]').textContent`),
        /Bought expansion card.*−\$5.*Drew a card.*Stone/s,
        'The purchase, payment and privately visible draw share their cause');
    assert.match(await page.evaluate(`document.querySelector('[data-activity-member="101:1"] .event-opponents').textContent`),
        /against.*Genoa.*Barcelona/s, 'Every defending side appears beside the competition');
    const recorded = await page.evaluate(`document.querySelector('[data-event-id="101:0"]').innerHTML`);
    events = [...events, {...event, id: '102:0', kind: 'UPGRADE_SHIPS'}];
    await show(baseline.game.players.map(player => ({...player, name: 'Changed', capital: 'paris'})));
    assert.equal(await page.evaluate(`document.querySelector('[data-event-id="101:0"]').innerHTML`), recorded,
        'Later actions, capitals and names cannot rewrite a grouped competition');
    events = [...events, {...attack, id: '103:0'}, {...result, id: '103:1',
        competition: {attackerWon: true, defenders: [defender]}}];
    for (const [viewer, outcomes] of [[actor, ['lost', 'won']], [defender, ['won', 'lost']],
        [secondDefender, ['won', null]], [null, [null, null]]]) {
        await show(baseline.game.players, viewer);
        for (const [index, id] of ['101:0', '103:0'].entries()) {
            const classes = await page.evaluate(`document.querySelector('[data-event-id="${id}"] > .activity-group').className`);
            assert.equal(classes, outcomes[index] ? `activity-group competition-activity ${outcomes[index]}` : 'activity-group',
                'Competition highlights follow the viewer as attacker, defender, uninvolved player or spectator');
        }
        assert.equal(await page.evaluate(`document.querySelector('[data-event-id="100:0"] .competition-activity')`), null,
            'An ordinary purchase stays neutral');
    }
    await show();
    const highlighted = await page.evaluate(`document.querySelector('[data-event-id="101:0"]').innerHTML`);
    await show();
    assert.equal(await page.evaluate(`document.querySelector('[data-event-id="101:0"]').innerHTML`), highlighted,
        'Polling preserves the recorded result and its highlight');
    for (const theme of ['light', 'dark']) {
        await page.evaluate(`document.documentElement.dataset.theme = '${theme}'`);
        const colors = await page.evaluate(`['won', 'lost'].map(outcome => {
            const group = document.querySelector('.competition-activity.' + outcome);
            const expected = getComputedStyle(document.documentElement)
                .getPropertyValue(outcome === 'won' ? '--result-success-fill' : '--result-loss-fill').trim();
            const probe = document.createElement('span');
            probe.style.backgroundColor = expected;
            group.append(probe);
            const matches = getComputedStyle(group).backgroundColor === getComputedStyle(probe).backgroundColor;
            probe.remove();
            return matches;
        })`);
        assert.deepEqual(colors, [true, true], `Wins use green and losses use red in the ${theme} theme`);
        await assertTextContrast(page, `${theme} competition activity`, '.activity-list');
        for (const [width, height] of [[1440, 980], [390, 844], [320, 740]]) {
            await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile: width < 700});
            await page.evaluate(`document.fonts.ready.then(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))`);
            assert(await page.evaluate(`document.querySelector('.activity-list').scrollWidth <= document.querySelector('.activity-list').clientWidth`),
                `Grouped receipts wrap within activity at ${width}px`);
            await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `activity-grouping-${theme}-${width}.png`));
        }
    }
    await page.evaluate(`document.documentElement.dataset.theme = 'light'`);
    assert.equal(await page.evaluate(`window.mapCommands.length`), 0, 'Reading groups never submits an action');
    console.log('Passed: purchase/draw grouping, multiple recorded defenders, stable history and responsive consequences.');
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1280, height: 900, deviceScaleFactor: 1, mobile: false});
    await checkTokenPurchaseRound(page, web);
} finally {
    page.ws.close();
    await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
}
