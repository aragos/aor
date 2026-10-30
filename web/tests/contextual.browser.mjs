import assert from 'node:assert/strict';

export async function checkContextualUx(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'contextual UX harness');
    let fixture = structuredClone(baseline);
    let revision = fixture.revision;
    async function show(changes = {}) {
        fixture = {...fixture, ...changes, revision: ++revision, game: {...fixture.game, ...changes.game}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'contextual fixture');
    }
    const viewer = baseline.viewerId;
    const other = baseline.game.players.find(player => player.id !== viewer);
    const click = selector => page.evaluate(`(() => {const element=document.querySelector(${JSON.stringify(selector)}); if (element instanceof SVGElement) element.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true})); else element.click();})()`);
    await show();
    assert.equal(await page.evaluate(`document.querySelectorAll('.player-pool').length`), baseline.game.players.length);
    for (const stage of ['CONFIGURATION', 'TOKEN_PURCHASE', 'INCOME', 'FINAL_CARD_PLAY', 'FINISHED']) {
        await show({game:{stage,players:baseline.game.players.map(player=>({...player,poolAvailable:false}))}});
        assert.equal(await page.evaluate(`document.querySelectorAll('.player-pool, .player-pool-detail, .pool-summary').length`), 0, `${stage} has no expansion pools in summaries or details`);
    }
    await show({...baseline});
    assert.equal(await page.evaluate(`document.querySelector('.player-summary.is-viewer .player-person').textContent`), baseline.game.players.find(player => player.id === viewer).name);
    const ownPool = baseline.game.players.find(player => player.id === viewer).poolTokens;
    assert.equal(await page.evaluate(`document.querySelector('.pool-summary [data-pool-current]').textContent`), String(ownPool));
    assert.equal(await page.evaluate(`document.querySelector('.pool-summary > .identity-label').textContent`), await page.evaluate(`document.querySelector('.player-summary.is-viewer .player-identity strong').textContent`));
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-capital-market]').length`), 6, 'Every capital uses a market badge');
    assert.equal(await page.evaluate(`[...document.querySelectorAll('[data-capital-market]')].every(badge=>badge.querySelector(':scope > rect').getAttribute('fill')==='#263c3c' && badge.querySelector(':scope > text').getAttribute('fill')==='#fffdf5' && badge.querySelector(':scope > g'))`), true, 'Capital background includes both market number and commodity');
    assert.equal(await page.evaluate(`document.querySelectorAll('.province-label > path').length`), 0, 'Capital crowns no longer extend outside market labels');
    assert.equal(await page.evaluate(`!!document.querySelector('[data-area-lock="V"]')`), true, 'Area V owns the shared Overland East lock');
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-overseas-lock]').length`), 2, 'Far East and New World show their access locks');
    assert.equal(await page.evaluate(`document.querySelectorAll('.province-label [data-area-lock], .province-label [data-overseas-lock]').length`), 0);
    const unlockedGroups = baseline.game.catalog.provinces.filter(province=>province.area==='V' || province.overseas==='FAR_EAST').map(province=>province.id);
    await show({game:{lockedProvinces:baseline.game.lockedProvinces.filter(id=>!unlockedGroups.includes(id))}});
    assert.equal(await page.evaluate(`!!document.querySelector('[data-area-lock="V"], [data-overseas-lock="FAR_EAST"]')`), false, 'Locks disappear when authoritative access unlocks the group');
    assert.equal(await page.evaluate(`!!document.querySelector('[data-overseas-lock="NEW_WORLD"]')`), true);
    await show({...baseline});
    const beforeAreaInspection = await page.evaluate('window.mapCommands.length');
    await click('[data-map-area-label="IV"]');
    assert.equal(await page.evaluate(`document.querySelector('[data-map-area-label="IV"]').getAttribute('aria-pressed')`), 'true');
    assert.equal(await page.evaluate(`!!document.querySelector('[data-area-outline="IV"] .area-highlight')`), true, 'Area inspection highlights its whole footprint');
    assert.equal(await page.evaluate(`Number(document.querySelector('[data-area-dimming="IV"]').getAttribute('opacity')) >= .4`), true, 'Area inspection strongly dims the rest of the map');
    assert.equal(await page.evaluate(`document.querySelector('[data-area-outline="IV"] .area-highlight').getAttribute('stroke-width')`), '10');
    assert.equal(await page.evaluate('window.mapCommands.length'), beforeAreaInspection, 'Area inspection does not submit');
    await page.evaluate(`document.querySelector('[data-sea="NORTH_SEA"] path').dispatchEvent(new MouseEvent('click',{bubbles:true}))`);
    assert.equal(await page.evaluate(`document.querySelector('[data-map-area-label="IV"]').getAttribute('aria-pressed')`), 'false', 'Sea clicks deselect an inspected area');
    assert.equal(await page.evaluate(`!!document.querySelector('[data-area-dimming]')`), false);
    await click('[data-map-province="genoa"]');
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-connected-sea]').length`), 1, 'Genoa highlights its connected sea');
    await click('[data-map-area-label="IV"]');
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-connected-sea]').length`), 0, 'Area inspection clears province sea emphasis');
    await click('[data-map-province="genoa"]');
    await page.evaluate(`document.querySelector('[data-map-terrain]').dispatchEvent(new MouseEvent('click',{bubbles:true}))`);
    assert.equal(await page.evaluate(`!!document.querySelector('.province-inspector')`), false, 'Non-province land clears province inspection');
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-connected-sea]').length`), 0, 'Clearing inspection clears the sea emphasis');
    assert.equal(await page.evaluate(`document.querySelectorAll('.sea-shape[fill^="url("]').length`), 0, 'Clearing inspection restores the ordinary sea fill');
    for (const province of ['genoa', 'rome']) {
        await click(`[data-map-province="${province}"]`);
        assert.equal(await page.evaluate(`document.querySelector('[data-map-layer="highlights"]').lastElementChild.dataset.provinceOutline`), province, 'Inspected province is above every neighboring border');
        assert.equal(await page.evaluate(`(() => {const levels=[...document.querySelectorAll('[data-highlight-priority]')].map(element=>Number(element.dataset.highlightPriority));return levels.every((level,index)=>!index || levels[index-1]<=level);})()`), true, 'Highlight paint order follows semantic priority');
    }
    await show({game:{players:baseline.game.players.map(player=>player.id===viewer ? {...player,poolTokens:ownPool-1} : player)}});
    assert.equal(await page.evaluate(`document.querySelector('.pool-summary [data-pool-current]').textContent`), String(ownPool-1), 'Prominent pool updates with the current position');
    await show({...baseline});
    const coastShades = await page.evaluate(`[...document.querySelectorAll('[data-coastal-space]')].map(coast=>{
        const land=document.querySelector('[data-map-province="'+coast.dataset.coastalSpace+'"] .reach-shade');
        const shade=coast.querySelector('.reach-shade');
        return {id:coast.dataset.coastalSpace,land:land?.getAttribute('fill'),coast:shade?.getAttribute('fill'),opacity:shade?.getAttribute('opacity')};
    })`);
    assert(coastShades.some(shade=>shade.coast==='#ffffff'), 'At least one reachable coast is light');
    assert(coastShades.some(shade=>shade.coast==='#142a31'), 'At least one unreachable coast is dimmed');
    for (const shade of coastShades) assert.equal(shade.coast, shade.land, 'Coast and province share reachability shading: '+shade.id);
    const reachable = baseline.game.expansionOffers.find(offer => offer.reachable);
    assert(reachable);
    assert.equal(await page.evaluate(`document.querySelector('[data-map-province="${reachable.destination}"]').classList.contains('eligible')`), true);

    assert.equal(await page.evaluate(`!!document.querySelector('.interactive-board select')`), false, 'Map has no drop-down menus');
    assert.equal(await page.evaluate(`getComputedStyle(document.querySelector('.map-zoom')).position`), 'absolute', 'Zoom controls float over the map');
    assert.equal(await page.evaluate(`document.querySelector('[data-map-province="${reachable.destination}"] .reach-shade').getAttribute('fill')`), '#ffffff', 'Reachable provinces use a light fill');
    assert.equal(await page.evaluate(`!!document.querySelector('[data-map-province="${reachable.destination}"] [stroke-dasharray]')`), false, 'Expansion highlights have no dashed border');
    await show({game:{stage:'PURCHASE',activity:{...baseline.game.activity,actor:viewer,purchaseStep:'ADVANCES'}}});
    await page.wait(`document.querySelector('#tab-advances[aria-selected=true]')`, 'purchase workspace');
    await show({prompt:{messageId:'prompt.expand',players:[other.id]},game:{...baseline.game,activity:{...baseline.game.activity,actor:other.id,decisionPlayer:other.id}}});
    await page.wait(`document.querySelector('#tab-map[aria-selected=true]')`, 'expansion opens map for waiting players');
    await show({...baseline});
    await page.wait(`document.querySelector('#tab-map[aria-selected=true]')`, 'own expansion opens map');
    await click('#tab-markets');
    await show();
    assert.equal(await page.evaluate(`document.querySelector('#tab-markets').getAttribute('aria-selected')`), 'true', 'Polling preserves subsequent workspace navigation');
    await click('#tab-map');

    const partial = baseline.game.expansionOffers.find(offer => offer.primaryAction === 'PLACE' && offer.establishesMarker && offer.blockers.length === 0);
    await show({game:{expansionOffers:[{...partial, requiredTokens:4, availablePool:2, resultingPool:null,
        blockers:['INSUFFICIENT_EXPANSION_TOKENS'], partialPlacements:[{tokens:1,resultingPool:1},{tokens:2,resultingPool:0}]}]}});
    await click(`[data-map-province="${partial.destination}"]`);
    assert.equal(await page.evaluate(`document.querySelector('.primary-expansion').disabled`), false);
    assert.match(await page.evaluate(`document.querySelector('.primary-expansion').textContent`), /Place maximum possible tokens.*2/);
    await click('.primary-expansion');
    assert.equal(await page.evaluate('window.mapCommands.at(-1)'), `place 2 ${partial.destination}`);
    await click('.decision-toolbar .pass-action');
    assert.equal(await page.evaluate(`!!document.querySelector('.province-inspector')`), false, 'Pass clears selected province');

    await show({prompt:{messageId:'prompt.expand',players:[other.id]},legalChoices:{commands:[]},game:{
        expansionOffers:[],activity:{...baseline.game.activity,actor:other.id,decisionPlayer:other.id},
        players:baseline.game.players.map(player=>({...player,poolAvailable:player.id!==viewer})),
    }});
    assert.equal(await page.evaluate(`document.querySelector('.pool-summary [data-pool-current]').textContent`), String(other.poolTokens), 'After passing, prominently show the active power pool');
    assert.equal(await page.evaluate(`document.querySelector('.pool-summary > .identity-label').textContent`), await page.evaluate(`document.querySelector('[data-player="${other.id}"] .player-identity strong').textContent`), 'Pool summary identifies the active power');
    assert.equal(await page.evaluate(`!!document.querySelector('.player-summary.is-viewer .player-pool')`), false, 'Own pool leaves capital summary after passing');
    assert.equal(await page.evaluate(`[...document.querySelectorAll('.player-summary.is-viewer .detail-list dt')].some(element=>element.textContent==='Expansion pool')`), false, 'Own pool is also hidden in capital detail');

    const reference = 'single-fur';
    await show({prompt:{messageId:'prompt.play-cards',players:[viewer]}, legalChoices:{commands:[`play ${reference} commodity fur`,'pass']},
        game:{stage:'PLAY_CARDS', expansionOffers:[],hand:[{reference,definition:{id:'fur-1',kind:'commodity',messageId:'card.fur-1',shape:'CIRCLE'}}],
            cardOffers:[{reference,playable:true,blockers:[],choices:[{kind:'COMMODITY',value:'fur',command:`play ${reference} commodity fur`}],commodities:['FUR'],leaderDiscounts:[],provinceTargets:[],requiredTargetCount:null,protectionMax:null}]}});
    await click(`[data-hand-card="${reference}"]`);
    assert.equal(await page.evaluate(`!!document.querySelector('.card-decision .target-choices')`), false, 'Single commodity needs no extra choice');
    assert.equal(await page.evaluate(`document.querySelector('.card-decision .card-confirmation > button').disabled`), false);
    await click('.card-decision .card-confirmation > button');
    assert.equal(await page.evaluate('window.mapCommands.at(-1)'), `play ${reference} commodity fur`);
    assert.equal(await page.evaluate(`!!document.querySelector('.card-decision')`), false, 'Card play clears detail');

    const advance = baseline.game.catalog.advances.find(advance => !baseline.game.players.find(player=>player.id===viewer).advances.includes(advance.id));
    await show({prompt:{messageId:'prompt.purchase-advances',players:[viewer]},legalChoices:{commands:[`buy-advance ${advance.letter}`,'pass']},
        game:{stage:'PURCHASE',activity:{actor:viewer,decisionPlayer:viewer,choice:'purchase-advances',purchaseStep:'ADVANCES',startEntry:revision},
            advanceOffers:[{advance:advance.id,price:10,unavailableReason:null,leaderDiscounts:[{leaderCardId:'charlemagne',baseAmount:20,writtenRecordBonus:10,amount:30}],
                priceBreakdown:{advance:advance.id,baseCost:40,categoryCredit:0,advanceCredits:[],effectiveDiscount:30,institutionalResearchCredit:0,leaders:[{leaderCardId:'charlemagne',baseAmount:20,writtenRecordBonus:10,amount:30}],price:10,miseryBefore:0,miseryAfter:0},miseryChanges:[]}],
            effects:{...baseline.game.effects,leaders:[{leader:'CHARLEMAGNE',owner:viewer,role:'DISPLAY',displayBox:null,protectionCash:0,patronageClaimants:[]}]},
            playedLeaderDiscounts:[{leader:'CHARLEMAGNE',usable:true,discounts:[{advance:advance.id,printedAmount:20,conditionalAmount:20,writtenRecordBonus:10,effectiveDiscount:30,condition:null}]}]}});
    await page.wait(`document.querySelector('#tab-advances[aria-selected=true]')`, 'advances workspace opens on purchase');
    assert.equal(await page.evaluate(`document.querySelector('[data-advance="${advance.id}"]').classList.contains('available')`), true);
    assert.match(await page.evaluate(`document.querySelector('[data-advance="${advance.id}"] .advance-price-compact').textContent`), /−\$30 credits/);
    assert.match(await page.evaluate(`document.querySelector('.advances-workspace .leader-list').textContent`), /Charlemagne/);
    assert.match(await page.evaluate(`document.querySelector('.advances-workspace .leader-in-box .leader-role').textContent`), /In the box/);
    assert.match(await page.evaluate(`document.querySelector('.advances-workspace .leader-in-box .leader-discount').textContent`), /30/);
    await click(`[data-advance="${advance.id}"]`);
    assert.match(await page.evaluate(`document.querySelector('.advance-decision .leader-discount').textContent`), /30/);
    await show({game:{activity:{...fixture.game.activity,purchaseStep:'RELIEF'}}});
    assert.equal(await page.evaluate(`!!document.querySelector('.advance-decision')`), false, 'Changing purchase steps clears advance inspection');
    await show({game:{activity:{...fixture.game.activity,purchaseStep:'ADVANCES'}}});
    await click(`[data-advance="${advance.id}"]`);
    await click('.decision-toolbar .pass-action');
    assert.equal(await page.evaluate(`!!document.querySelector('.advance-decision')`), false, 'Pass clears advance');
    await show({prompt:{messageId:'prompt.purchase-relief',players:[viewer]},legalChoices:{commands:['relieve-misery','pass']},game:{reliefOffers:[]}});
    assert.equal(await page.evaluate(`!!document.querySelector('#relief-spaces')`), false, 'No relief purchase without a tier offer');

    await show({prompt:{messageId:'prompt.play-cards',players:[viewer]},legalChoices:{commands:[`play target player ${other.id}`]},
        game:{stage:'PLAY_CARDS',hand:[{reference:'target',definition:{id:'civil-war',kind:'event',messageId:'card.civil-war',shape:'SQUARE'}}],
            cardOffers:[{reference:'target',playable:true,blockers:[],choices:[{kind:'PLAYER',value:other.id,command:`play target player ${other.id}`}],commodities:[],leaderDiscounts:[],provinceTargets:[],requiredTargetCount:null,protectionMax:null}]}});
    await click('[data-hand-card="target"]');
    assert.equal(await page.evaluate(`!!document.querySelector('[data-hand-card="target"] .card-event-text')`), false, 'The compact hand omits full event paragraphs');
    assert.match(await page.evaluate(`document.querySelector('[data-hand-card="target"] .hand-card-summary').textContent`), /.+/, 'The held event has a concise effect summary');
    assert.equal(await page.evaluate(`document.querySelector('.card-inspection-dialog a[href^="/help#card-"]')`), null, 'Card inspection omits the redundant rules link');
    await page.wait(`document.querySelector('[data-player="${other.id}"].eligible-target')`, 'legal player target highlighted');
    const count = await page.evaluate('window.mapCommands.length');
    await click(`[data-player="${other.id}"] > summary`);
    assert.equal(await page.evaluate('window.mapCommands.length'), count, 'Capital target selection only prepares draft');
    assert.equal(await page.evaluate(`document.querySelector('[data-player="${other.id}"].picked-target') !== null`), true);
    await click('.card-decision .card-confirmation > button');
    assert.equal(await page.evaluate('window.mapCommands.at(-1)'), `play target player ${other.id}`);
    assert.equal(await page.evaluate(`!!document.querySelector('.eligible-target')`), false);

    const targetOffers = ['first-map-card', 'second-map-card'].map((reference,index) => ({
        reference,playable:true,blockers:[],choices:[{kind:'PROVINCE',value:index ? 'genoa' : 'rome',command:`play ${reference} province ${index ? 'genoa' : 'rome'}`}],
        commodities:[],leaderDiscounts:[],provinceTargets:[index ? 'genoa' : 'rome'],requiredTargetCount:null,protectionMax:null,
    }));
    await show({game:{hand:targetOffers.map(offer=>({reference:offer.reference,definition:{id:'rebellion',kind:'event',messageId:'card.rebellion',shape:'SQUARE'}})),cardOffers:targetOffers}});
    await click('[data-hand-card="first-map-card"]');
    await page.wait(`document.querySelector('[data-map-province="rome"].eligible')`, 'first card map target');
    assert.equal(await page.evaluate(`document.querySelector('[data-province-outline="rome"] .province-highlight').getAttribute('stroke')`), '#2b6d9a', 'Card targets highlight their coastal water');
    await click('[data-hand-card="second-map-card"]');
    await page.wait(`document.querySelector('[data-map-province="genoa"].eligible') && !document.querySelector('[data-map-province="rome"].eligible')`, 'switching cards refreshes automatic targets');
    await click('[data-hand-card="second-map-card"]');
    assert.equal(await page.evaluate(`document.querySelector('[data-map-province="genoa"]').classList.contains('eligible')`), true, 'Reselecting the same card keeps its targets');
    await show();
    assert.equal(await page.evaluate(`!!document.querySelector('.card-decision') && !!document.querySelector('[data-map-province="genoa"].eligible')`), true, 'Polling preserves the current card and target picker');
    await show({game:{stage:'PURCHASE',activity:{...baseline.game.activity,purchaseStep:'ADVANCES'}}});
    assert.equal(await page.evaluate(`!!document.querySelector('.card-decision, .province-inspector, .eligible-target')`), false, 'Phase change clears card, province and player focus');
    assert.equal(await page.evaluate(`document.querySelector('[data-map-province="genoa"]').classList.contains('eligible')`), false, 'Phase change clears stale map targets');
    await show({game:{stage:'PLAY_CARDS'}});
    await click('[data-hand-card="second-map-card"]');
    const beforeCoastPick = await page.evaluate('window.mapCommands.length');
    await page.evaluate(`document.querySelector('[data-coastal-space="genoa"] path').dispatchEvent(new MouseEvent('click',{bubbles:true}))`);
    assert.equal(await page.evaluate('window.mapCommands.length'), beforeCoastPick, 'Picking a coast only edits the draft');
    assert.equal(await page.evaluate(`document.querySelector('[data-province-outline="genoa"] .province-highlight').getAttribute('stroke-width')`), '6', 'Selected target highlights its coast');
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-province-outline="genoa"] .province-highlight').length`), 1, 'Province selection uses one combined perimeter');
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-map-province="genoa"] .province-highlight, [data-coastal-space="genoa"] .province-highlight').length`), 0, 'Province selection has one combined outline with no internal shoreline outline');
    assert.equal(await page.evaluate(`[...document.querySelectorAll('[data-map-area]')].every(area=>{
        const paths=area.querySelectorAll(':scope > path');
        return paths.length===1 && paths[0].hasAttribute('clip-path');
    })`), true, 'Each area border stays within its exact member footprint without a separating halo');
    assert.equal(await page.evaluate(`document.querySelector('.card-decision .card-confirmation > button').disabled`), false);
    await click('.decision-heading [data-close-action]');

    await show({game:{hand:[{reference:'area-target',definition:{id:'black-death',kind:'event',messageId:'card.black-death',shape:'CIRCLE'}}],
        cardOffers:[{reference:'area-target',playable:true,blockers:[],choices:[
            {kind:'AREA',value:'viii',command:'play area-target area viii'},
            {kind:'AREA',value:'vii',command:'play area-target area vii'}],
            commodities:[],leaderDiscounts:[],provinceTargets:[],requiredTargetCount:null,protectionMax:null}]}});
    await click('[data-hand-card="area-target"]');
    await page.wait(`document.querySelector('[data-map-area-label="VIII"] .area-label-bg').getAttribute('stroke')==='#2b6d9a'`, 'legal Black Death areas highlighted');
    const beforeAreaTarget = await page.evaluate('window.mapCommands.length');
    await click('[data-map-area-label="VIII"]');
    assert.equal(await page.evaluate('window.mapCommands.length'), beforeAreaTarget, 'Area target click only edits the draft');
    assert.equal(await page.evaluate(`document.querySelector('[data-map-area-label="VIII"]').getAttribute('aria-pressed')`), 'true');
    assert.equal(await page.evaluate(`document.querySelector('.card-decision .card-confirmation > button').disabled`), false);
    await click('.card-decision .card-confirmation > button');
    assert.equal(await page.evaluate('window.mapCommands.at(-1)'), 'play area-target area viii');
    assert.equal(await page.evaluate(`document.querySelector('[data-map-area-label="VIII"]').getAttribute('aria-pressed')`), 'false', 'Playing an area card clears the area selection');

    const participants = baseline.game.players.map(({id,name,capital})=>({id,name,capital}));
    const event = {...baseline.events.at(-1),id:'fixture-result',kind:'COMPETITION_RESULT',actor:viewer,stage:'EXPANSION',turnNumber:baseline.game.turnNumber,
        provinces:['rome'],amount:8,detail:'COMPETITION',dice:[{player:null,value:1,color:'WHITE'},{player:null,value:6,color:'BLACK'},{player:null,value:4,color:'GREEN'}],
        war:null,competition:{attackerWon:true,defenders:[other.id]},participants,cashChanges:[],miseryChanges:[]};
    await show({game:{stage:'EXPANSION'},events:[...baseline.events,event]});
    assert.equal(await page.evaluate(`document.querySelector('.action-result-notice, .competition-log')`),null,
        'Polling a historical competition cannot create an action receipt');
    assert.match(await page.evaluate(`document.querySelector('[data-event-id="fixture-result"]').textContent`), /Competition won.*Rome/);
    assert.match(await page.evaluate(`document.querySelector('[data-event-id="fixture-result"]').textContent`), /White die 1.*Black die 6.*Green die 4/,
        'Every recorded competition die identifies its color');
    const war = {...event,id:'fixture-war',kind:'WAR_RESULT',actor:viewer,target:other.id,competition:null,
        provinces:[],dice:[{player:viewer,value:3,color:'WHITE'},{player:other.id,value:3,color:'BLACK'}],
        war:{initiator:viewer,opponent:other.id,totals:{[viewer]:4,[other.id]:3},winner:viewer,requiredTransfers:1}};
    await show({events:[...baseline.events,war]});
    assert.equal(await page.evaluate(`document.querySelector('.action-result-notice')`),null,
        'A historical War stays in activity instead of reopening an outcome panel');
    assert.match(await page.evaluate(`document.querySelector('[data-event-id="fixture-war"]').textContent`), /won the War/);

    console.log('Passed: capital identity, player names/pools, reachability, remaining tokens, singleton commodity, phase focus, advance discounts, played leaders, target capitals, selection cleanup and recorded outcomes without persistent panels.');
}

export async function checkMapGestures(page) {
    await page.send('Page.bringToFront');
    await page.send('Emulation.setDeviceMetricsOverride', {width:1440,height:1000,deviceScaleFactor:1,mobile:false});
    await page.click('Fit map');
    await page.evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
    const view = () => page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox').split(' ').map(Number)`);
    const fit = await view();
    const bounds = await page.evaluate(`document.querySelector('.world-map').dataset.mapBounds.split(' ').map(Number)`);
    await page.evaluate(`document.querySelector('.world-map').scrollIntoView({block:'center',behavior:'instant'})`);
    const center = await page.evaluate(`(() => {const r=document.querySelector('.world-map').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2};})()`);
    const seaLabel = await page.evaluate(`(() => {
        const svg=document.querySelector('.world-map');
        const frame=svg.getBoundingClientRect();
        getSelection()?.removeAllRanges();
        for(const label of svg.querySelectorAll('[data-sea] text')) {
            const rect=label.getBoundingClientRect();
            const x=rect.x+rect.width/2,y=rect.y+rect.height/2;
            const hit=document.elementFromPoint(x,y);
            if(x>frame.left+15 && x<frame.right-15 && y>frame.top+15 && y<frame.bottom-25 && hit?.closest('.world-map')) {
                return {x,y,dx:x>frame.left+120?-90:90};
            }
        }
        return null;
    })()`);
    assert(seaLabel,'A visible sea label offers a real SVG drag target away from floating controls');
    const {x,y,dx}=seaLabel;
    await page.send('Input.dispatchMouseEvent', {type:'mousePressed',x,y,button:'left',clickCount:1});
    await page.send('Input.dispatchMouseEvent', {type:'mouseMoved',x:x+Math.sign(dx)*12,y,button:'left',buttons:1});
    await page.evaluate('new Promise(resolve => requestAnimationFrame(resolve))');
    await page.send('Input.dispatchMouseEvent', {type:'mouseMoved',x:x+dx,y:y+12,button:'left',buttons:1});
    await page.evaluate('new Promise(resolve => requestAnimationFrame(resolve))');
    await page.send('Input.dispatchMouseEvent', {type:'mouseReleased',x:x+dx,y:y+12,button:'left',clickCount:1});
    assert.equal(await page.evaluate(`getSelection()?.toString()`), '', 'Dragging a sea label never selects map text');
    await page.send('Input.dispatchMouseEvent', {type:'mouseWheel',...center,deltaX:0,deltaY:-150,modifiers:2});
    await page.wait(`Number(document.querySelector('.world-map').getAttribute('viewBox').split(' ')[2]) < ${fit[2]}`, 'modifier scroll zoom');
    const zoomed = await view();
    await page.send('Input.dispatchMouseEvent', {type:'mouseWheel',...center,deltaX:0,deltaY:-100});
    assert.deepEqual(await view(), zoomed, 'Ordinary scrolling does not zoom');
    for (const direction of [-1,1]) {
        await page.send('Input.dispatchMouseEvent',{type:'mousePressed',...center,button:'left',clickCount:1});
        await page.send('Input.dispatchMouseEvent',{type:'mouseMoved',x:center.x+direction*12,y:center.y+direction*12,button:'left',buttons:1});
        await page.evaluate('new Promise(resolve => requestAnimationFrame(resolve))');
        await page.send('Input.dispatchMouseEvent',{type:'mouseMoved',x:center.x+direction*2500,y:center.y+direction*2500,button:'left',buttons:1});
        await page.evaluate('new Promise(resolve => requestAnimationFrame(resolve))');
        await page.send('Input.dispatchMouseEvent',{type:'mouseReleased',x:center.x+direction*2500,y:center.y+direction*2500,button:'left',clickCount:1});
        const [x,y,w,h] = await view();
        assert(x>=bounds[0]-.01 && y>=bounds[1]-.01 && x+w<=bounds[0]+bounds[2]+.01 && y+h<=bounds[1]+bounds[3]+.01, 'Pan stays within map bounds');
    }
    await page.click('Fit map');
    await page.evaluate(`document.querySelector('.world-map').scrollIntoView({block:'center',behavior:'instant'})`);
    await page.send('Emulation.setTouchEmulationEnabled',{enabled:true,maxTouchPoints:2});
    const touch = (distance) => [{x:center.x-distance,y:center.y,id:1},{x:center.x+distance,y:center.y,id:2}];
    await page.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:touch(40)});
    await page.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:touch(100)});
    await page.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
    await page.wait(`Number(document.querySelector('.world-map').getAttribute('viewBox').split(' ')[2]) < ${fit[2]}`, 'two-finger pinch zoom');
    await page.send('Emulation.setTouchEmulationEnabled',{enabled:false});
    await page.click('Fit map');
    console.log('Passed: modifier scroll, ordinary scrolling, bounded dragging and two-finger map pinch.');
}
