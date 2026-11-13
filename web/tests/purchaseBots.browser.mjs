import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {readFileSync, writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {open} from './browser-support.mjs';
import {chooseBotMove, JointBotSelector, PurchaseBotSelector} from '../../ai/build/dist/js/productionLibrary/aor-ai.mjs';

const api = process.env.MAP_API_URL;
const web = process.env.MAP_WEB_URL;
const pause = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const baselineBytes = readFileSync(new URL('../src/ai/model.json', import.meta.url), 'utf8');
const actorBytes = readFileSync(new URL('../src/ai/purchaseModel.json', import.meta.url), 'utf8');
const jointBytes = readFileSync(new URL('../src/ai/jointModel.json', import.meta.url), 'utf8');
const jointAsset = JSON.parse(jointBytes);
const purchaseAsset = JSON.parse(actorBytes);
const actor = purchaseAsset.models?.find(model => model.rulesVersion === 'ah1996-three-epoch-5') ?? purchaseAsset;
const javaHome = process.env.JAVA_HOME ?? null;
const javaVersion = javaHome ? execFileSync(join(javaHome, 'bin/java'), ['--version'], {encoding: 'utf8'}).trim() : null;
if (process.env.AOR_PURCHASE_EXPECT_ACTIVE === '1') assert.match(javaVersion ?? '', /(?:openjdk|java) 21\./, 'Active browser proof requires the explicitly supplied JDK21.');
if (process.env.AOR_PURCHASE_EXPECT_ACTIVE === '1') assert(actor?.model, 'The qualified asset must be installed before the active-actor proof.');
const selector = new PurchaseBotSelector(baselineBytes, actorBytes);
const jointSelector = jointAsset === null ? null : new JointBotSelector(baselineBytes, actorBytes, jointBytes);
const page = await open(web);
const collected = [];
let gameId, token;
const headers = () => ({'Content-Type': 'application/json', Authorization: `Bearer ${token}`});
async function state() {
    const response = await fetch(`${api}/api/games/${gameId}`, {headers: headers()});
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
    for (let attempt = 0; attempt < 8; attempt++) {
        const snapshot = await state();
        const response = await fetch(`${api}/api/games/${gameId}/commands`, {
            method: 'POST', headers: headers(), body: JSON.stringify({expectedRevision: snapshot.revision,
                expectedSubmissionVersion: snapshot.submissionVersion, operationId: crypto.randomUUID(), command}),
        });
        if (response.ok) return response.json();
        const error = await response.json();
        if (!['STALE_REVISION', 'STALE_SUBMISSION'].includes(error.code)) assert.fail(`${command}: ${JSON.stringify(error)}`);
    }
    assert.fail(`Human command could not settle: ${command}`);
}
async function driveUntil(expression, description) {
    const deadline = Date.now() + 120000;
    while (Date.now() < deadline) {
        if (await page.evaluate(expression)) return;
        const snapshot = await state();
        assert(!snapshot.automation.paused, `Unexpected pause while ${description}`);
        assert(snapshot.game.stage !== 'FINISHED' && snapshot.game.turnNumber <= 8, `Purchase fixture did not reach ${description} within eight turns.`);
        if (snapshot.prompt?.players.includes(snapshot.viewerId) && !snapshot.submitted.includes(snapshot.viewerId)) {
            const choices = snapshot.legalChoices.commands;
            const command = choices.includes('bid-capital') ? 'bid-capital 0'
                : choices.includes('buy-tokens') ? 'buy-tokens 0'
                : choices.includes('pass') ? 'pass'
                : choices.find(value => value.startsWith('choose-capital ')) ?? choices[0];
            assert(command, `Human prompt has a real offered command: ${snapshot.prompt.messageId}`);
            await send(command);
        } else await pause(60);
    }
    assert.fail(`Timed out driving actual legal choices: ${description}`);
}
async function control(label) {
    await page.evaluate(`document.querySelector('.table-configuration').open = true`);
    await page.wait(`Array.from(document.querySelectorAll('.bot-controls button')).some(button => button.textContent === ${JSON.stringify(label)} && !button.disabled)`, label);
    await page.click(label);
}
const capture = () => page.evaluate(`({inputs: window.purchaseProof.inputs, replies: window.purchaseProof.replies,
    submissions: window.purchaseProof.submissions, terminated: window.purchaseProof.terminated})`);
const held = () => page.evaluate(`window.purchaseProof.held.map(item => ({input: item.input, reply: item.reply, transport: item.transport}))`);

try {
    await page.send('Page.enable');
    await page.send('Page.addScriptToEvaluateOnNewDocument', {source: `
        window.purchaseProof = {inputs: [], replies: [], submissions: [], held: [], terminated: 0, holdNextPurchase: true, latestWork: null};
        const proof = window.purchaseProof;
        const originalFetch = window.fetch;
        window.fetch = async (input, init) => {
            const response = await originalFetch(input, init);
            const path = String(input);
            if (!path.includes('/bots/')) return response;
            const request = init?.body ? JSON.parse(init.body) : null;
            const body = await response.clone().json();
            if (path.includes('/bots/commands')) proof.submissions.push({request, status: response.status, code: body.code});
            if (response.ok && Array.isArray(body.observations)) {
                const runnerId = request?.runnerId ?? new URL(path, location.href).searchParams.get('runnerId');
                proof.latestWork = {work: body, runnerId};
            }
            return response;
        };
        const OriginalWorker = window.Worker;
        window.Worker = class extends OriginalWorker {
            constructor(...args) {
                super(...args);
                this.inputs = new Map();
                this.jobs = 0;
                this.addEventListener('message', event => {
                    if (this.releasing) return;
                    const entry = this.inputs.get(event.data.id);
                    if (!entry) return;
                    const item = {...entry, reply: structuredClone(event.data), elapsedMs: performance.now() - entry.postedAt};
                    proof.replies.push(item);
                    if (proof.holdNextPurchase && entry.input.purchase && event.data.command) {
                        proof.holdNextPurchase = false;
                        proof.held.push({...item, worker: this});
                        event.stopImmediatePropagation();
                    }
                });
            }
            postMessage(input, ...args) {
                const latest = proof.latestWork;
                const job = latest?.work.observations.find(job => job.seed === input.seed && JSON.stringify(job.observation) === JSON.stringify(input.observation));
                const transport = job ? {expectedRevision: latest.work.revision, expectedSubmissionVersion: job.submissionVersion,
                    epoch: latest.work.epoch, runnerId: latest.runnerId, seat: job.seat, decisionId: job.decisionId} : null;
                const entry = {input: structuredClone(input), transport, postedAt: performance.now(), cold: this.jobs++ === 0};
                this.inputs.set(input.id, entry);
                proof.inputs.push(entry);
                super.postMessage(input, ...args);
            }
            terminate() {proof.terminated++; super.terminate();}
        };
        proof.releaseHeld = () => {
            for (const item of proof.held.splice(0)) {
                item.worker.releasing = true;
                item.worker.dispatchEvent(new MessageEvent('message', {data: item.reply}));
                item.worker.releasing = false;
            }
        };
    `});
    const createdResponse = await fetch(`${api}/api/games`, {method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({name: 'Qualified purchase lifecycle', players: ['Ada'], bots: [{difficulty: 'HARD'}, {difficulty: 'HARD'}, {difficulty: 'HARD'}],
            options: {'map': 'standard', 'initial-card': 'no-crusades', 'deck-modification': 'enabled', 'immediate-master-art-discard': 'enabled'}, undoPolicy: 'linear-game'})});
    assert(createdResponse.ok, await createdResponse.clone().text());
    const created = await createdResponse.json();
    assert.equal(created.links.length, 1);
    gameId = created.gameId;
    const path = created.links[0].path;
    token = new URLSearchParams(path.split('#')[1]).get('token');
    await page.send('Page.navigate', {url: web + path});
    await page.wait(`document.querySelector('.bot-controls')`, 'actual StrictMode game mount');
    await driveUntil('window.purchaseProof.held.length === 1', 'a real computed purchase reply');
    const firstHeld = (await held())[0];
    assert(firstHeld.transport, 'Held reply is bound to its actual server work receipt.');
    assert(firstHeld.input.purchase, 'Qualified work contains the actual safe purchase projection.');

    await control('Pause automated opponents');
    const paused = await waitState(snapshot => snapshot.automation.paused, 'durable pause');
    await page.wait('window.purchaseProof.terminated > 0', 'pause terminates the worker');
    const submissionsBeforePauseRelease = (await capture()).submissions.length;
    await page.evaluate('window.purchaseProof.releaseHeld()');
    await pause(700);
    assert.equal((await state()).revision, paused.revision, 'Late computed reply cannot advance a paused game.');
    assert.equal((await capture()).submissions.length, submissionsBeforePauseRelease, 'Late reply never reaches bot submission.');
    collected.push(await capture());

    await page.send('Page.reload');
    await page.wait(`document.querySelector('.bot-controls')?.textContent.includes('paused')`, 'paused reload restores durable state');
    await pause(300);
    assert.equal((await capture()).inputs.length, 0, 'Reload does not silently resume paused automation.');
    await page.evaluate('window.purchaseProof.holdNextPurchase = false');
    await control('Resume automated opponents');
    await waitState(snapshot => !snapshot.automation.paused, 'explicit resume after reload');
    await driveUntil(`(() => {
        const purchases = window.purchaseProof.replies.filter(item => item.input.purchase && item.reply.command);
        return purchases.filter(item => item.input.observation.decision === 'purchase-shipping').length >= 6
            && purchases.filter(item => item.input.observation.decision === 'purchase-advances').length >= 8
            && purchases.filter(item => item.reply.command.startsWith('buy-advance ')).length >= 2;
    })()`, 'repeated real shipping/advance decisions and purchases');

    await page.evaluate('window.purchaseProof.holdNextPurchase = true');
    await driveUntil('window.purchaseProof.held.length === 1', 'the next computed purchase before undo');
    const old = (await held())[0];
    assert(old.transport, 'Undo test retains the actual old decision receipt.');
    const rewound = await send('undo');
    assert.equal(rewound.automation.paused, true);
    await page.wait(`document.querySelector('.bot-controls')?.textContent.includes('paused')`, 'undo fences the active StrictMode runner');
    const submissionsBeforeUndoRelease = (await capture()).submissions.length;
    await page.evaluate('window.purchaseProof.releaseHeld()');
    await pause(700);
    assert.equal((await state()).revision, rewound.revision, 'Late computed purchase cannot recreate undone history.');
    assert.equal((await capture()).submissions.length, submissionsBeforeUndoRelease, 'Canceled purchase never submits after undo.');
    const stale = await fetch(`${api}/api/games/${gameId}/bots/commands`, {method: 'POST', headers: headers(),
        body: JSON.stringify({...old.transport, operationId: crypto.randomUUID(), command: old.reply.command})});
    assert(!stale.ok, 'The server rejects an actual purchase command with its obsolete receipt.');
    const staleError = await stale.json();
    assert.equal(staleError.code, 'BOT_STALE_EPOCH', 'Undo revokes the exact old runner epoch before a command can be considered.');
    assert.equal((await state()).revision, rewound.revision, 'Rejected stale receipt leaves history unchanged.');

    const freshStart = (await capture()).inputs.length;
    await page.evaluate('window.purchaseProof.holdNextPurchase = false');
    await control('Resume automated opponents');
    await waitState(snapshot => !snapshot.automation.paused, 'explicit resume after undo');
    await driveUntil(`window.purchaseProof.inputs.slice(${freshStart}).some(item => item.input.purchase)`, 'fresh purchase work after undo');
    await control('Pause automated opponents');
    await waitState(snapshot => snapshot.automation.paused, 'fixture stops its own automation');
    collected.push(await capture());

    const replies = collected.flatMap(item => item.replies);
    const purchases = replies.filter(item => item.input.purchase);
    let nonbaseline = 0;
    for (const {input, reply} of replies) {
        assert(reply.command && !reply.error, JSON.stringify(reply));
        assert(input.observation.candidates.some(candidate => candidate.command === reply.command), 'Worker response must be an actual offered command.');
        assert.deepEqual(Object.keys(input).sort(), ['difficulty', 'id', 'observation', 'seed',
            ...(input.purchase ? ['purchase'] : []), ...(input.card ? ['card'] : []),
            ...(input.expansion ? ['expansion'] : [])].sort());
        if (input.purchase) {
            assert.equal('baselineIndex' in input.purchase, false, 'Server never manufactures the actor recommendation.');
            assert.deepEqual(input.purchase.actions.map(action => action.command), input.observation.candidates.map(candidate => candidate.command).sort());
        }
        if (input.card) {
            assert.equal(input.difficulty, 'HARD');
            assert.equal(input.observation.decision, 'play-cards');
            assert.deepEqual(Object.keys(input.card).sort(), ['actions', 'encoderVersion', 'stateFeatures']);
            assert.deepEqual(input.card.actions.map(action => action.command), input.observation.candidates.map(candidate => candidate.command).sort());
            assert.equal(input.purchase, undefined, 'A card job contains no purchase projection.');
        }
        if (input.expansion) {
            assert.equal(input.difficulty, 'HARD');
            assert.equal(input.observation.decision, 'expand');
            assert.equal(input.expansion.encoderVersion, 'aor-expansion-observation-2');
            assert.deepEqual(Object.keys(input.expansion).sort(), ['actions', 'encoderVersion', 'stateFeatures']);
            assert.deepEqual(input.expansion.actions.map(action => action.command), input.observation.candidates.map(candidate => candidate.command).sort());
            assert.equal(input.purchase, undefined);
            assert.equal(input.card, undefined);
        }
        const observation = JSON.stringify(input.observation);
        const purchase = input.purchase ? JSON.stringify(input.purchase) : null;
        const expected = jointSelector
            ? jointSelector.choose(observation, purchase, input.card ? JSON.stringify(input.card) : null, input.difficulty, input.seed)
            : selector.choose(observation, purchase, input.difficulty, input.seed);
        assert.equal(reply.command, expected, 'Actual browser worker agrees with cached generated selector on the exact server input.');
        if (input.purchase && reply.command !== chooseBotMove(JSON.stringify(input.observation), baselineBytes, input.difficulty, input.seed)) nonbaseline++;
    }
    const expansions = replies.filter(item => item.input.expansion);
    assert(expansions.length > 0, 'Live HARD expansion offers must reach the unchanged production worker.');
    const advances = purchases.filter(item => item.input.observation.decision === 'purchase-advances');
    const refreshed = advances.filter((item, index) => index > 0 && advances[index - 1].reply.command.startsWith('buy-advance ')
        && advances[index - 1].input.observation.playerId === item.input.observation.playerId
        && advances[index - 1].transport?.decisionId !== item.transport?.decisionId
        && JSON.stringify(advances[index - 1].input.purchase) !== JSON.stringify(item.input.purchase));
    assert(refreshed.length > 0, 'A completed purchase produces fresh actual advance quotes before the next choice.');
    const timing = items => {
        const values = items.map(item => item.elapsedMs).sort((a, b) => a - b);
        if (!values.length) return {count: 0};
        return {count: values.length, meanMs: values.reduce((a, b) => a + b, 0) / values.length,
            p50Ms: values[Math.floor((values.length - 1) * .5)], p95Ms: values[Math.floor((values.length - 1) * .95)], maxMs: values.at(-1)};
    };
    const report = {kind: 'real-server-purchase-browser-lifecycle', activeActor: actor !== null, javaHome, javaVersion,
        jointActorActive: jointAsset !== null, jointAssetSha256: createHash('sha256').update(jointBytes).digest('hex'),
        jointBehaviorIdentity: jointAsset?.behaviorIdentity ?? null,
        jointSupportProfile: jointAsset?.supportProfile ?? null,
        webMode: process.env.AOR_BROWSER_PREVIEW === '1' ? 'built-production' : 'vite-development',
        baselineSha256: createHash('sha256').update(baselineBytes).digest('hex'), actorAssetSha256: createHash('sha256').update(actorBytes).digest('hex'),
        replies: replies.length, purchaseReplies: purchases.length, expansionReplies: expansions.length,
        expansionActorInstalled: false, learnedDeviations: nonbaseline,
        shippingReplies: purchases.filter(item => item.input.observation.decision === 'purchase-shipping').length,
        advanceReplies: advances.length, refreshedAdvanceQuotes: refreshed.length,
        pauseLateReplyBlocked: true, pausedReloadStayedPaused: true, undoLateReplyBlocked: true,
        staleReceiptRejected: staleError.code, freshWorkAfterUndo: true,
        workerRoundtripTiming: {cold: timing(replies.filter(item => item.cold)), warm: timing(replies.filter(item => !item.cold)),
            coldPurchase: timing(purchases.filter(item => item.cold)), warmPurchase: timing(purchases.filter(item => !item.cold)),
            meaning: 'PostMessage to actual reply receipt; cold includes module import/model parsing. Includes event scheduling and transfer, excludes server projection/API. Descriptive roundtrip timing for the reported web mode, not isolated neural inference or a tuning gate.'}};
    if (process.env.AOR_PURCHASE_EXPORT_INPUTS === '1') {
        // Synthetic actor-visible inputs only: omit credentials, game IDs and lease/submission data.
        const inputs = purchases.map(({input, reply}) => ({observation: input.observation,
            purchase: input.purchase, difficulty: input.difficulty, seed: input.seed, command: reply.command}));
        writeFileSync(join(process.env.AOR_BROWSER_ARTIFACTS, 'purchase-worker-inputs.json'),
            JSON.stringify({kind: 'sanitized-purchase-worker-inputs-1',
                baselineSha256: report.baselineSha256, actorAssetSha256: report.actorAssetSha256,
                inputs}, null, 2) + '\n', {flag: 'wx'});
    }
    writeFileSync(join(process.env.AOR_BROWSER_ARTIFACTS, 'purchase-bots-lifecycle.json'), JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify(report));
} finally {
    page.ws.close();
}
