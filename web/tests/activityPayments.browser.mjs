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
    const longPayment = {...payment, id: 'long-payment', participants: [
        {id: actor, name: 'Original actor with a recorded name near the length limit', capital: null},
        participants[1],
    ]};
    let revision = 25000;
    const earlier = Array.from({length: 10}, (_, index) => ({...payout, id: `earlier-${index}`}));
    const later = Array.from({length: 10}, (_, index) => ({...payout, id: `later-${index}`}));
    const events = [payment, ...earlier, longPayment, ...later, payout];
    async function show(players = baseline.game.players) {
        const snapshot = {...baseline, revision: ++revision, events, game: {...baseline.game, players}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(snapshot)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'payment activity fixture');
    }
    await show();
    await page.evaluate(`document.querySelector('#tab-activity').click()`);
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

    async function checkLayout(width, height, mobile) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        await page.evaluate(`document.querySelector('.activity-list').scrollTop = 0`);
        await page.evaluate(`document.fonts.ready.then(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))`);
        const geometry = await page.evaluate(`(() => {
            const list = document.querySelector('.activity-list');
            const bounds = list.getBoundingClientRect();
            const panel = document.querySelector('#workspace-activity').getBoundingClientRect();
            const control = document.querySelector('.activity-pin');
            const pin = control?.checkVisibility() ? control.getBoundingClientRect() : null;
            const text = document.createTreeWalker(list, NodeFilter.SHOW_TEXT);
            const rectangles = [];
            for (let node = text.nextNode(); node; node = text.nextNode()) {
                if (!node.textContent.trim()) continue;
                const range = document.createRange(); range.selectNodeContents(node);
                rectangles.push(...range.getClientRects());
            }
            return {top: bounds.top, bottom: bounds.bottom, panelTop: panel.top, panelBottom: panel.bottom,
                height: bounds.height, viewportHeight: innerHeight,
                scrollable: list.scrollHeight > list.clientHeight,
                overflows: rectangles.some(rect => rect.left < bounds.left - 1 || rect.right > bounds.right + 1),
                overlaps: pin && rectangles.some(rect => rect.bottom > bounds.top && rect.top < bounds.bottom &&
                    rect.left < pin.right && rect.right > pin.left && rect.top < pin.bottom && rect.bottom > pin.top)};
        })()`);
        assert(geometry.height > 30 && geometry.top >= geometry.panelTop - 1 &&
            geometry.bottom <= geometry.panelBottom + 1 && geometry.bottom <= geometry.viewportHeight,
        `Activity uses a bounded workspace at ${width}px: ${JSON.stringify(geometry)}`);
        assert.equal(geometry.scrollable, true, 'All receipts remain reachable through scrolling');
        assert.equal(geometry.overflows, false, `Payment labels wrap within activity at ${width}px`);
        assert(!geometry.overlaps, `The pin control cannot cover receipt text at ${width}px`);
    }
    await checkLayout(1440, 980, false);
    await checkLayout(1280, 900, false);
    await checkLayout(390, 844, true);
    await page.evaluate(`document.querySelector('.activity-list').scrollTop = 100`);
    const scroll = await page.evaluate(`document.querySelector('.activity-list').scrollTop`);
    assert(scroll > 0, 'Fixture exercises the bounded activity scroller');
    await show();
    assert.equal(await page.evaluate(`document.querySelector('.activity-list').scrollTop`), scroll,
        'Polling preserves the activity scroll position');
    await page.evaluate(`document.querySelector('#tab-map').click()`);
    await show();
    assert.equal(await page.evaluate(`document.querySelector('.activity-list').checkVisibility()`), false,
        'Polling preserves the chosen workspace');
    await page.evaluate(`document.querySelector('#tab-activity').click()`);
    assert.equal(await page.evaluate(`document.querySelector('.activity-list').scrollTop`), scroll,
        'Returning to Activity preserves the prior scroll position');
    const incomeRelief = {...payment, id: 'new-world-relief', kind: 'INCOME', actor: null, target: null,
        cashChanges: [], miseryChanges: [{player: actor, spaces: -1, fromValue: 20, toValue: 10,
            reasons: [{kind: 'INCOME_RELIEF', spaces: null, advance: 'NEW_WORLD', card: null}]}]};
    const deferred = {...payment, id: 'deferred-misery', kind: 'FORGO_MARKER', target: null,
        cashChanges: [], miseryChanges: [{player: actor, spaces: 1, fromValue: 20, toValue: 30,
            reasons: [{kind: 'CARD_EFFECT', spaces: null, advance: null, card: 'the-crusades'}]}]};
    events.push(incomeRelief, deferred);
    await show();
    const reliefSelector = '[data-event-id="new-world-relief"] .event-misery-change';
    assert.match(await page.evaluate(`document.querySelector('${reliefSelector}').textContent`),
        /Venice.*−1 misery.*20 → 10.*New World.*Income relief/s,
        'Automatic relief names its public advance and records the actual boxes');
    const reliefHtml = await page.evaluate(`document.querySelector('${reliefSelector}').innerHTML`);
    events.push({...payment, id: 'after-relief'});
    await show(baseline.game.players.map(player => ({...player, capital: 'barcelona', misery: 80})));
    assert.equal(await page.evaluate(`document.querySelector('${reliefSelector}').innerHTML`), reliefHtml,
        'Later activity and player state do not rewrite a misery receipt');
    await page.evaluate(`document.querySelector('[data-event-id="deferred-misery"] .event-card').click()`);
    await page.wait(`document.querySelector('.card-inspection-dialog .card-event-text')`, 'Deferred misery cause opens its card in game');
    assert.match(await page.evaluate(`document.querySelector('.card-inspection-dialog .card-decision h2').textContent`), /Crusades/);
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1280, height: 900, deviceScaleFactor: 1, mobile: false});
    console.log('Passed: named immutable cash receipts, consistent signed payments, activity workspace, responsive text wrapping and polling continuity.');
}
