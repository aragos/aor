import assert from 'node:assert/strict';

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
    await page.wait(`document.querySelector('.decision-panel [role=alert]')?.textContent.includes('response was lost') && !document.querySelector('#bid').form.querySelector('button').disabled`, 'uncertain write releases the form');
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
    assert.match(await page.evaluate(`document.querySelector('.private-draft-status').textContent`), /Unsaved draft: 9/);
    assert.equal(await page.evaluate('window.uiCommands.length'), 1);
    console.log('Passed: unknown write recovery, resumed reads, no automatic replay, late receipt reconciliation and saved-versus-unsaved private feedback.');
}
