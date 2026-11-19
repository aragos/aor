import assert from 'node:assert/strict';
import {open} from './browser-support.mjs';

const api = process.env.MAP_API_URL;
const web = process.env.MAP_WEB_URL;
const cdp = process.env.MAP_CDP_URL;
const modelHash = process.env.AOR_TEST_BROAD_MODEL_SHA256;
const playerCount = Number(process.env.AOR_TEST_BROAD_PLAYER_COUNT ?? '6');
const profile = process.env.AOR_TEST_BROAD_PROFILE ?? 'aor-broad-hard-1';
const botCount = playerCount - 1;
assert.match(modelHash, /^[a-f0-9]{64}$/);
const page = await open(web);

try {
    await page.send('Page.enable');
    await page.send('Page.addScriptToEvaluateOnNewDocument', {source: `
        const proof = window.broadBotProof = {proposals: [], workerJobs: 0};
        const request = window.fetch;
        window.fetch = async (...args) => {
            const response = await request(...args);
            if (response.ok && String(args[0]).includes('/bots/')) {
                const body = await response.clone().json();
                for (const job of body.observations ?? []) {
                    proof.proposals.push({seat: job.seat, proposal: job.proposal,
                        decision: job.observation.decision, configuration: job.observation.configuration,
                        admitted: job.observation.candidates.some(candidate =>
                            candidate.command === job.proposal?.command)});
                }
            }
            return response;
        };
        const OriginalWorker = window.Worker;
        window.Worker = class extends OriginalWorker {
            postMessage(input, ...args) {
                proof.workerJobs++;
                super.postMessage(input, ...args);
            }
        };
    `});

    const result = await fetch(`${api}/api/games`, {method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({name: 'Native Hard variant playtest', players: ['Ada'],
            bots: Array.from({length: botCount}, () => ({difficulty: 'HARD'})),
            options: {map: 'european', 'initial-card': 'no-events', 'draw-three-keep-two': 'enabled'}})});
    assert(result.ok, await result.clone().text());
    const game = await result.json();
    await page.send('Page.navigate', {url: web + game.links[0].path});
    await page.wait(`document.querySelectorAll('[data-hand-card]').length === 3`, 'human opening hand');
    await page.evaluate(`document.querySelector('[data-hand-card]').click()`);
    await page.wait(`document.querySelector('.selected-action-confirmation')`, 'opening return confirmation');
    await page.evaluate(`document.querySelector('.selected-action-confirmation').click()`);
    await page.wait(`document.querySelector('#bid')`, 'all automata return an opening card');
    await page.wait(`window.broadBotProof.proposals.filter(job => job.decision === 'bid-capital').length >= ${botCount}`,
        'native capital proposals for every automated seat');

    const {proposals, workerJobs} = await page.evaluate('window.broadBotProof');
    const opening = proposals.filter(job => job.decision === 'return-card');
    assert.equal(new Set(opening.map(job => job.seat)).size, botCount);
    assert.equal(workerJobs, 0);
    assert(proposals.every(job => job.admitted && job.proposal.profile === profile &&
        job.proposal.assetSha256 === modelHash && job.configuration.playerCount === playerCount &&
        job.configuration.drawThreeKeepTwo));
    console.log(`The native Hard model returned opening cards and submitted legal capital proposals in a ${playerCount}-player European game.`);
} finally {
    page.ws.close();
    await fetch(`${cdp}/json/close/${page.target.id}`);
}
