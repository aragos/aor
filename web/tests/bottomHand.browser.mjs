import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';

/** Check the rendered hand, including clipping and pointer access, rather than DOM presence. */
export async function assertBottomHandVisible(page, label) {
    const geometry = await page.evaluate(`(() => {
        const hand = document.querySelector('.table-hand');
        const cards = hand.querySelector('.hand-cards');
        const bounds = hand.getBoundingClientRect();
        const contents = cards.getBoundingClientRect();
        const decision = document.querySelector('.decision-panel').getBoundingClientRect();
        const point = {x: contents.left + Math.min(30, contents.width / 2), y: contents.top + Math.min(18, contents.height / 2)};
        const hit = document.elementFromPoint(point.x, point.y);
        return {
            viewport: {width: innerWidth, height: innerHeight},
            hand: {left: bounds.left, right: bounds.right, top: bounds.top, bottom: bounds.bottom, height: bounds.height},
            cards: {height: cards.clientHeight, width: cards.clientWidth},
            handReachable: !!hit?.closest('.table-hand'),
            modalOpen: !!document.querySelector('.card-inspection-dialog:modal'),
            decisionOverlaps: Math.min(bounds.right, decision.right) > Math.max(bounds.left, decision.left) + 1 &&
                Math.min(bounds.bottom, decision.bottom) > Math.max(bounds.top, decision.top) + 1,
            pageOverflow: document.documentElement.scrollWidth > innerWidth,
        };
    })()`);
    const {hand, viewport, cards} = geometry;
    assert(hand.left >= -1 && hand.right <= viewport.width + 1 && hand.top >= -1 &&
        hand.bottom <= viewport.height + 1 && hand.height > 50, `${label}: hand is in the viewport: ${JSON.stringify(geometry)}`);
    assert(cards.height > 20 && cards.width > 100, `${label}: cards have usable space`);
    assert(geometry.handReachable || geometry.modalOpen, `${label}: hand remains reachable except while its reference popup is modal`);
    assert.equal(geometry.decisionOverlaps, false, `${label}: hand and decision reserve separate space: ${JSON.stringify(geometry)}`);
    assert.equal(geometry.pageOverflow, false, `${label}: no horizontal page overflow`);
}

async function assertControlReachable(page, selector, label) {
    await page.evaluate(`document.querySelector(${JSON.stringify(selector)}).scrollIntoView({block:'nearest',inline:'nearest',behavior:'instant'})`);
    const reachable = await page.evaluate(`(() => {
        const button = document.querySelector(${JSON.stringify(selector)});
        const rect = button.getBoundingClientRect();
        const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
        return rect.top >= 0 && rect.bottom <= innerHeight && !button.disabled && (hit === button || button.contains(hit));
    })()`);
    assert.equal(reachable, true, `${label}: control is reachable without covering the hand`);
    await assertBottomHandVisible(page, label);
}

async function assertEffectReachable(page, selector, label) {
    await page.evaluate(`document.querySelector(${JSON.stringify(selector)}).scrollIntoView({block:'center',inline:'nearest',behavior:'instant'})`);
    await page.evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
    const geometry = await page.evaluate(`(() => {
        const effect = document.querySelector(${JSON.stringify(selector)});
        const dimensions = element => {
            const rect = element.getBoundingClientRect();
            return {left:rect.left,top:rect.top,right:rect.right,bottom:rect.bottom,width:rect.width,height:rect.height};
        };
        const bounds = dimensions(effect);
        const decision = dimensions(document.querySelector('.decision-panel'));
        const side = dimensions(document.querySelector('.workspace-side'));
        const confirmation = dimensions(document.querySelector('.decision-panel .card-confirmation'));
        const hit = document.elementFromPoint(bounds.left+bounds.width/2,bounds.top+bounds.height/2);
        return {effect:bounds,decision,side,confirmation,visible:effect.checkVisibility() &&
            bounds.top >= Math.max(0,decision.top,side.top)-1 &&
            bounds.bottom <= Math.min(innerHeight,decision.bottom,side.bottom,confirmation.top)+1 &&
            !!hit && (hit===effect || effect.contains(hit)),hit:hit?.tagName+'.'+(hit?.getAttribute('class')??'')};
    })()`);
    assert.equal(geometry.visible, true, `${label}: chosen effect can be read above confirmation: ${JSON.stringify(geometry)}`);
    await assertBottomHandVisible(page, label);
}

export async function checkBottomHand(page, web, baseline) {
    let progress = 'initializing';
    const mark = step => {progress = step; console.log('Bottom hand: ' + step);};
    try {
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'bottom hand harness');
    const viewer = baseline.viewerId;
    const other = baseline.game.players.find(player => player.id !== viewer).id;
    const definitions = baseline.game.cardInspections;
    const definition = id => {
        const card = definitions.find(card => card.definition.id === id)?.definition;
        assert(card, `Catalog supplies ${id}`);
        return card;
    };
    const hand = [
        {reference: 'bottom-commodity', definition: definition('cloth-wine')},
        {reference: 'bottom-target', definition: definition('the-crusades')},
        {reference: 'bottom-leader', definition: definition('leonardo-da-vinci')},
        {reference: 'bottom-burden', definition: definition('stirrups')},
    ];
    const commodityOffer = {reference: 'bottom-commodity', playable: true, blockers: [],
        commodities: ['CLOTH', 'WINE'], leaderDiscounts: [], provinceTargets: [], requiredTargetCount: null, protectionMax: null,
        choices: [
            {kind: 'COMMODITY', value: 'cloth', command: 'play bottom-commodity commodity cloth', cashChanges: [{player: viewer, before: 80, after: 85}]},
            {kind: 'COMMODITY', value: 'wine', command: 'play bottom-commodity commodity wine', cashChanges: [{player: viewer, before: 80, after: 100}, {player: other, before: 40, after: 85}]},
        ]};
    const targetOffer = {reference: 'bottom-target', playable: true, blockers: [], commodities: [], leaderDiscounts: [],
        choices: [{kind: 'PROVINCE', value: 'jerusalem', command: 'play bottom-target province jerusalem',
            miseryChanges: [{player: viewer, spaces: 1, fromValue: 20, toValue: 30}]}],
        provinceTargets: [], requiredTargetCount: null, protectionMax: null};
    const advance = baseline.game.catalog.advances.find(advance => advance.id === 'NATIONALISM');
    assert(advance, 'Catalog supplies a purchasable advance');
    const commonGame = {
        turnNumber: baseline.game.turnNumber ?? 1,
        currentEpoch: 3,
        hand,
        cardOffers: [],
        expansionOffers: [],
        advanceOffers: [],
        burdenReferences: ['bottom-burden'],
        players: baseline.game.players.map((player, index) => ({...player,
            capital: player.capital ?? ['venice', 'genoa', 'barcelona', 'paris', 'london', 'hamburg'][index],
            cash: player.id === viewer ? 80 : 40, misery: 20, poolAvailable: true, poolTokens: 8})),
        commodityPayouts: [
            {commodity: 'CLOTH', adjustments: [], owners: [{player: viewer, provinces: ['genoa'], marketCount: 1, effectiveBox: 1, payout: 5, industryBonus: 0}]},
            {commodity: 'WINE', adjustments: ['SHORTAGE'], owners: [{player: viewer, provinces: ['venice'], marketCount: 1, effectiveBox: 2, payout: 20, industryBonus: 0},
                {player: other, provinces: ['lisbon', 'barcelona'], marketCount: 2, effectiveBox: 3, payout: 45, industryBonus: 0}]},
        ],
    };
    let revision = 26000;
    let fixture;
    async function show(choice, stage, changes = {}, actor = viewer) {
        await page.send('Page.bringToFront');
        await page.evaluate(`document.querySelector('.card-inspection-dialog[open] .close-detail')?.click()`);
        fixture = {...baseline, revision: ++revision, events: [], pendingBid: null, submitted: [],
            prompt: {messageId: `prompt.${choice}`, players: [actor]},
            legalChoices: {commands: ['pass'], bidMax: null, bidMin: null},
            ...changes,
            game: {...baseline.game, ...commonGame, stage,
                activity: {...baseline.game.activity, actor, decisionPlayer: actor, choice, purchaseStep: null},
                ...changes.game}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, `${choice} bottom hand fixture`);
        await page.evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
    }
    const click = selector => page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
    const target = () => page.evaluate(`document.querySelector('[data-map-province="jerusalem"]').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))`);
    for (const [width, height, mobile] of [[1440, 980, false], [1280, 800, false], [390, 844, true]]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        const viewport = `${width}×${height}`;
        mark(viewport + ' commodity and layout');
        const capture = async situation => {
            if (process.env.AOR_BROWSER_ARTIFACTS && width !== 1440) {
                await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-bottom-hand-${width}-${situation}.png`));
            }
        };
        await show('play-cards', 'PLAY_CARDS', {game: {cardOffers: [commodityOffer, targetOffer]},
            legalChoices: {commands: ['play bottom-commodity commodity cloth', 'play bottom-commodity commodity wine', 'play bottom-target province jerusalem', 'pass']}});
        assert.equal(await page.evaluate(`document.querySelectorAll('.player-summary').length`), baseline.game.players.length);
        assert.equal(await page.evaluate(`document.querySelector('.hand-heading, .hand-title')`), null, 'The hand has no redundant visible heading or card count');
        assert.equal(await page.evaluate(`!!document.querySelector('.hand-toggle')`), mobile, 'Expand is shown only when the four-card hand needs more room');
        const cardHeights = await page.evaluate(`[...document.querySelectorAll('.hand-card')].map(card=>card.getBoundingClientRect().height)`);
        assert(Math.max(...cardHeights)-Math.min(...cardHeights)<1, 'Compact cards share the height required by the tallest content');
        if (!mobile) {
            const columns=await page.evaluate(`(() => {const hand=document.querySelector('.table-hand').getBoundingClientRect();const map=document.querySelector('.workspace-main').getBoundingClientRect();const action=document.querySelector('.decision-panel').getBoundingClientRect();return {handLeft:hand.left,handRight:hand.right,mapLeft:map.left,mapRight:map.right,handBottom:hand.bottom,actionBottom:action.bottom,actionHeight:action.height};})()`);
            assert(Math.abs(columns.handLeft-columns.mapLeft)<1 && Math.abs(columns.handRight-columns.mapRight)<1, 'The desktop hand aligns with the map column');
            assert(columns.actionBottom>=columns.handBottom-1 && columns.actionHeight>100, 'Actions use the full sidebar alongside the hand');
        }
        await assertBottomHandVisible(page, `${viewport} commodity choice`);
        assert.equal(await page.evaluate(`document.querySelector('[data-eligible-card], .decision-cards')`), null, 'The hand is the only playable-card list');
        await click('[data-hand-card="bottom-commodity"]');
        assert.equal(await page.evaluate(`document.querySelector('.card-inspection-dialog[open], .inspection-panel')`), null, 'Commodity selection needs no inspector');
        assert.equal(await page.evaluate(`document.querySelectorAll('.decision-panel .card-decision').length`), 1, 'One selected-card action context');
        await page.evaluate(`document.querySelector('.card-decision .target-choices button:last-child').click()`);
        assert.match(await page.evaluate(`document.querySelector('.decision-panel .cash-preview').textContent`), /\$80 → \$100/);
        assert.equal(await page.evaluate(`!!document.querySelector('.cash-preview .identity-label svg')`), true, 'Cash preview retains the capital silhouette');
        await assertControlReachable(page, '.decision-actions .play-card-action', `${viewport} commodity preview`);
        await assertEffectReachable(page, '.decision-panel .cash-preview', `${viewport} commodity consequence`);
        await capture('commodity');

        mark(viewport + ' event selection and effects');
        await click('[data-hand-card="bottom-target"]');
        await page.wait(`document.querySelector('[data-map-province="jerusalem"].eligible')`, 'Hand-selected event activates targets');
        await target();
        assert.equal(await page.evaluate(`document.querySelector('.card-inspection-dialog[open], .inspection-panel')`), null, 'Selected event does not also open an inspector');
        assert.match(await page.evaluate(`document.querySelector('.decision-heading h2').textContent`), /Crusades/);
        assert.equal(await page.evaluate(`document.querySelector('.decision-panel .card-decision h2')`), null, 'The selected card has one title in the decision heading');
        assert.equal(await page.evaluate(`document.querySelector('.decision-panel .card-effect-detail')`), null, 'Selected effects do not require opening a rules disclosure');
        assert.equal(await page.evaluate(`document.querySelector('.card-play-effects .card-event-text').checkVisibility()`), true, 'The selected event spells out its effects');
        assert.match(await page.evaluate(`document.querySelector('.card-play-effects').textContent`), /Area VI.*Jerusalem/s);
        assert.match(await page.evaluate(`document.querySelector('.misery-preview').textContent`), /20.*30/);
        await assertControlReachable(page, '.decision-actions .play-card-action', `${viewport} event targeting`);
        await assertEffectReachable(page, `.decision-panel [data-misery-preview-player="${viewer}"]`, `${viewport} event consequence`);
        await capture('target');
        const commandCount = await page.evaluate('window.mapCommands.length');
        if (await page.evaluate(`!!document.querySelector('.hand-toggle')`)) {
            await click('.hand-toggle');
            assert.equal(await page.evaluate(`document.querySelector('.hand-toggle').getAttribute('aria-expanded')`), 'true');
            await assertBottomHandVisible(page, `${viewport} expanded hand`);
            await click('.hand-toggle');
        }
        assert.equal(await page.evaluate(`document.querySelector('.decision-actions .play-card-action').disabled`), false, 'Expanding the hand preserves the event target');
        assert.equal(await page.evaluate('window.mapCommands.length'), commandCount, 'Expanding the hand cannot submit');

        mark(viewport + ' purchase and popup');
        await show('purchase-advances', 'PURCHASE', {game: {
            activity: {...baseline.game.activity, actor: viewer, decisionPlayer: viewer, choice: 'purchase-advances', purchaseStep: 'ADVANCES'},
            advanceOffers: [{advance: advance.id, price: 30, unavailableReason: null}],
        }, legalChoices: {commands: [`buy-advance ${advance.letter}`, 'pass']}});
        await click(`[data-advance="${advance.id}"]`);
        await page.evaluate(`(() => {const card=document.querySelector('[data-hand-card="bottom-leader"]');card.focus();card.click();})()`);
        assert.equal(await page.evaluate(`!!document.querySelector('.card-inspection-dialog .card-decision')`), true, 'Held leader remains inspectable during purchase');
        assert.equal(await page.evaluate(`!!document.querySelector('.decision-panel .advance-decision')`), true, 'Card inspection preserves the prepared purchase');
        await assertBottomHandVisible(page, `${viewport} advance purchase`);
        assert.equal(await page.evaluate(`document.querySelector('.card-inspection-dialog').matches(':modal')`), true, 'Card reference opens a modal popup');
        const preparedPurchase = await page.evaluate(`document.querySelector('.decision-panel .advance-decision').textContent`);
        mark(viewport + ' popup polling');
        const referencePoll = {...fixture, revision: ++revision};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(referencePoll)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'Reference survives polling');
        assert.equal(await page.evaluate(`document.querySelector('.card-inspection-dialog').open`), true);
        mark(viewport + ' popup Escape');
        await page.send('Page.bringToFront');
        await page.send('Input.dispatchKeyEvent', {type:'keyDown',key:'Escape',code:'Escape',windowsVirtualKeyCode:27,nativeVirtualKeyCode:27});
        await page.send('Input.dispatchKeyEvent', {type:'keyUp',key:'Escape',code:'Escape',windowsVirtualKeyCode:27,nativeVirtualKeyCode:27});
        await page.wait(`!document.querySelector('.card-inspection-dialog[open]')`, 'Escape dismisses card popup');
        assert.equal(await page.evaluate(`document.activeElement?.dataset.handCard`), 'bottom-leader', 'Popup restores focus to its source card');
        assert.equal(await page.evaluate(`document.querySelector('.decision-panel .advance-decision').textContent`), preparedPurchase, 'Popup polling and dismissal preserve the purchase draft');
        await page.evaluate(`(() => {const card=document.querySelector('[data-hand-card="bottom-leader"]');card.focus();card.click();})()`);
        mark(viewport + ' popup backdrop');
        await page.wait(`document.querySelector('.card-inspection-dialog:modal')`, 'Reference reopens without replacing the action');
        await page.send('Input.dispatchMouseEvent',{type:'mousePressed',x:2,y:2,button:'left',clickCount:1});
        await page.send('Input.dispatchMouseEvent',{type:'mouseReleased',x:2,y:2,button:'left',clickCount:1});
        await page.wait(`!document.querySelector('.card-inspection-dialog[open]')`, 'Backdrop dismisses card popup');
        assert.equal(await page.evaluate(`document.querySelector('.decision-panel .advance-decision').textContent`), preparedPurchase, 'Backdrop dismissal leaves the purchase draft intact');
        await assertControlReachable(page, '.decision-actions .buy-advance-action', `${viewport} purchase confirmation`);
        assert.equal(await page.evaluate(`document.querySelector('.held-leader-note, [data-held-card], .waiting-hand-summary')`), null, 'No mirrored held-card reminders accompany another decision');
        await capture('purchase');

        mark(viewport + ' expansion');
        await show('expand', 'EXPANSION', {game: {expansionOffers: [{destination: 'rome', primaryAction: 'PLACE', requiredTokens: 2,
            availablePool: 8, resultingPool: 6, establishesMarker: true, uncertain: false, blockers: [], partialPlacements: [],
            shipping: null, incomeBefore: 15, incomeAfter: 18, reachable: true}]},
            legalChoices: {commands: ['place 2 rome', 'pass']}});
        await page.evaluate(`document.querySelector('[data-map-province="rome"]').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))`);
        const draft = await page.evaluate(`document.querySelector('.map-orders').textContent`);
        await click('[data-hand-card="bottom-target"]');
        await click('.card-inspection-dialog .close-detail');
        assert.equal(await page.evaluate(`document.querySelector('.map-orders').textContent`), draft, 'Consulting a held event preserves the prepared placement');
        await assertControlReachable(page, '.primary-expansion', `${viewport} expansion`);
        await capture('expansion');

        mark(viewport + ' waiting and tools');
        await show('expand', 'EXPANSION', {legalChoices: {commands: []}}, other);
        await assertBottomHandVisible(page, `${viewport} waiting`);
        await capture('waiting');
        await click('[data-hand-card="bottom-burden"]');
        assert.equal(await page.evaluate(`document.querySelector('.decision-panel .card-decision form')`), null, 'Waiting inspection cannot prepare a card play');
        await assertBottomHandVisible(page, `${viewport} waiting inspection`);
        if (process.env.AOR_BROWSER_ARTIFACTS) await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-bottom-hand-${width}.png`), width, height, mobile);
        await click('.card-inspection-dialog .close-detail');

        assert.equal(await page.evaluate(`document.querySelector('.table-effects')`), null, 'Turn information has no bottom disclosure');
        if (mobile) await click('#tab-players');
        for (const selector of ['.table-configuration', '.command-dock']) {
            await assertControlReachable(page, `${selector} > summary`, `${viewport} auxiliary expander ${selector}`);
            await click(`${selector} > summary`);
            await page.wait(`document.querySelector('${selector}').open`, `${selector} opens`);
            await assertBottomHandVisible(page, `${viewport} open ${selector}`);
        }
        assert.equal(await page.evaluate(`document.querySelector('.command-private-hand, .command-dock [data-hand-card]')`), null, 'Commands do not repeat the private hand');
        await capture('tools');
        for (const selector of ['.table-configuration', '.command-dock']) {
            await click(`${selector} > summary`);
            await page.wait(`!document.querySelector('${selector}').open`, `${selector} closes`);
        }
    }

    for (const [width,height,mobile] of [[1280,800,false],[390,844,true]]) {
        await page.send('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile});
        await show('expand','EXPANSION',{game:{hand:[hand[3]],burdenReferences:[]},legalChoices:{commands:[]}},other);
        await page.wait(`document.querySelector('.table-hand[data-overflow="${mobile}"]')`, 'Small hand expansion follows the viewport');
        assert.equal(await page.evaluate(`!!document.querySelector('.hand-toggle')`), mobile, 'A name-only phone hand always offers expansion');
        assert(await page.evaluate(`document.querySelector('.table-hand').getBoundingClientRect().height<150`),'A short one-card hand follows its content height');
        await assertBottomHandVisible(page,`${width} short natural hand`);
    }

    mark('small-hand expansion visibility passed; dense payout and expanded tools');
    const densePayouts = commonGame.commodityPayouts.map(payout => ({...payout,
        owners: commonGame.players.map(player => ({player: player.id, provinces: [player.capital],
            marketCount: 1, effectiveBox: 1, payout: 5, industryBonus: 0})),
    }));
    for (const [width, height, mobile] of [[1280, 800, false], [390, 844, true]]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        await show('expand', 'EXPANSION', {game: {commodityPayouts: densePayouts, hand: [...hand, ...hand.map((card,index)=>({...card,reference:`dense-extra-${index}`}))]}, legalChoices: {commands: []}}, other);
        await page.evaluate(`document.querySelector('[data-hand-card="bottom-commodity"]').focus()`);
        await assertBottomHandVisible(page, `${width} dense dual-commodity summary`);
        const titleVisible = await page.evaluate(`(() => {
            const title = document.querySelector('[data-hand-card="bottom-commodity"] h3').getBoundingClientRect();
            const hand = document.querySelector('.hand-cards').getBoundingClientRect();
            return title.top >= hand.top && title.bottom <= hand.bottom && title.left >= hand.left && title.right <= hand.right;
        })()`);
        assert.equal(titleVisible, true, 'Focusing a dense compact card keeps its title visible');
        if (process.env.AOR_BROWSER_ARTIFACTS) await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-bottom-hand-${width}-six-owners.png`));
        await click('.hand-toggle');
        await page.wait(`document.querySelector('.table-hand.hand-expanded')`, 'Dense hand expands');
        await page.evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
        if (process.env.AOR_BROWSER_ARTIFACTS) await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-bottom-hand-${width}-six-owners-expanded.png`));
        assert.equal(await page.evaluate(`document.querySelectorAll('[data-hand-card="bottom-commodity"] .owner-payout').length`), commonGame.players.length * 2, 'Both commodities retain all projected owner payouts');
        const clipping = await page.evaluate(`(() => {
            const card = document.querySelector('[data-hand-card="bottom-commodity"]');
            const bounds = card.getBoundingClientRect();
            const dimensions = rect => ({left:rect.left,top:rect.top,right:rect.right,bottom:rect.bottom,width:rect.width,height:rect.height});
            return [...card.querySelectorAll('.owner-payout')].filter(owner => {
                const rect = owner.getBoundingClientRect();
                return rect.left < bounds.left || rect.right > bounds.right || rect.top < bounds.top || rect.bottom > bounds.bottom;
            }).map(owner => ({text:owner.textContent,owner:dimensions(owner.getBoundingClientRect()),card:dimensions(bounds)}));
        })()`);
        assert.deepEqual(clipping, [], 'Expanded dual-commodity payouts fit their card without clipping amounts');
        const expandedHeights = await page.evaluate(`[...document.querySelectorAll('.hand-card')].map(card=>card.getBoundingClientRect().height)`);
        assert(Math.max(...expandedHeights)-Math.min(...expandedHeights)<1, 'Expanded cards share equal heights across rows');
        await assertBottomHandVisible(page, `${width} expanded dual-commodity values`);

        if (mobile) await click('#tab-players');
        await assertControlReachable(page, '.player-summary.is-viewer > summary', `${width} expanded hand and player details`);
        await click('.player-summary.is-viewer > summary');
        await page.wait(`document.querySelector('.player-summary.is-viewer').open`, 'Viewer details open');
        await click('.player-summary:has(.player-detail:popover-open) > summary');
        await page.wait(`!document.querySelector('.player-detail:popover-open')`, 'Player detail closes before returning to tools');
        await assertControlReachable(page, '.command-dock > summary', `${width} expanded hand and Commands`);
        await click('.command-dock > summary');
        await page.wait(`document.querySelector('.command-dock').open`, 'Commands open after closing player details with the hand expanded');
        await page.evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
        if (process.env.AOR_BROWSER_ARTIFACTS) await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-bottom-hand-${width}-expanded-and-tools.png`));
        await assertBottomHandVisible(page, `${width} expanded hand with Commands after player inspection`);
        await click('.command-dock > summary');
        await page.wait(`!document.querySelector('.command-dock').open`, 'Combined Commands closes');
        await click('.hand-toggle');
    }

    await page.send('Emulation.setDeviceMetricsOverride', {width: 390, height: 844, deviceScaleFactor: 1, mobile: true});
    mark('finite discard/trade and large-hand keyboard/polling');
    for (const action of ['discard-card', 'offer-card']) {
        const commands = [`${action} bottom-commodity`, `${action} bottom-target`];
        await show(action === 'discard-card' ? 'buy-card' : 'offer-card', action === 'discard-card' ? 'BUY_CARD' : 'PLAY_CARDS',
            {legalChoices: {commands}});
        const count = await page.evaluate('window.mapCommands.length');
        await click('[data-hand-card="bottom-commodity"]');
        await page.wait(`document.querySelector('.sheet-toggle[aria-expanded="true"]') && document.querySelector('.selected-action-confirmation:not([disabled])')`, `${action} prepares a review`);
        await click('.sheet-toggle');
        assert.equal(await page.evaluate(`document.querySelector('.sheet-toggle').getAttribute('aria-expanded')`), 'false', 'Phone review can be minimized');
        await click('[data-hand-card="bottom-target"]');
        await page.wait(`document.querySelector('.sheet-toggle[aria-expanded="true"]') && document.querySelector('.selected-action-confirmation').checkVisibility()`, `${action} selection reopens review`);
        assert.match(await page.evaluate(`document.querySelector('.selected-action-confirmation').textContent`), /Crusades/);
        await assertControlReachable(page, '.selected-action-confirmation', `${action} reopens phone confirmation`);
        assert.equal(await page.evaluate('window.mapCommands.length'), count, 'Changing a finite hand selection only edits the draft');
    }

    await page.send('Emulation.setDeviceMetricsOverride', {width: 1280, height: 800, deviceScaleFactor: 1, mobile: false});
    const largeHand = Array.from({length: 14}, (_, index) => ({reference: `large-${index}`, definition: hand[index % hand.length].definition}));
    await show('play-cards', 'PLAY_CARDS', {game: {hand: largeHand}});
    await page.wait(`document.querySelector('.hand-cards[data-draggable="true"]')`, 'Large hand has discoverable horizontal overflow');
    await page.evaluate(`document.querySelector('[data-hand-card="large-13"]').focus()`);
    const focused = await page.evaluate(`(() => {
        const card = document.activeElement.getBoundingClientRect();
        const hand = document.querySelector('.hand-cards').getBoundingClientRect();
        return card.left >= hand.left - 1 && card.right <= hand.right + 1;
    })()`);
    assert.equal(focused, true, 'Keyboard focus brings an offscreen held card into view');
    const scrolled = await page.evaluate(`document.querySelector('.hand-cards').scrollLeft`);
    await page.evaluate(`window.setMapSnapshot(${JSON.stringify({...fixture, revision: ++revision})})`);
    await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'Polling update with the same hand');
    assert.equal(await page.evaluate(`document.querySelector('.hand-cards').scrollLeft`), scrolled, 'Polling preserves the horizontal hand viewport');
    await click('.hand-toggle');
    assert.equal(await page.evaluate(`document.querySelectorAll('.table-hand.hand-expanded [data-hand-card]').length`), 14, 'Expanded hand exposes the same fourteen cards');
    assert.equal(await page.evaluate(`document.querySelectorAll('.table-hand').length`), 1, 'Expansion does not create a duplicate hand');
    const count = await page.evaluate('window.mapCommands.length');
    await click('[data-hand-card="large-1"]');
    await page.wait(`document.querySelector('.card-inspection-dialog:modal')`, 'Expanded event opens its reference popup');
    assert.equal(await page.evaluate('window.mapCommands.length'), count, 'Expanded reference selection cannot submit an order');
    await assertBottomHandVisible(page, 'Large expanded hand');
    await click('.card-inspection-dialog .close-detail');
    console.log('Passed: persistent bottom hand across card, target, purchase, expansion and waiting flows; reserved phone geometry, auxiliary tools with expanded hand/player details, single action contexts, six-owner commodity detail, finite selection reopening and polling scroll continuity.');
    } catch (error) {
        console.error('Bottom hand failed during: ' + progress);
        if (process.env.AOR_BROWSER_ARTIFACTS) {
            await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-bottom-hand-failure.png')).catch(captureError => console.error('Failure capture: ' + captureError.message));
        }
        throw error;
    }
}

if (process.argv[1]?.endsWith('/bottomHand.browser.mjs')) {
    const game = await createGame(6);
    const baseline = await game.snapshot(game.links[0]);
    const page = await open('about:blank');
    try { await checkBottomHand(page, process.env.MAP_WEB_URL, baseline); }
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
