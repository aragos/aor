import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';
import {checkFinished} from './table.browser.mjs';

const web = process.env.MAP_WEB_URL;
const game = await createGame(3);
const viewer = game.links[0];
const page = await open(web + viewer.path);
const click = selector => page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
const urban = '.personal-options label:nth-of-type(3) input';
const capture = name => page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `${name}.png`));
async function visit(url) {
    const previous = await page.evaluate('performance.timeOrigin');
    await page.send('Page.navigate', {url});
    await page.wait(`performance.timeOrigin !== ${previous}`, 'fresh page');
}
async function continueGame(stop) {
    for (let step = 0; step < 2000; step++) {
        const publicState = await game.snapshot(viewer);
        if (stop(publicState)) return publicState;
        assert.notEqual(publicState.game.stage, 'FINISHED', 'Reach the requested live decision');
        const actor = game.links.find(link => publicState.prompt?.players.includes(link.id) && !publicState.submitted.includes(link.id));
        assert(actor, 'A live actor exists');
        const state = await game.snapshot(actor);
        const choices = state.legalChoices.commands;
        const command = state.game.stage === 'BIDDING' ? 'bid-capital 0'
            : state.game.stage === 'TOKEN_PURCHASE' ? 'buy-tokens 0'
            : actor.id === viewer.id && choices.includes('buy-advance V') ? 'buy-advance V'
            : choices.find(command => command.startsWith('choose-capital '))
                ?? choices.find(command => command.startsWith('choose-turn-position '))
                ?? (state.game.stage === 'FINAL_CARD_PLAY' ? choices.find(command => command.startsWith('play ')) : null)
                ?? choices.find(command => command === 'stabilize cash')
                ?? choices.find(command => command === 'stabilize misery')
                ?? choices.find(command => command === 'pass') ?? choices[0];
        await game.send(actor, command);
    }
    throw new Error('The fixture must reach its requested position');
}
try {
    await page.wait(`document.querySelector('#bid')`, 'live bid');
    await page.fill('#bid', '7');
    for (const [width, height, mobile] of [[1280, 900, false], [390, 844, true]]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        if (mobile) {await page.wait(`document.querySelector('#tab-players')`, 'phone tools'); await click('#tab-players');}
        assert.equal(await page.evaluate(`document.querySelector('.tool-close')`), null, 'Closed tools have no close button');
        for (const tool of ['personal', 'configuration', 'commands']) {
            await page.evaluate(`document.querySelector('#tool-tab-${tool}').scrollIntoView({block:'nearest'})`);
            const point = await page.evaluate(`(() => {const r = document.querySelector('#tool-tab-${tool}').getBoundingClientRect(); return {x:r.x+r.width/2,y:r.y+r.height/2};})()`);
            for (let tap = 0; tap < 3; tap++) {
                const selected = tap !== 1;
                await page.send('Input.dispatchMouseEvent', {type: 'mousePressed', ...point, button: 'left', clickCount: 1});
                await page.send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...point, button: 'left', clickCount: 1});
                await page.wait(`document.querySelector('#tool-tab-${tool}').getAttribute('aria-selected') === '${selected}'`, 'same point toggles the selected tool');
                assert.equal(await page.evaluate(`document.querySelector('.tool-panels').hidden`), !selected);
                assert.equal(await page.evaluate(`Boolean(document.querySelector('.tool-close'))`), selected);
            }
            assert.equal(await page.evaluate(`document.querySelectorAll('.tool-bar [aria-selected=true]').length`), 1);
            assert.equal(await page.evaluate(`document.querySelectorAll('.tool-panels [role=tabpanel]:not([hidden])').length`), 1);
            assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7');
        }
        await page.fill('#command', 'pass');
        await click('#tool-tab-personal');
        await click('#tool-tab-commands');
        assert.equal(await page.evaluate(`document.querySelector('#command').value`), 'pass', 'Switching tabs preserves drafts');
        await click('#tool-tab-commands');
        await click('#tool-tab-commands');
        assert.equal(await page.evaluate(`document.querySelector('#command').value`), 'pass', 'Closing and reopening a tab preserves drafts');
        if (mobile) await page.evaluate(`document.querySelector('.tool-bar').scrollIntoView({block:'start'})`);
        assert.equal(await page.evaluate(`(() => {
            const close = document.querySelector('.tool-close').getBoundingClientRect();
            const panels = document.querySelector('.tool-panels').getBoundingClientRect();
            const content = document.querySelector('.tool-panel-content').getBoundingClientRect();
            return close.left === panels.left && close.top === panels.top && close.bottom <= content.top &&
                close.width >= 44 && close.height >= 44 && close.bottom <= innerHeight;
        })()`), true, 'Close stays in the upper-left corner above scrolling content with a reachable touch target');
        await page.evaluate(`document.querySelector('.tool-panel-content').scrollTop = 10000`);
        await capture(`tools-tabs-${width}`);
        const closePoint = await page.evaluate(`(() => {
            const button = document.querySelector('.tool-close');
            const bounds = button.getBoundingClientRect();
            const x = bounds.x + bounds.width / 2, y = bounds.y + bounds.height / 2;
            return {reachable: button.contains(document.elementFromPoint(x, y)),
                bounds: bounds.toJSON(), hit: document.elementFromPoint(x, y)?.outerHTML};
        })()`);
        assert.equal(closePoint.reachable, true, 'Close remains reachable after scrolling long content: ' + JSON.stringify(closePoint));
        await page.evaluate(`document.querySelector('#tool-tab-personal').focus(); document.activeElement.dispatchEvent(new KeyboardEvent('keydown', {key: 'ArrowRight', bubbles: true}));`);
        assert.equal(await page.evaluate('document.activeElement.id'), 'tool-tab-configuration', 'Tool tabs support roving keyboard focus');
        await click('.tool-close');
        assert.equal(await page.evaluate('document.activeElement.id'), 'tool-tab-configuration', 'Closing tools restores focus');
        assert.equal(await page.evaluate(`document.querySelector('.tool-panels').hidden`), true);
        assert.equal(await page.evaluate(`document.querySelector('.tool-close')`), null, 'Closing removes the close button');
        assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7', 'Closing tools preserves the live decision draft');
    }
    await visit('about:blank');
    const opportunity = await continueGame(state => state.prompt?.messageId === 'prompt.buy-card' &&
        state.prompt.players.includes(viewer.id) && state.legalChoices.commands.includes('buy-card'));
    assert(opportunity.game.players.find(player => player.id === viewer.id).advances.includes('URBAN_ASCENDANCY'));
    await visit(web + viewer.path);
    await page.wait(`document.querySelector('.simple-action')`, 'live Urban Ascendancy decision');
    assert.equal((await game.snapshot(viewer)).revision, opportunity.revision, 'The preference defaults off');
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1280, height: 900, deviceScaleFactor: 1, mobile: false});
    await page.wait(`!document.querySelector('#tab-players')`, 'desktop tools');
    await click('#tool-tab-personal');
    await click(urban);
    await page.wait(`document.querySelector('#tool-tab-commands small').textContent.endsWith('${opportunity.revision + 1}')`, 'preference buys a live card');
    const purchased = await game.snapshot(viewer);
    const before = opportunity.game.players.find(player => player.id === viewer.id);
    const after = purchased.game.players.find(player => player.id === viewer.id);
    assert.equal(after.cash, opportunity.game.cardPurchaseOffer.cashAfter);
    assert.equal(after.handCount, before.handCount + 1);
    assert.equal(purchased.history.filter(line => line === `${viewer.id}: buy-card`).length, 1);
    await page.reload();
    await page.wait(`document.querySelector('${urban}')?.checked`, 'standing purchase preference persists');
    assert.equal((await game.snapshot(viewer)).revision, purchased.revision, 'Reload cannot buy twice');
    await visit('about:blank');
    const finished = await continueGame(state => state.game.stage === 'FINISHED');
    assert(finished.statistics.length > 1, 'Existing game history supplies turn statistics');
    assert.equal(finished.statistics.at(-1).finished, true);
    assert.deepEqual(finished.statistics.at(-1).players.map(player => player.score), finished.game.scores.map(score => score.total));
    await checkFinished(page, web, finished);
    await visit('about:blank');
    await visit(web + viewer.path);
    await page.wait(`document.querySelector('.final-scores-dialog[open]')`, 'live final popup');
    for (const [width, height, mobile] of [[1280, 900, false], [390, 844, true]]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        await click('#result-tab-statistics');
        for (const metric of ['score', 'advances', 'cash', 'misery']) {
            await page.fill('.statistics-metric select', metric);
            await page.wait(`document.querySelector('.statistics-chart title').textContent.includes('${metric === 'advances' ? 'Advances' : metric[0].toUpperCase() + metric.slice(1)}')`, 'metric updates chart');
        }
        await page.fill('.statistics-metric select', 'score');
        await click('.statistics-data > summary');
        assert.equal(await page.evaluate(`document.querySelectorAll('.statistics-data tbody tr').length`), finished.statistics.length);
        await click('.statistics-data > summary');
        await capture(`game-statistics-${width}`);
        assert(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`));
        await click('#result-tab-scores');
        await capture(`game-results-${width}`);
    }
    await click('.final-scores-dialog > .close-detail');
    await click('.final-scores-toggle');
    await page.wait(`document.querySelector('.final-scores-dialog[open]')`, 'results reopen');

    // Deliberately shuffled totals exercise numeric zero, negatives and Chaos independently of who won the live game.
    await visit(web + '/tests/ui-harness.html');
    await page.wait(`typeof window.setUiSnapshot === 'function'`, 'StrictMode polling harness');
    const fixture = {...finished, game: {...finished.game, scores: finished.game.scores.map((score, index) =>
        ({...score, total: [null, -20, 0][index], winner: index === 2}))}};
    await page.evaluate(`window.setUiSnapshot(${JSON.stringify(fixture)})`);
    await page.wait(`document.querySelector('#result-panel-scores tbody')`, 'shuffled scores');
    assert.deepEqual(await page.evaluate(`[...document.querySelectorAll('#result-panel-scores tbody td:last-child')].map(cell => cell.textContent)`), ['0', '-20', '—']);

    const live = {...opportunity, revision: finished.revision + 500,
        game: {...opportunity.game, activity: {...opportunity.game.activity, decisionEntry: 99000}}};
    await page.evaluate(`window.setUiSnapshot(${JSON.stringify(live)})`);
    await page.wait(`window.uiCommands.length === 1 && window.uiRequests >= 4`, 'StrictMode auto-purchase and polling');
    assert.deepEqual(await page.evaluate(`window.uiCommands.map(entry => entry.command)`), ['buy-card']);
    const blocked = {...live, revision: live.revision + 1, game: {...live.game,
        cardPurchaseOffer: {...live.game.cardPurchaseOffer, blockers: ['INSUFFICIENT_CASH']},
        activity: {...live.game.activity, decisionEntry: 99001}}};
    await page.evaluate(`window.setUiSnapshot(${JSON.stringify(blocked)})`);
    const reads = await page.evaluate('window.uiRequests');
    await page.wait(`window.uiRequests >= ${reads + 2}`, 'blocked opportunity polling');
    assert.equal(await page.evaluate('window.uiCommands.length'), 1, 'Unavailable purchases never auto-submit');
    const rejected = {...live, revision: live.revision + 2,
        game: {...live.game, activity: {...live.game.activity, decisionEntry: 99002}}};
    await page.evaluate(`window.uiNextError = 'INSUFFICIENT_CASH'; window.setUiSnapshot(${JSON.stringify(rejected)})`);
    await page.wait(`window.uiCommands.length === 2 && document.querySelector('[role=alert]')`, 'automatic rejection is visible');
    const rejectedReads = await page.evaluate('window.uiRequests');
    await page.wait(`window.uiRequests >= ${rejectedReads + 2}`, 'poll after rejection');
    assert.equal(await page.evaluate('window.uiCommands.length'), 2, 'Rejected purchases are not retried');
    console.log('Passed: stable bottom tabs, preserved drafts, live Urban Ascendancy preference, final score ranking, replay statistics and desktop/phone results.');
} finally {
    page.ws.close();
    await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
}
