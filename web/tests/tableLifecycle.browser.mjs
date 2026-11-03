import assert from 'node:assert/strict';
import {createGame, open} from './browser-support.mjs';
import {checkSetupEvidence, checkDecisionEvidence} from './nextsteps.browser.mjs';
import {checkPortal} from './portal.browser.mjs';
import {checkHosted} from './hosted.browser.mjs';
import {checkBackgroundPolling} from './backgroundPolling.browser.mjs';
import {checkEuropeanMap} from './european-map.browser.mjs';
import {checkLiveCardMap} from './cardMap.browser.mjs';
import {checkLiveCompetitionLifecycle} from './competitionLifecycle.browser.mjs';
import {checkLiveCivilWar} from './eventDecisions.browser.mjs';
import {checkLiveMarkerReduction} from './decisionClarity.browser.mjs';
import {checkBidWithdrawal} from './purchaseClarity.browser.mjs';
import {checkTokenPurchaseRound} from './roundSummaries.browser.mjs';
import {checkParallelPlanning} from './planning.browser.mjs';
import {checkTurnOrder} from './turnOrder.browser.mjs';
import {checkResponsiveLifecycle} from './responsiveLifecycle.browser.mjs';
import {checkMapGeometry} from './mapGeometry.browser.mjs';

// Independently runnable lifecycle portion of the full table gate, using live setup offers.
const game = await createGame(6);
for (const [seat, link] of game.links.entries()) await game.send(link, `bid-capital ${6 - seat}`);
for (let step = 0; step < 200; step++) {
    const state = await game.snapshot(game.links[0]);
    if (state.game.stage === 'EXPANSION') break;
    const actor = game.links.find(link => state.prompt?.players.includes(link.id));
    assert(actor, 'Setup must expose its current actor');
    const current = await game.snapshot(actor);
    const choices = current.legalChoices.commands;
    await game.send(actor, current.game.stage === 'TOKEN_PURCHASE' ? `buy-tokens ${8 + game.links.indexOf(actor)}`
        : choices.includes('stabilize cash') ? 'stabilize cash' : choices.includes('pass') ? 'pass' : choices[0]);
}
const baseline = await game.snapshot(game.links[0]);
assert.equal(baseline.game.stage, 'EXPANSION');
const page = await open('about:blank');
const web = process.env.MAP_WEB_URL;
try {
    await page.send('Page.bringToFront');
    await checkDecisionEvidence(page, web, baseline);
    await checkSetupEvidence(page, web);
    await checkPortal(page, web);
    await checkHosted(page, web, process.env.AOR_BROWSER_ARTIFACTS);
    await checkBackgroundPolling(page, web);
    await checkEuropeanMap(page, web);
    await checkLiveCardMap(page, web);
    await checkLiveCompetitionLifecycle(page, web);
    await checkLiveCivilWar(page, web);
    await checkLiveMarkerReduction(page, web);
    await checkBidWithdrawal(page, web);
    await checkTokenPurchaseRound(page, web);
    await checkParallelPlanning(page, web);
    await checkTurnOrder(page, web);
    await checkResponsiveLifecycle(page, web);
    await checkMapGeometry(page, web);
} finally {
    page.ws.close();
    await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
}
