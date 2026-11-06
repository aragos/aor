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
        assert.equal(await page.evaluate(`document.querySelector('.games-home').getAttribute('href')`), '/play');
        if (mobile) {
            assert.equal(await page.evaluate(`document.querySelector('#workspace-players').checkVisibility()`), false);
            assert.equal(await page.evaluate(`document.querySelector('.game-table > .table-tools')`), null);
            assert(await page.evaluate(`document.querySelector('.workspace-side').getBoundingClientRect().height < 160`), 'A short waiting pane shrinks to its content');
            const layout = await page.evaluate(`(() => {
                const tabs = [...document.querySelectorAll('.workspace-tabs [role=tab]')].map(tab => tab.getBoundingClientRect().toJSON());
                const map = document.querySelector('.workspace-main').getBoundingClientRect();
                const rail = document.querySelector('.workspace-bar').getBoundingClientRect();
                const side = document.querySelector('.workspace-side').getBoundingClientRect();
                const table = document.querySelector('.table-workspace').getBoundingClientRect();
                return {tabs, mapLeft: map.left, mapBottom: map.bottom, railBottom: rail.bottom,
                    sideLeft: side.left, sideRight: side.right, tableLeft: table.left, tableRight: table.right};
            })()`);
            assert.equal(layout.tabs.length, 5);
            assert(layout.tabs.every(tab => tab.right <= layout.mapLeft + 1 && tab.width >= 40 && tab.height >= 44), 'Touch-sized icons stay to the left of the workspace');
            assert(layout.tabs.every((tab, i) => i === 0 || tab.top >= layout.tabs[i - 1].bottom), 'Tabs form a vertical rail');
            assert(Math.abs(layout.railBottom - layout.mapBottom) < 1, 'The icon rail ends with the map workspace');
            assert(Math.abs(layout.sideLeft - layout.tableLeft) < 1 && Math.abs(layout.sideRight - layout.tableRight) < 1,
                'The action area spans the full table width below the rail');
            await click('#tab-players');
            assert.equal(await page.evaluate(`document.querySelector('#workspace-players').checkVisibility()`), true);
            assert.equal(await page.evaluate(`document.querySelectorAll('.player-overview').length`), 1);
            assert.equal(await page.evaluate(`document.querySelector('#workspace-players .table-configuration').checkVisibility()`), true);
            await page.send('Emulation.setTouchEmulationEnabled', {enabled: true});
            async function tapPlayer() {
                const point = await page.evaluate(`(() => {
                    const box = document.querySelector('.player-summary.is-viewer > summary .player-identity').getBoundingClientRect();
                    return {x: box.left + 24, y: box.top + box.height / 2};
                })()`);
                await page.send('Input.dispatchTouchEvent', {type: 'touchStart', touchPoints: [point]});
                await page.send('Input.dispatchTouchEvent', {type: 'touchEnd', touchPoints: []});
            }
            await tapPlayer();
            await page.wait(`document.querySelector('.player-summary.is-viewer .player-detail:popover-open')`, 'Tapping the player opens details');
            await tapPlayer();
            await page.wait(`!document.querySelector('.player-detail:popover-open') && !document.querySelector('.player-summary.is-viewer').open`,
                'Tapping the same player closes details');
            await tapPlayer();
            await page.wait(`document.querySelector('.player-summary.is-viewer .player-detail:popover-open')`, 'The player can reopen after closing');
            await page.send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
            await page.send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
            await page.wait(`!document.querySelector('.player-summary.is-viewer').open`, 'Escape synchronizes the player summary');
            await page.send('Emulation.setTouchEmulationEnabled', {enabled: false});
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
    try {
        await checkMobileTable(page, process.env.MAP_WEB_URL, await game.snapshot(game.links[0]));
        await game.send(game.links[0], 'bid-capital 7');
        await page.send('Emulation.setDeviceMetricsOverride', {width: 390, height: 844, deviceScaleFactor: 1, mobile: true});
        await page.send('Page.navigate', {url: process.env.MAP_WEB_URL + game.links[0].path});
        await page.wait(`document.querySelector('.withdraw-bid') && document.querySelector('.sheet-toggle')?.checkVisibility()`,
            'Live pending bid has Undo beside the action toggle');
        for (const label of ['Hide actions', 'Show actions']) {
            const buttons = await page.evaluate(`['.undo-action', '.sheet-toggle'].map(selector => {
                const button = document.querySelector('.decision-toolbar ' + selector);
                return {height: button.getBoundingClientRect().height, text: button.textContent.trim()};
            })`);
            assert.equal(buttons[0].height, buttons[1].height, 'Undo and the action toggle have the same height');
            assert.equal(buttons[1].text, label, 'The action toggle has only its text label');
            await page.evaluate(`document.querySelector('.sheet-toggle').click()`);
        }
    }
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
