import assert from 'node:assert/strict';

export async function checkFinished(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/ui-harness.html'});
    await page.wait(`typeof window.setUiSnapshot === 'function'`, 'finished application harness');
    const finished = {...baseline, prompt:null, legalChoices:{commands:[],bidMin:null,bidMax:null}, game:{...baseline.game,
        stage:'FINISHED',expansionOffers:[],cardOffers:[],advanceOffers:[],shippingOffer:null,
        players:baseline.game.players.map(player=>({...player,misery:null,income:null})),
        scores:baseline.game.players.map(player=>({player:player.id,advancePoints:0,cash:player.cash,misery:null,total:null,winner:false})),
    }};
    await page.evaluate(`window.setUiSnapshot(${JSON.stringify(finished)})`);
    await page.wait(`document.querySelectorAll('.final-scores tbody tr').length === ${baseline.game.players.length}`, 'visible final scores');
    assert.equal(await page.evaluate(`document.querySelectorAll('.header-stage .waiting-status, .player-summary .player-status').length`), 0, 'Completed game has no waiting indicators');
    assert.equal(await page.evaluate(`document.querySelector('.decision-heading .eyebrow').textContent`), 'Game complete');
    assert.equal(await page.evaluate(`!!(document.querySelector('.final-scores').compareDocumentPosition(document.querySelector('.table-workspace')) & Node.DOCUMENT_POSITION_FOLLOWING)`), true, 'Results precede the inspection workspace');
    assert.equal(await page.evaluate(`document.querySelector('.game-actions button:not([disabled])')`), null, 'Finished game offers no further decisions');
    assert.equal(await page.evaluate(`window.uiCommands.length`), 0);
    console.log('Passed: completed-game results precede inspection and waiting indicators disappear.');
}

// Run through App, including its polling and canonical submission path. Snapshot fields and quote
// values come from authoritative fixture projections, never from duplicated game calculations.
export async function checkTable(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/ui-harness.html'});
    await page.wait(`typeof window.setUiSnapshot === 'function'`, 'full application harness');
    let fixture = structuredClone(baseline);
    async function show(changes = {}) {
        fixture = {...fixture, ...changes, game: {...fixture.game, ...changes.game}};
        const requests = await page.evaluate('window.uiRequests');
        await page.evaluate(`window.setUiSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`window.uiRequests > ${requests}`, 'application polling fixture');
    }
    await show();
    await page.wait(`document.querySelector('.game-table')`, 'compact game table');
    assert.equal(await page.evaluate(`document.querySelectorAll('.player-overview').length > 0`), true);
    assert.equal(await page.evaluate(`document.querySelectorAll('.decision-panel').length`), 1, 'One home for active decision');
    assert.equal(await page.evaluate(`document.querySelectorAll('.expansion-form').length`), 0, 'Contextual flow replaces duplicate expansion form');
    assert.equal(await page.evaluate('window.uiCommands.length'), 0, 'Initial render cannot submit');

    assert.equal(await page.evaluate(`document.querySelector('.available-resources, .stock-details')`), null, 'Permanent resource section is omitted');
    const incomeLabels = await page.evaluate(`[...document.querySelectorAll('.income-value')].map(element => ({text:element.textContent, title:element.title, label:element.getAttribute('aria-label')}))`);
    assert.equal(incomeLabels.length, baseline.game.players.length);
    for (const income of incomeLabels) {
        assert(!income.text.includes('+') && !income.text.toLowerCase().includes('income'), 'Income overview uses icon and unsigned amount');
        assert(income.title && income.label, 'Income keeps hover and accessible labels');
    }
    await page.evaluate(`document.querySelector('.player-summary.is-viewer > summary').click()`);
    assert.equal(await page.evaluate(`document.querySelector('.player-summary.is-viewer a[href="/help#tracks"]').target`), '_blank', 'Tracks open outside the game');
    assert.equal(await page.evaluate(`document.querySelector('.player-summary.is-viewer .reference-track')`), null, 'Static tracks leave the game view');
    await page.evaluate(`document.querySelector('.player-summary.is-viewer > summary').click()`);
    const offer = fixture.game.expansionOffers.find(offer => offer.primaryAction === 'PLACE' && offer.establishesMarker && offer.blockers.length === 0);
    assert(offer, 'Live fixture must expose a legal marker establishment');
    await page.evaluate(`document.querySelector('[data-map-province="${offer.destination}"]').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))`);
    await page.wait(`document.querySelector('.map-orders')`, 'contextual marker decision');
    assert.equal(await page.evaluate('window.uiCommands.length'), 0, 'Keyboard selection prepares a draft');
    const selected = await page.evaluate(`document.querySelector('.map-orders').textContent`);
    assert(selected.includes(String(offer.requiredTokens)), 'Marker commitment is shown');
    assert(selected.includes(String(offer.resultingPool)), 'Remaining pool is shown');
    const viewBox = await page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox')`);
    await show({revision: fixture.revision + 1});
    assert.equal(await page.evaluate(`document.querySelector('[data-map-province="${offer.destination}"]').getAttribute('aria-pressed')`), 'true', 'Selection survives polling');
    assert.equal(await page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox')`), viewBox, 'Viewport survives polling');
    assert.equal(await page.evaluate('window.uiCommands.length'), 0, 'Polling never submits a draft');

    const shippingLimited = {...offer, requiredTokens:4, availablePool:8, resultingPool:null,
        blockers:['SHIPPING_CAPACITY_EXCEEDED'], shipping:{used:0,capacity:2,required:4},
        partialPlacements:[{tokens:1,resultingPool:7},{tokens:2,resultingPool:6}]};
    await show({game:{expansionOffers:[shippingLimited]}});
    assert.equal(await page.evaluate(`document.querySelector('.primary-expansion').disabled`),false,
        'Legal partial shipping placement remains actionable');
    assert.match(await page.evaluate(`document.querySelector('.primary-expansion').textContent`), /2 tokens/,
        'Shipping limit defaults to the maximum legal commitment');
    await show({game:{expansionOffers:baseline.game.expansionOffers}});

    const blocked = fixture.game.expansionOffers.find(offer => offer.blockers.length > 0 && offer.primaryAction !== 'INSPECT');
    assert(blocked, 'Fixture must include an inspectable blocked destination');
    await page.evaluate(`document.querySelector('[data-map-province="${blocked.destination}"]').dispatchEvent(new MouseEvent('click',{bubbles:true}))`);
    await page.wait(`document.querySelector('[data-map-province="${blocked.destination}"][aria-pressed="true"]')`, 'blocked market remains inspectable');
    assert.equal(await page.evaluate(`document.querySelector('.map-orders .primary-expansion')?.disabled`), true, 'Blocked primary action cannot submit');
    assert.equal(await page.evaluate('window.uiCommands.length'), 0);

    // A mandatory interruption changes decision ownership; the old expansion draft cannot submit.
    const other = fixture.game.players.find(player => player.id !== fixture.viewerId).id;
    await show({revision: fixture.revision + 1, prompt: {messageId: 'prompt.civil-war-loss', players: [other]},
        legalChoices: {...fixture.legalChoices, commands: []},
        game: {expansionOffers: [], activity: {...fixture.game.activity, actor: fixture.viewerId, decisionPlayer: other, choice: 'civil-war-loss'}}});
    await page.wait(`!document.querySelector('.map-orders .primary-expansion:not([disabled])')`, 'interruption disables stale expansion');
    assert.equal(await page.evaluate('window.uiCommands.length'), 0, 'Changed prompt cannot auto-submit');
    assert((await page.evaluate(`document.querySelector('.decision-panel').textContent`)).includes(fixture.game.players.find(player => player.id === other).capital.replace(/\b\w/g, letter=>letter.toUpperCase())), 'Interruption names recipient');
    await show({...baseline, revision: fixture.revision + 1});
    await page.evaluate(`document.querySelector('[data-map-province="${offer.destination}"]').dispatchEvent(new MouseEvent('click',{bubbles:true}))`);
    await page.evaluate(`document.querySelector('#tab-markets').click()`);
    await page.evaluate(`document.querySelector('#tab-map').click()`);
    assert.equal(await page.evaluate(`document.querySelector('[data-map-province="${offer.destination}"]').getAttribute('aria-pressed')`), 'true', 'Workspace switching retains selected market');
    await page.evaluate(`window.uiNextError='INSUFFICIENT_EXPANSION_TOKENS'; document.querySelector('.primary-expansion').click()`);
    await page.wait(`document.querySelector('.decision-panel [role=alert]') && !document.querySelector('.primary-expansion').disabled`, 'rejected draft retained');
    assert.equal(await page.evaluate('window.uiCommands.at(-1).command'), `place ${offer.requiredTokens} ${offer.destination}`);
    assert.equal(await page.evaluate(`document.querySelector('[data-map-province="${offer.destination}"]').getAttribute('aria-pressed')`), 'true');
    const oldRevision = fixture.revision;
    await page.evaluate(`window.uiNextError='STALE_REVISION'; window.uiSnapshot={...window.uiSnapshot,revision:${oldRevision + 1}}; document.querySelector('.primary-expansion').click()`);
    await page.wait(`window.uiCommands.length === 2 && !document.querySelector('.primary-expansion').disabled`, 'stale response refresh completed');
    assert.equal(await page.evaluate('window.uiCommands.at(-1).expectedRevision'), oldRevision, 'Stale submission uses rendered revision');
    assert.equal(await page.evaluate('window.uiCommands.length'), 2, 'Refresh never automatically retries rejected intent');
    assert.equal(await page.evaluate(`document.querySelector('.activity-toggle').getAttribute('aria-expanded')`), 'true',
        'Activity is visible by default');
    await page.evaluate(`document.querySelector('.activity-toggle').click()`);
    await page.wait(`document.querySelector('.activity-toggle').getAttribute('aria-expanded') === 'false'`, 'activity explicitly collapsed');
    const nextEvent = {...baseline.events.at(-1),id:'fixture-activity-1',kind:'BUY_CARD',actor:baseline.viewerId,
        card:null,provinces:[],dice:[],amount:null,detail:null,drawReason:null,protectionCash:null,leaderRebate:null,
        marketRoll:null,cashChanges:[{player:baseline.viewerId,amount:-10}],
        miseryChanges:[{player:baseline.viewerId,spaces:2,fromValue:20,toValue:40}]};
    const events = [...baseline.events,nextEvent];
    await show({...baseline, revision:fixture.revision+1,events});
    assert.equal(await page.evaluate(`document.querySelector('.activity-list').checkVisibility()`), false, 'Closed activity remains hidden during polling');
    await page.evaluate(`document.querySelector('.activity-toggle').click()`);
    await page.wait(`document.querySelector('.activity-list').checkVisibility()`, 'activity reopened');
    await page.evaluate(`document.querySelector('.command-dock > summary').focus()`);
    const updatedEvents=[...events,{...nextEvent,id:'fixture-activity-2'}];
    await show({revision:fixture.revision+1,events:updatedEvents});
    await page.evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
    assert.equal(await page.evaluate(`document.querySelector('.table-activity .unread-badge, .table-activity h2, .table-activity h3')`), null, 'Activity has no heading or unread count');
    await show({revision:fixture.revision+1,events:updatedEvents});
    assert.equal(await page.evaluate(`new Set([...document.querySelectorAll('.activity-list [data-event-id]')].map(entry=>entry.dataset.eventId)).size`), updatedEvents.filter(event=>event.kind!=='PASS').length, 'Repeated polling keeps stable unique activity entries');
    assert.equal(await page.evaluate(`document.activeElement===document.querySelector('.command-dock > summary')`), true, 'Activity updates preserve focus');
    assert.match(await page.evaluate(`document.querySelector('[data-event-id="fixture-activity-1"]').textContent`), /−\$10/,
        'Activity includes the cash spent in its purchase entry');
    assert.match(await page.evaluate(`document.querySelector('[data-event-id="fixture-activity-1"]').textContent`), /\+2 misery.*20 → 40/,
        'Activity includes signed misery spaces and the recorded boxes');
    await show({events:[...updatedEvents,{...nextEvent,id:'continued-omitted',kind:'PASS',cashChanges:[]}]});
    assert.equal(await page.evaluate(`document.querySelector('[data-event-id="continued-omitted"]')`), null,
        'Continued entries are omitted from activity');
    console.log('Passed: compact decision table, marker commitment, blocked inspection, keyboard selection, polling/viewport continuity and changed-owner interruption.');
}

export async function checkLiveTokenPurchase(page, game, actor, web, finalAmount) {
    const state = await game.snapshot(actor);
    const me = state.game.players.find(player => player.id === state.viewerId);
    const aboveStock = me.stockTokens + 1;
    assert(aboveStock <= me.cash, 'Fixture cash must permit a stock-capped purchase');
    await page.send('Page.navigate', {url: web + actor.path});
    await page.wait(`document.querySelector('#bid') && document.querySelector('#purchase-preview')`, 'token purchase controls');
    const quotes = {};
    for (const amount of [aboveStock, -2, finalAmount]) {
        const response = await fetch(new URL('/api/games/' + game.created.gameId + '/quote-token-purchase', process.env.MAP_API_URL), {
            method: 'POST', headers: {'Content-Type': 'application/json', Authorization: 'Bearer ' + actor.token},
            body: JSON.stringify({expectedRevision: state.revision, amount}),
        });
        assert(response.ok, `Exact quote rejected: ${await response.clone().text()}`);
        const result = await response.json();
        quotes[amount] = result;
        assert.equal(result.quote.amount, amount);
        assert.equal(result.revision, state.revision);
        await page.fill('#bid', String(amount));
        await page.wait(`document.querySelector('#purchase-preview')?.textContent.includes('$${result.quote.cashAfter}')`, `authoritative purchase preview ${amount}`);
        const label = (await game.snapshot(actor)).pendingBid === null ? 'Buy tokens' : 'Update token purchase';
        await page.click(label);
        await page.wait(`[...document.querySelectorAll('button')].some(button => button.textContent === 'Update token purchase' && !button.disabled)`, `purchase saved ${amount}`);
        assert.equal((await game.snapshot(actor)).pendingBid, amount);
        assert.equal((await game.snapshot(actor)).revision, state.revision, 'Pending replacement does not change committed revision');
    }
    assert.equal(quotes[aboveStock].quote.tokensReceived, me.stockTokens, 'Positive purchase is capped by stock');
    assert.equal(quotes[-2].quote.tokensReceived, 0, 'Negative purchase buys no tokens');
    assert.equal(quotes[-2].quote.cost, 2, 'Negative purchase pays its absolute amount');
    console.log('Passed: live exact token quotes above stock, negative purchases, and replacement of private purchases.');
    return {snapshot: state, quotes};
}

export async function checkTokenContinuity(page, web, tokenFixture) {
    await page.send('Page.navigate', {url: web + '/tests/ui-harness.html'});
    await page.wait(`typeof window.setUiSnapshot === 'function'`, 'token continuity harness');
    const {snapshot, quotes} = tokenFixture;
    const largeAmount = Number(Object.keys(quotes).find(amount => Number(amount) > 20));
    await page.evaluate(`window.uiHoldQuotes = true; window.uiQuoteReplies = ${JSON.stringify(quotes)}; window.setUiSnapshot(${JSON.stringify(snapshot)})`);
    await page.wait(`document.querySelector('#bid')`, 'token draft');
    await page.fill('#bid', '-2');
    await page.wait(`window.uiQuoteRequests.some(request => request.amount === -2)`, 'held negative quote');
    await page.fill('#bid', String(largeAmount));
    await page.wait(`window.uiQuoteRequests.some(request => request.amount === ${largeAmount})`, 'held stock-capped quote');
    assert.equal(await page.evaluate(`document.querySelector('#bid').form.querySelector('button').disabled`), true, 'Cannot submit while exact quote is outstanding');
    await page.evaluate(`window.uiHoldQuotes = false; window.releaseUiQuotes()`);
    await page.wait(`!document.querySelector('#bid').form.querySelector('button').disabled`, 'latest quote enabled');
    assert.equal(await page.evaluate(`document.querySelector('#bid').value`), String(largeAmount));
    assert((await page.evaluate(`document.querySelector('#purchase-preview').textContent`)).includes(`$${quotes[largeAmount].quote.cashAfter}`), 'Late earlier amount cannot replace latest preview');
    const requests = await page.evaluate('window.uiRequests');
    await page.wait(`window.uiRequests > ${requests}`, 'polling while editing purchase');
    assert.equal(await page.evaluate(`document.querySelector('#bid').value`), String(largeAmount), 'Polling preserves draft amount');
    assert.equal(await page.evaluate('window.uiCommands.length'), 0);

    // Revision supersession invalidates every quote from the previous position.
    await page.evaluate(`window.uiQuoteReplies = {}; window.setUiSnapshot(${JSON.stringify({...snapshot, revision: snapshot.revision + 1})})`);
    await page.wait(`window.uiQuoteRequests.some(request => request.expectedRevision === ${snapshot.revision + 1})`, 'quote revalidation after revision change');
    assert.equal(await page.evaluate(`document.querySelector('#bid').form.querySelector('button').disabled`), true, 'Stale quote cannot authorize submission');
    assert.equal(await page.evaluate('window.uiCommands.length'), 0);
    const quotesBeforeChaos = await page.evaluate('window.uiQuoteRequests.length');
    const chaos = {...snapshot, revision:snapshot.revision+2, prompt:{messageId:'prompt.buy-tokens',players:[]},
        game:{...snapshot.game,players:snapshot.game.players.map(player=>player.id===snapshot.viewerId?{...player,misery:null,income:null}:player)}};
    await page.evaluate(`window.setUiSnapshot(${JSON.stringify(chaos)})`);
    await page.wait(`!document.querySelector('#bid')`, 'Chaos purchase controls absent');
    const polled = await page.evaluate('window.uiRequests');
    await page.wait(`window.uiRequests > ${polled}`, 'Chaos polling');
    assert.equal(await page.evaluate('window.uiQuoteRequests.length'), quotesBeforeChaos, 'Chaos does not request token quotes');
    console.log('Passed: held quote amount supersession, outstanding quote guard, polling draft retention and revision invalidation.');
}

export async function checkDecisionFamilies(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/ui-harness.html'});
    await page.wait(`typeof window.setUiSnapshot === 'function'`, 'decision family harness');
    let revision = baseline.revision + 100;
    const viewer = baseline.viewerId;
    const other = baseline.game.players.find(player => player.id !== viewer).id;
    const hand = baseline.game.hand[0];
    assert(hand, 'Decision fixture needs an actual private hand card');
    async function show(choice, commands, game = {}, stage = 'PLAY_CARDS') {
        const snapshot = {...baseline, revision: ++revision, pendingBid: null, submitted: [],
            prompt: {messageId: 'prompt.' + choice, players: [viewer]},
            legalChoices: {commands, bidMin: null, bidMax: null},
            game: {...baseline.game, stage, expansionOffers: [], cardOffers: [],
                activity: {actor: other, decisionPlayer: viewer, choice, purchaseStep: null, startEntry: revision}, ...game}};
        const requests = await page.evaluate('window.uiRequests');
        await page.evaluate(`window.setUiSnapshot(${JSON.stringify(snapshot)})`);
        await page.wait(`window.uiRequests > ${requests}`, choice + ' polled');
        await page.evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
        await page.wait(`window.uiSnapshot.revision === ${revision} && document.querySelector('.decision-panel')`, choice + ' fixture');
        return snapshot;
    }
    const finite = [
        ['return-card', `return-card ${hand.reference}`, 'SETUP_RETURN'],
        ['immediate-master-art-discard', `discard-card ${hand.reference}`, 'PURCHASE'],
        ['buy-card', `discard-card ${hand.reference}`, 'BUY_CARD'],
        ['civil-war-loss', 'civil-war-loss cash'],
        ['replace-event-token', 'replace-event-token pool'],
        ['renaissance', `swap-order ${other}`],
        ['written-record', `written-record ${other}`],
        ['adjust-market', 'adjust-market grain shortage'],
        ['offer-card', `offer-card ${hand.reference}`],
        ['purchase-advances', 'patronize leonardo-da-vinci', 'PURCHASE'],
    ];
    for (const [choice, command, stage] of finite) {
        await show(choice, [command], {}, stage);
        const count = await page.evaluate('window.uiCommands.length');
        await page.evaluate(`document.querySelector('.game-actions .action-buttons button.secondary').click()`);
        await page.wait(`document.querySelector('.selected-action button:not([disabled])')`, choice + ' selected');
        assert.equal(await page.evaluate('window.uiCommands.length'), count, `${choice} selection cannot submit`);
        await page.evaluate(`document.querySelector('.selected-action button').click()`);
        await page.wait(`window.uiCommands.length === ${count + 1}`, choice + ' confirmed');
        assert.equal(await page.evaluate('window.uiCommands.at(-1).command'), command);
    }
    for (const [choice, command] of [['cathedral', 'cathedral'], ['windmill', 'windmill'], ['purchase-stabilization', 'stabilize misery'], ['buy-card', 'buy-card']]) {
        await show(choice, [command]);
        const count = await page.evaluate('window.uiCommands.length');
        await page.evaluate(`document.querySelector('.game-actions .action-buttons button').click()`);
        await page.wait(`window.uiCommands.length === ${count + 1}`, command + ' submitted');
        assert.equal(await page.evaluate('window.uiCommands.at(-1).command'), command);
    }
    await show('purchase-relief', ['relieve-misery'], {
        reliefOffers: [{spaces: 1, cashCost: 2, ratingSpent: 1, affordable: true}],
        activity: {...baseline.game.activity, actor: viewer, decisionPlayer: viewer, purchaseStep: 'RELIEF'},
    }, 'PURCHASE');
    await page.evaluate(`document.querySelector('#relief-spaces button').click()`);
    await page.evaluate(`document.querySelector('#relief-spaces').closest('form').querySelector(':scope > button').click()`);
    await page.wait(`window.uiCommands.at(-1)?.command === 'relieve-misery 1'`, 'quoted misery relief');

    await show('choose-turn-position', ['choose-turn-position 1','choose-turn-position 2'], {provisionalTieOrder:[other]}, 'TURN_ORDER_TIE');
    assert.equal(await page.evaluate(`document.querySelector('.turn-tie .capital-choices button:not(.secondary)').disabled`), true, 'Turn tie requires explicit slot selection');
    await page.evaluate(`document.querySelector('.turn-tie .capital-choices button.secondary').click()`);
    await page.evaluate(`document.querySelector('.turn-tie .capital-choices button:not(.secondary)').click()`);
    await page.wait(`window.uiCommands.at(-1)?.command === 'choose-turn-position 1'`, 'tie slot confirmed');
    const source = baseline.game.board.find(province => province.marker?.owner===viewer).id;
    await show('place-event-marker', ['forgo-marker',`move-marker ${source}`], {markerPlacement:{player:viewer,destination:'rome'}});
    await page.evaluate(`document.querySelector('#marker-source button').click()`);
    await page.evaluate(`document.querySelector('#marker-source').closest('form').querySelector(':scope > button').click()`);
    await page.wait(`window.uiCommands.at(-1)?.command === 'move-marker ${source}'`, 'event marker moved');
    await show('place-event-marker', ['forgo-marker'], {markerPlacement:{player:viewer,destination:'rome'}});
    await page.evaluate(`document.querySelector('.game-actions .guided-form > button.secondary').click()`);
    await page.wait(`window.uiCommands.at(-1)?.command === 'forgo-marker'`, 'event marker forgone');

    for (const [id,kind,value] of [['civil-war','PLAYER',other],['black-death','AREA','i'],['the-crusades','PROVINCE','acre'],['papal-decree','CATEGORY','none']]) {
        const reference='fixture-'+id;
        const command=`play ${reference} ${kind.toLowerCase()} ${value}`;
        await show('play-cards', [command], {
            hand:[{reference,definition:{id,kind:'event',messageId:'card.'+id,shape:'SQUARE'}}],
            cardOffers:[{reference,playable:true,blockers:[],choices:[{kind,value,command}],commodities:[],leaderDiscounts:[],provinceTargets:kind==='PROVINCE'?[value]:[],requiredTargetCount:null,protectionMax:null}],
        });
        await page.evaluate(`document.querySelector('[data-hand-card="${reference}"]').click()`);
        await page.wait(`document.querySelector('.card-decision .target-choices button:not(.map-picker)')`, kind+' target');
        const before=await page.evaluate('window.uiCommands.length');
        if (kind === 'PROVINCE' || kind === 'AREA') {
            await page.wait(`document.querySelector('#tab-map[aria-selected=true]') && document.querySelector('.eligible-shade')`,
                id+' automatically opens the map and shades legal targets');
            assert.equal(await page.evaluate(`document.querySelector('button.map-picker')`), null,
                'Map picking needs no activation button');
            const selector = kind === 'PROVINCE' ? `[data-map-province="${value}"]` : `[data-map-area-label="${value.toUpperCase()}"]`;
            await page.evaluate(`document.querySelector(${JSON.stringify(selector)}).dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))`);
        } else {
            await page.evaluate(`document.querySelector('.card-decision .target-choices button:not(.map-picker)').click()`);
        }
        assert.equal(await page.evaluate('window.uiCommands.length'),before,kind+' selection cannot submit');
        await page.evaluate(`document.querySelector('.card-decision form > button:last-child').click()`);
        await page.wait(`window.uiCommands.at(-1)?.command === ${JSON.stringify(command)}`,kind+' canonical play');
    }
    const advance = baseline.game.catalog.advances[0];
    await show('purchase-advances', [`buy-advance ${advance.letter}`], {
        advanceOffers:[{advance:advance.id,price:10,unavailableReason:null}],
        activity:{actor:viewer,decisionPlayer:viewer,choice:'purchase-advances',purchaseStep:'ADVANCES',startEntry:revision},
    }, 'PURCHASE');
    await page.evaluate(`document.querySelector('#tab-advances').click()`);
    await page.evaluate(`[...document.querySelectorAll('.advance-tile')].find(tile=>tile.querySelector('.advance-letter').textContent===${JSON.stringify(advance.letter)}).click()`);
    await page.wait(`document.querySelector('.advance-decision button:not(.close-detail):not([disabled])')`, 'affordable advance decision');
    await page.evaluate(`document.querySelector('.advance-decision button:not(.close-detail)').click()`);
    await page.wait(`window.uiCommands.at(-1)?.command === 'buy-advance ${advance.letter}'`, 'advance purchase canonical command');
    await show('purchase-advances', [`buy-advance ${advance.letter}`], {
        advanceOffers:[{advance:advance.id,price:10,unavailableReason:'MISSING_ADVANCE_PREREQUISITE'}],
    }, 'PURCHASE');
    await page.evaluate(`[...document.querySelectorAll('.advance-tile')].find(tile=>tile.querySelector('.advance-letter').textContent===${JSON.stringify(advance.letter)}).click()`);
    assert.equal(await page.evaluate(`document.querySelector('.decision-panel .advance-decision')`), null, 'Blocked advance inspection leaves the decision flow alone');
    assert.match(await page.evaluate(`document.querySelector('.inspection-panel .advance-decision .blocker').textContent`), /prerequisite/i);
    await page.evaluate(`document.querySelector('.advance-decision .close-detail').click()`);
    const dualReference = 'fixture-dual';
    await show('play-cards', [`play ${dualReference} commodity cloth`, `play ${dualReference} commodity wine`], {
        hand: [{reference:dualReference,definition:{id:'cloth-wine',kind:'commodity',messageId:'card.cloth-wine',shape:'CIRCLE'}}],
        cardOffers: [{reference:dualReference,playable:true,blockers:[],choices:[
            {kind:'COMMODITY',value:'cloth',command:`play ${dualReference} commodity cloth`},
            {kind:'COMMODITY',value:'wine',command:`play ${dualReference} commodity wine`}],commodities:['CLOTH','WINE'],leaderDiscounts:[],provinceTargets:[],requiredTargetCount:null,protectionMax:null}],
        commodityPayouts: [
            {commodity:'CLOTH',adjustments:['SURPLUS'],owners:[{player:viewer,provinces:['paris'],marketCount:1,effectiveBox:0,payout:0,industryBonus:0}]},
            {commodity:'WINE',adjustments:[],owners:[{player:other,provinces:['barcelona'],marketCount:1,effectiveBox:1,payout:5,industryBonus:0}]},
        ],
    });
    await page.wait(`document.querySelector('[data-hand-card="${dualReference}"]')`, 'dual commodity hand');
    assert.equal(await page.evaluate(`document.querySelectorAll('[data-hand-card="${dualReference}"] .card-commodity').length`), 2, 'Both commodity choices show current values');
    const clothOwners = await page.evaluate(`[...document.querySelector('[data-hand-card="${dualReference}"] .card-commodity').querySelectorAll('.owner-payout')].map(owner=>owner.textContent)`);
    assert.equal(clothOwners.length, 1, 'Card omits powers without qualifying ownership');
    assert(clothOwners[0].includes('$0'), 'A zero-paying owner remains visible on the card');
    await page.evaluate(`document.querySelector('#tab-markets').click()`);
    assert.equal(await page.evaluate(`document.querySelector('.market-tile .owner-payout').textContent`), clothOwners[0], 'Market and hand share the same owner payout');
    await page.evaluate(`document.querySelector('[data-hand-card="${dualReference}"]').click()`);
    await page.wait(`document.querySelectorAll('.card-decision .target-choices button').length === 2`, 'dual choice controls');
    const beforeChoice = await page.evaluate('window.uiCommands.length');
    await page.evaluate(`document.querySelector('.card-decision .target-choices button').click()`);
    assert.equal(await page.evaluate('window.uiCommands.length'), beforeChoice, 'Commodity selection prepares draft');
    await page.evaluate(`document.querySelector('.card-decision form > button:last-child').click()`);
    await page.wait(`window.uiCommands.at(-1)?.command === 'play ${dualReference} commodity cloth'`, 'dual commodity play');

    // Protection is an explicit amount choice. Invalid drafts must not fall back to a bare play.
    const reference = 'fixture-leader';
    await show('play-cards', [`play ${reference}`], {
        hand: [{reference, definition: {id:'leonardo-da-vinci', kind:'leader',messageId:'card.leonardo-da-vinci',shape:'SQUARE'}}],
        cardOffers: [{reference,playable:true,blockers:[],choices:[{kind:'NONE',value:null,command:`play ${reference}`},{kind:'PROTECTION',value:null,command:null}],
            commodities:[],leaderDiscounts:[{advance:'ARCHITECTURE',printedAmount:20,conditionalAmount:0,writtenRecordBonus:0,effectiveDiscount:20,condition:null}],provinceTargets:[],requiredTargetCount:null,protectionMax:5}],
    });
    await page.wait(`document.querySelector('[data-hand-card="${reference}"]')`, 'leader hand');
    assert.equal(await page.evaluate(`document.querySelector('[data-hand-card="${reference}"] .leader-discount strong').textContent`), '−$20', 'Leader hand shows its projected discount, distinct from a purchase price');
    await page.evaluate(`document.querySelector('[data-hand-card="${reference}"]').click()`);
    await page.wait(`document.querySelector('.card-decision input[type=number]')`, 'leader protection');
    assert.equal(await page.evaluate(`document.querySelector('.card-decision input[type=number]').value`), '0', 'Protection starts at zero');
    assert.equal(await page.evaluate(`document.querySelector('.card-decision form > button:last-child').disabled`), false, 'Default protection is ready to play');
    for (const amount of ['', '-1', '6']) {
        await page.fill('.card-decision input[type=number]', amount);
        assert.equal(await page.evaluate(`document.querySelector('.card-decision form > button:last-child').disabled`), true, `Invalid protection ${amount} cannot become a bare play`);
    }
    await page.fill('.card-decision input[type=number]', '3');
    await page.evaluate(`document.querySelector('.card-decision form > button:last-child').click()`);
    await page.wait(`window.uiCommands.at(-1)?.command === 'play ${reference} protect 3'`, 'valid protection submission');
    for (const [stage, prompt, id, kind] of [['FINAL_CARD_PLAY','final-card-play','grain','commodity'], ['PLAY_CARDS','prepare-war','gunpowder','event']]) {
        const cardReference = `fixture-${prompt}`;
        const command = `play ${cardReference}`;
        await show(prompt, [command], {
            hand:[{reference:cardReference,definition:{id,kind,messageId:`card.${id}`,shape:'SQUARE'}}],
            cardOffers:[{reference:cardReference,playable:true,blockers:[],choices:[{kind:'NONE',value:null,command}],commodities:[],leaderDiscounts:[],provinceTargets:[],requiredTargetCount:null,protectionMax:null}],
        }, stage);
        await page.evaluate(`document.querySelector('[data-hand-card="${cardReference}"]').click()`);
        await page.wait(`document.querySelector('.card-decision form > button:last-child:not([disabled])')`, prompt+' card draft');
        await page.evaluate(`document.querySelector('.card-decision form > button:last-child').click()`);
        await page.wait(`window.uiCommands.at(-1)?.command === ${JSON.stringify(command)}`, prompt+' canonical play');
    }
    console.log('Passed: setup return, immediate discard, Civil War, replacement, Renaissance, Written Record, market variant, trade offer, Patronage, Cathedral, Windmill, stabilization, relief and protection guard; turn tie, marker move/forgo, Buy Card/discard and all single-target card kinds.');
}
