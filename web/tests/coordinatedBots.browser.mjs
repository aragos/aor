import assert from 'node:assert/strict';
import {join} from 'node:path';
import {open} from './browser-support.mjs';

// The configured server loads only the explicitly constructed JVM asset fixture for this suite.
const expectedProfile = process.env.AOR_TEST_COORDINATED_PROFILE ?? 'aor-coordinated-83-support-1';
assert(['aor-coordinated-83-support-1', 'aor-coordinated-win-support-1', 'aor-coordinated-robust-support-1'].includes(expectedProfile));
const api = process.env.MAP_API_URL;
const web = process.env.MAP_WEB_URL;
const cdp = process.env.MAP_CDP_URL;
const page = await open(web);
const pause = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
let second, gameId, token;
const headers = () => ({'Content-Type': 'application/json', Authorization: `Bearer ${token}`});
async function state() {
    const response = await fetch(`${api}/api/games/${gameId}`, {headers: headers()});
    assert(response.ok, await response.clone().text());
    return response.json();
}
async function waitState(predicate, description) {
    for (let attempt = 0; attempt < 200; attempt++) {
        const snapshot = await state();
        if (predicate(snapshot)) return snapshot;
        await pause(100);
    }
    assert.fail(`Timed out: ${description}`);
}
async function send(command) {
    for (let attempt = 0; attempt < 8; attempt++) {
        const snapshot = await state();
        const response = await fetch(`${api}/api/games/${gameId}/commands`, {method: 'POST', headers: headers(),
            body: JSON.stringify({expectedRevision: snapshot.revision, expectedSubmissionVersion: snapshot.submissionVersion,
                operationId: crypto.randomUUID(), command})});
        if (response.ok) return response.json();
        const failure = await response.json();
        assert(['STALE_REVISION', 'STALE_SUBMISSION'].includes(failure.code), JSON.stringify(failure));
    }
    assert.fail(`Could not settle ${command}`);
}
async function control(label) {
    await page.evaluate(`document.querySelector('#tool-tab-configuration').click()`);
    await page.wait(`Array.from(document.querySelectorAll('.bot-controls button')).some(button => button.textContent === ${JSON.stringify(label)} && !button.disabled)`, label);
    await page.click(label);
}

try {
    await page.send('Page.enable');
    await page.send('Page.addScriptToEvaluateOnNewDocument', {source: `
        window.coordinatedProof = {jobs: [], submits: [], workerJobs: [], lost: false};
        const proof = window.coordinatedProof;
        const originalFetch = window.fetch;
        window.fetch = async (input, init) => {
            const response = await originalFetch(input, init);
            if (!String(input).includes('/bots/')) return response;
            const body = await response.clone().json();
            if (response.ok && Array.isArray(body.observations)) proof.jobs.push(...body.observations);
            if (String(input).includes('/bots/commands')) {
                proof.submits.push({request: JSON.parse(init.body), status: response.status});
                if (response.ok && !proof.lost) {
                    proof.lost = true;
                    throw new TypeError('Constructed lost response after accepted write');
                }
            }
            return response;
        };
        const OriginalWorker = window.Worker;
        window.Worker = class extends OriginalWorker {
            postMessage(input, ...args) {
                proof.workerJobs.push(structuredClone(input));
                super.postMessage(input, ...args);
            }
        };
    `});
    const response = await fetch(`${api}/api/games`, {method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({name: 'Constructed server policy lifecycle', players: ['Ada'],
            bots: Array.from({length: 3}, () => ({difficulty: 'HARD'})), undoPolicy: 'linear-game',
            options: {'map': 'standard', 'initial-card': 'no-crusades', 'deck-modification': 'enabled', 'immediate-master-art-discard': 'enabled'}})});
    assert(response.ok, await response.clone().text());
    const created = await response.json();
    gameId = created.gameId;
    const path = created.links[0].path;
    token = new URLSearchParams(path.split('#')[1]).get('token');
    await page.send('Page.navigate', {url: web + path});
    await page.wait(`document.querySelector('#bid')`, 'human capital bid');
    await page.fill('#bid', '7');
    await waitState(snapshot => snapshot.submitted.length === 3, 'three server proposals submit sealed bids');
    await page.wait(`window.coordinatedProof.lost`, 'accepted write loses its response');
    assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7');
    assert.deepEqual(await page.evaluate('window.coordinatedProof.workerJobs'), [], 'Server profiles never reach the browser learner');
    const initialProof = await page.evaluate('window.coordinatedProof');
    assert.equal(new Set(initialProof.submits.filter(item => item.status === 200).map(item => item.request.operationId)).size, 3,
        'Uncertain write reconciliation does not resubmit a different operation');

    await control('Pause automated opponents');
    await waitState(snapshot => snapshot.automation.paused, 'durable pause');
    await page.send('Page.reload');
    await page.wait(`document.querySelector('.bot-controls')?.textContent.includes('paused')`, 'reload preserves the server profile pause');
    await control('Resume automated opponents');
    await waitState(snapshot => !snapshot.automation.paused, 'explicit resume');
    second = await open(web + path);
    await second.wait(`document.querySelector('.bot-controls')`, 'second StrictMode mount');
    await second.evaluate(`document.querySelector('#tool-tab-configuration').click()`);
    await second.wait(`document.querySelector('.bot-controls').textContent.includes('another browser tab')`, 'lease excludes the second tab');
    second.ws.close();
    await fetch(`${cdp}/json/close/${second.target.id}`);
    second = null;
    await page.send('Page.bringToFront');

    const deadline = Date.now() + 150000;
    const decisions = ['buy-tokens', 'play-cards', 'purchase-shipping', 'purchase-advances', 'expand'];
    while (Date.now() < deadline) {
        const seen = await page.evaluate('window.coordinatedProof.jobs.map(job => job.observation.decision)');
        if (decisions.every(decision => seen.includes(decision))) break;
        const snapshot = await state();
        assert(!snapshot.automation.paused && snapshot.game.turnNumber <= 5, 'Constructed profile remains live within the bounded partial game');
        if (snapshot.prompt?.players.includes(snapshot.viewerId) && !snapshot.submitted.includes(snapshot.viewerId)) {
            const choices = snapshot.legalChoices.commands;
            const command = choices.includes('bid-capital') ? 'bid-capital 0'
                : choices.includes('buy-tokens') ? 'buy-tokens 0'
                : choices.includes('pass') ? 'pass'
                : choices.find(value => value.startsWith('choose-capital ')) ?? choices[0];
            assert(command, 'The human uses an authoritative legal offer');
            await send(command);
        } else await pause(80);
    }
    const proof = await page.evaluate('window.coordinatedProof');
    assert(decisions.every(decision => proof.jobs.some(job => job.observation.decision === decision)), 'Live server work covers C/P/E/T');
    assert.deepEqual(proof.workerJobs, [], 'All four phases use the cached shared server chooser');
    for (const job of [...initialProof.jobs, ...proof.jobs]) {
        assert.equal(job.proposal.profile, expectedProfile);
        assert.match(job.proposal.assetSha256, /^[0-9a-f]{64}$/);
        assert(job.observation.candidates.some(candidate => candidate.command === job.proposal.command));
        assert.equal(job.card ?? null, null);
        assert.equal(job.purchase ?? null, null);
    }
    for (const {request, status} of proof.submits) {
        if (status !== 200) continue;
        assert(proof.jobs.some(job => job.decisionId === request.decisionId && job.proposal.command === request.command),
            'The ordinary authoritative submit receives the exact proposed command');
    }
    await control('Pause automated opponents');
    await waitState(snapshot => snapshot.automation.paused, 'the fixture stops its own runner');
    await page.send('Emulation.setDeviceMetricsOverride', {width: 390, height: 844, deviceScaleFactor: 1, mobile: true});
    await page.wait(`document.querySelector('#tab-players')`, 'phone workspace');
    await page.evaluate(`document.querySelector('#tab-players').click()`);
    assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth + 1`), true);
    await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'coordinated-policy-phone.png'));
    console.log('Constructed server profile passed live C/P/E/T, exact proposals, worker bypass, private bids, lost-response reconciliation, reload/pause/resume, lease and phone checks.');
} finally {
    page.ws.close();
    if (second) {second.ws.close(); await fetch(`${cdp}/json/close/${second.target.id}`);}
}
