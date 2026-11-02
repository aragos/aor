import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';

export async function checkMobileTable(page, web, baseline) {
    const other = baseline.game.players.find(player => player.id !== baseline.viewerId).id;
    let revision = 65000;
    async function show(changes = {}) {
        const fixture = {...baseline, prompt: {messageId: 'prompt.play-cards', players: [other]},
            legalChoices: {commands: [], bidMin: null, bidMax: null}, ...changes,
            revision: ++revision, game: {...baseline.game, stage: 'PLAY_CARDS', hand: [],
                cardOffers: [], expansionOffers: [], ...changes.game}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'mobile table render');
    }
    const click = selector => page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
    for (const [width, height, mobile] of [[390, 844, true], [430, 932, true], [1280, 900, false]]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
        await page.wait(`typeof window.setMapSnapshot === 'function'`, 'mobile table harness');
        await show();
        assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
        assert.equal(await page.evaluate(`document.querySelector('.game-header .waiting-status')`), null);
        assert.equal(await page.evaluate(`document.querySelector('.player-overview .player-status')`), null);
        assert.match(await page.evaluate(`document.querySelector('.decision-status').textContent`), /Waiting for/);
        assert.equal(await page.evaluate(`document.querySelector('.decision-status .identity-label') !== null`), true);
        assert.equal(await page.evaluate(`document.querySelector('.games-home').getAttribute('href')`), '/');
        if (mobile) {
            assert.equal(await page.evaluate(`document.querySelector('#workspace-players').checkVisibility()`), false);
            assert.equal(await page.evaluate(`document.querySelector('.game-table > .table-tools')`), null);
            assert(await page.evaluate(`document.querySelector('.workspace-side').getBoundingClientRect().height < 160`), 'A short waiting pane shrinks to its content');
            const layout = await page.evaluate(`(() => {
                const tabs = [...document.querySelectorAll('.workspace-tabs [role=tab]')].map(tab => tab.getBoundingClientRect().toJSON());
                const map = document.querySelector('.workspace-main').getBoundingClientRect();
                return {tabs, mapLeft: map.left};
            })()`);
            assert.equal(layout.tabs.length, 5);
            assert(layout.tabs.every(tab => tab.right <= layout.mapLeft + 1 && tab.width >= 40 && tab.height >= 44), 'Touch-sized icons stay to the left of the workspace');
            assert(layout.tabs.every((tab, i) => i === 0 || tab.top >= layout.tabs[i - 1].bottom), 'Tabs form a vertical rail');
            await click('#tab-players');
            assert.equal(await page.evaluate(`document.querySelector('#workspace-players').checkVisibility()`), true);
            assert.equal(await page.evaluate(`document.querySelectorAll('.player-overview').length`), 1);
            assert.equal(await page.evaluate(`document.querySelector('#workspace-players .table-configuration').checkVisibility()`), true);
            await click('.player-summary.is-viewer [data-help-topic="misery-track"]');
            await page.wait(`document.querySelector('.reference-dialog #misery-track .reference-track')`, 'Player misery help opens');
            await click('.reference-popup > .reference-link');
            await page.evaluate(`[...document.querySelectorAll('.reference-popup nav button')].find(button => button.textContent === 'Income track').click()`);
            await page.wait(`document.querySelector('.reference-dialog #income-track .payout-box')`, 'Income track opens through another help topic');
            assert.deepEqual(await page.evaluate(`[...document.querySelectorAll('#income-track .payout-box strong')].map(box => box.textContent)`), baseline.game.catalog.incomeTrack.map(amount => '$' + amount), 'The topic chooser retains the active game’s starting-player income track');
            await click('.reference-dialog .close-detail');
            await page.wait(`!document.querySelector('.reference-dialog')`, 'Income reference closes before navigation');
            await page.evaluate(`document.querySelector('#tab-players').dispatchEvent(new KeyboardEvent('keydown', {key: 'ArrowUp', bubbles: true}))`);
            assert.equal(await page.evaluate(`document.activeElement.id`), 'tab-activity', 'Vertical arrow keys navigate the icon rail');
            await click('#tab-players');
            await click('.command-dock > summary');
            await page.fill('#command', 'pass');
            await page.evaluate(`window.preResizeCommand = document.querySelector('#command'); true`);
            await page.send('Emulation.setDeviceMetricsOverride', {width: 1280, height: 900, deviceScaleFactor: 1, mobile: false});
            await page.wait(`!document.querySelector('#tab-players')`, 'Desktop tool placement');
            assert.equal(await page.evaluate(`document.querySelector('#command') === window.preResizeCommand && document.querySelector('#command').value === 'pass'`), true, 'Resizing preserves the mounted tool and its typed draft');
            await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
            await page.wait(`document.querySelector('#tab-players')`, 'Phone tools return');
            await click('#tab-players');
            assert.equal(await page.evaluate(`document.querySelector('#command').value`), 'pass', 'Returning to phone preserves the command draft');
            await click('.command-dock > summary');
        } else {
            assert.equal(await page.evaluate(`document.querySelector('.player-overview').checkVisibility()`), true);
            assert.equal(await page.evaluate(`document.querySelector('#tab-players')`), null);
        }
        await show({game: {effects: {...baseline.game.effects, papalCategory: 'SCIENCE'}}});
        await click('#tab-advances');
        assert.equal(await page.evaluate(`document.querySelector('.papal-decree-banner').checkVisibility()`), true);
        assert.match(await page.evaluate(`document.querySelector('.papal-decree-banner').textContent`), /Papal Decree.*Science/);
        await click('#tab-map');
        if (process.env.AOR_BROWSER_ARTIFACTS) await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-mobile-table-${width}.png`));
    }
    console.log('Passed: mobile icon rail, Players workspace, compact waiting pane, single waiting location, relocated tools, Papal Decree banner and desktop player strip.');
}

if (process.argv[1]?.endsWith('/mobileTable.browser.mjs')) {
    const game = await createGame(3);
    const page = await open('about:blank');
    try { await checkMobileTable(page, process.env.MAP_WEB_URL, await game.snapshot(game.links[0])); }
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
