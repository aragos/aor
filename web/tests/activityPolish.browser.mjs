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
        await page.wait(`document.querySelector('.command-dock > summary small')?.textContent.trim() === 'Revision ${fixture.revision}'`, 'Accepted display snapshot');
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
    for (const width of [1280, 390]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height: 1000, deviceScaleFactor: 1, mobile: width < 700});
        await page.evaluate('document.fonts.ready.then(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))');
        const geometry = await page.evaluate(`(() => {
            const footer = document.querySelector('.decision-actions');
            const buttons = [...footer.querySelectorAll('button:not(.help-link)')];
            return {heights: buttons.map(button => button.getBoundingClientRect().height),
                help: [...footer.querySelectorAll('.button-with-help')].map(wrapper => {
                    const button = wrapper.querySelector('button:not(.help-link)').getBoundingClientRect();
                    const help = wrapper.querySelector('.help-link').getBoundingClientRect();
                    return {top: button.top, helpTop: help.top, helpBottom: help.bottom,
                        separate: !wrapper.querySelector('button:not(.help-link) .help-link')};
                })};
        })()`);
        assert(geometry.heights.length >= 3, 'Exercise wrapped and short labels in a button group');
        assert(Math.max(...geometry.heights) - Math.min(...geometry.heights) < 1,
            `Grouped action buttons have equal heights at ${width}px: ${JSON.stringify(geometry.heights)}`);
        assert(geometry.help.every(help => help.separate && help.helpTop < help.top && help.helpBottom > help.top),
            'Help is a separate control attached to the button border, outside its label');
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
    console.log('Passed: named planned leaders, play-time Mongol enhancement, equal grouped action heights and separate corner help on desktop and phone.');
} finally {
    page.ws.close();
    await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
}
