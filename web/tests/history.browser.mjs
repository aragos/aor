import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';

const game = await createGame(3, {undoPolicy:'friendly-turn'});
await game.send(game.links[0], 'start');
for (const [index, link] of game.links.entries()) await game.send(link, `bid-capital ${3-index}`);
for (let step=0; step<100; step++) {
    const state=await game.snapshot(game.links[0]);
    if (state.game.stage==='EXPANSION') break;
    const actor=game.links.find(link=>state.prompt?.players.includes(link.id));
    assert(actor,'Fixture must expose an actor');
    const current=await game.snapshot(actor);
    const choices=current.legalChoices.commands;
    const command=current.game.stage==='TOKEN_PURCHASE' ? `buy-tokens ${8+game.links.indexOf(actor)}`
        : choices.includes('upgrade-ships') ? 'upgrade-ships'
        : choices.includes('stabilize cash') ? 'stabilize cash'
        : choices.includes('pass') ? 'pass' : choices[0];
    await game.send(actor, command);
}
const before=await game.snapshot(game.links[0]);
const actor=game.links.find(link=>link.id===before.game.activity.actor);
const oneToken=state=>state.game.expansionOffers.filter(offer=>offer.primaryAction==='PLACE' &&
    ((offer.requiredTokens===1 && !offer.blockers.length) || offer.partialPlacements.some(choice=>choice.tokens===1)));
const target=oneToken(before)[0].destination;
await game.send(actor, `place 1 ${target}`);
const page=await open(process.env.MAP_WEB_URL+actor.path);
try {
    await page.wait(`document.querySelector('.undo-action:not([disabled])')`,'live undo available');
    await page.evaluate(`document.querySelector('.commands-toggle').click()`);
    const player=before.game.players.find(player=>player.id===actor.id);
    const capital=player.capital.replace(/\b\w/g,letter=>letter.toUpperCase());
    assert((await page.evaluate(`document.querySelector('.command-dock pre').textContent`)).includes(capital+': place 1 '+target));
    await page.click('Display names');
    assert((await page.evaluate(`document.querySelector('.command-dock pre').textContent`)).includes(player.name+': place 1 '+target));
    await page.reload();
    await page.wait(`document.querySelector('#history-identifiers button[aria-pressed=true]')?.textContent==='Display names'`, 'history name preference survives reload');
    await page.evaluate(`document.querySelector('.commands-toggle').click()`);
    await page.click('Canonical IDs');
    assert((await page.evaluate(`document.querySelector('.command-dock pre').textContent`)).includes(actor.id+': place 1 '+target));
    await page.evaluate(`document.querySelector('.commands-toggle').click()`);
    await page.click('Undo last action');
    await page.wait(`document.querySelector('.command-dock pre').textContent.includes('undo ')`,'audited undo');
    assert.equal((await game.snapshot(actor)).game.board.some(province=>province.id===target),false);
    assert.equal(await page.evaluate(`document.querySelector('.province-inspector')`),null,'Undo clears old map context');
    const placed=await game.send(actor,`place 1 ${target}`);
    await page.wait(`document.querySelector('.command-dock summary small').textContent.endsWith('${placed.revision}')`,'placement polling');
    const alternative=oneToken(placed).find(offer=>offer.destination!==target).destination;
    const from=placed.undo.actions.at(-1).entry;
    await page.evaluate(`document.querySelector('.commands-toggle').click(); document.querySelector('.history-editor').open=true`);
    await page.fill('#history-from',String(from));
    await page.click('Load current actions');
    assert.equal(await page.evaluate(`document.querySelector('#history-suffix').value`),'@'+from);
    await page.fill('#history-suffix',`place 1 ${alternative}`);
    await page.click('Apply history edit');
    await page.wait(`document.querySelector('.command-dock pre').textContent.includes('edit-history ')`,'history edit committed');
    const edited=await game.snapshot(actor);
    assert.equal(edited.game.board.some(province=>province.id===target),false);
    assert(edited.game.board.some(province=>province.id===alternative));
    assert.equal(edited.revision,placed.revision+1);
    await page.fill('#history-from',String(edited.undo.actions.at(-1).entry));
    await page.click('Load current actions');
    const extra=oneToken(edited).find(offer=>offer.destination!==alternative).destination;
    const newer=await game.send(actor,`place 1 ${extra}`);
    await page.wait(`document.querySelector('.history-editor .blocker')`,'history editor detects newer revision');
    assert.equal(await page.evaluate(`document.querySelector('.history-editor form button').disabled`),true);
    assert.equal((await game.snapshot(actor)).revision,newer.revision,'Stale edit draft cannot auto-submit');
    if (process.env.AOR_BROWSER_ARTIFACTS) await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS,'history-editor.png'),1280);
    console.log('Passed: live undo, immutable edit audit, suffix editor, retained references, polling and stale-draft protection.');
} finally {
    page.ws.close();
    await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
}
