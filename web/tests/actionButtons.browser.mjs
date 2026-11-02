import assert from 'node:assert/strict';
import {join} from 'node:path';
import {waitForMap} from './browser-support.mjs';

export async function checkActionButtons(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.send('Page.bringToFront');
    await page.send('Emulation.setEmulatedMedia', {features: [{name: 'prefers-reduced-motion', value: 'reduce'}]});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'action footer harness');
    const viewer = baseline.viewerId;
    const stone = baseline.game.cardInspections.find(card => card.definition.id === 'stone-1').definition;
    let revision = 42000;
    let fixture;
    async function show(changes = {}) {
        fixture = {...baseline, prompt: {messageId: 'prompt.play-cards', players: [viewer]},
            undo: {...baseline.undo, available: true}, legalChoices: {commands: ['play footer-stone commodity stone', 'pass']},
            ...changes, revision: ++revision, game: {...baseline.game, stage: 'PLAY_CARDS',
                hand: [{reference: 'footer-stone', definition: stone}],
                cardOffers: [{reference: 'footer-stone', playable: true, blockers: [], commodities: ['STONE'],
                    leaderDiscounts: [], provinceTargets: [], requiredTargetCount: null, protectionMax: null,
                    choices: [{kind: 'COMMODITY', value: 'stone', command: 'play footer-stone commodity stone'}]}],
                ...changes.game}};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'action footer snapshot');
        await waitForMap(page, fixture);
        await page.evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
    }
    const click = selector => page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
    const geometry = () => page.evaluate(`(() => {
        const bounds=element=>{const r=element.getBoundingClientRect();return {top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height};};
        const footer=document.querySelector('.decision-actions');
        const panel=document.querySelector('.decision-panel');
        return {footer:bounds(footer),panel:bounds(panel),undo:bounds(document.querySelector('.undo-action')),
            buttons:[...footer.querySelectorAll('button')].map(button=>({text:button.textContent.trim(),className:button.className,...bounds(button),
                clipped:button.scrollWidth>button.clientWidth+1 || button.scrollHeight>button.clientHeight+1})),
            gap:getComputedStyle(footer).columnGap, layout:footer.dataset.layout,
            count:footer.dataset.actionCount};
    })()`);
    const pinned = async label => {
        await page.evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
        await page.wait(`(() => {const p=document.querySelector('.decision-panel').getBoundingClientRect();const f=document.querySelector('.decision-actions').getBoundingClientRect();return f.bottom<=p.bottom && f.height>0;})()`, label + ' footer fits');
        const frame = await geometry();
        assert(frame.footer.top > frame.undo.bottom && frame.footer.bottom <= frame.panel.bottom, label + ': footer and toolbar remain separate');
        assert(frame.panel.bottom - frame.footer.bottom < 16, label + ': footer is pinned to the action area bottom');
        assert(frame.undo.right <= frame.panel.right && frame.undo.top >= frame.panel.top, label + ': Undo stays in its upper right toolbar');
        assert(frame.buttons.every(button => !button.clipped && button.height >= 44), label + ': complete labels fit touch-sized controls: ' + JSON.stringify(frame));
        if (frame.buttons.length === 2 && frame.buttons.some(button => button.text === 'Pass')) {
            assert.equal(frame.buttons[0].top, frame.buttons[1].top, label + ': joined halves align at the top');
            assert.equal(frame.buttons[0].bottom, frame.buttons[1].bottom, label + ': joined halves share their full height');
        }
        return frame;
    };
    for (const [width, height, mobile] of [[1280, 900, false], [390, 844, true], [320, 740, true]]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        console.log('Action footer:', width, 'card review and activity navigation');
        await show();
        await click('[data-hand-card="footer-stone"]');
        await page.wait(`document.querySelector('.decision-actions .play-card-action')`, 'Selected card action reaches the footer');
        let frame = await pinned(`${width} selected card`);
        assert.equal(frame.buttons.length, 2);
        assert.equal(frame.gap, '0px', 'One action and Pass share a joined control: ' + JSON.stringify(frame));
        const pass = frame.buttons.find(button => button.text === 'Pass');
        const action = frame.buttons.find(button => button.text !== 'Pass');
        assert.equal(pass.height, action.height);
        assert.equal(pass.left, action.right, 'The two actions meet at a single divider');
        await page.evaluate(`document.querySelector('.play-card-action').focus()`);
        await page.send('Input.dispatchKeyEvent', {type:'keyDown',key:'Tab',code:'Tab',windowsVirtualKeyCode:9,nativeVirtualKeyCode:9});
        await page.send('Input.dispatchKeyEvent', {type:'keyUp',key:'Tab',code:'Tab',windowsVirtualKeyCode:9,nativeVirtualKeyCode:9});
        assert.equal(await page.evaluate(`document.activeElement.classList.contains('pass-action')`), true, 'Keyboard order follows the action then Pass');
        const beforeScroll = frame.footer;
        await page.evaluate(`document.querySelector('.decision-scroll').scrollTop=100000`);
        assert.deepEqual((await geometry()).footer, beforeScroll, 'Scrolling the action review cannot move the footer');
        await click('#tab-activity');
        await page.wait(`document.querySelector('.activity-list').checkVisibility()`, 'Activity opens in its workspace tab');
        assert.deepEqual((await geometry()).footer, beforeScroll, 'Activity cannot reduce the action footer space');
        await pinned(`${width} activity tab`);
        await show();
        assert.equal(await page.evaluate(`document.querySelector('#tab-activity').getAttribute('aria-selected')`), 'true', 'Polling preserves the selected Activity tab');
        await click('#tab-map');
        assert.equal(await page.evaluate(`document.querySelector('.activity-resizer, .activity-toggle')`), null, 'The action sidebar has no activity split controls');
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-action-footer-${width}.png`));

        const placement = baseline.game.expansionOffers.find(offer => offer.partialPlacements.length > 0);
        assert(placement, 'Live expansion provides the longest partial-placement label');
        const alternate = baseline.game.expansionOffers.find(offer => offer.destination !== placement.destination && offer.primaryAction === 'PLACE' && offer.blockers.length === 0);
        assert(alternate, 'Live expansion provides another destination');
        const partial = {...placement, primaryAction:'PLACE', resultingPool:null, blockers:['INSUFFICIENT_EXPANSION_TOKENS']};
        await show({prompt:{messageId:'prompt.expand',players:[viewer]},legalChoices:{commands:['pass']},
            game:{stage:'EXPANSION',expansionOffers:[partial,alternate],activity:{...baseline.game.activity,actor:viewer,decisionPlayer:viewer,choice:'expand'}}});
        await page.evaluate(`document.querySelector('[data-map-province="${placement.destination}"]').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))`);
        frame = await pinned(`${width} long expansion label`);
        assert(frame.buttons.some(button=>button.text.includes('Place maximum possible tokens')));
        assert.equal(frame.buttons.length, 2);
        assert.equal(frame.gap, '0px', 'Long action labels retain the joined Pass layout: ' + JSON.stringify(frame));
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-action-footer-long-${width}.png`));

        await page.evaluate(`document.querySelector('.partial-placement').open = true`);
        await page.wait(`document.querySelector('.partial-expansion') && !document.querySelector('.primary-expansion')`, 'Partial review replaces the primary action');
        await click('#partial-count button');
        fixture = {...fixture,revision:++revision};
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'Partial review polling');
        assert.equal(await page.evaluate(`!!document.querySelector('.partial-expansion')`), true, 'Polling preserves the partial review');
        await page.evaluate(`document.querySelector('[data-map-province="${alternate.destination}"]').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))`);
        await page.wait(`document.querySelector('.primary-expansion') && !document.querySelector('.partial-expansion')`, 'Another destination starts its own full-placement review');

        const cancel = 'cancel-adjustment gold surplus';
        await show({prompt:{messageId:'prompt.cancel-adjustments',players:[viewer]},legalChoices:{commands:[cancel,'pass']},
            game:{stage:'DRAW_CARD',adjustments:{GOLD:['SURPLUS'],IVORY:['SURPLUS']},
                adjustmentOffers:[{command:cancel,commodities:['GOLD','IVORY'],cost:10,cashAfter:20}]}});
        await click('.game-actions .action-buttons button[aria-pressed]');
        frame = await pinned(`${width} priced cancellation label`);
        assert(frame.buttons.some(button=>/Gold \/ Ivory.*Surplus.*\$10/.test(button.text)));
        assert.equal(frame.buttons.length, 2);
        assert.equal(frame.gap, '0px', JSON.stringify(frame));
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-action-footer-priced-${width}.png`));

        await show({prompt:{messageId:'prompt.purchase-stabilization',players:[viewer]},
            legalChoices:{commands:['stabilize cash','stabilize misery','pass']},game:{stage:'PURCHASE',
                stabilizationCost:10,stabilizationMisery:20,activity:{...baseline.game.activity,actor:viewer,decisionPlayer:viewer,choice:'purchase-stabilization',purchaseStep:'STABILIZATION'}}});
        frame = await pinned(`${width} three actions`);
        assert.equal(frame.buttons.length, 3);
        assert.notEqual(frame.gap, '0px', 'Larger action sets use separate buttons');
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-action-footer-multiple-${width}.png`));
    }
    await show({prompt:{messageId:'prompt.bid-capital',players:[viewer]},legalChoices:{commands:[],bidMin:0,bidMax:40},game:{stage:'BIDDING'}});
    assert.equal(await page.evaluate(`document.querySelector('.bid-action').form === document.querySelector('#bid').form`), true, 'Footer button retains native form ownership');
    const before = await page.evaluate('window.mapCommands.length');
    await page.fill('#bid','-1');
    await click('.bid-action');
    assert.equal(await page.evaluate('window.mapCommands.length'), before, 'Native validation prevents an invalid footer submission');
    await page.fill('#bid','7');
    await page.evaluate(`document.querySelector('#bid').focus()`);
    await page.send('Input.dispatchKeyEvent', {type:'keyDown',key:'Enter',code:'Enter',text:'\r',unmodifiedText:'\r',windowsVirtualKeyCode:13,nativeVirtualKeyCode:13});
    await page.send('Input.dispatchKeyEvent', {type:'keyUp',key:'Enter',code:'Enter',windowsVirtualKeyCode:13,nativeVirtualKeyCode:13});
    await page.wait(`window.mapCommands.at(-1) === 'bid-capital 7'`, 'Enter submits the form through its pinned action');
    assert.equal(await page.evaluate('window.mapCommands.length'), before+1);
    console.log('Passed: pinned and joined action footer, full long labels, separate multi-action controls, native form validation/Enter, and activity navigation without changing action space.');
}
