import assert from 'node:assert/strict';

export async function checkActivityPayments(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1280, height: 900, deviceScaleFactor: 1, mobile: false});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'activity payment harness');
    const actor = baseline.viewerId;
    const other = baseline.game.players.find(player => player.id !== actor).id;
    const participants = [{id: actor, name: 'Original actor', capital: 'venice'},
        {id: other, name: 'Original recipient', capital: 'genoa'}];
    const payout = {...baseline.events.at(-1), id: 'commodity-receipts', kind: 'PLAY_CARD', actor,
        target: null, card: 'cloth-wine', commodity: 'WINE', provinces: [], dice: [], amount: null,
        war: null, competition: null, detail: null, protectionCash: null, leaderRebate: null,
        drawReason: null, marketRoll: null, reliefPayment: null, tokenChanges: [], miseryChanges: [], participants,
        cashChanges: [{player: actor, amount: 20}, {player: other, amount: 45}]};
    const payment = {...payout, id: 'cash-spent', kind: 'PATRONIZE', card: null, commodity: null, target: other,
        cashChanges: [{player: actor, amount: -10}, {player: other, amount: 10}]};
    let revision = 25000;
    const events = [payment, ...Array.from({length: 20}, (_, index) => ({...payout, id: `payout-${index}`})), payout];
    async function show(players = baseline.game.players) {
        const snapshot = {...baseline, revision: ++revision, events, game: {...baseline.game, players}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(snapshot)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'payment activity fixture');
    }
    await show();
    const selector = '[data-event-id="commodity-receipts"] .event-cash-change';
    const receipts = await page.evaluate(`[...document.querySelectorAll(${JSON.stringify(selector)})].map(row => ({
        player: row.dataset.cashPlayer, text: row.textContent, emblems: row.querySelectorAll('svg').length,
    }))`);
    assert.deepEqual(receipts.map(row => row.player), [actor, other]);
    assert.match(receipts[0].text, /Venice\s*\+\$20/,
        'The playing player has the same explicit name and signed amount as other commodity recipients');
    assert.match(receipts[1].text, /Genoa\s*\+\$45/);
    assert(receipts.every(row => row.emblems === 1), 'Every cash receipt includes its recorded emblem');
    assert.match(await page.evaluate(`document.querySelector('[data-event-id="cash-spent"] .event-cash-change').textContent`),
        /Venice\s*−\$10/, 'Spending uses the same explicit player label and signed amount');
    const styles = await page.evaluate(`[...document.querySelectorAll('.event-cash-change')].map(row => {
        const style = getComputedStyle(row); return [style.display, style.backgroundColor, style.borderColor, style.padding];
    })`);
    assert(styles.every(style => JSON.stringify(style) === JSON.stringify(styles[0])),
        'Actor receipts, other receipts, gains and payments use the same presentation');
    const recorded = await page.evaluate(`document.querySelector('[data-event-id="commodity-receipts"]').innerHTML`);
    await show(baseline.game.players.map(player => ({...player, name: 'Changed current name', capital: 'barcelona'})));
    assert.equal(await page.evaluate(`document.querySelector('[data-event-id="commodity-receipts"]').innerHTML`), recorded,
        'Later player names and capitals cannot change payment labels or emblems');

    async function checkInset(width, height, mobile) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        await page.evaluate(`document.querySelector('.activity-list').scrollTop = 0`);
        await page.evaluate(`document.fonts.ready.then(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))`);
        const geometry = await page.evaluate(`(() => {
            const toggle = document.querySelector('.activity-toggle').getBoundingClientRect();
            const first = document.querySelector('.activity-list li');
            const text = document.createTreeWalker(first, NodeFilter.SHOW_TEXT);
            const rectangles = [];
            for (let node = text.nextNode(); node; node = text.nextNode()) {
                if (!node.textContent.trim()) continue;
                const range = document.createRange(); range.selectNodeContents(node);
                rectangles.push(...range.getClientRects());
            }
            return {firstTop: first.getBoundingClientRect().top, toggleBottom: toggle.bottom,
                overlaps: rectangles.some(rect => rect.left < toggle.right && rect.right > toggle.left &&
                    rect.top < toggle.bottom && rect.bottom > toggle.top),
                height: document.querySelector('.table-activity').getBoundingClientRect().height};
        })()`);
        assert(geometry.firstTop < geometry.toggleBottom, 'Open activity control shares the first entry instead of adding a row');
        assert.equal(geometry.overlaps, false, `Inset control does not cover activity text at ${width}px`);
        assert(geometry.height <= 416, 'Activity remains bounded and scrollable');
    }
    await checkInset(1280, 900, false);
    await checkInset(390, 844, true);
    await page.evaluate(`document.querySelector('.activity-list').scrollTop = 100`);
    const scroll = await page.evaluate(`document.querySelector('.activity-list').scrollTop`);
    assert(scroll > 0, 'Fixture exercises the bounded activity scroller');
    await show();
    assert.equal(await page.evaluate(`document.querySelector('.activity-list').scrollTop`), scroll,
        'Polling preserves the activity scroll position');
    await page.evaluate(`document.querySelector('.activity-toggle').click()`);
    await show();
    assert.equal(await page.evaluate(`document.querySelector('.activity-toggle').getAttribute('aria-expanded')`), 'false',
        'Polling preserves the collapsed state');
    assert.equal(await page.evaluate(`document.querySelector('.activity-toggle').getAttribute('aria-label')`), 'Show activity');
    assert.equal(await page.evaluate(`document.querySelector('.activity-toggle').textContent.trim()`), '', 'Toggle stays icon only');
    await page.evaluate(`document.querySelector('.activity-toggle').click()`);
    assert.equal(await page.evaluate(`document.querySelector('.activity-list').scrollTop`), scroll,
        'Reopening preserves the prior scroll position');
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1280, height: 900, deviceScaleFactor: 1, mobile: false});
    console.log('Passed: named immutable cash receipts, consistent signed payments, inset activity control, responsive text clearance and polling continuity.');
}
