import {checkContextualUx, checkMapGestures} from './contextual.browser.mjs';
import {checkSetupEvidence, checkDecisionEvidence} from './nextsteps.browser.mjs';
import {join} from 'node:path';
import {readFile} from 'node:fs/promises';

const artifacts = process.env.AOR_BROWSER_ARTIFACTS ?? '/private/tmp';
import assert from 'node:assert/strict';
import {open, createGame} from './browser-support.mjs';
import {checkTable, checkLiveTokenPurchase, checkTokenContinuity, checkDecisionFamilies, checkFinished} from './table.browser.mjs';

const web = process.env.MAP_WEB_URL ?? 'http://127.0.0.1:5191';
const cdp = process.env.MAP_CDP_URL ?? 'http://127.0.0.1:9351';
const boardGeometry = JSON.parse(await readFile(new URL('../src/map/boardGeometry.json', import.meta.url), 'utf8'));

async function checkPieceBounds(page) {
    const failures = await page.evaluate(`(() => {
        const geometry=${JSON.stringify(Object.fromEntries(Object.entries(boardGeometry.provinces).map(([id, shape]) => [id, shape.pieces])))};
        const failures=[];
        for(const label of document.querySelectorAll('[data-province-label]')){
            const id=label.dataset.provinceLabel, pieces=label.querySelectorAll('[data-piece-kind]');
            if(!pieces.length) continue;
            const fit=geometry[id][pieces.length].fit;
            const land=document.querySelector('[data-map-province="'+id+'"] .province-shape');
            const coast=document.querySelector('[data-coastal-space="'+id+'"] > path');
            for(const piece of pieces){
                const rect=piece.getBBox();
                const matrix=land.getScreenCTM().inverse().multiply(piece.getScreenCTM());
                for(const dx of [0,.5,1]) for(const dy of [0,.5,1]){
                    const point=new DOMPoint(rect.x+rect.width*dx,rect.y+rect.height*dy).matrixTransform(matrix);
                    if(!land.isPointInFill(point) && (fit==='LAND' || !coast?.isPointInFill(point))) failures.push(id+':'+fit);
                }
            }
        }
        return [...new Set(failures)];
    })()`);
    assert.deepEqual(failures, [], 'Rendered markers and token groups fit their own land or coastal footprint');
}

let tokenFixture;
const game = await createGame(6);
const page = await open(web + game.links[0].path);
try {
await page.wait(`document.querySelector('#setup-rebuilt-marker-bonus')`, 'configuration controls');
await page.click('Start setup');
await page.wait(`document.querySelector('#bid')`, 'capital bidding');
await page.fill('#bid', '7');
await page.click('Submit bid');
await page.wait(`[...document.querySelectorAll('button')].some(button => button.textContent === 'Update bid')`, 'private submission');
const submittedState = await game.snapshot(game.links[0]);
assert.equal(submittedState.pendingBid, 7);
assert.equal((await game.snapshot(game.links[1])).pendingBid, null, 'Other seats cannot see private bid');
assert.deepEqual((await game.snapshot(game.links[1])).game.revealedBids, {}, 'Submitted bids remain unrevealed');
await page.fill('#bid', '0');
await page.click('Update bid');
await page.wait(`document.querySelector('.pending-submission')?.textContent.includes('$0')`, 'replaced bid');
assert.equal((await game.snapshot(game.links[0])).revision, submittedState.revision, 'Submitted replacement cannot advance committed revision');
for (const link of game.links.slice(1)) await game.send(link, 'bid-capital 0');
for (let step = 0; step < 100; step++) {
    const first = await game.snapshot(game.links[0]);
    if (first.game.stage === 'EXPANSION') break;
    const actor = game.links.find(link => first.prompt?.players.includes(link.id));
    assert(actor, `No actor during ${first.game.stage}`);
    const state = await game.snapshot(actor);
    const commands = state.legalChoices.commands;
    const command = state.game.stage === 'TOKEN_PURCHASE' ? 'buy-tokens ' + (8 + game.links.indexOf(actor))
        : state.game.stage === 'CAPITAL_CHOICE' ? commands[0]
        : state.game.activity?.purchaseStep === 'SHIPPING' && commands.includes('upgrade-ships') ? 'upgrade-ships'
        : commands.find(c => c === 'pass') ?? commands.find(c => c === 'stabilize cash') ?? commands[0];
    assert(command, 'Fixture must be able to advance');
    if (state.game.stage === 'TOKEN_PURCHASE' && !tokenFixture) {
        tokenFixture = await checkLiveTokenPurchase(page, game, actor, web, 8 + game.links.indexOf(actor));
    } else {
        await game.send(actor, command);
    }
}
const first = await game.snapshot(game.links[0]);
const actor = game.links.find(link => first.prompt?.players.includes(link.id));
await page.send('Page.navigate', {url: web + actor.path});
    await page.wait(`location.hash === ${JSON.stringify(actor.path.slice(actor.path.indexOf('#')))}`, 'expansion actor navigation');
    await page.wait(`document.querySelectorAll('[data-map-province]').length === 108`, 'full map');
    await page.wait(`document.querySelector('.expansion-form, .pool-summary')`, 'expansion decision ready');
    const before = await page.snapshot();
    const expectedSeaAreas = {BALTIC_SEA:'I', GREENLAND_SEA:'II', BLACK_SEA:'V',
        EASTERN_MEDITERRANEAN:'VI', ADRIATIC_SEA:'VII', CENTRAL_MEDITERRANEAN:'VIII', AEGEAN_SEA:'VIII', CANARY_BASIN:'VIII'};
    assert.deepEqual(before.game.catalog.seaAreas, expectedSeaAreas, 'Catalog exposes only the assigned sea areas');
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-sea]').length`), 11, 'Aegean and Central Mediterranean are separate seas');
    assert.equal(await page.evaluate(`document.querySelector('[data-sea="AEGEAN_SEA"]').dataset.seaArea`), 'VIII');
    assert.equal(await page.evaluate(`document.querySelector('[data-sea="CENTRAL_MEDITERRANEAN"]').dataset.seaArea`), 'VIII');
    assert.deepEqual(before.game.catalog.seaCoasts.filter(connection => connection.province === 'crete').map(connection => connection.sea).sort(),
        ['AEGEAN_SEA','CENTRAL_MEDITERRANEAN','EASTERN_MEDITERRANEAN'], 'Crete adjoins all three seas');
    await page.evaluate(`document.fonts.ready.then(()=>true)`);
    const textOverflows = await page.evaluate(`(() => {
        const layouts=${JSON.stringify(Object.fromEntries(Object.entries(boardGeometry.provinces).map(([id, shape]) => [id, shape.labelFit])))};
        const failures=[];
        for(const label of document.querySelectorAll('[data-province-label]')){
            const id=label.dataset.provinceLabel, fit=layouts[id];
            if(fit==='CONSTRAINED') continue;
            const land=document.querySelector('[data-map-province="'+id+'"] .province-shape');
            const coast=document.querySelector('[data-coastal-space="'+id+'"] > path');
            for(const part of label.querySelectorAll(':scope > .province-name, :scope > .market-label')){
                const rect=part.getBBox();
                const matrix=land.getScreenCTM().inverse().multiply(part.getScreenCTM());
                for(const dx of [0,.5,1]) for(const dy of [0,.5,1]){
                    const point=new DOMPoint(rect.x+rect.width*dx,rect.y+rect.height*dy).matrixTransform(matrix);
                    if(!land.isPointInFill(point) && (fit==='LAND' || !coast?.isPointInFill(point))) failures.push(id+':'+fit);
                }
            }
        }
        return [...new Set(failures)];
    })()`);
    assert.deepEqual(textOverflows, [], 'Actual rendered names and badges fit their designated land or coastal footprint');
    await checkPieceBounds(page);
    for (const [sea, shape] of Object.entries(boardGeometry.seas)) {
        const enclosingAreas = await page.evaluate(`[...document.querySelectorAll('[data-map-area]')]
            .filter(area=>area.querySelector('path').isPointInFill(new DOMPoint(${shape.label[0]},${shape.label[1]})))
            .map(area=>area.dataset.mapArea)`);
        if (expectedSeaAreas[sea]) assert.deepEqual(enclosingAreas, [expectedSeaAreas[sea]], 'Area borders enclose assigned seas: '+sea);
        else assert.equal(await page.evaluate(`document.querySelector('[data-sea="${sea}"]').hasAttribute('data-sea-area')`), false, 'Intervening water does not assign an unassigned sea to an area');
    }
    await page.evaluate(`document.querySelector('[data-map-province="crete"]').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))`);
    await page.wait(`document.querySelector('.province-inspector .province-details')`, 'Crete inspection');
    await page.evaluate(`document.querySelector('.province-details').open=true`);
    const creteDetails = await page.evaluate(`document.querySelector('.province-details').textContent`);
    for (const sea of ['Aegean Sea','Central Mediterranean','Eastern Mediterranean']) assert(creteDetails.includes(sea), 'Crete inspection names '+sea);
    const fitBeforeSeaZoom = await page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox')`);
    const cretePoint = await page.evaluate(`(() => {document.querySelector('.world-map').scrollIntoView({block:'center',behavior:'instant'});const rect=document.querySelector('[data-map-province="crete"]').getBoundingClientRect();return {x:rect.x+rect.width/2,y:rect.y+rect.height/2};})()`);
    await page.send('Input.dispatchMouseEvent',{type:'mouseWheel',...cretePoint,deltaX:0,deltaY:-400,modifiers:2});
    await page.wait(`document.querySelector('.world-map').getAttribute('viewBox')!==${JSON.stringify(fitBeforeSeaZoom)}`, 'Mediterranean inspection zoom');
    await page.screenshot(join(artifacts,'aor-mediterranean-seas.png'),1440);
    await page.click('Fit map');
    assert.equal((await page.snapshot()).revision,before.revision,'Crete sea inspection cannot submit an order');
    assert.equal(await page.evaluate(`[...document.querySelectorAll('[data-map-area] > path')].every(path=>(path.getAttribute('d').match(/M/g)??[]).length===1)`), true, 'Every area has one continuous perimeter');
    const areaPoint = await page.evaluate(`(() => {const svg=document.querySelector('.world-map');svg.scrollIntoView({block:'center',behavior:'instant'});const point=new DOMPoint(${boardGeometry.areas.IV.label[0]},${boardGeometry.areas.IV.label[1]}).matrixTransform(svg.getScreenCTM());return {x:point.x,y:point.y};})()`);
    await page.send('Input.dispatchMouseEvent', {type:'mousePressed',...areaPoint,button:'left',clickCount:1});
    await page.send('Input.dispatchMouseEvent', {type:'mouseReleased',...areaPoint,button:'left',clickCount:1});
    await page.wait(`document.querySelector('[data-map-area-label="IV"][aria-pressed="true"]')`, 'direct area label selection');
    assert.equal((await page.snapshot()).revision, before.revision, 'Area inspection cannot submit');
    await page.screenshot(join(artifacts, 'aor-area-inspection.png'), 1440);
    // Hit the new continental extensions, away from labels, with real pointer input.
    for (const [province, x, y] of [
        ['bergen', 1150, -20], ['kiev', 2450, 720], ['sarai', 2914, 565],
        ['cagliari', 1305, 1222], ['athens', 2040, 1456], ['bari', 1650, 1475.5],
        ['cairo', 2430, 2050], ['tripoli', 1510, 2000], ['west-africa', 130, 2020],
    ]) {
        const point = await page.evaluate(`(() => {
            const svg = document.querySelector('.world-map');
            svg.scrollIntoView({block:'center', behavior:'instant'});
            const point = new DOMPoint(${x}, ${y}).matrixTransform(svg.getScreenCTM());
            return {x: point.x, y: point.y};
        })()`);
        await page.send('Input.dispatchMouseEvent', {type:'mousePressed', ...point, button:'left', clickCount:1});
        await page.send('Input.dispatchMouseEvent', {type:'mouseReleased', ...point, button:'left', clickCount:1});
        await page.wait(`document.querySelector('[data-map-province="${province}"][aria-pressed="true"]')`, `${province} frame selection`);
    }
    assert.equal((await page.snapshot()).revision, before.revision, 'Frame selection cannot submit');
    const target = before.legalChoices.commands.find(c => c.startsWith('place 1 ') && before.game.catalog.provinces.some(p => p.id === c.split(' ')[2] && p.satellite)).split(' ')[2];
    const pick = id => page.evaluate(`document.querySelector('[data-map-province="${id}"]').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))`);
    await pick(target);
    assert.equal((await page.snapshot()).revision, before.revision, 'Inspection cannot submit');
    await page.wait(`document.querySelector('.map-orders .primary-expansion')`, 'selected satellite action');
    assert.equal(await page.evaluate(`document.querySelector('.map-orders .primary-expansion').disabled`), false);
    await page.evaluate(`document.querySelector('.map-orders .primary-expansion').click()`);
    await page.wait(`[...document.querySelectorAll('.save-status')].some(e => e.textContent.includes('Saved'))`, 'successful placement');
    let after = await page.snapshot();
    assert.equal(after.revision, before.revision + 1);
    assert(after.game.board.some(p => p.id === target && p.tokens.some(t => t.owner === after.viewerId && t.white === 1)));
    const market = after.game.expansionOffers.find(offer => offer.primaryAction === 'PLACE' && offer.establishesMarker && !offer.blockers.length && offer.partialPlacements.some(choice => choice.tokens === 1));
    assert(market, 'Fixture must support legal partial occupation');
    await pick(market.destination);
    await page.evaluate(`document.querySelector('.partial-placement').open = true`);
    await page.fill('#partial-count', '1');
    assert.equal((await page.snapshot()).revision, after.revision, 'Partial amount selection cannot submit');
    await page.evaluate(`document.querySelector('.partial-placement form button').click()`);
    await page.wait(`document.querySelector('.primary-expansion')?.textContent.includes('${market.requiredTokens - 1}')`, 'remaining marker commitment after partial occupation');
    const partialState = await page.snapshot();
    assert.equal(partialState.revision, after.revision + 1);
    assert.equal(partialState.game.expansionOffers.find(offer => offer.destination === market.destination).requiredTokens, market.requiredTokens - 1, 'Existing friendly token reduces marker commitment');
    await page.evaluate(`document.querySelector('.primary-expansion').click()`);
    await page.wait(`!document.querySelector('.primary-expansion')`, 'marker established');
    after = await page.snapshot();
    assert.equal(after.game.board.find(province => province.id === market.destination).marker.owner, after.viewerId);
    console.log('Passed: live satellite placement, partial-market draft and remaining marker establishment.');
    assert.equal(await page.evaluate(`document.querySelector('.map-toolbar input[type=search]')`), null, 'Map search menu removed');
    await page.evaluate(`document.querySelector('[aria-label="Zoom in"]').click()`);
    const zoomed = await page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox')`);
    await page.click('Fit map');
    assert.notEqual(await page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox')`), zoomed);
    await checkMapGestures(page);
    await page.screenshot(join(artifacts, 'aor-map-fit.png'), 1440);
    const screenshotOffer = after.game.expansionOffers.find(offer => offer.establishesMarker && !offer.blockers.length);
    assert(screenshotOffer, 'Screenshot fixture needs a legal marker draft');
    await page.send('Emulation.setDeviceMetricsOverride', {width: 390, height: 844, deviceScaleFactor: 1, mobile: true});
    await pick(screenshotOffer.destination);
    await page.evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
    assert.equal(await page.evaluate('document.documentElement.scrollWidth <= innerWidth'), true, 'No mobile overflow');
    await page.evaluate(`document.querySelector('.primary-expansion').scrollIntoView({block:'nearest'})`);
    assert.equal(await page.evaluate(`(() => {const button=document.querySelector('.primary-expansion');const rect=button.getBoundingClientRect();return rect.top>=0 && rect.bottom<=innerHeight && document.elementFromPoint(rect.x+rect.width/2,rect.y+rect.height/2)===button;})()`), true, 'Phone marker submit is reachable and unobstructed');
    assert.equal(await page.evaluate(`(() => {const map=document.querySelector('.world-map').getBoundingClientRect();const sheet=document.querySelector('.decision-panel').getBoundingClientRect();const top=Math.max(0,map.top), bottom=Math.min(sheet.top,innerHeight,map.bottom);if(bottom-top<80)return false;const element=document.elementFromPoint(Math.max(10,map.left+map.width/2),(top+bottom)/2);return !!element?.closest('.world-map');})()`), true, 'Phone board stays visible and unobstructed above decision sheet');
    await page.viewportScreenshot(join(artifacts, 'aor-phone-viewport.png'));
    await page.send('Emulation.setPageScaleFactor', {pageScaleFactor:2});
    assert.equal(await page.evaluate('visualViewport.scale'), 2, 'Phone pinch zoom is 200%');
    await page.evaluate(`document.querySelector('.primary-expansion').scrollIntoView({block:'nearest',inline:'nearest'})`);
    assert.equal(await page.evaluate(`(() => {const button=document.querySelector('.primary-expansion');const rect=button.getBoundingClientRect();const visible=visualViewport;return !button.disabled && rect.bottom>visible.offsetTop && rect.top<visible.offsetTop+visible.height && rect.right>visible.offsetLeft && rect.left<visible.offsetLeft+visible.width;})()`), true, 'Pinch-zoomed phone submit remains reachable');
    await page.viewportScreenshot(join(artifacts, 'aor-phone-pinch-200.png'));
    await page.send('Emulation.setPageScaleFactor', {pageScaleFactor:1});
    await page.screenshot(join(artifacts, 'aor-map-mobile.png'), 390, 844, true);
    await page.evaluate(`document.querySelector('.sheet-toggle').click()`);
    assert.equal(await page.evaluate(`document.querySelector('.sheet-toggle').getAttribute('aria-expanded')`), 'false', 'Phone sheet minimizes');
    assert.equal(await page.evaluate(`document.querySelector('.pool-summary').checkVisibility()`), true, 'Active pool remains visible when the phone decision sheet is collapsed');
    await page.send('Emulation.setEmulatedMedia', {features: [{name: 'prefers-reduced-motion', value: 'reduce'}]});
    assert.equal(await page.evaluate(`matchMedia('(prefers-reduced-motion: reduce)').matches`), true);
    const touchPoint = await page.evaluate(`(() => {const control = document.querySelector('[aria-label="Zoom in"]'); control.scrollIntoView({block:'center'}); const rect=control.getBoundingClientRect();return {x:rect.x+rect.width/2,y:rect.y+rect.height/2};})()`);
    const beforeTouch = await page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox')`);
    await page.send('Input.dispatchTouchEvent', {type:'touchStart', touchPoints:[touchPoint]});
    await page.send('Input.dispatchTouchEvent', {type:'touchEnd', touchPoints:[]});
    await page.wait(`document.querySelector('.world-map').getAttribute('viewBox') !== ${JSON.stringify(beforeTouch)}`, 'touch zoom');
    await page.evaluate(`document.querySelector('.sheet-toggle').click()`);
    assert.equal(await page.evaluate(`document.querySelector('.sheet-toggle').getAttribute('aria-expanded')`), 'true', 'Phone sheet reopens with draft retained');
    await page.screenshot(join(artifacts, 'aor-map-phone-tall.png'), 430, 1100, true);
    assert.equal(await page.evaluate('document.documentElement.scrollWidth <= innerWidth'), true, 'No tall phone overflow');
    // Match a 1440-pixel desktop viewport at 200% browser zoom: 720 CSS pixels at 2× scale.
    await page.send('Emulation.setDeviceMetricsOverride', {width:720,height:500,deviceScaleFactor:2,mobile:false});
    await page.evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
    await page.evaluate(`document.querySelector('.primary-expansion').scrollIntoView({block:'center',behavior:'instant'})`);
    assert.equal(await page.evaluate('document.documentElement.scrollWidth <= innerWidth'), true, '200% desktop zoom reflow has no page overflow');
    await page.viewportScreenshot(join(artifacts, 'aor-desktop-reflow-200.png'));
    await page.wait(`(() => {const button=document.querySelector('.primary-expansion');const rect=button.getBoundingClientRect();return rect.top>=-1 && rect.bottom<=innerHeight+1 && !button.disabled;})()`, 'Submit reachable in 200% desktop zoom reflow');
    await page.screenshot(join(artifacts, 'aor-map-desktop.png'), 1440);

    for (const [count, expected] of [[3, 67], [4, 83], [5, 96]]) {
        const reduced = await createGame(count);
        await page.send('Page.navigate', {url: web + reduced.links[0].path});
        await page.wait(`document.querySelectorAll('.map-province.active').length === ${expected}`, `${count}-player board`);
        assert.equal(await page.evaluate(`document.querySelectorAll('.map-province.inactive[tabindex]').length`), 0);
    }

    await checkTable(page, web, after);
    await checkFinished(page, web, after);
    await checkContextualUx(page, web, after);
    await checkTokenContinuity(page, web, tokenFixture);
    await checkDecisionFamilies(page, web, after);

    // Use the same components with deterministic interruptions and mixed piece ages.
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'component harness');
    let revision = 500;
    async function show(changes) {
        const fixture = {...after, prompt: {messageId: 'prompt.test', players: [after.viewerId]}, ...changes, revision: ++revision,
            game: {...after.game, ...changes.game}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'fixture render');
        return fixture;
    }
    await show({game:{board:after.game.catalog.provinces.map(province=>({id:province.id,marker:null,
        tokens:after.game.players.map(player=>({owner:player.id,coloured:1,white:1}))}))}});
    await checkPieceBounds(page);
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-province-label="copenhagen"] [data-piece-kind]').length`), 12, 'Even the narrowest province fits every owner and token age');
    const collisions = await page.evaluate(`(() => {
        const overlaps=(a,b)=>a.left<b.right && a.right>b.left && a.top<b.bottom && a.bottom>b.top;
        return [...document.querySelectorAll('[data-province-label]')].flatMap(label=>{
            const text=[...label.querySelectorAll(':scope > .province-name, :scope > .market-label')].map(part=>part.getBoundingClientRect());
            return [...label.querySelectorAll('[data-piece-kind]')].some(piece=>text.some(rect=>overlaps(rect,piece.getBoundingClientRect()))) ? [label.dataset.provinceLabel] : [];
        });
    })()`);
    assert.deepEqual(collisions,[],'Actual rendered labels and dense pieces never overlap');
    for (const province of ['copenhagen','acre','levant','north-america','south-america']) {
        await pick(province);
        await page.wait(`document.querySelector('[data-dense-province="${province}"]')`,'readable dense occupancy fallback');
        const text = await page.evaluate(`document.querySelector('[data-dense-province="${province}"]').textContent`);
        assert.equal((text.match(/1 established and 1 new tokens/g)??[]).length,6,'Every owner and age is retained at readable size');
        if (province === 'copenhagen') {
            await page.screenshot(join(artifacts,'aor-crowded-fit.png'),1440);
            await page.screenshot(join(artifacts,'aor-crowded-phone.png'),390,844,true);
            await page.send('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
            const point=await page.evaluate(`(() => {const piece=document.querySelector('[data-province-label="copenhagen"] [data-piece-kind]');const rect=piece.getBoundingClientRect();return {x:rect.x+rect.width/2,y:rect.y+rect.height/2};})()`);
            await page.send('Input.dispatchMouseEvent',{type:'mouseWheel',...point,deltaX:0,deltaY:-550,modifiers:2});
            await page.screenshot(join(artifacts,'aor-crowded-zoom.png'),1440);
            await page.click('Fit map');
        }
    }
    const pirateFixture = await show({legalChoices: {commands: ['play hand-pirates']}, game: {stage: 'PLAY_CARDS',
        hand: [{reference: 'hand-pirates', definition: {id: 'pirates-vikings', kind: 'event', messageId: 'card.pirates-vikings', shape: 'SQUARE'}}],
        pirateTargets: {requiredCount: 2, eligibleProvinces: ['barcelona', 'genoa', 'rome']},
        cardOffers: [{reference:'hand-pirates', playable:true, blockers:[], choices:[{kind:'PROVINCES',value:null,command:null}], commodities:[],leaderDiscounts:[],provinceTargets:['barcelona','genoa','rome'],requiredTargetCount:2,protectionMax:null}]}});
    await page.evaluate(`document.querySelector('[data-hand-card="hand-pirates"]').click()`);
    await page.wait(`document.querySelector('.card-decision .map-picker[aria-pressed=true]')`, 'card map targets activate automatically');
    await pick('barcelona'); await pick('genoa'); await pick('rome');
    assert.equal(await page.evaluate(`document.querySelectorAll('.card-decision .target-choices button:not(.map-picker)[aria-pressed="true"]').length`), 2);
    assert.equal(await page.evaluate('window.mapCommands.length'), 0);
    await show({...pirateFixture, game:{...pirateFixture.game,
        pirateTargets:{requiredCount:2,eligibleProvinces:['genoa','rome']},
        cardOffers:pirateFixture.game.cardOffers.map(offer=>({...offer,provinceTargets:['genoa','rome']}))}});
    await page.wait(`document.querySelector('.card-decision .clear-targets')`, 'lost pirate target explained');
    assert.equal(await page.evaluate(`document.querySelector('.card-decision form > button:last-child').disabled`), true, 'Lost target disables card submission');
    await page.evaluate(`document.querySelector('.card-decision .clear-targets').click()`);
    assert.equal(await page.evaluate('window.mapCommands.length'), 0, 'Clearing invalid targets never submits');
    await pick('genoa'); await pick('rome');
    await page.evaluate(`document.querySelector('.card-decision form > button:last-child').click()`);
    assert.equal(await page.evaluate('window.mapCommands.at(-1)'), 'play hand-pirates provinces genoa rome');

    await page.evaluate(`document.querySelector('.card-decision .close-detail')?.click()`);
    await show({legalChoices: {commands: ['cede-war']}, game: {warCession: {winner: 'p2', requiredCount: 2,
        eligibleProvinces: ['genoa', 'china', 'india'], overseasCapacity: 1}}});
    await page.wait(`document.querySelector('.decision .map-picker[aria-pressed=true]')`, 'war cession targets activate automatically');
    await page.evaluate(`['china','india','genoa'].forEach(id => document.querySelector('[data-map-province="'+id+'"]').dispatchEvent(new MouseEvent('click',{bubbles:true})))`);
    assert.equal(await page.evaluate(`document.querySelectorAll('.province-checks input:checked').length`), 2);
    await page.evaluate(`document.querySelector('.decision form > button:last-child').click()`);
    assert.equal(await page.evaluate('window.mapCommands.at(-1)'), 'cede-war china genoa');

    await show({legalChoices: {commands: []}, game: {board: [{id: 'toledo', marker: null,
        tokens: [{owner: after.game.players[0].id, coloured: 1, white: 1}, {owner: after.game.players[1].id, coloured: 0, white: 1}]}]}});
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-province-label="toledo"] [data-piece-kind="tokens"]').length`), 3);
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-province-label="toledo"] [data-piece-age="new"]').length`), 2);
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-province-label="toledo"] [data-piece-age="established"]').length`), 1);
    console.log('Passed: continental frame selection, live placement and rejection, draft preservation, inspection, keyboard picking, zoom, mobile layout, 3–6 player boards, pirate limits, War overseas limits, mixed owners and ages.');
    await checkDecisionEvidence(page,web,after);
    await checkSetupEvidence(page,web);
} finally {
    page.ws.close();
    await fetch(cdp + '/json/close/' + page.target.id);
}
