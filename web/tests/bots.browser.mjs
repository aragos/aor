import assert from 'node:assert/strict';
import {join} from 'node:path';
import {open} from './browser-support.mjs';

const api = process.env.MAP_API_URL;
const web = process.env.MAP_WEB_URL;
const cdp = process.env.MAP_CDP_URL;
const page = await open(web);
let second;
const pause = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
let gameId, token;
async function state() {
    const response = await fetch(`${api}/api/games/${gameId}`, {headers: {Authorization: `Bearer ${token}`}});
    assert(response.ok, await response.clone().text());
    return response.json();
}
async function waitState(predicate, description) {
    for (let attempt = 0; attempt < 150; attempt++) {
        const snapshot = await state();
        if (predicate(snapshot)) return snapshot;
        await pause(100);
    }
    throw new Error(`Timed out: ${description}`);
}
async function send(command) {
    const snapshot = await state();
    const response = await fetch(`${api}/api/games/${gameId}/commands`, {
        method: 'POST', headers: {'Content-Type': 'application/json', Authorization: `Bearer ${token}`},
        body: JSON.stringify({expectedRevision: snapshot.revision, expectedSubmissionVersion: snapshot.submissionVersion,
            operationId: crypto.randomUUID(), command}),
    });
    assert(response.ok, `Rejected ${command}: ${await response.clone().text()}`);
    return response.json();
}
const click = selector => page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);

try {
    await page.send('Page.enable');
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 980, deviceScaleFactor: 1, mobile: false});
    await page.send('Page.addScriptToEvaluateOnNewDocument', {source: `
        window.botWorkerInputs = [];
        window.holdBotComputation = false;
        const OriginalWorker = window.Worker;
        window.Worker = class extends OriginalWorker {
            postMessage(input, ...args) {
                window.botWorkerInputs.push(structuredClone(input));
                if (!window.holdBotComputation) super.postMessage(input, ...args);
            }
        };
    `});
    await page.send('Page.navigate', {url: web + '/play'});
    await page.wait(`document.querySelector('#names')`, 'local creation form');
    await page.fill('#game-name', 'A human and two local opponents');
    await page.fill('#names', 'Ada');
    await page.click('Add automated opponent');
    await page.click('Add automated opponent');
    await page.fill('#bot-difficulty-0', 'EASY');
    await page.fill('#bot-difficulty-1', 'HARD');
    await page.evaluate(`document.querySelector('.creation-settings').open = true`);
    await page.click('Linear game');
    assert.equal(await page.evaluate(`document.querySelector('.automated-opponents').textContent.includes('3 of 6 seats')`), true);
    await page.click('Create game');
    await page.wait(`document.querySelectorAll('.seat-links a').length === 1`, 'only the human receives a private seat link');
    const path = await page.evaluate(`document.querySelector('.seat-links a').getAttribute('href')`);
    gameId = /^\/games\/([^#]+)/.exec(path)[1];
    token = new URLSearchParams(path.split('#')[1]).get('token');
    await page.send('Page.navigate', {url: web + path});
    await page.send('Page.bringToFront');
    await page.wait(`document.querySelector('#bid')`, 'human capital bid');
    await page.fill('#bid', '7');
    await waitState(snapshot => snapshot.submitted.length === 2, 'both client workers submit their sealed bids');
    await page.wait(`document.querySelectorAll('.player-status').length === 2`, 'private submissions appear through ordinary polling');
    assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7', 'Bot writes and polling preserve the human draft');
    const initial = await state();
    assert.equal(initial.game.players.length, 3);
    assert.deepEqual(initial.automation.players.map(player => player.difficulty), ['EASY', 'HARD']);
    assert.equal(initial.automation.canControl, true);
    assert.equal(initial.game.hand.length, initial.game.players[0].handCount, 'The human snapshot contains only the human hand');
    const inputs = await page.evaluate('window.botWorkerInputs');
    assert(inputs.length >= 2, 'Real Kotlin worker chooses moves for both automated seats');
    assert(inputs.every(input => input.observation.playerId !== initial.viewerId));
    assert(inputs.every(input => Object.keys(input).sort().join(',') === 'difficulty,id,observation,seed'), 'Workers receive no authorization, other-seat views or transport receipts');

    await click('.table-configuration > summary');
    await page.click('Pause automated opponents');
    await waitState(snapshot => snapshot.automation.paused, 'pause is durable');
    assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7', 'Pausing does not clear a valid bid draft');
    await page.evaluate(`document.querySelector('.bot-controls button').scrollIntoView({block: 'nearest'})`);
    await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'automated-opponents-desktop.png'));
    await page.send('Emulation.setDeviceMetricsOverride', {width: 390, height: 844, deviceScaleFactor: 1, mobile: true});
    await page.wait(`document.querySelector('#tab-players')`, 'phone Players workspace');
    await click('#tab-players');
    await page.evaluate(`document.querySelector('.bot-controls button').scrollIntoView({block: 'nearest'})`);
    assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth + 1`), true, 'Automated controls fit on a phone');
    assert.equal(await page.evaluate(`(() => {
        const button = document.querySelector('.bot-controls button').getBoundingClientRect();
        return Boolean(document.elementFromPoint(button.x + button.width / 2, button.y + button.height / 2)?.closest('.bot-controls'));
    })()`), true, 'The automation control is reachable after scrolling the Players workspace');
    assert.equal(await page.evaluate(`document.querySelector('.game-name').getBoundingClientRect().right <= document.querySelector('.private-seat-link').getBoundingClientRect().left + 1`), true, 'Long game names leave space for the private seat link');
    await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'automated-opponents-phone.png'));
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 980, deviceScaleFactor: 1, mobile: false});
    await page.wait(`!document.querySelector('#tab-players')`, 'desktop layout returns');
    await page.wait(`document.querySelector('.bot-controls button')?.textContent === 'Resume automated opponents' && !document.querySelector('.bot-controls button').disabled`, 'paused snapshot and control are settled');
    // A direct refresh can lose its result to a newer poll. Retain the paused representation
    // deliberately, then require the ordinary conditional read to reconcile the control change.
    await page.evaluate(`(async () => {
        const originalFetch = window.fetch;
        window.restoreBotSnapshotFetch = () => {window.fetch = originalFetch;};
        const snapshotPath = ${JSON.stringify('/api/games/')} + ${JSON.stringify(gameId)};
        const pausedResponse = await originalFetch(snapshotPath, {headers: {Authorization: ${JSON.stringify(`Bearer ${token}`)}}});
        const pausedBody = await pausedResponse.text();
        if (!JSON.parse(pausedBody).automation.paused) throw new Error('The cached automation snapshot must be paused.');
        window.botMissNextResumeRefresh = true;
        window.botMissedResumeRefreshes = 0;
        window.botConditionalAutomationReads = [];
        window.fetch = async (input, init) => {
            const response = await originalFetch(input, init);
            if (String(input) !== snapshotPath || init?.method === 'POST') return response;
            const conditional = new Headers(init?.headers).has('If-None-Match');
            if (!conditional && window.botMissNextResumeRefresh) {
                window.botMissNextResumeRefresh = false;
                window.botMissedResumeRefreshes++;
                return new Response(pausedBody, {status: 200, headers: pausedResponse.headers});
            }
            if (conditional && window.botMissedResumeRefreshes) {
                window.botConditionalAutomationReads.push({status: response.status,
                    automation: response.ok ? (await response.clone().json()).automation : null});
            }
            return response;
        };
    })()`);
    await page.click('Resume automated opponents');
    await waitState(snapshot => !snapshot.automation.paused, 'explicit resume');
    await page.wait(`window.botMissedResumeRefreshes === 1`, 'resume direct refresh deliberately retains the cached paused snapshot');
    try {
        await page.wait(`window.botConditionalAutomationReads.some(read => read.status === 200 && read.automation?.paused === false)`, 'ordinary conditional polling recovers the resumed automation view');
    } catch (failure) {
        console.log('Resume polling diagnostics', {automation: (await state()).automation,
            reads: await page.evaluate('window.botConditionalAutomationReads'),
            controls: await page.evaluate(`document.querySelector('.bot-controls').textContent`)});
        throw failure;
    }
    await page.wait(`document.querySelector('.bot-controls button')?.textContent === 'Pause automated opponents' && !document.querySelector('.bot-controls button').disabled`, 'resumed UI recovers after the missed direct refresh');
    assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7', 'Automation polling preserves the prepared human bid');
    await page.evaluate('window.restoreBotSnapshotFetch()');

    second = await open(web + path);
    await second.wait(`document.querySelector('.bot-controls')`, 'second StrictMode game mount');
    await second.evaluate(`document.querySelector('.table-configuration').open = true`);
    await second.wait(`document.querySelector('.bot-controls').textContent.includes('another browser tab')`, 'single lease excludes a second tab');
    await second.click('Run automated opponents here');
    await page.send('Page.bringToFront');
    try {
        await page.wait(`document.querySelector('.bot-controls').textContent.includes('another browser tab') || document.querySelector('.bot-controls').textContent.includes('stopped')`, 'old runner stops after explicit takeover');
    } catch (failure) {
        console.log('Takeover diagnostics', {automation: (await state()).automation,
            original: await page.evaluate(`document.querySelector('.bot-controls').textContent`),
            second: await second.evaluate(`document.querySelector('.bot-controls').textContent`)});
        throw failure;
    }
    const takeBack = await page.evaluate(`document.querySelector('.bot-controls button').textContent`);
    await page.click(takeBack);
    await fetch(`${cdp}/json/close/${second.target.id}`);
    second.ws.close(); second = null;
    await page.send('Page.bringToFront');

    await page.fill('#bid', '7');
    await click('.bid-action');
    await waitState(snapshot => snapshot.game.stage !== 'BIDDING', 'capital bids reveal normally');
    for (let step = 0; step < 30; step++) {
        const snapshot = await state();
        if (snapshot.game.stage === 'TOKEN_PURCHASE') break;
        if (snapshot.prompt?.players.includes(snapshot.viewerId)) {
            const command = snapshot.legalChoices.commands.find(command => command.startsWith('choose-capital '));
            assert(command, `Human setup decision has a capital command: ${snapshot.prompt.messageId}`);
            await send(command);
        } else await pause(150);
    }
    const tokenStage = await waitState(snapshot => snapshot.game.stage === 'TOKEN_PURCHASE' && snapshot.submitted.length === 2, 'bots navigate capital choices and token purchases');
    await page.wait(`document.querySelector('#bid') && document.querySelector('#purchase-preview')`, 'human token decision');
    await page.fill('#bid', '11');
    await page.wait(`document.querySelector('#purchase-preview').textContent.includes('Cash remaining')`, 'live token quote');
    assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '11');
    const beforeHeld = await page.evaluate('window.botWorkerInputs.length');
    await page.evaluate('window.holdBotComputation = true');
    await send(`buy-tokens ${tokenStage.legalChoices.bidMax}`);
    await page.wait(`window.botWorkerInputs.length > ${beforeHeld}`, 'an actual next bot decision is pending in the worker');
    const rewound = await send('undo');
    assert.equal(rewound.automation.paused, true, 'History rewind revokes local automation');
    const restored = rewound.revision;
    await page.evaluate('window.holdBotComputation = false');
    await page.wait(`document.querySelector('.bot-controls').textContent.includes('paused')`, 'history pause is visible');
    await pause(1200);
    assert.equal((await state()).revision, restored, 'Obsolete worker work cannot recreate undone play');
    await page.click('Resume automated opponents');
    await waitState(snapshot => !snapshot.automation.paused && snapshot.submitted.length === 2, 'resumption computes fresh private submissions');

    await page.evaluate(`document.querySelector('.table-configuration').open = true`);
    await click('.abandon-game');
    await page.wait(`document.querySelector('dialog[open]')?.textContent.includes('ends it immediately')`, 'solo abandonment explains the actual final consequence');
    assert.equal(await page.evaluate(`document.querySelector('dialog[open]').textContent.includes('all automated opponents')`), true);
    await page.click('Agree and abandon game');
    await waitState(snapshot => snapshot.status === 'ABANDONED', 'creator consent includes separate bot agreements');
    await page.wait(`document.querySelector('.bot-controls').textContent.includes('ended')`, 'terminal automation status');
    assert.equal(await page.evaluate(`document.querySelector('.bot-controls button') === null`), true, 'Closed games expose no automation mutation');
    console.log('Local automated opponents passed: creation/difficulties, real worker decisions, private bids, draft preservation, single-tab lease/takeover, setup and token flow, pending-worker rewind, explicit resume, phone layout and final abandonment.');
} finally {
    page.ws.close();
    if (second) {second.ws.close(); await fetch(`${cdp}/json/close/${second.target.id}`);}
}
