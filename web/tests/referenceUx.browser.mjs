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
    await page.evaluate(`document.querySelector('#tab-activity').click()`);
    assert.equal(await page.evaluate(`document.querySelector('.game-header .commands-toggle')`),null);
    assert.equal(await page.evaluate(`document.querySelector('.military-summary')`),null);
    assert.equal(await page.evaluate(`document.querySelector('.commodity-legend, .track-reference')`),null);
    const entry = await page.evaluate(`document.querySelector('[data-event-id="immutable-activity"] .activity-content').innerHTML`);
    assert.equal(await page.evaluate(`document.querySelectorAll('.table-activity .event-card').length`),1,'The newest card is rendered once');
    assert.equal(await page.evaluate(`document.querySelector('.event-card').tagName`),'BUTTON');
    const gameLocation = await page.evaluate('location.href');
    await page.evaluate(`document.querySelector('.event-card').click()`);
    await page.wait(`document.querySelector('.card-inspection-dialog[open]')?.textContent.includes('The Crusades')`, 'Activity card opens inside the game');
    assert.equal(await page.evaluate('location.href'),gameLocation,'Inspecting activity does not navigate to Help');
    await page.evaluate(`document.querySelector('.card-inspection-dialog .close-detail').click()`);
    assert.match(await page.evaluate(`document.querySelector('.event-misery-change').textContent`), /\+2 misery.*20 → 40/);
    await show({game:{players:baseline.game.players.map(player => player.id === viewer ? {...player,name:'Changed current name',capital:'venice',misery:1000} : player)}});
    assert.equal(await page.evaluate(`document.querySelector('[data-event-id="immutable-activity"] .activity-content').innerHTML`),entry,'Later identity changes cannot rewrite an activity entry');
    await show({game:{effects:{...baseline.game.effects,military:[{player:other.id,event:'ARMOR'}]}}});
    assert.match(await page.evaluate(`document.querySelector('.military-summary').textContent`),/Armor/);
    await show({events:Array.from({length:100},(_,index)=>({...event,id:`long-${index}`}))});
    const logBounds = await page.evaluate(`(() => {
        const bounds = selector => {
            const rect = document.querySelector(selector).getBoundingClientRect();
            return {left:rect.left,right:rect.right,top:rect.top,bottom:rect.bottom,height:rect.height};
        };
        return {log:bounds('.table-activity'),tabs:bounds('.workspace-tabs'),hand:bounds('.table-hand')};
    })()`);
    assert(logBounds.log.height > 0 && logBounds.log.top >= logBounds.tabs.bottom - 1 &&
        logBounds.log.bottom <= logBounds.hand.top + 1 &&
        Math.abs(logBounds.log.left - logBounds.hand.left) < 1 &&
        Math.abs(logBounds.log.right - logBounds.hand.right) < 1,
    'Activity fits between workspace tabs and the aligned hand: ' + JSON.stringify(logBounds));
    assert.equal(await page.evaluate(`document.querySelector('.activity-toggle, .activity-resizer')`),null,'Activity has no sidebar split controls');
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

    const gameLocationBeforeHelp = await page.evaluate('location.href');
    assert.equal(await page.evaluate(`document.querySelector('.game-header .help-link')`), null);
    await page.evaluate(`document.querySelector('#tab-markets').click(); document.querySelector('.market-tile .help-link').click()`);
    await page.wait(`document.querySelector('.reference-popup .payout-box')`, 'Commodity reference from the main market box');
    await page.evaluate(`document.querySelector('.reference-popup > .reference-link').click()`);
    await page.wait(`document.querySelector('.reference-popup nav')`, 'Help topics popup');
    const choose = async name => {
        await page.evaluate(`[...document.querySelectorAll('.reference-popup nav button')].find(button => button.textContent === ${JSON.stringify(name)}).click()`);
    };
    await choose('Payout boxes');
    await page.wait(`document.querySelector('.reference-popup .commodity-name')`, 'Public commodity catalog');
    assert.equal(await page.evaluate(`document.querySelectorAll('.reference-popup nav button').length`), 12);
    await choose('Stone');
    await page.wait(`document.querySelector('#commodity-stone .payout-box')`, 'Requested payout popup');
    assert.equal(await page.evaluate(`document.querySelectorAll('.reference-popup .payout-box').length`), 10);
    assert.equal(await page.evaluate(`document.querySelector('.reference-popup .card-event-text, .reference-popup .advance-benefits, #advances, #card-charlemagne')`), null, 'Reference omits duplicated card and advance catalogs');
    assert.equal(await page.evaluate('location.href'), gameLocationBeforeHelp, 'Reference stays inside the game');
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS,'aor-help-popup-desktop.png'),1280,900);
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS,'aor-help-popup-phone.png'),390,844,true);
    assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`),true,'Popup fits a phone');
    for (const [name, selector] of [['Map key', '#map-legend .reference-legend'],
        ['Misery track', '#misery-track .reference-track'], ['Shipping', '#shipping-track .reference-track'],
        ['Undo policies', '#undo-friendly-game'], ['Variants', '#variant-map']]) {
        await page.evaluate(`document.querySelector('.reference-popup > .reference-link').click()`);
        await choose(name);
        await page.wait(`document.querySelector(${JSON.stringify(selector)})`, name + ' popup');
    }
    await page.evaluate(`document.querySelector('.reference-dialog .close-detail').click()`);
    await page.send('Page.navigate', {url:web+'/'});
    await page.wait(`document.querySelector('#variant-map')`, 'Variant help controls');
    assert.equal(await page.evaluate(`document.querySelector('#variant-map button:first-child').textContent`),'Standard map');
    assert.equal(await page.evaluate(`document.querySelector('#variant-label-map [data-help-topic="variant-map"]').getAttribute('aria-haspopup')`),'dialog');
    const namesBefore = await page.evaluate(`document.querySelector('textarea').value`);
    await page.evaluate(`document.querySelector('#variant-label-map [data-help-topic="variant-map"]').click()`);
    await page.wait(`document.querySelector('.reference-popup #variant-map')`, 'Public variant popup without a game');
    assert.equal(await page.evaluate(`document.querySelectorAll('.reference-popup #variants article').length`), 1, 'Only the requested variant is shown');
    await page.evaluate(`document.querySelector('.reference-dialog .close-detail').click()`);
    assert.equal(await page.evaluate(`document.querySelector('textarea').value`), namesBefore, 'Variant help preserves creation draft');
    console.log('Passed: immutable and unique bounded activity, clickable cards, turn favicons, conditional military summary, and public contextual reference.');
}
