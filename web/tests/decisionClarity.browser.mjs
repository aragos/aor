import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, waitForMap} from './browser-support.mjs';
import {assertTextAlignment} from './identityAlignment.browser.mjs';

export async function checkDecisionClarity(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'decision clarity harness');
    const viewer = baseline.viewerId;
    const other = baseline.game.players.find(player => player.id !== viewer).id;
    let revision = 45000;
    async function show(changes = {}) {
        const fixture = {...baseline, ...changes, revision: ++revision,
            game: {...baseline.game, ...changes.game}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'clarity fixture');
        await waitForMap(page, fixture);
        return fixture;
    }
    await show({prompt: {messageId: 'prompt.buy-tokens', players: [viewer, other]},
        game: {stage: 'TOKEN_PURCHASE', endingTriggered: true,
            activity: {...baseline.game.activity, actor: other, decisionPlayer: null}}});
    assert.equal(await page.evaluate(`document.querySelector('.interrupt-owner')`), null,
        'Simultaneous purchases have no stray Resumes label');
    assert.equal(await page.evaluate(`document.querySelector('.game-header .ending-status').textContent`), 'Final turn · ');
    assert.equal(await page.evaluate(`document.querySelector('.ending-notice')`), null, 'No duplicate ending banners');
    await page.evaluate(`document.querySelector('#tab-advances').click()`);
    await page.evaluate(`document.querySelector('[data-advance="RENAISSANCE"]').click()`);
    await page.wait(`document.querySelector('.advance-prerequisites')`, 'expanded advance');
    assert.match(await page.evaluate(`document.querySelector('.advance-prerequisites').textContent`), /Master Art/);
    assert.match(await page.evaluate(`document.querySelector('.advance-relief').textContent`), /\$100.*this turn/);

    const card = baseline.game.hand[0];
    await show({prompt: {messageId: 'prompt.buy-card', players: [viewer]},
        legalChoices: {commands: ['pass', `discard-card ${card.reference}`], bidMin: null, bidMax: null},
        game: {stage: 'BUY_CARD', cardOffers: [], activity: {actor: viewer, decisionPlayer: viewer,
            choice: 'buy-card', decisionEntry: revision + 1}}});
    assert.match(await page.evaluate(`document.querySelector('.discard-request').textContent`), /Master Art: select a card in your hand to discard/);
    await page.evaluate(`document.querySelector('[data-hand-card="${card.reference}"]').click()`);
    await page.wait(`document.querySelector('.selected-action-confirmation')`, 'discard confirmation');
    assert.equal(await page.evaluate(`window.mapCommands.length`), 0, 'Selecting a discard never commits');
    await page.evaluate(`document.querySelector('[data-hand-card="${card.reference}"]').click()`);
    await page.wait(`!document.querySelector('.selected-action-confirmation')`, 'click again clears discard');

    await show({prompt: {messageId: 'prompt.cede-war', players: [viewer]},
        legalChoices: {commands: ['cede-war'], bidMin: null, bidMax: null},
        game: {stage: 'PLAY_CARDS', warCession: {winner: other, requiredCount: 0,
            eligibleProvinces: [], overseasCapacity: 0},
            activity: {actor: other, decisionPlayer: viewer, choice: 'cede-war', decisionEntry: revision + 1}}});
    assert.equal(await page.evaluate(`!!document.querySelector('.interrupt-owner')`), true, 'Actual interruptions retain their owner');
    await assertTextAlignment(page, '.war-cession-summary', 'War winner and surrounding text share a baseline');
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-war-winner-alignment.png'), 1280, 900);
    console.log('Passed: advance prerequisites/relief, explicit Master Art discard, toggle cleanup, compact ending status, interruption ownership and War alignment.');
}

export async function checkLiveMarkerReduction(page, web) {
    const game = await createGame(3, {options: {map: 'european'}});
    const actor = game.links[0];
    for (const player of game.links) await game.send(player, player === actor ? 'bid-capital 2' : 'bid-capital 0');

    async function reachExpansion(ready) {
        for (let step = 0; step < 220; step++) {
            const baseline = await game.snapshot(actor);
            const player = game.links.find(candidate => baseline.prompt?.players.includes(candidate.id));
            assert(player, `A live actor at ${baseline.game.stage}`);
            const state = player === actor ? baseline : await game.snapshot(player);
            const me = state.game.players.find(candidate => candidate.id === player.id);
            const isExpansion = state.prompt.messageId === 'prompt.expand';
            if (isExpansion && player === actor && ready(state)) return state;
            // Expand through cheap land routes first, then use ships as the remaining markets require.
            const market = isExpansion && player === actor && state.game.expansionOffers
                .filter(offer => offer.primaryAction === 'PLACE' && offer.establishesMarker && !offer.blockers.length)
                .sort((a, b) => (a.shipping?.required ?? 0) - (b.shipping?.required ?? 0) || a.requiredTokens - b.requiredTokens)[0];
            const choices = state.legalChoices.commands;
            const command = state.game.stage === 'TOKEN_PURCHASE' ? `buy-tokens ${player === actor ? Math.min(me.cash, 36) : 0}`
                : market ? `place ${market.requiredTokens} ${market.destination}`
                : player === actor && choices.includes('choose-capital venice') ? 'choose-capital venice'
                : player === actor && choices.includes('upgrade-ships') && state.game.shippingOffer?.blockers.length === 0 ? 'upgrade-ships'
                : choices.includes('stabilize cash') ? 'stabilize cash'
                : choices.includes('pass') ? 'pass'
                : choices[0];
            assert(command, `An offered fixture command at ${state.game.stage}`);
            await game.send(player, command);
        }
        assert.fail('Could not reach the requested marker supply');
    }

    const state = await reachExpansion(() => true);
    const player = state.game.players.find(player => player.id === actor.id);
    assert(player.boardMarkers < 25, 'The live game still has unused domination markers');
    assert.deepEqual(state.game.markerReductionOffers, []);
    assert.equal(state.legalChoices.commands.some(command => command.startsWith('reduce-marker ')), false);
    await page.send('Page.navigate', {url: web + actor.path});
    await page.wait(`document.querySelector('.pool-summary')`, 'live expansion controls');
    assert.equal(await page.evaluate(`document.querySelector('.marker-reduction')`), null,
        'Voluntary reduction stays hidden while markers remain available');
    const response = await fetch(`${process.env.MAP_API_URL}/api/games/${game.created.gameId}/commands`, {
        method: 'POST', headers: {'Content-Type': 'application/json', Authorization: 'Bearer ' + actor.token},
        body: JSON.stringify({expectedRevision: state.revision, command: `reduce-marker ${player.capital}`}),
    });
    assert.equal(response.status, 422, 'The server rejects an unoffered voluntary reduction');
    assert.equal((await response.json()).code, 'MARKERS_STILL_AVAILABLE');
    const after = await game.snapshot(actor);
    assert.equal(after.revision, state.revision, 'Rejection does not append history');
    assert.deepEqual(after.game.board, state.game.board, 'Rejection leaves all pieces in place');

    const exhausted = await reachExpansion(snapshot => snapshot.game.markerReductionOffers.length > 0);
    const offer = exhausted.game.markerReductionOffers[0];
    assert.equal(exhausted.game.players.find(player => player.id === actor.id).stockMarkers, 0);
    await page.wait(`document.querySelector('.marker-reduction-toggle')`, 'Live exhaustion notice and reduction action');
    assert.match(await page.evaluate(`document.querySelector('.marker-reduction').textContent`), /All 25 domination markers are in play/);
    assert.equal(await page.evaluate(`document.querySelector('#marker-reduction-province')`), null, 'Review starts collapsed');
    for (const [width, height, mobile] of [[1440, 980, false], [390, 844, true]]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        await page.click('Reduce a marker');
        assert.equal(await page.evaluate(`document.activeElement.id`), 'marker-reduction-province');
        await page.fill('#marker-reduction-province', offer.command);
        await page.wait(`document.querySelector('.reduce-marker-action:not([disabled])')`, 'Chosen marker can be reviewed');
        assert.equal((await game.snapshot(actor)).revision, exhausted.revision, 'Choosing a market is read-only');
        assert.match(await page.evaluate(`document.querySelector('.reduction-preview').textContent`), /commodity control/);
        assert((await page.evaluate(`document.querySelector('.reduction-preview').textContent`)).includes(`$${offer.incomeBefore} → $${offer.incomeAfter}`));
        await page.wait(`(() => {
            const panel = document.querySelector('.decision-scroll').getBoundingClientRect();
            const preview = document.querySelector('.reduction-preview').getBoundingClientRect();
            const confirm = document.querySelector('.reduce-marker-action').getBoundingClientRect();
            return preview.top >= panel.top && preview.bottom <= panel.bottom + 1 && confirm.bottom <= innerHeight;
        })()`, 'Reduction consequences and confirmation are both visible');
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, mobile ? 'marker-reduction-phone.png' : 'marker-reduction.png'));
        if (!mobile) {
            await page.click('Cancel');
            assert.equal(await page.evaluate(`document.activeElement.textContent`), 'Reduce a marker');
        }
    }
    await page.evaluate(`document.querySelector('.reduce-marker-action').click()`);
    await page.wait(`!document.querySelector('.marker-reduction') && !document.querySelector('.reduce-marker-action')`,
        'Successful reduction clears the exhausted state and the review');
    const reduced = await game.snapshot(actor);
    assert.equal(reduced.revision, exhausted.revision + 1);
    assert.equal(reduced.game.players.find(player => player.id === actor.id).stockMarkers, 1);
    assert.equal(reduced.game.board.find(province => province.id === offer.province).marker, null);
    console.log('Passed: live marker reduction is unavailable below 25 markers, then offers an explicit review and returns a marker on confirmation.');
}
