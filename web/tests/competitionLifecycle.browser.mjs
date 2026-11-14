import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open, waitForMap} from './browser-support.mjs';

export async function checkLiveCompetitionLifecycle(page, web) {
    const game = await createGame(3);
    for (const player of game.links) await game.send(player, 'bid-capital 0');
    const capitals = ['genoa', 'venice', 'barcelona'];
    let state;
    for (let step = 0; step < 100; step++) {
        state = await game.snapshot(game.links[0]);
        if (state.game.stage === 'EXPANSION') break;
        const actor = game.links.find(player => state.prompt?.players.includes(player.id));
        assert(actor, `Competition setup has an actor at ${state.game.stage}`);
        const current = await game.snapshot(actor);
        const commands = current.legalChoices.commands;
        const command = current.game.stage === 'TOKEN_PURCHASE' ? `buy-tokens ${18 + game.links.indexOf(actor)}`
            : capitals.map(capital => `choose-capital ${capital}`).find(command => commands.includes(command))
                ?? commands.find(command => command.startsWith('choose-turn-position '))
                ?? commands.find(command => command === 'pass')
                ?? commands.find(command => command === 'stabilize cash')
                ?? commands.find(command => command === 'stabilize misery');
        assert(command, `Competition setup has a command at ${current.game.stage}`);
        await game.send(actor, command);
    }
    assert.equal(state.game.stage, 'EXPANSION');
    let attacker;
    let target;
    for (let step = 0; step < game.links.length; step++) {
        const actor = game.links.find(player => state.prompt?.players.includes(player.id));
        assert(actor, 'Expansion has an actor');
        const current = await game.snapshot(actor);
        target = current.game.expansionOffers.find(offer => offer.primaryAction === 'ATTACK' &&
            !offer.blockers.length && current.legalChoices.commands.includes(`attack ${offer.destination}`));
        if (target) { attacker = actor; state = current; break; }
        state = await game.send(actor, 'pass');
    }
    assert(attacker && target, 'Adjacent real capitals offer a legal first-round competition');
    const turn = state.game.turnNumber;
    await page.send('Page.navigate', {url: web + attacker.path});
    await page.wait(`document.querySelector('.pool-summary')`, 'live competition actor');
    await waitForMap(page, state);
    await page.evaluate(`document.querySelector('[data-map-province="${target.destination}"]').dispatchEvent(new KeyboardEvent('keydown', {key:'Enter',bubbles:true}))`);
    await page.wait(`document.querySelector('.primary-expansion:not([disabled])')`, 'live authoritative attack');
    const greenPreview = await page.evaluate(`document.querySelector('.competition-green-die').textContent`);
    const faces = target.greenDieWins;
    assert(Array.isArray(faces), 'Live competition offers project the actual green-die results');
    assert.match(greenPreview, faces.length ? /Green die wins on/ : /Green die cannot win/);
    if (faces.length) assert(greenPreview.includes(faces.length === 1 ? String(faces[0]) : `${faces[0]}–${faces.at(-1)}`));
    await page.evaluate(`document.querySelector('.competition-green-die .help-link').focus(); document.querySelector('.competition-green-die .help-link').click()`);
    await page.wait(`document.querySelector('#turn-order .order-track')`, 'competition explains printed boxes');
    await page.evaluate(`document.querySelector('.reference-dialog .close-detail').click()`);
    assert.equal(await page.evaluate(`document.activeElement.matches('.competition-green-die .help-link')`), true);
    assert.equal(await page.evaluate(`document.querySelector('.primary-expansion').disabled`), false, 'Reference preserves the live attack draft');
    await page.evaluate(`document.querySelector('.primary-expansion').click()`);
    await page.wait(`document.querySelector('.competition-notice')`, 'live competition result');
    const resolved = await game.snapshot(attacker);
    const outcome = resolved.events.findLast(event => event.competition);
    assert(outcome, 'The resolved competition remains in activity');
    for (const defender of outcome.competition.defenders) {
        const identity = outcome.participants.find(player => player.id === defender);
        assert(identity, 'Live outcomes record each opposing identity');
        const name = identity.capital ? identity.capital[0].toUpperCase() + identity.capital.slice(1) : identity.name;
        assert((await page.evaluate(`document.querySelector('.competition-notice .event-opponents').textContent`)).includes(name),
            'The submitting player sees the opposing side in the result notice');
    }
    await page.evaluate(`document.querySelector('#tab-activity').click()`);
    const action = resolved.events.findLast(event => event.kind === 'ATTACK');
    assert.equal(await page.evaluate(`document.querySelector('[data-activity-member="${outcome.id}"]').closest('li').dataset.eventId`), action.id,
        'A live competition action and its recorded resolution share one activity row');
    assert.equal(await page.evaluate(`document.querySelector('[data-event-id="${action.id}"] > .activity-group').className`),
        `activity-group competition-activity ${outcome.competition.attackerWon ? 'won' : 'lost'}`,
        'A live competition highlights the complete activity group from the submitting player’s perspective');
    for (const [width, height] of [[1280, 900], [390, 844]]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile: width < 700});
        await page.evaluate(`document.fonts.ready.then(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))`);
        assert(await page.evaluate(`document.querySelector('.activity-list').scrollWidth <= document.querySelector('.activity-list').clientWidth`),
            'Grouped competition facts fit the activity workspace');
        const notice = await page.evaluate(`(() => {
            const notice = document.querySelector('.competition-notice');
            const content = notice.querySelector('.activity-content');
            const province = content.querySelector('.event-province');
            const commodities = [...province.parentElement.querySelectorAll('.commodity-name')];
            return {
                summaries: content.querySelectorAll('.event-summary').length,
                resolutions: content.querySelectorAll('.event-resolution').length,
                resolution: content.querySelector('.event-resolution').textContent,
                dice: content.querySelector('.event-resolution').querySelectorAll('.event-die').length,
                province: province.dataset.activityProvince,
                commodities: commodities.map(node => node.textContent.toUpperCase()),
                commoditiesOutsideBox: !province.querySelector('.commodity-name'),
                textSizedIcons: commodities.every(node => Math.abs(node.querySelector('svg').getBoundingClientRect().height -
                    parseFloat(getComputedStyle(node).fontSize)) < 1),
                fits: notice.scrollWidth <= notice.clientWidth, width: notice.clientWidth, scrollWidth: notice.scrollWidth,
                dismissReachable: notice.querySelector('.close-detail').checkVisibility(),
            };
        })()`);
        assert.equal(notice.summaries, 1, 'The action-pane receipt has one cause summary');
        assert.equal(notice.resolutions, 1, 'The action-pane receipt groups outcome and dice below its cause');
        assert.match(notice.resolution, outcome.competition.attackerWon ? /Competition won/ : /Competition lost/);
        assert.equal(notice.dice, outcome.dice.length, 'The action-pane receipt preserves all recorded dice');
        assert.equal(notice.province, target.destination);
        assert.deepEqual(notice.commodities, resolved.game.catalog.provinces.find(province => province.id === target.destination).commodities);
        assert(notice.commoditiesOutsideBox && notice.textSizedIcons,
            'The action-pane receipt also puts text-sized commodities outside the province box');
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `competition-pane-${width}.png`));
        assert(notice.fits && notice.dismissReachable, `The action-pane result wraps and remains dismissible at ${width}px: ${JSON.stringify(notice)}`);
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `competition-activity-${width}.png`));
    }
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1280, height: 900, deviceScaleFactor: 1, mobile: false});
    await page.evaluate(`document.querySelector('#tab-map').click()`);
    state = resolved;
    for (let step = 0; step < game.links.length; step++) {
        if (state.game.stage !== 'EXPANSION') break;
        const actor = game.links.find(player => state.prompt?.players.includes(player.id));
        assert(actor, 'Remaining expansion has an actor');
        state = await game.send(actor, 'pass');
    }
    assert.notEqual(state.game.stage, 'EXPANSION', 'All players finished expansion');
    await page.wait(`!document.querySelector('.header-stage strong')?.textContent.includes('Expansion')`, 'live expansion phase completed');
    assert.equal(await page.evaluate(`document.querySelector('.competition-notice')`), null, 'An expansion-ending server poll clears the popup');
    assert.equal((await game.snapshot(attacker)).events.some(event => event.id === outcome.id), true, 'The activity outcome survives popup expiry');

    for (let step = 0; step < 100; step++) {
        state = await game.snapshot(attacker);
        if (state.game.stage === 'EXPANSION') break;
        const actor = game.links.find(player => state.prompt?.players.includes(player.id));
        assert(actor, `Next round has an actor at ${state.game.stage}`);
        const current = await game.snapshot(actor);
        const commands = current.legalChoices.commands;
        const command = current.game.stage === 'TOKEN_PURCHASE' ? `buy-tokens ${1 + game.links.indexOf(actor)}`
            : commands.find(command => command === 'pass')
                ?? commands.find(command => command === 'stabilize cash')
                ?? commands.find(command => command === 'stabilize misery');
        assert(command, `Next round has a command at ${current.game.stage}`);
        await game.send(actor, command);
    }
    assert.equal(state.game.stage, 'EXPANSION');
    assert(state.game.turnNumber > turn, 'A new expansion belongs to a later turn');
    await page.wait(`document.querySelector('.header-stage strong')?.textContent.includes('Expansion')`, 'next live expansion');
    assert.equal(await page.evaluate(`document.querySelector('.competition-notice')`), null, 'The next expansion cannot restore a completed receipt');
    console.log('Passed: live competition receipt expires at expansion end, retains activity, and stays cleared in the next expansion.');
}

if (process.argv[1]?.endsWith('/competitionLifecycle.browser.mjs')) {
    const page = await open('about:blank');
    try { await checkLiveCompetitionLifecycle(page, process.env.MAP_WEB_URL); }
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
