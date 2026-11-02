import assert from 'node:assert/strict';
import {createGame, open} from './browser-support.mjs';

/** App recovery uses deterministic transport; service tests prove the durable private receipts. */
export async function checkRequestRecovery(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/ui-harness.html'});
    await page.wait(`typeof window.setUiSnapshot === 'function'`, 'request recovery harness');
    const fixture = {...baseline, pendingBid: null, submissionVersion: null, submissionReceipts: [], submitted: [],
        prompt: {messageId: 'prompt.bid-capital', players: baseline.game.players.map(player => player.id)},
        legalChoices: {commands: ['bid-capital'], bidMin: 0, bidMax: 40},
        game: {...baseline.game, stage: 'BIDDING', activity: null, expansionOffers: [], cardOffers: [],
            players: baseline.game.players.map(player => ({...player, misery: 0}))}};
    await page.evaluate(`window.setUiSnapshot(${JSON.stringify(fixture)})`);
    await page.wait(`document.querySelector('#bid')`, 'editable private bid');
    await page.fill('#bid', '7');
    await page.evaluate(`window.uiNextError = 'OUTCOME_UNKNOWN'; document.querySelector('#bid').form.requestSubmit()`);
    await page.wait(`document.querySelector('.decision-panel [role=alert]')?.textContent.includes('response was lost') && !document.querySelector('.bid-action').disabled`, 'uncertain write releases the form');
    assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7');
    const reads = await page.evaluate('window.uiRequests');
    await page.wait(`window.uiRequests > ${reads}`, 'reads resume after the unknown write');
    assert.equal(await page.evaluate('window.uiCommands.length'), 1, 'Recovery never replays the mutation');
    await page.evaluate(`window.setUiSnapshot({...window.uiSnapshot, pendingBid: 7,
        submitted: [window.uiSnapshot.viewerId], submissionVersion: 'new-private-version',
        submissionReceipts: [window.uiCommands[0].operationId]})`);
    await page.wait(`document.querySelector('.decision-panel .success')?.textContent.includes('request completed')`, 'late private receipt confirms the original request');
    assert.match(await page.evaluate(`document.querySelector('.private-draft-status').textContent`), /Saved privately: 7/);
    await page.fill('#bid', '9');
    assert.match(await page.evaluate(`document.querySelector('.private-draft-status').textContent`), /Not submitted: 9/);
    assert.equal(await page.evaluate('window.uiCommands.length'), 1);
    await checkAccessRecovery(page);
    console.log('Passed: unknown write recovery, resumed reads, no automatic replay, late receipt reconciliation and saved-versus-unsaved private feedback.');
}

/** Losing authorization discards the conditional snapshot; ordinary connection errors retain drafts. */
async function checkAccessRecovery(page) {
    await page.evaluate(`(() => {
        window.accessOriginalFetch = window.fetch;
        window.accessConditionalReads = 0;
        window.fetch = async (input, init) => {
            const response = await window.accessOriginalFetch(input, init);
            if (!String(input).startsWith('/api/games/') || init?.method === 'POST') return response;
            if (window.accessReadError) {
                const code = window.accessReadError;
                delete window.accessReadError;
                return new Response(JSON.stringify({code}), {status: code === 'UNAUTHORIZED' ? 401 : 403});
            }
            const etag = '"same-authorized-view"';
            if (new Headers(init?.headers).get('If-None-Match') === etag) {
                window.accessConditionalReads++;
                return new Response(null, {status: 304, headers: {ETag: etag}});
            }
            return new Response(await response.text(), {headers: {ETag: etag}});
        };
        window.dispatchEvent(new Event('online'));
    })()`);
    try {
        await page.wait(`window.accessConditionalReads > 0`, 'authorized snapshot has a cached ETag');
        const readyIcon = await page.evaluate(`document.querySelector('link[rel=icon]').href`);
        await page.fill('#bid', '11');
        await page.evaluate(`window.accessReadError = 'NETWORK'; window.dispatchEvent(new Event('online'))`);
        await page.wait(`document.querySelector('.connection-error')`, 'transient read error retains the game');
        assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '11', 'Connection errors preserve the private draft');
        assert.equal(await page.evaluate(`document.querySelector('link[rel=icon]').href`), readyIcon);

        for (const phase of ['poll', 'mutation', 'stale-refresh', 'unknown-refresh']) {
            for (const code of ['UNAUTHORIZED', 'GAME_MEMBERSHIP_REQUIRED']) {
                const commands = await page.evaluate('window.uiCommands.length');
                if (phase === 'poll') {
                    await page.evaluate(`window.accessReadError = ${JSON.stringify(code)}; window.dispatchEvent(new Event('online'))`);
                } else {
                    await page.fill('#bid', '13');
                    const writeError = phase === 'mutation' ? code : phase === 'stale-refresh' ? 'STALE_REVISION' : 'OUTCOME_UNKNOWN';
                    await page.evaluate(`window.uiNextError = ${JSON.stringify(writeError)};
                        ${phase === 'mutation' ? '' : `window.accessReadError = ${JSON.stringify(code)};`}
                        document.querySelector('#bid').form.requestSubmit()`);
                }
                const message = code === 'UNAUTHORIZED' ? 'This player link is missing or invalid.' : 'Your account is not a participant in this game.';
                await page.wait(`!document.querySelector('#bid') && document.querySelector('main > [role=alert]')?.textContent === ${JSON.stringify(message)}`, `${phase} ${code} removes the authorized game view and shows its diagnostic`);
                assert.notEqual(await page.evaluate(`document.querySelector('link[rel=icon]').href`), readyIcon, 'An inaccessible game cannot advertise an available action');

                await page.evaluate(`window.dispatchEvent(new Event('online'))`);
                await page.wait(`document.querySelector('#bid')`, 'restored access fetches the unchanged snapshot unconditionally');
                assert.equal(await page.evaluate(`document.querySelector('link[rel=icon]').href`), readyIcon);
                const conditionalReads = await page.evaluate('window.accessConditionalReads');
                await page.evaluate(`window.dispatchEvent(new Event('online'))`);
                await page.wait(`window.accessConditionalReads > ${conditionalReads}`, 'restored snapshot resumes conditional reads');
                assert.equal(await page.evaluate('window.uiCommands.length'), commands + (phase === 'poll' ? 0 : 1), 'Access recovery never replays the mutation');
            }
        }
    } finally {await page.evaluate(`window.fetch = window.accessOriginalFetch`);}
    console.log('Passed: poll/write/recovery access loss clears cached ETags and the action favicon with a visible diagnostic, unchanged authorized views recover without replay, and transient read errors preserve drafts.');
}

if (process.argv[1]?.endsWith('/requestRecovery.browser.mjs')) {
    const game = await createGame(3);
    const page = await open('about:blank');
    try {await checkRequestRecovery(page, process.env.MAP_WEB_URL, await game.snapshot(game.links[0]));}
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
