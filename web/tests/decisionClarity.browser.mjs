import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame} from './browser-support.mjs';

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
    const alignment = await page.evaluate(`(() => {
        const row = document.querySelector('.war-cession-summary');
        const name = row.querySelector('.identity-label').getBoundingClientRect();
        const text = row.querySelector('span').getBoundingClientRect();
        return Math.abs((name.top + name.height / 2) - (text.top + text.height / 2));
    })()`);
    assert(alignment < 2, 'War winner symbol and surrounding text share a vertical center');
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-war-winner-alignment.png'), 1280, 900);
    console.log('Passed: advance prerequisites/relief, explicit Master Art discard, toggle cleanup, compact ending status, interruption ownership and War alignment.');
}

export async function checkLiveMarkerReduction(page, web) {
    const game = await createGame(3);
    for (const player of game.links) await game.send(player, 'bid-capital 0');
    let state;
    let actor;
    for (let step = 0; step < 100; step++) {
        state = await game.snapshot(game.links[0]);
        actor = game.links.find(player => state.prompt?.players.includes(player.id));
        assert(actor, `A real actor at ${state.game.stage}`);
        state = await game.snapshot(actor);
        if (state.game.stage === 'EXPANSION') break;
        const command = state.game.stage === 'TOKEN_PURCHASE' ? 'buy-tokens 5'
            : state.legalChoices.commands.find(command => command.startsWith('choose-capital '))
                ?? state.legalChoices.commands.find(command => command.startsWith('choose-turn-position '))
                ?? state.legalChoices.commands.find(command => command === 'pass')
                ?? state.legalChoices.commands.find(command => command === 'stabilize cash');
        assert(command, `A setup command at ${state.game.stage}`);
        await game.send(actor, command);
    }
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
    console.log('Passed: live marker reduction is absent below 25 deployed markers; direct requests are rejected without changing board or history.');
}
