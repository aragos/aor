import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open, waitForMap} from './browser-support.mjs';
import {checkActionButtons} from './actionButtons.browser.mjs';
import {checkMobileTable} from './mobileTable.browser.mjs';
import {checkReferenceUx} from './referenceUx.browser.mjs';
import {checkTable} from './table.browser.mjs';
import {checkBottomHand} from './bottomHand.browser.mjs';
import {checkTableConsistency} from './tableConsistency.browser.mjs';

export async function checkExpansionLayout(page, web, baseline) {
    const viewer = baseline.viewerId;
    const placement = baseline.game.expansionOffers.find(offer => offer.primaryAction === 'PLACE' &&
        offer.establishesMarker && offer.blockers.length === 0);
    assert(placement, 'The live game offers a full marker placement');
    let revision = 68000;
    const players = baseline.game.players.map((player, index) => ({...player,
        poolAvailable: player.id === viewer,
        name: index === 1 ? 'A long player handle that must not enlarge one box' : player.name}));
    async function show(changes = {}) {
        const fixture = {...baseline, submitted: [players[1].id], ...changes, revision: ++revision,
            game: {...baseline.game, players, ...changes.game}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'Expansion layout fixture');
        await waitForMap(page, fixture);
        await page.evaluate('document.fonts.ready.then(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))');
    }
    const click = selector => page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
    for (const [width, height, mobile] of [[1440, 1000, false], [900, 900, false], [390, 844, true], [320, 640, true]]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
        await page.wait(`typeof window.setMapSnapshot === 'function'`, 'Expansion layout harness');
        await show();
        if (mobile) await click('#tab-players');
        const playerHeights = () => page.evaluate(`(() => {
            const boxes = [...document.querySelectorAll('.player-summary')];
            return boxes.map(box => ({height: box.getBoundingClientRect().height,
                needed: box.querySelector('summary').getBoundingClientRect().height +
                    parseFloat(getComputedStyle(box).borderTopWidth) + parseFloat(getComputedStyle(box).borderBottomWidth)}));
        })()`);
        const assertEqualHeights = async () => {
            const boxes = await playerHeights();
            assert(Math.max(...boxes.map(box => box.height)) - Math.min(...boxes.map(box => box.height)) < 1,
                `Player boxes share one height at ${width}px: ${JSON.stringify(boxes)}`);
            assert(Math.abs(boxes[0].height - Math.max(...boxes.map(box => box.needed))) < 1,
                'Player boxes only reserve the height needed by the tallest summary');
            return boxes[0].height;
        };
        const before = await assertEqualHeights();
        await click('.player-summary.is-viewer > summary');
        await page.wait(`document.querySelector('.player-detail:popover-open')`, 'Player detail floats');
        assert.equal(await assertEqualHeights(), before, 'Opening player details does not grow any box');
        await click('.player-summary.is-viewer > summary');
        await show({submitted: [], game: {players: players.map(player => ({...player, poolAvailable: false}))}});
        assert(await assertEqualHeights() < before, 'Boxes shrink together when the extra status/pool row disappears');
        if (mobile) {
            await click('.player-summary.is-viewer [data-help-topic="income-track"]');
            await page.wait(`document.querySelector('#income-track .payout-box')`, 'Income question mark opens the mapping');
            assert.equal(await page.evaluate(`document.querySelectorAll('#income-track .payout-box').length`), 26);
            assert.match(await page.evaluate(`document.querySelector('#income-track').textContent`),
                new RegExp(`3 markers provide \\$${baseline.game.catalog.incomeTrack[3]} base income`));
            await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-income-help-${width}.png`));
            await click('.reference-dialog .close-detail');
        }
        await show({undo: {...baseline.undo, available: true}, game: {
            effects: {...baseline.game.effects, military: players.map(player => ({player: player.id, event: 'ARMOR'}))},
        }});
        await click('#tab-map');
        await page.evaluate(`document.querySelector('[data-map-province="${placement.destination}"]').dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true}))`);
        await page.wait(`document.querySelector('.primary-expansion')`, 'Expansion review uses the live offer');
        const bounds = () => page.evaluate(`(() => {
            const rect = selector => document.querySelector(selector).getBoundingClientRect().toJSON();
            return {status: rect('.decision-status'), pool: rect('.pool-summary'),
                review: rect('.decision-scroll'), footer: rect('.decision-actions'), toolbar: rect('.decision-toolbar'),
                map: rect('.workspace-main'), hand: rect('.table-hand'),
                scrollHeight: document.querySelector('.decision-scroll').scrollHeight};
        })()`);
        const initial = await bounds();
        assert(Math.abs(initial.status.left - initial.pool.left) < 1 && Math.abs(initial.status.width - initial.pool.width) < 1,
            `Your move matches the pool summary width at ${width}px`);
        if (mobile) {
            assert(initial.review.height >= 150, `Phone review has readable space: ${JSON.stringify(initial)}`);
            const heights = await page.evaluate(`[...document.querySelectorAll('.decision-actions button:not(.help-link)')].map(button => button.getBoundingClientRect().height)`);
            assert(heights.every(height => height >= 44), 'Compact actions retain touch-sized targets');
            assert(Math.max(...heights) - Math.min(...heights) < 1, 'Compact grouped buttons remain equally tall');
        }
        assert(initial.map.height >= 100 && initial.hand.bottom <= height, 'Map and hand stay visible');
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-expansion-review-${width}.png`));
        await page.evaluate(`document.querySelector('.decision-scroll').scrollTop = 100000`);
        const scrolled = await bounds();
        assert.deepEqual(scrolled.footer, initial.footer, 'Scrolling cannot move confirmation');
        assert.deepEqual(scrolled.toolbar, initial.toolbar, 'Scrolling cannot move Undo');
        if (mobile) assert(scrolled.status.bottom < scrolled.review.top, 'The heading scrolls out to leave room for consequences');
        assert.equal(await page.evaluate(`(() => {
            const review = document.querySelector('.decision-scroll').getBoundingClientRect();
            const consequence = document.querySelector('.province-actions .value-change').getBoundingClientRect();
            return consequence.bottom <= review.bottom + 1 && consequence.top >= review.top;
        })()`), true, 'Scrolling exposes the placement consequences');
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-expansion-consequences-${width}.png`));
        if (mobile) {
            await click('.hand-toggle');
            const expanded = await bounds();
            await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-expanded-review-${width}.png`));
            assert(expanded.review.height >= 150 && expanded.hand.bottom <= height && expanded.map.height >= 60,
                `Expanded hand leaves room for the review and map: ${JSON.stringify(expanded)}`);
            await click('.hand-toggle');
        }
        assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
    }
    console.log('Passed: equal content-sized player boxes, full-width status, income mapping, and readable phone expansion with pinned Undo/confirmation.');
}

if (process.argv[1]?.endsWith('/expansionLayout.browser.mjs')) {
    const page = await open('about:blank');
    try {
        const game = await createGame(6);
        for (const link of game.links) await game.send(link, 'bid-capital 0');
        let baseline;
        for (let step = 0; step < 100; step++) {
            const state = await game.snapshot(game.links[0]);
            const actor = game.links.find(link => state.prompt?.players.includes(link.id));
            assert(actor);
            const current = await game.snapshot(actor);
            if (current.game.stage === 'EXPANSION') { baseline = current; break; }
            const commands = current.legalChoices.commands;
            const command = current.game.stage === 'TOKEN_PURCHASE' ? `buy-tokens ${18 + game.links.indexOf(actor)}`
                : commands.find(command => command.startsWith('choose-capital '))
                    ?? commands.find(command => command.startsWith('choose-turn-position '))
                    ?? commands.find(command => command === 'pass')
                    ?? commands.find(command => command === 'stabilize cash');
            assert(command, `Setup progresses from ${current.game.stage}`);
            await game.send(actor, command);
        }
        assert(baseline);
        const web = process.env.MAP_WEB_URL;
        await checkExpansionLayout(page, web, baseline);
        await checkMobileTable(page, web, baseline);
        await checkActionButtons(page, web, baseline);
        await checkReferenceUx(page, web, baseline);
        await page.send('Emulation.setDeviceMetricsOverride', {width: 1280, height: 900, deviceScaleFactor: 1, mobile: false});
        await checkTable(page, web, baseline);
        await checkBottomHand(page, web, baseline);
        await checkTableConsistency(page, web, baseline);
        const actor = game.links.find(link => link.id === baseline.viewerId);
        const offer = baseline.game.expansionOffers.find(offer => offer.primaryAction === 'PLACE' &&
            offer.establishesMarker && !offer.blockers.length);
        await page.send('Emulation.setDeviceMetricsOverride', {width: 320, height: 640, deviceScaleFactor: 1, mobile: true});
        await page.send('Page.navigate', {url: web + actor.path});
        await page.wait(`document.querySelector('.pool-summary')`, 'Live phone expansion');
        await waitForMap(page, baseline);
        await page.evaluate(`document.querySelector('[data-map-province="${offer.destination}"]').dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true}))`);
        await page.wait(`document.querySelector('.primary-expansion:not([disabled])')`, 'Live marker commitment');
        await page.evaluate(`document.querySelector('.decision-scroll').scrollTop = 100000`);
        assert(await page.evaluate(`(() => {
            const button = document.querySelector('.primary-expansion');
            const rect = button.getBoundingClientRect();
            const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
            return hit === button || button.contains(hit);
        })()`), 'The live placement control remains reachable after scrolling');
        await page.evaluate(`document.querySelector('.primary-expansion').click()`);
        await page.wait(`!document.querySelector('.primary-expansion') && document.querySelector('[data-map-province="${offer.destination}"]').getAttribute('aria-pressed') === 'false'`, 'Successful live placement clears its draft');
        const placed = await game.snapshot(actor);
        assert.equal(placed.game.players.find(player => player.id === actor.id).poolTokens, offer.resultingPool);
        assert.equal(placed.game.board.find(province => province.id === offer.destination).marker.owner, actor.id);
        console.log('Passed: live phone marker placement after scrolling, authoritative pool balance, and accepted draft cleanup.');
    } finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
