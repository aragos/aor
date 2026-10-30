import {join} from 'node:path';
import assert from 'node:assert/strict';

export async function checkReferenceUx(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'reference UX harness');
    const viewer = baseline.viewerId;
    const other = baseline.game.players.find(player => player.id !== viewer);
    let revision = 16000;
    const event = {...baseline.events.at(-1), id:'immutable-activity', kind:'PLAY_CARD', actor:viewer,
        target:null, card:'the-crusades', provinces:[], dice:[], amount:null, detail:null,
        war:null, competition:null, cashChanges:[], miseryChanges:[{player:viewer,spaces:2,fromValue:20,toValue:40}], drawReason:null, leaderRebate:null,
        protectionCash:null, marketRoll:null, participants:[{id:viewer,name:'Original player',capital:null}]};
    async function show(changes = {}) {
        const snapshot = {...baseline, events:[event], ...changes, revision:++revision,
            game:{...baseline.game, effects:{...baseline.game.effects,military:[]},...changes.game}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(snapshot)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'reference fixture');
    }
    await show();
    assert.equal(await page.evaluate(`document.querySelector('.game-header .commands-toggle')`),null);
    assert.equal(await page.evaluate(`document.querySelector('.military-summary')`),null);
    assert.equal(await page.evaluate(`document.querySelector('.commodity-legend, .track-reference')`),null);
    const entry = await page.evaluate(`document.querySelector('[data-event-id="immutable-activity"] .activity-content').innerHTML`);
    assert.equal(await page.evaluate(`document.querySelectorAll('.table-activity .event-card').length`),1,'The newest card is rendered once');
    assert.equal(await page.evaluate(`document.querySelector('.event-card').tagName`),'BUTTON');
    const gameLocation = await page.evaluate('location.href');
    await page.evaluate(`document.querySelector('.event-card').click()`);
    await page.wait(`document.querySelector('.inspection-panel')?.textContent.includes('The Crusades')`, 'Activity card opens inside the game');
    assert.equal(await page.evaluate('location.href'),gameLocation,'Inspecting activity does not navigate to Help');
    await page.evaluate(`document.querySelector('.inspection-panel .close-detail').click()`);
    assert.match(await page.evaluate(`document.querySelector('.event-misery-change').textContent`), /\+2 misery.*20 → 40/);
    await show({game:{players:baseline.game.players.map(player => player.id === viewer ? {...player,name:'Changed current name',capital:'venice',misery:1000} : player)}});
    assert.equal(await page.evaluate(`document.querySelector('[data-event-id="immutable-activity"] .activity-content').innerHTML`),entry,'Later identity changes cannot rewrite an activity entry');
    await show({game:{effects:{...baseline.game.effects,military:[{player:other.id,event:'ARMOR'}]}}});
    assert.match(await page.evaluate(`document.querySelector('.military-summary').textContent`),/Armor/);
    await show({events:Array.from({length:100},(_,index)=>({...event,id:`long-${index}`}))});
    assert.equal(await page.evaluate(`document.querySelector('.table-activity').getBoundingClientRect().height <= 416`),true,'Activity has a bounded height');
    assert.equal(await page.evaluate(`document.querySelector('.activity-toggle').textContent.trim()`),'','Activity control is icon only');
    assert.equal(await page.evaluate(`document.querySelector('.activity-toggle').getAttribute('aria-label')`),'Hide activity');
    assert.equal(await page.evaluate(`document.querySelector('.activity-list').scrollHeight > document.querySelector('.activity-list').clientHeight`),true,'All entries remain reachable through scrolling');
    assert.equal(await page.evaluate(`document.querySelectorAll('.activity-list [data-event-id]').length`),100);

    const readyIcon = await page.evaluate(`document.querySelector('link[rel=icon]').href`);
    await show({prompt:{messageId:'prompt.expand',players:[other.id]}});
    await page.wait(`document.querySelector('link[rel=icon]')?.href !== ${JSON.stringify(readyIcon)}`, 'Waiting game favicon');
    const grayscale = await page.evaluate(`fetch(document.querySelector('link[rel=icon]').href).then(response=>response.text())`);
    assert(grayscale.includes('#777') && grayscale.includes('#ddd'), 'Waiting favicon is grayscale');
    await show({prompt:{messageId:'prompt.expand',players:[viewer]}});
    await page.wait(`document.querySelector('link[rel=icon]')?.href === ${JSON.stringify(readyIcon)}`, 'Actionable game favicon');
    const colorful = await page.evaluate(`fetch(document.querySelector('link[rel=icon]').href).then(response=>response.text())`);
    assert(colorful.includes('#205c4b') && colorful.includes('#f5ce62'),'Turn favicon uses the colorful shield and money bag');

    await page.send('Page.navigate', {url:web+'/help#commodity-stone'});
    await page.wait(`document.querySelector('#commodity-stone .payout-box')`, 'Public card reference loads without a player token');
    assert.equal(await page.evaluate(`document.querySelector('.reference-page .card-event-text, .reference-page .advance-benefits, #commodity-cards')`), null, 'Help omits general game rules');
    assert.equal(await page.evaluate(`document.title`),'AoR · Help');
    assert.equal(await page.evaluate(`document.querySelectorAll('#commodities .payout-box').length`),120);
    assert.equal(await page.evaluate(`document.querySelectorAll('#advances article').length`),26);
    assert.equal(await page.evaluate(`document.querySelectorAll('#map-legend svg').length > 10`),true);
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS,'aor-help-desktop.png'),1280,900);
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS,'aor-help-phone.png'),390,844,true);
    assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`),true,'Reference fits a phone');
    await page.send('Page.navigate', {url:web+'/'});
    await page.wait(`document.querySelector('#variant-map')`, 'Variant help links');
    assert.equal(await page.evaluate(`document.querySelector('#variant-map button:first-child').textContent`),'Standard map');
    assert.equal(await page.evaluate(`document.querySelector('#variant-label-map a').getAttribute('href')`),'/help#variant-map');
    console.log('Passed: immutable and unique bounded activity, clickable cards, turn favicons, conditional military summary, and public contextual reference.');
}
