import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';

export async function checkHelpTracks(page, web) {
    // Background tabs defer media-query events, so foreground the responsive interaction fixture.
    await page.send('Page.bringToFront');
    const game = await createGame(6);
    const baseline = await game.snapshot(game.links[0]);
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'StrictMode help harness');
    const capitals = ['venice', 'genoa', 'barcelona', 'paris', 'london', 'hamburg'];
    const players = baseline.game.players.map((player, index) => ({...player, capital: capitals[index],
        boardMarkers: index < 3 ? 3 : index + 1, misery: index < 3 ? 0 : baseline.game.catalog.miseryTrack.at(-1),
        ships: index < 3 ? {fleet: 'GALLEY', level: 2} : {fleet: 'NONE', level: 0}}));
    const fixture = {...baseline, revision: 82000, game: {...baseline.game, players,
        commodityPayouts: [{commodity: 'STONE', adjustments: [], owners: players.slice(0, 3).map(player => ({
            player: player.id, provinces: [], marketCount: 2, effectiveBox: 2, payout: 4, industryBonus: 0,
        }))}]}};
    await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
    await page.wait(`document.querySelector('[data-test-revision="82000"]')`, 'help snapshot');
    await page.fill('#bid', '7');
    const ids = players.map(player => player.id).sort();
    const click = async selector => {
        await page.wait(`document.querySelector(${JSON.stringify(selector)})`, selector);
        await page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
    };
    const occupants = selector => page.evaluate(`[...document.querySelectorAll(${JSON.stringify(selector + ' [data-track-player]')})].map(marker => marker.dataset.trackPlayer).sort()`);
    const close = async () => {
        await page.send('Input.dispatchKeyEvent', {type:'keyDown', key:'Escape', code:'Escape', windowsVirtualKeyCode:27});
        await page.send('Input.dispatchKeyEvent', {type:'keyUp', key:'Escape', code:'Escape', windowsVirtualKeyCode:27});
        await page.wait(`!document.querySelector('.reference-dialog')`, 'help dismissal');
        assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7', 'Inspection and dismissal preserve the bid draft');
    };
    for (const width of [1440, 390]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height: 1000, deviceScaleFactor: 1, mobile: width < 600});
        if (width < 600) await click('#tab-players');
        for (const topic of ['income-track', 'misery-track', 'shipping-track']) {
            await click(`[data-help-topic="${topic}"]`);
            await page.wait(`document.querySelector('#${topic} [data-track-player]')`, topic);
            assert.deepEqual(await occupants(`#${topic}`), ids, 'Every player appears once, including shared spaces');
            assert.equal(await page.evaluate(`document.querySelectorAll('#${topic} .reference-track-player.is-viewer').length`), 1);
            assert.equal(await page.evaluate(`document.querySelectorAll('#${topic} [data-track-player] svg').length`), players.length, 'Capital identities retain symbols');
            assert.equal(await page.evaluate(`document.querySelector('.reference-dialog').scrollWidth <= document.querySelector('.reference-dialog').clientWidth`), true, 'Track fits the popup');
            if (topic === 'income-track') assert.deepEqual(await occupants('#income-track [data-track-box="3"]'), players.slice(0, 3).map(player => player.id).sort());
            if (topic === 'misery-track') assert.deepEqual(await occupants('#misery-track [data-track-box="chaos"]'), players.slice(3).map(player => player.id).sort());
            if (topic === 'shipping-track') assert.deepEqual(await occupants('#shipping-track [data-track-fleet="NONE"]'), players.slice(3).map(player => player.id).sort());
            await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `help-${topic}-${width}.png`));
            await close();
        }
        await click('#tab-markets');
        await click('[data-help-topic="commodity-stone"]');
        await page.wait(`document.querySelector('#commodity-stone .commodity-card-counts')`, 'epoch card quantities');
        assert.equal(await page.evaluate(`document.querySelector('.commodity-reference-choices')`), null, 'A single-commodity lookup needs no chooser');
        assert.deepEqual(await page.evaluate(`[...document.querySelectorAll('.commodity-card-counts dd')].map(value => Number(value.textContent))`), [2, 0, 0]);
        assert.match(await page.evaluate(`document.querySelector('.commodity-circulation').textContent`), /Unplayed in circulation: 2/);
        assert.deepEqual(await occupants('#commodity-stone'), ids);
        assert.deepEqual(await occupants('#commodity-stone [data-track-box="0"]'), players.slice(3).map(player => player.id).sort());
        assert.deepEqual(await occupants('#commodity-stone [data-track-box="2"]'), players.slice(0, 3).map(player => player.id).sort());
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `help-commodity-${width}.png`));
        await close();
    }
    await click('#tab-players');
    await click('[data-help-topic="income-track"]');
    await page.wait(`document.querySelector('#income-track [data-track-player]')`, 'open track before polling');
    const updated = {...fixture, revision: 82001, game: {...fixture.game, players: players.map((player, index) =>
        index === 0 ? {...player, boardMarkers: 7} : player)}};
    await page.evaluate(`window.setMapSnapshot(${JSON.stringify(updated)})`);
    await page.wait(`document.querySelector('#income-track [data-track-box="7"] [data-track-player="${players[0].id}"]')`, 'open track follows accepted polling');
    await close();
    await click('#tab-markets');
    await click('[data-help-topic="commodity-stone"]');
    await page.wait(`document.querySelector('.commodity-circulation')`, 'circulation before polling');
    const played = {...updated, revision: 82002, game: {...updated.game,
        commodityCards: baseline.game.commodityCards.map(count => count.commodity === 'STONE' ? {...count, unplayed: 1} : count),
        commodityPayouts: [{...fixture.game.commodityPayouts[0], owners: fixture.game.commodityPayouts[0].owners.map((owner, index) =>
            index === 0 ? {...owner, effectiveBox: 12} : owner)}]}};
    await page.evaluate(`window.setMapSnapshot(${JSON.stringify(played)})`);
    await page.wait(`document.querySelector('#commodity-stone [data-track-box="12"] [data-track-player="${players[0].id}"]')`, 'payout positions beyond ten stay visible');
    assert.match(await page.evaluate(`document.querySelector('.commodity-circulation').textContent`), /Unplayed in circulation: 1/);
    await close();
    assert.equal(await page.evaluate('window.mapCommands.length'), 0, 'Help never submits an action');
    await checkSharedCardHelp(page, web);
    console.log('Passed: six-player track positions, stacked markers, Chaos, no ships, commodity epoch and circulation counts, live lookup updates and desktop/phone fit.');
}

async function checkSharedCardHelp(page, web) {
    let sample;
    for (let attempt = 0; attempt < 12 && !sample; attempt++) {
        const game = await createGame(6, {options: {'initial-card': 'commodity-only'}});
        for (const link of game.links) {
            const snapshot = await game.snapshot(link);
            const card = snapshot.game.hand.find(card => snapshot.game.cardInspections.some(info =>
                info.definition.id === card.definition.id && info.commodities.length > 1));
            if (card) {sample = {game, link, snapshot, card}; break;}
        }
    }
    assert(sample, 'A real opening deal contains a shared commodity card');
    const {game, link, snapshot, card} = sample;
    const commodities = snapshot.game.cardInspections.find(info => info.definition.id === card.definition.id).commodities;
    await page.send('Page.navigate', {url: web + link.path});
    await page.wait(`location.pathname === ${JSON.stringify(new URL(link.path, web).pathname)} && document.querySelector('#bid') && document.querySelector('[data-hand-card="${card.reference}"]')`, 'Live shared commodity card');
    await page.fill('#bid', '7');
    const help = `[data-hand-card="${card.reference}"] + .card-track-links .help-link`;
    assert.equal(await page.evaluate(`document.querySelectorAll(${JSON.stringify(help)}).length`), 1);
    for (const width of [1280, 390]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height: 900, deviceScaleFactor: 1, mobile: width < 700});
        await page.wait(`matchMedia('(max-width: 700px)').matches === ${width < 700}`, 'Live card viewport');
        if (width < 700) {
            await page.wait(`document.querySelector('.hand-toggle')`, 'Phone hand can expand');
            await page.evaluate(`if (!document.querySelector('.table-hand').classList.contains('hand-expanded')) document.querySelector('.hand-toggle').click()`);
        }
        await page.wait(`document.querySelector(${JSON.stringify(help)}).checkVisibility()`, 'The single card help is visible');
        await page.evaluate(`document.querySelector(${JSON.stringify(help)}).focus(); document.activeElement.click()`);
        await page.wait(`document.querySelector('.commodity-reference-choices')`, 'Live card commodity chooser');
        assert.equal(await page.evaluate(`document.querySelectorAll('.commodity-reference-choices button').length`), commodities.length);
        for (const [index, commodity] of commodities.entries()) {
            await page.evaluate(`document.querySelectorAll('.commodity-reference-choices button')[${index}].click()`);
            await page.wait(`document.querySelector('#commodity-${commodity.toLowerCase()} .payout-box')`, 'Selected live commodity payout boxes');
            const printed = snapshot.game.catalog.commodities.find(entry => entry.id === commodity).printedPayouts;
            assert.deepEqual(await page.evaluate(`[...document.querySelectorAll('.reference-dialog .payout-box strong')].map(box => box.textContent)`),
                printed.map(amount => '$' + amount), 'Switching shows the selected commodity’s prices');
        }
        assert.equal(await page.evaluate(`document.querySelector('.reference-dialog').scrollWidth <= document.querySelector('.reference-dialog').clientWidth`), true);
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `shared-commodity-live-${width}.png`));
        await page.send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
        await page.send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
        await page.wait(`!document.querySelector('.reference-dialog')`, 'Live shared lookup closes');
        assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7', 'The lookup preserves the live bid draft');
        assert.equal(await page.evaluate(`document.activeElement.matches(${JSON.stringify(help)})`), true, 'Focus returns to the single help control');
    }
    const after = await game.snapshot(link);
    assert.equal(after.pendingBid, null, 'Help does not submit the pending bid');
    assert.deepEqual(after.history, snapshot.history, 'Help does not play a card or change gameplay');
    console.log('Passed: one help control on a live shared card, both commodity payouts, desktop/phone fit and bid/focus preservation.');
}

if (process.argv[1]?.endsWith('/helpTracks.browser.mjs')) {
    const page = await open('about:blank');
    // Exercise the same hidden-tab entry state left by other live checks in the complete suite.
    const otherTab = await open('about:blank');
    try { await checkHelpTracks(page, process.env.MAP_WEB_URL); }
    finally {
        for (const target of [page, otherTab]) {
            target.ws.close();
            await fetch(`${process.env.MAP_CDP_URL}/json/close/${target.target.id}`);
        }
    }
}
