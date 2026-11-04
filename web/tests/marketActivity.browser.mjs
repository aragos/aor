import assert from 'node:assert/strict';
import {join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createGame, open} from './browser-support.mjs';

export async function checkMarketActivity(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'StrictMode market activity harness');
    const live = baseline.events.filter(event => event.marketRoll).slice(-2);
    assert.equal(live.length, 2, 'The live game recorded both market rolls');
    let revision = 37000;
    async function show(events, players = baseline.game.players) {
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify({...baseline,
            revision: ++revision, events, game: {...baseline.game, players}})})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'Market activity render');
    }
    const content = id => page.evaluate(`document.querySelector('[data-market-roll-id="${id}"]').innerHTML`);
    const rows = () => page.evaluate(`document.querySelectorAll('.activity-list > li').length`);
    await show([live[0]]);
    await page.evaluate(`document.querySelector('#tab-activity').click()`);
    assert.equal(await rows(), 1);
    const original = await content(live[0].id);
    await show(live);
    assert.equal(await rows(), 1, 'Two automatic market rolls share one activity entry');
    assert.equal(await content(live[0].id), original, 'Appending the second roll preserves the first recorded result');
    assert.equal(await page.evaluate(`document.querySelectorAll('.market-roll-round > span').length`), 1,
        'The combined activity has one heading');
    assert.equal(await page.evaluate(`document.querySelector('.market-roll-round > span').textContent`), 'Shortages and surpluses');
    assert.equal(await page.evaluate(`document.querySelectorAll('.market-roll-round .event-die').length`), 6,
        'Both colored dice triples remain visible');
    for (const event of live) {
        const colors = await page.evaluate(`[...document.querySelectorAll('[data-market-roll-id="${event.id}"] .event-die')].map(die => die.getAttribute('aria-label'))`);
        assert.deepEqual(colors, event.dice.map(die => `${die.color[0]}${die.color.slice(1).toLowerCase()} die ${die.value}`));
    }
    const actor = baseline.viewerId;
    const participant = baseline.game.players.find(player => player.id === actor);
    const recorded = {...live[1], id: 'recorded-surplus', participants: [{id: actor, name: participant.name, capital: 'venice'}],
        marketRoll: {total: 2, commodities: ['STONE'], adjustment: 'SURPLUS', cancelled: ['STONE']},
        cashChanges: [{player: actor, amount: -2}],
        miseryChanges: [{player: actor, spaces: 1, fromValue: 10, toValue: 20,
            reasons: [{kind: 'SURPLUS_DEBT', advance: null, card: null}]}]};
    await show([live[0], recorded]);
    const recordedHtml = await content(recorded.id);
    assert.match(await page.evaluate(`document.querySelector('[data-market-roll-id="${recorded.id}"]').textContent`),
        /Stone.*surplus.*Cancels.*Stone.*Venice.*−\$2.*\+1.*10 → 20/s,
        'Combining retains each roll meaning, cancellation, payment and misery endpoints');
    for (const [width, height, mobile] of [[1280, 900, false], [390, 844, true]]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        await page.evaluate(`document.fonts.ready.then(() => new Promise(resolve => requestAnimationFrame(resolve)))`);
        assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`), true,
            `Combined market activity fits at ${width}px`);
        assert.equal(await page.evaluate(`document.querySelector('.market-roll-round').scrollWidth <= document.querySelector('.activity-list').clientWidth`), true);
        if (process.env.AOR_BROWSER_ARTIFACTS) await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-market-activity-${width}.png`));
    }
    await show([live[0], recorded, {...live[0], id: 'next-turn-roll', turnNumber: live[0].turnNumber + 1}],
        baseline.game.players.map(player => ({...player, name: 'Later identity', capital: 'barcelona'})));
    assert.equal(await rows(), 2, 'The next turn keeps its own activity entry');
    assert.equal(await content(recorded.id), recordedHtml, 'Later identities and activity cannot rewrite the grouped receipt');
    await show([live[0], {...live[0], id: 'intervening-pass', kind: 'PASS', marketRoll: null}, live[1]]);
    assert.equal(await rows(), 2, 'Even a hidden pass separates distinct market entries');
    assert.equal(await page.evaluate(`window.mapCommands.length`), 0, 'Reading the combined activity submits no actions');
    console.log('Passed: combined market activity with live colored dice, stable recorded outcomes, cancellation, payments, misery and phone wrapping.');
}

export async function checkLiveMarketActivity(page, web) {
    const game = await createGame(3);
    for (const player of game.links) await game.send(player, 'bid-capital 0');
    let baseline;
    for (let step = 0; step < 100; step++) {
        const state = await game.snapshot(game.links[0]);
        if (state.events.filter(event => event.marketRoll).length >= 2) {baseline = state; break;}
        const actor = game.links.find(player => state.prompt?.players.includes(player.id));
        assert(actor, `Market setup has an actor at ${state.game.stage}`);
        const current = await game.snapshot(actor);
        const commands = current.legalChoices.commands;
        const command = current.game.stage === 'TOKEN_PURCHASE' ? 'buy-tokens 0'
            : commands.includes('pass') ? 'pass'
                : commands.includes('stabilize cash') ? 'stabilize cash' : commands[0];
        assert(command, `Market setup has a command at ${current.game.stage}`);
        await game.send(actor, command);
    }
    assert(baseline, 'The live game reaches its first market roll pair');
    await checkMarketActivity(page, web, baseline);
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1280, height: 900, deviceScaleFactor: 1, mobile: false});
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    const page = await open(process.env.MAP_WEB_URL);
    try {await checkLiveMarketActivity(page, process.env.MAP_WEB_URL);}
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
