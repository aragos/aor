import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';

const game = await createGame(3);
const baseline = await game.snapshot(game.links[0]);
assert.equal(baseline.game.scores.length, baseline.game.players.length, 'Live snapshots project every current score');
assert(baseline.game.scores.every(score => !score.winner), 'A current score does not declare a winner');
const page = await open('about:blank');
const web = process.env.MAP_WEB_URL;
try {
    await page.send('Page.navigate', {url: web + '/tests/ui-harness.html'});
    await page.wait(`typeof window.setUiSnapshot === 'function'`, 'StrictMode display fixture');
    const viewer = baseline.viewerId;
    const other = baseline.game.players.find(player => player.id !== viewer).id;
    let fixture = {...baseline, revision: 91000,
        prompt: {messageId: 'prompt.purchase-shipping', players: [viewer]},
        legalChoices: {commands: ['pass', 'upgrade-ships']},
        planning: {available: true, version: 'display-version', canArm: true, canExecute: false,
            saved: {turnNumber: 1, stage: 'PURCHASE', armed: false, pause: null, actions: [
                {decision: 'purchase-advances', command: 'patronize marco-polo'},
                {decision: 'purchase-advances', command: 'patronize charlemagne'}]},
            preview: {effects: [], boundary: 'INCOMPLETE'}, receipts: [], executions: []},
        game: {...baseline.game, stage: 'PURCHASE', turnNumber: 1,
            activity: {actor: viewer, decisionPlayer: viewer, choice: 'purchase-shipping',
                purchaseStep: 'SHIPPING', startEntry: 0, decisionEntry: 0},
            shippingOffer: {cost: 10, ships: {fleet: 'GALLEY', level: 1}, cashAfter: 20, blockers: []},
            effects: {...baseline.game.effects, leaders: [{leader: 'MARCO_POLO', owner: other,
                role: 'DISPLAY', protectionCash: 0, patronageClaimants: [], mongolEnhanced: true}]}}};
    async function poll() {
        fixture = {...fixture, revision: fixture.revision + 1};
        await page.evaluate(`window.setUiSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`document.querySelector('#tool-tab-commands small')?.textContent.trim() === 'Revision ${fixture.revision}'`, 'Accepted display snapshot');
    }
    await poll();
    for (const score of baseline.game.scores) {
        assert.equal(await page.evaluate(`document.querySelector('[data-player="${score.player}"] .player-score b').textContent`),
            String(score.total), 'Each player box uses its authoritative live score');
    }
    assert.equal(await page.evaluate(`document.querySelector('.final-scores-dialog')`), null, 'Current scores do not open final results');
    assert.deepEqual(await page.evaluate(`[...document.querySelectorAll('.plan-actions li > strong')].map(node => node.textContent)`),
        ['Patronize · Marco Polo', 'Patronize · Charlemagne'], 'Plans use catalog leader names');
    await page.evaluate(`document.querySelector('#tab-advances').click()`);
    assert.match(await page.evaluate(`document.querySelector('[data-played-leader="MARCO_POLO"] .leader-enhancement').textContent`), /doubled by Mongol Armies/);
    async function assertSeparateHelp(width) {
        const controls = await page.evaluate(`(() => {
            const intersects = (a, b) => a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top;
            return [...document.querySelectorAll('.button-with-help')].map(wrapper => {
                const action = wrapper.querySelector('button:not(.help-link)');
                const help = wrapper.querySelector('.help-link');
                const button = action.getBoundingClientRect();
                const marker = help.getBoundingClientRect();
                const buttons = [...wrapper.parentElement.querySelectorAll('button:not(.help-link)')];
                return {separate: !action.contains(help), outside: buttons.every(button => !intersects(marker, button.getBoundingClientRect())),
                    width: marker.width, height: marker.height,
                    labelFits: action.scrollWidth <= action.clientWidth && action.scrollHeight <= action.clientHeight,
                    actionEdge: document.elementFromPoint(button.right - 2, button.top + Math.min(14, button.height / 2)) === action,
                    reachable: [1, .5, 0].every(fraction => {
                        const hit = document.elementFromPoint(marker.left + marker.width / 2, marker.top + 1 + (marker.height - 2) * fraction);
                        return help.contains(hit);
                    })};
            });
        })()`);
        assert(controls.length >= 2, 'Exercise inline planning help and footer help');
        assert(controls.every(help => help.separate && help.outside && help.actionEdge && help.reachable),
            `Help and its hit area stay outside all adjacent buttons at ${width}px: ${JSON.stringify(controls)}`);
        assert(controls.every(help => help.width >= 28 && help.height >= 28 && help.labelFits),
            `Help targets and complete action labels fit at ${width}px: ${JSON.stringify(controls)}`);
    }
    for (const [width, height] of [[1280, 900], [390, 844], [320, 667]]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile: width < 700});
        await page.evaluate('document.fonts.ready.then(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))');
        const heights = await page.evaluate(`[...document.querySelectorAll('.decision-actions button:not(.help-link)')].map(button => button.getBoundingClientRect().height)`);
        assert(heights.length >= 3, 'Exercise wrapped and short labels in a button group');
        assert(Math.max(...heights) - Math.min(...heights) < 1,
            `Grouped action buttons have equal heights at ${width}px: ${JSON.stringify(heights)}`);
        await assertSeparateHelp(width);
        await page.evaluate(`document.querySelector('.decision-actions .help-link').focus()`);
        const helpPoint = await page.evaluate(`(() => {
            const rect = document.activeElement.getBoundingClientRect();
            return {x: rect.left + rect.width / 2, y: rect.top + rect.height / 2};
        })()`);
        await page.send('Input.dispatchMouseEvent', {type: 'mousePressed', button: 'left', clickCount: 1, ...helpPoint});
        await page.send('Input.dispatchMouseEvent', {type: 'mouseReleased', button: 'left', clickCount: 1, ...helpPoint});
        await page.wait(`document.querySelector('.reference-dialog #plans')`, 'Planning help opens from its own target');
        await poll();
        await page.send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
        await page.send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
        await page.wait(`!document.querySelector('.reference-dialog')`, 'Planning help closes');
        assert.equal(await page.evaluate(`document.activeElement.matches('.decision-actions .help-link')`), true,
            'Closing help restores focus to its separate control after polling');
        assert.equal(await page.evaluate(`document.querySelectorAll('.plan-actions li').length`), 2,
            'Opening help and polling preserve the saved plan');
        assert(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`), 'Display fits viewport');
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `activity-polish-${width}.png`));
        if (width < 700) {
            await page.evaluate(`document.querySelector('#tab-players').click()`);
            assert(await page.evaluate(`[...document.querySelectorAll('.player-score')].every(node => node.checkVisibility())`),
                'All scores are visible in phone player boxes');
            assert(await page.evaluate(`[...document.querySelectorAll('.player-summary > summary')].every(node => node.scrollWidth <= node.clientWidth)`),
                'Scores fit within player boxes');
            await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'current-scores-phone.png'));
            await page.evaluate(`document.querySelector('#tab-advances').click()`);
        }
    }
    fixture = {...fixture, legalChoices: {commands: ['pass']}, game: {...fixture.game, shippingOffer: null}};
    await poll();
    assert.equal(await page.evaluate(`document.querySelector('.decision-actions').dataset.actionCount`), '2',
        'Exercise a plan action and continuation');
    assert.equal(await page.evaluate(`document.querySelector('.decision-actions').dataset.layout`), 'separate',
        'Help remains beside its action instead of interrupting a joined button pair');
    await assertSeparateHelp(320);
    fixture = {...fixture, game: {...fixture.game,
        scores: fixture.game.scores.map(score => ({...score, total: score.player === viewer ? -25 : null})), voids: {...fixture.game.voids, crusades: true},
        effects: {...fixture.game.effects, leaders: fixture.game.effects.leaders.map(leader => ({...leader, mongolEnhanced: false}))}}};
    await poll();
    assert.equal(await page.evaluate(`document.querySelector('[data-player="${viewer}"] .player-score b').textContent`), '-25',
        'Polling updates negative current scores');
    assert.equal(await page.evaluate(`document.querySelector('[data-player="${other}"] .player-score b').textContent`), 'Chaos',
        'Chaos has no numeric score');
    assert.equal(await page.evaluate(`document.querySelector('.leader-enhancement')`), null,
        'Later Mongol Armies does not enhance a previously played Marco Polo');
    assert.equal(await page.evaluate('window.uiCommands.length'), 0, 'Inspection and polling submit no actions');
    console.log('Passed: current scores, named planned leaders, play-time Mongol enhancement, equal action heights and separate help targets on desktop and phone.');
} finally {
    page.ws.close();
    await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
}
