import assert from 'node:assert/strict';
import {open, createGame} from './browser-support.mjs';

async function advance(page, milliseconds) {
    let listener;
    let timeout;
    const completed = new Promise((resolve, reject) => {
        listener = event => {
            if (JSON.parse(event.data).method === 'Emulation.virtualTimeBudgetExpired') resolve();
        };
        page.ws.addEventListener('message', listener);
        timeout = setTimeout(() => reject(new Error('Background virtual-time budget did not complete')), 10000);
    });
    try {
        await page.send('Emulation.setVirtualTimePolicy', {policy: 'pauseIfNetworkFetchesPending', budget: milliseconds});
        await completed;
    } finally {clearTimeout(timeout); page.ws.removeEventListener('message', listener);}
}

/** Real server offers and real hidden-document state; virtual time avoids a minute-long sleep. */
export async function checkBackgroundPolling(front, web) {
    const game = await createGame(3);
    const player = game.links[0];
    const page = await open(web + player.path);
    const responses = [];
    const record = event => {
        const message = JSON.parse(event.data);
        if (message.method === 'Network.responseReceived' &&
            message.params.response.url.endsWith(`/api/games/${game.created.gameId}`)) {
            responses.push(message.params.response.status);
        }
    };
    page.ws.addEventListener('message', record);
    try {
        await page.send('Network.enable');
        await page.send('Page.bringToFront');
        await page.wait(`document.querySelector('#bid')`, 'Live game capital bid');
        const initial = await game.snapshot(player);
        const ready = await page.evaluate(`document.querySelector('link[rel=icon]').href`);
        await page.fill('#bid', '3');
        await page.click('Submit bid');
        await page.wait(`document.querySelector('link[rel=icon]').href !== ${JSON.stringify(ready)}`, 'Submitting a private bid clears the turn favicon');
        const waiting = await page.evaluate(`document.querySelector('link[rel=icon]').href`);

        await front.send('Page.bringToFront');
        await page.wait(`document.hidden`, 'Game tab is actually hidden');
        await page.evaluate(`window.backgroundFrames=0; requestAnimationFrame(()=>window.backgroundFrames++)`);
        await advance(page, 1100);
        assert.equal(await page.evaluate(`document.querySelector('link[rel=icon]').href`), waiting);

        await game.send(game.links[1], 'bid-capital 2');
        await game.send(game.links[2], 'bid-capital 1');
        await advance(page, 5500);

        assert.equal(await page.evaluate(`document.hidden`), true, 'No activation is needed to refresh');
        assert.equal(await page.evaluate(`document.querySelector('link[rel=icon]').href`), ready, 'A hidden tab becomes colorful when another player makes it our turn');
        assert.equal(await page.evaluate(`window.backgroundFrames`), 0, 'The favicon refresh does not require an animation frame');

        const current = await game.snapshot(player);
        assert(current.prompt.players.includes(player.id), 'The live server offers this player a capital');
        await game.send(player, current.legalChoices.commands[0]);
        await advance(page, 5500);

        assert.equal(await page.evaluate(`document.querySelector('link[rel=icon]').href`), waiting, 'A turn handled in another session clears the hidden tab favicon');
        await advance(page, 125000);
        assert(responses.includes(304), 'Unchanged hidden reads retain the view using conditional requests');
        const idleReads = responses.length;
        await advance(page, 5000);
        assert.equal(responses.length, idleReads, 'Unchanged 304 reads do not prolong recent activity');
        await advance(page, 30000);
        assert.equal(responses.length, idleReads + 1, 'Quiet hidden games use thirty-second checks');

        const beforeChange = await game.snapshot(player);
        const actor = game.links.find(link => beforeChange.prompt.players.includes(link.id));
        const acting = await game.snapshot(actor);
        await game.send(actor, acting.legalChoices.commands[0]);
        await advance(page, 31000);
        const recentReads = responses.length;
        await advance(page, 5500);
        assert(responses.length > recentReads, 'Accepting another player’s activity restores five-second checks');

        await checkUnconditionalPolling(front, web, initial);
        console.log('Passed: hidden games refresh within five seconds of recent activity, idle at thirty seconds, and retain favicon/cancellation behavior without activation or animation frames.');
    } finally {
        page.ws.removeEventListener('message', record);
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}

/** Duplicate 200 responses also stay idle; successful mutations renew activity independently. */
async function checkUnconditionalPolling(front, web, initial) {
    const page = await open(web + '/tests/ui-harness.html');
    try {
        await page.send('Page.bringToFront');
        await page.wait(`typeof window.setUiSnapshot === 'function'`, 'Unconditional polling harness');
        await page.evaluate(`window.setUiSnapshot(${JSON.stringify(initial)})`);
        await page.wait(`document.querySelector('#bid')`, 'Initial game snapshot accepted');
        await front.send('Page.bringToFront');
        await page.wait(`document.hidden`, 'Unconditional game tab is actually hidden');
        await advance(page, 125000);
        const idleReads = await page.evaluate(`window.uiRequests`);

        await advance(page, 5000);

        assert.equal(await page.evaluate(`window.uiRequests`), idleReads, 'Identical 200 snapshots do not prolong recent activity');
        await advance(page, 30000);
        assert.equal(await page.evaluate(`window.uiRequests`), idleReads + 1);

        await page.fill('#bid', '7');
        await page.click('Submit bid');
        await page.wait(`window.uiCommands.length === 1`, 'Successful unchanged mutation');
        await advance(page, 31000);
        const mutationReads = await page.evaluate(`window.uiRequests`);

        await advance(page, 5500);

        assert(await page.evaluate(`window.uiRequests > ${mutationReads}`), 'A successful mutation renews fast background checks even if its response is unchanged');
        await advance(page, 125000);
        await page.evaluate(`window.uiSnapshot = {...window.uiSnapshot, pendingBid: 7}`);
        await advance(page, 31000);
        const changedReads = await page.evaluate(`window.uiRequests`);

        await advance(page, 5500);

        assert(await page.evaluate(`window.uiRequests > ${changedReads}`), 'Accepted private readiness changes renew activity without a committed revision change');
    } finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}

if (process.env.AOR_BROWSER_SUITE === 'backgroundPolling.browser.mjs') {
    const front = await open('about:blank');
    try {await checkBackgroundPolling(front, process.env.MAP_WEB_URL);}
    finally {front.ws.close(); await fetch(`${process.env.MAP_CDP_URL}/json/close/${front.target.id}`);}
}
