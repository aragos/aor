import assert from 'node:assert/strict';
import {join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createGame, open} from './browser-support.mjs';
import {assertBottomHandVisible} from './bottomHand.browser.mjs';

export async function checkActivityWorkspace(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/ui-harness.html'});
    async function viewport(width, height, mobile) {
        // Native popup dismissal may activate a different Chrome target. Hidden targets defer
        // viewport and media events, so bring the actual table forward before testing reflow.
        await page.send('Page.bringToFront');
        await page.wait(`document.visibilityState==='visible'`, 'The table is visible before resizing');
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
    }
    await viewport(1280, 900, false);
    await page.wait(`typeof window.setUiSnapshot === 'function'`, 'StrictMode activity workspace harness');
    await page.evaluate(`localStorage.removeItem('aor.setting.pin-activity')`);
    const offer = baseline.game.expansionOffers.find(candidate => candidate.primaryAction === 'PLACE' &&
        candidate.establishesMarker && candidate.blockers.length === 0);
    assert(offer, 'The live expansion offers a legal placement to prepare');
    const source = {...baseline.events.at(-1), kind: 'PLAY_CARD', actor: baseline.viewerId,
        card: 'the-crusades', provinces: [], dice: [], target: null, amount: null, detail: null,
        competition: null, war: null, cashChanges: [], miseryChanges: [], tokenChanges: [],
        protectionCash: null, leaderRebate: null, drawReason: null, marketRoll: null, reliefPayment: null};
    let fixture = {...baseline, events: Array.from({length: 50}, (_, index) => ({...source, id: `workspace-event-${index}`}))};
    async function poll() {
        fixture = {...fixture, revision: fixture.revision + 1};
        await page.evaluate(`window.setUiSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`document.querySelector('.command-dock > summary small')?.textContent.trim() === 'Revision ${fixture.revision}'`, 'Activity application polling');
    }
    const click = selector => page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
    const settle = () => page.evaluate('document.fonts.ready.then(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))');
    const bounds = selector => page.evaluate(`(() => {const r=document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect();return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,height:r.height};})()`);
    const selected = () => page.evaluate(`document.querySelector('[data-map-province="${offer.destination}"]').getAttribute('aria-pressed')`);
    await poll();
    await page.wait(`document.querySelector('#tab-map[aria-selected="true"]')`, 'Expansion starts on its map');
    assert.equal(await page.evaluate(`document.querySelector('.workspace-side .table-activity, .activity-toggle, .activity-resizer')`), null,
        'Activity has no space or split controls in the action sidebar');
    await page.evaluate(`document.querySelector('[data-map-province="${offer.destination}"]').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))`);
    await page.wait(`document.querySelector('.primary-expansion:not([disabled])')`, 'Authoritative live placement draft');
    const draft = await page.evaluate(`document.querySelector('.primary-expansion').textContent`);
    await settle();
    const action = await bounds('.decision-panel');
    const footer = await bounds('.decision-actions');
    await click('#tab-activity');
    await page.wait(`document.querySelector('.activity-list').checkVisibility()`, 'Activity opens in the workspace');
    await settle();
    assert.deepEqual(await bounds('.decision-panel'), action, 'Activity navigation preserves the full action area');
    assert.deepEqual(await bounds('.decision-actions'), footer, 'Confirmation stays in its pinned footer');
    assert.equal(await selected(), 'true', 'Reading activity preserves the prepared map target');
    assert.equal(await page.evaluate(`document.querySelector('.primary-expansion').textContent`), draft);
    await assertBottomHandVisible(page, 'Activity tab beside a prepared placement');
    const activity = await bounds('#workspace-activity');
    assert(activity.width > action.width && activity.height > 100, 'Activity uses the main workspace');
    if (process.env.AOR_BROWSER_ARTIFACTS) await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-activity-tab.png'));
    await page.evaluate(`document.querySelector('.activity-list').focus()`);
    await page.send('Input.dispatchKeyEvent', {type:'keyDown',key:'PageDown',code:'PageDown',windowsVirtualKeyCode:34,nativeVirtualKeyCode:34});
    await page.send('Input.dispatchKeyEvent', {type:'keyUp',key:'PageDown',code:'PageDown',windowsVirtualKeyCode:34,nativeVirtualKeyCode:34});
    await page.wait(`document.querySelector('.activity-list').scrollTop>0`, 'The activity log scrolls with the keyboard');
    // Native PageDown animates independently of React; establish a settled baseline before testing
    // preservation so a still-running keyboard scroll cannot change the programmatic position.
    await page.wait(`(() => {
        const top = document.querySelector('.activity-list').scrollTop;
        if (window.activityKeyboardScroll !== top) {
            window.activityKeyboardScroll = top; window.activityKeyboardScrollAt = performance.now();
            return false;
        }
        return performance.now() - window.activityKeyboardScrollAt > 250;
    })()`, 'Keyboard scrolling settles before recording activity scroll');
    await page.evaluate(`window.activityNode=document.querySelector('.table-activity'); document.querySelector('.activity-list').scrollTop=220`);
    const scroll = await page.evaluate(`document.querySelector('.activity-list').scrollTop`);
    assert(scroll > 0, 'A long log exercises the activity scroller');
    await poll();
    assert.equal(await page.evaluate(`document.querySelector('#tab-activity').getAttribute('aria-selected')`), 'true', 'Polling retains the Activity tab');
    assert.equal(await page.evaluate(`document.querySelector('.activity-list').scrollTop`), scroll, 'Polling retains activity scroll');
    await click('#tab-map');
    await click('#tab-activity');
    assert.equal(await page.evaluate(`document.querySelector('.activity-list').scrollTop`), scroll, 'Switching tabs retains activity scroll');
    await page.evaluate(`document.querySelector('.activity-list').scrollTop=0; document.querySelector('.event-card').focus(); document.querySelector('.event-card').click()`);
    await page.wait(`document.querySelector('.card-inspection-dialog[open]')`, 'Activity source card opens in a popup');
    await poll();
    await page.send('Input.dispatchKeyEvent', {type:'keyDown',key:'Escape',code:'Escape',windowsVirtualKeyCode:27,nativeVirtualKeyCode:27});
    await page.send('Input.dispatchKeyEvent', {type:'keyUp',key:'Escape',code:'Escape',windowsVirtualKeyCode:27,nativeVirtualKeyCode:27});
    await page.wait(`!document.querySelector('.card-inspection-dialog')`, 'Activity inspection closes');
    assert.equal(await page.evaluate(`document.activeElement.matches('.event-card')`), true, 'Closing inspection returns focus to its activity card');
    assert.equal(await selected(), 'true', 'Inspection and polling preserve the prepared placement');
    assert.equal(await page.evaluate(`!!document.querySelector('[data-province-outline="${offer.destination}"][data-highlight-priority="4"]')`), true, 'Temporary card inspection preserves the prepared province border');
    await page.evaluate(`document.querySelector('.activity-list').scrollTop=220`);
    await viewport(1440, 980, false);
    await page.wait(`document.querySelector('.activity-pin')?.checkVisibility()`, 'Wide screens offer activity pinning');
    assert.equal(await page.evaluate(`document.querySelector('.activity-pin').getAttribute('aria-label')`), 'Pin activity to left');
    await settle();
    const readingEntry = () => page.evaluate(`(() => {
        const list = document.querySelector('.activity-list');
        const viewport = list.getBoundingClientRect();
        return [...list.querySelectorAll('li')].find(entry => entry.getBoundingClientRect().bottom > viewport.top + 1)?.dataset.eventId;
    })()`);
    const beforePin = await readingEntry();
    await click('.activity-pin');
    await page.wait(`!document.querySelector('#tab-activity') && document.querySelector('#workspace-activity').checkVisibility()`, 'Activity is pinned outside workspace tabs');
    await settle();
    const rail = await bounds('#workspace-activity');
    const main = await bounds('.workspace-main');
    const side = await bounds('.workspace-side');
    const hand = await bounds('.table-hand');
    assert(rail.width > 200 && rail.right <= main.left + 1 && main.right <= side.left + 1,
        `The left activity block, main workspace and actions have distinct columns: ${JSON.stringify({rail, main, side})}`);
    assert(rail.bottom >= hand.bottom - 1, 'The optional left block extends beside the hand');
    assert.equal(await page.evaluate(`document.querySelector('.table-activity')===window.activityNode`), true, 'Pinning keeps the same live activity surface');
    assert.equal(await readingEntry(), beforePin, 'Pinning retains the visible activity entry as text reflows');
    assert(await page.evaluate(`document.querySelector('.activity-list').scrollTop>0`), 'Pinning keeps the reader within the log');
    assert.equal(await selected(), 'true', 'Pinning retains the prepared placement');
    assert.equal(await page.evaluate(`document.querySelector('.activity-pin').getAttribute('aria-label')`), 'Return activity to tab');
    await assertBottomHandVisible(page, 'Pinned activity');
    await poll();
    assert.equal(await page.evaluate(`document.querySelector('#tab-activity')`), null, 'Polling retains the left block');
    if (process.env.AOR_BROWSER_ARTIFACTS) await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-activity-left.png'));
    await viewport(390, 844, true);
    await page.wait(`document.querySelector('#tab-activity')?.checkVisibility()`, 'Narrow screens restore activity tab access');
    await click('#tab-activity');
    await settle();
    assert.equal(await page.evaluate(`document.querySelector('.activity-pin')?.checkVisibility() ?? false`), false, 'Phones do not offer an unusable left block');
    assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`), true, 'Activity fits the phone width');
    await assertBottomHandVisible(page, 'Phone Activity tab');
    assert.equal(await selected(), 'true', 'Narrow fallback retains the prepared placement');
    await poll();
    assert.equal(await page.evaluate(`document.querySelector('#tab-activity').getAttribute('aria-selected')`), 'true', 'Narrow polling leaves Activity selected');
    if (process.env.AOR_BROWSER_ARTIFACTS) await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-activity-phone.png'));
    await viewport(1440, 980, false);
    await page.wait(`!document.querySelector('#tab-activity') && document.querySelector('.activity-pin')?.checkVisibility()`, 'Widening restores the preferred left block');
    await click('.activity-pin');
    await page.wait(`document.querySelector('#tab-activity[aria-selected="true"]')`, 'Unpinning returns directly to the Activity tab');
    assert.equal(await page.evaluate(`document.querySelector('.table-activity')===window.activityNode`), true, 'Tab and left block share one activity surface');
    assert.equal(await page.evaluate(`document.querySelectorAll('.table-activity').length`), 1, 'Activity is never duplicated');
    await page.evaluate(`document.querySelector('#tab-activity').focus()`);
    await page.send('Input.dispatchKeyEvent', {type:'keyDown',key:'Home',code:'Home'});
    await page.wait(`document.activeElement.id==='tab-map' && document.querySelector('#tab-map[aria-selected="true"]')`, 'Home selects the first workspace tab');
    await page.send('Input.dispatchKeyEvent', {type:'keyDown',key:'End',code:'End'});
    await page.wait(`document.activeElement.id==='tab-activity' && document.querySelector('#tab-activity[aria-selected="true"]')`, 'End includes Activity in workspace keyboard navigation');
    await page.send('Input.dispatchKeyEvent', {type:'keyDown',key:'ArrowRight',code:'ArrowRight'});
    await page.wait(`document.activeElement.id==='tab-map'`, 'Arrow navigation wraps from Activity to Map');
    await click('#tab-activity');
    const before = await page.evaluate('window.uiCommands.length');
    await page.evaluate(`window.uiNextError='INSUFFICIENT_EXPANSION_TOKENS'; document.querySelector('.primary-expansion').click()`);
    await page.wait(`window.uiCommands.length===${before + 1} && !document.querySelector('.primary-expansion').disabled`, 'Rejected placement returns to an editable draft');
    assert.equal(await selected(), 'true', 'Rejection preserves the placement while reading Activity');
    await click('.primary-expansion');
    await page.wait(`window.uiCommands.length===${before + 2} && !document.querySelector('.primary-expansion')`, 'Accepted placement clears its obsolete action context');
    assert.equal(await selected(), 'false', 'Successful cleanup remains separate from tab/polling preservation');
    assert.equal(await page.evaluate(`document.querySelector('#tab-activity').getAttribute('aria-selected')`), 'true', 'Submitting an action does not redirect Activity');
    await click('#tab-map');
    await page.evaluate(`document.querySelector('[data-map-province="${offer.destination}"]').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))`);
    await page.wait(`document.querySelector('.primary-expansion:not([disabled])')`, 'Prepare another placement for inspection dismissal');
    await page.evaluate(`document.querySelector('[data-map-terrain]').dispatchEvent(new MouseEvent('click',{bubbles:true}))`);
    assert.equal(await selected(), 'false', 'Background clicks dismiss the selected province indication');
    await click('#tab-activity');
    await page.evaluate(`document.querySelector('.activity-list').scrollTop=0; document.querySelector('.event-card').click()`);
    await page.wait(`document.querySelector('.card-inspection-dialog[open]')`, 'Inspect a card after dismissing the province border');
    await click('.card-inspection-dialog .close-detail');
    assert.equal(await selected(), 'false', 'Closing a later popup does not resurrect the dismissed province border');
    assert.equal(await page.evaluate(`!!document.querySelector('[data-province-outline="${offer.destination}"][data-highlight-priority="4"]')`), false);
    assert.equal(await page.evaluate(`document.querySelector('.primary-expansion').textContent`), draft, 'Dismissing the border and reading a popup preserve the placement draft');
    await click('#tab-map');
    await page.evaluate(`document.querySelector('[data-map-province="${offer.destination}"]').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))`);
    assert.equal(await selected(), 'true', 'Explicitly selecting the same province restores its border');
    console.log('Passed: Activity workspace and optional left block, real-offer draft preservation, scrolling, card inspection, polling, responsive fallback, keyboard navigation and accepted/rejected cleanup.');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    const web = process.env.MAP_WEB_URL;
    const game = await createGame(6);
    for (const player of game.links) await game.send(player, 'bid-capital 0');
    let baseline;
    for (let step = 0; step < 100; step++) {
        const state = await game.snapshot(game.links[0]);
        const actor = game.links.find(player => state.prompt?.players.includes(player.id));
        assert(actor, `Activity setup has an actor at ${state.game.stage}`);
        baseline = await game.snapshot(actor);
        if (baseline.game.stage === 'EXPANSION') break;
        const commands = baseline.legalChoices.commands;
        const command = baseline.game.stage === 'TOKEN_PURCHASE' ? `buy-tokens ${8 + game.links.indexOf(actor)}`
            : baseline.game.stage === 'CAPITAL_CHOICE' ? commands[0]
                : baseline.game.activity?.purchaseStep === 'SHIPPING' && commands.includes('upgrade-ships') ? 'upgrade-ships'
                    : commands.find(candidate => candidate === 'pass') ?? commands.find(candidate => candidate === 'stabilize cash') ?? commands[0];
        assert(command, `Activity setup has a command at ${baseline.game.stage}`);
        await game.send(actor, command);
    }
    assert.equal(baseline.game.stage, 'EXPANSION');
    const page = await open(web);
    try { await checkActivityWorkspace(page, web, baseline); }
    catch (error) {
        console.error('Activity failure geometry:', await page.evaluate(`(() => {
            const pin = document.querySelector('.activity-pin');
            const activity = document.querySelector('#workspace-activity');
            return {wide: matchMedia('(min-width: 1400px)').matches, innerWidth,
                visibility: document.visibilityState,
                tab: document.querySelector('.workspace-tabs [aria-selected="true"]')?.id,
                pinExists: !!pin, pinVisible: pin?.checkVisibility(), activityHidden: activity?.hidden,
                pinned: document.querySelector('.table-workspace')?.dataset.activityPinned,
                storedPin: localStorage.getItem('aor.setting.pin-activity'),
                dialogOpen: !!document.querySelector('dialog[open]'),
                activity: activity ? {height: activity.clientHeight, width: activity.clientWidth} : null};
        })()`));
        if (process.env.AOR_BROWSER_ARTIFACTS) await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-activity-failure.png'));
        throw error;
    }
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
