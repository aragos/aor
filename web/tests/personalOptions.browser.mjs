import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open, waitForMap} from './browser-support.mjs';

const web = process.env.MAP_WEB_URL;
let game = await createGame(3);
let viewer = game.links[0];
const page = await open(web + viewer.path);
const click = selector => page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
const dark = '.personal-options label:nth-of-type(2) input';
const skip = '.personal-options label:nth-of-type(1) input';
async function visitSeat(link, tag) {
    const previous = await page.evaluate('performance.timeOrigin');
    const url = new URL(link.path, web);
    url.searchParams.set('personal-options-seat', tag);
    await page.send('Page.navigate', {url: url.href});
    await page.wait(`performance.timeOrigin !== ${previous} && document.querySelector('.game-table')`, 'fresh seat table');
}
try {
    await page.wait(`document.querySelector('#bid') && document.querySelector('.personal-options')`, 'live options and bid');
    await page.fill('#bid', '7');
    await click('.personal-options > summary');
    await click(dark);
    await page.wait(`document.documentElement.dataset.theme === 'dark'`, 'dark mode applies immediately');
    assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7', 'Options preserve the bid draft');
    assert.equal(await page.evaluate(`localStorage.getItem('aor.setting.dark-mode')`), 'true');
    await click(skip);
    const before = await game.snapshot(viewer);
    await page.reload();
    await page.wait(`document.querySelector('.personal-options') && document.documentElement.dataset.theme === 'dark'`, 'personal options survive reload');
    await click('.personal-options > summary');
    assert.equal(await page.evaluate(`document.querySelector('${skip}').checked`), true);
    assert.equal((await game.snapshot(viewer)).revision, before.revision, 'Preferences have no game effect without an opportunity');
    for (const [width, height, mobile] of [[1440, 980, false], [390, 844, true]]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        if (mobile) {
            await page.wait(`document.querySelector('#tab-players')`, 'phone player tools');
            await click('#tab-players');
        }
        await page.evaluate(`document.querySelector('${dark}').scrollIntoView({block: 'nearest'})`);
        if (mobile) assert(await page.evaluate(`(() => {
            const option = document.querySelector('${dark}').getBoundingClientRect();
            const panel = document.querySelector('#workspace-players').getBoundingClientRect();
            return option.top >= panel.top && option.bottom <= panel.bottom;
        })()`), 'Personal options are reachable inside the player workspace');
        assert(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth + 1`));
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `personal-options-${mobile ? 'phone' : 'desktop'}.png`));
    }
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 980, deviceScaleFactor: 1, mobile: false});
    // Normal play supplies the real offer. Retry a game if dice and accumulating misery end it
    // before a successful attack; never alter the authority's outcomes or invent an offer.
    let windmill;
    for (let attempt = 0; attempt < 8 && !windmill; attempt++) {
        if (attempt > 0) {game = await createGame(3); viewer = game.links[0];}
        await visitSeat(game.links[1], `other-${attempt}`);
        for (let seat = 0; seat < 3; seat++) await game.send(game.links[seat], `bid-capital ${seat === 0 ? 1 : 0}`);
        for (let step = 0; step < 450; step++) {
            const publicState = await game.snapshot(viewer);
            if (publicState.game.stage === 'FINISHED') break;
            const actor = game.links.find(link => publicState.prompt?.players.includes(link.id));
            assert(actor, `An actor exists at ${publicState.game.stage}`);
            const state = await game.snapshot(actor);
            const commands = state.legalChoices.commands;
            const me = state.game.players.find(player => player.id === actor.id);
            if (state.game.windmillOffer?.player === viewer.id) { windmill = state; break; }
            let command;
            if (state.game.stage === 'TOKEN_PURCHASE') {
                const pool = actor.id === viewer.id && me.advances.includes('WIND_WATERMILL')
                    ? Math.min(24, me.cash, me.stockTokens) : 0;
                command = `buy-tokens ${pool}`;
            } else if (commands.some(command => command.startsWith('choose-capital '))) {
                command = ['genoa', 'venice', 'barcelona'].map(capital => `choose-capital ${capital}`)
                    .find(command => commands.includes(command));
            } else if (actor.id === viewer.id && commands.includes('buy-advance I')) {
                command = 'buy-advance I';
            } else if (actor.id === viewer.id && commands.includes('buy-advance J')) {
                command = 'buy-advance J';
            } else if (actor.id === viewer.id && state.game.stage === 'EXPANSION' && me.advances.includes('WIND_WATERMILL')) {
                command = state.game.expansionOffers.filter(offer => offer.primaryAction === 'ATTACK' &&
                    offer.establishesMarker && offer.blockers.length === 0 &&
                    offer.availablePool - offer.requiredTokens >=
                        state.game.catalog.provinces.find(province => province.id === offer.destination).marketNumber)
                    .map(offer => `attack ${offer.destination}`).find(command => commands.includes(command));
            }
            command ??= commands.find(command => command.startsWith('choose-turn-position '))
                ?? commands.find(command => command === 'stabilize misery')
                ?? commands.find(command => command === 'pass');
            assert(command, `Find normal play command at ${state.game.stage}: ${commands}`);
            await game.send(actor, command);
        }
    }
    assert(windmill, 'Normal play reaches a real Wind/Watermill opportunity');
    assert.equal((await game.snapshot(viewer)).revision, windmill.revision,
        'Viewing another player does not auto-decline the owner’s opportunity');
    await visitSeat(viewer, 'owner');
    await page.wait(`document.querySelector('.command-dock summary small')?.textContent.endsWith('${windmill.revision + 1}')`, 'standing preference declines live opportunity');
    const declined = await game.snapshot(viewer);
    assert.equal(declined.revision, windmill.revision + 1);
    assert.equal(declined.game.windmillOffer, null);
    assert.equal(declined.history.at(-1), `${viewer.id}: pass`);
    assert.equal(declined.game.players.find(player => player.id === viewer.id).poolTokens,
        windmill.game.players.find(player => player.id === viewer.id).poolTokens, 'Declining spends no tokens');
    await waitForMap(page, declined);
    await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'personal-options-dark-table.png'));
    await click('[data-help-topic="misery-track"]');
    await page.wait(`document.querySelector('.reference-dialog #misery-track')`, 'dark reference dialog');
    await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'personal-options-dark-reference.png'));
    await click('.reference-dialog .close-detail');

    await page.send('Page.navigate', {url: web + '/tests/ui-harness.html'});
    await page.wait(`typeof window.setUiSnapshot === 'function'`, 'StrictMode lifecycle harness');
    await page.evaluate(`window.setUiSnapshot(${JSON.stringify(windmill)})`);
    await page.wait(`window.uiCommands.length === 1 && window.uiRequests >= 4`, 'StrictMode auto-decline and repeated polling');
    assert.deepEqual(await page.evaluate(`window.uiCommands.map(entry => entry.command)`), ['pass'],
        'StrictMode replay and unchanged polling submit only once');
    const rejected = {...windmill, revision: windmill.revision + 10,
        game: {...windmill.game, activity: {...windmill.game.activity,
            decisionEntry: windmill.game.activity.decisionEntry + 10}}};
    await page.evaluate(`window.uiNextError = 'INSUFFICIENT_CASH'; window.setUiSnapshot(${JSON.stringify(rejected)})`);
    await page.wait(`window.uiCommands.length === 2 && document.querySelector('[role=alert]')`, 'rejected automatic decline is visible');
    const reads = await page.evaluate('window.uiRequests');
    await page.wait(`window.uiRequests >= ${reads + 2}`, 'polling after rejection');
    assert.equal(await page.evaluate('window.uiCommands.length'), 2, 'Rejected or uncertain decisions are never silently retried');
    await click('.personal-options > summary');
    await page.evaluate(`Storage.prototype.setItem = function() {throw new Error('Storage quota exceeded');};`);
    await click(dark);
    await page.wait(`document.documentElement.dataset.theme === 'light'`, 'quota failure uses updated memory rather than the older stored value');
    assert.equal(await page.evaluate(`localStorage.getItem('aor.setting.dark-mode')`), 'true', 'The old disk value remains readable');
    await click(dark);
    await page.wait(`document.documentElement.dataset.theme === 'dark'`, 'theme remains editable with quota failures');
    await page.evaluate(`Storage.prototype.getItem = function() {throw new Error('Storage unavailable');};`);
    await click(dark);
    await page.wait(`document.documentElement.dataset.theme === 'light'`, 'preferences remain usable without storage');
    await click(dark);
    await page.wait(`document.documentElement.dataset.theme === 'dark'`, 'shared in-memory theme updates');
    assert.equal(await page.evaluate(`document.querySelector('${dark}').checked`), true);
    console.log('Personal options passed: persistent browser preferences, dark desktop/phone/dialogs, draft preservation, live Wind/Watermill decline, StrictMode once-only behavior and storage fallback.');
} finally {
    page.ws.close();
    await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
}
