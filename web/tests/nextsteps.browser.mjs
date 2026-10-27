import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';

const artifacts = process.env.AOR_BROWSER_ARTIFACTS;

export async function checkAccessibleNames(page) {
    const tree = await page.send('Accessibility.getFullAXTree');
    const controls = tree.nodes.filter(node => !node.ignored &&
        ['button','textbox','combobox','spinbutton','checkbox','tab','link'].includes(node.role?.value));
    assert(controls.length > 0);
    assert.deepEqual(controls.filter(node => !node.name?.value?.trim()).map(node => node.role.value), [],
        'Every exposed control has an accessible name');
}

// Computed HTML foreground/background pairs, including inherited backgrounds. SVG fills,
// gradients, operating-system controls and manual screen-reader usability remain separate evidence.
export async function checkContrast(page, label) {
    const results = await page.evaluate(`(() => {
        const rgba = value => (value.match(/[\\d.]+/g) ?? []).map(Number);
        const blend = (top, bottom) => top.slice(0,3).map((channel,index) => channel*(top[3]??1)+bottom[index]*(1-(top[3]??1)));
        const lum = color => color.map(channel => {const value=channel/255; return value<=.04045 ? value/12.92 : ((value+.055)/1.055)**2.4;}).reduce((sum,value,index)=>sum+value*[.2126,.7152,.0722][index],0);
        const results = [];
        for (const element of document.querySelectorAll('main *')) {
            if (!(element instanceof HTMLElement) || !element.checkVisibility({checkOpacity:true,checkVisibilityCSS:true}) ||
                element.closest('button:disabled, input:disabled, select:disabled') ||
                ![...element.childNodes].some(node => node.nodeType===Node.TEXT_NODE && node.textContent.trim())) continue;
            const ancestors=[];
            for(let parent=element;parent;parent=parent.parentElement) ancestors.unshift(parent);
            if (ancestors.some(parent => getComputedStyle(parent).backgroundImage!=='none' || Number(getComputedStyle(parent).opacity)<1)) continue;
            const background=ancestors.reduce((color,parent)=>blend(rgba(getComputedStyle(parent).backgroundColor),color),[255,255,255]);
            const style=getComputedStyle(element), foreground=blend(rgba(style.color),background);
            const a=lum(foreground),b=lum(background),ratio=(Math.max(a,b)+.05)/(Math.min(a,b)+.05);
            const size=parseFloat(style.fontSize), large=size>=24 || (size>=18.66 && Number(style.fontWeight)>=700);
            results.push({tag:element.tagName,classes:element.className,text:element.textContent.trim().slice(0,60),ratio,minimum:large?3:4.5});
        }
        return results;
    })()`);
    if (artifacts) await writeFile(join(artifacts, `contrast-${label}.json`), JSON.stringify(results,null,2));
    assert.deepEqual(results.filter(result => result.ratio+.01 < result.minimum), [], `Readable HTML text contrast: ${label}`);
    return results.length;
}

export async function checkSetupEvidence(page, web) {
    const game = await createGame(3);
    const [creator, other] = game.links;
    await page.send('Page.navigate', {url:web+creator.path});
    await page.wait(`document.querySelector('#rule-setup')`, 'live rule options');
    const initial = await game.snapshot(creator);
    for (const option of initial.game.ruleOptions) {
        const alternate = option.choices.find(value => value !== option.value);
        for (const value of [alternate, option.value]) {
            await page.fill(`#rule-${option.key}`, value);
            await page.evaluate(`document.querySelector('#rule-${option.key}').closest('.rule-option').querySelector('button').click()`);
            await page.wait(`document.querySelector('#rule-${option.key}').closest('.rule-option').querySelector('button').disabled && document.querySelector('.save-status').textContent==='Saved'`, `saved option ${option.key}`);
            assert.equal((await game.snapshot(creator)).game.ruleOptions.find(candidate => candidate.key===option.key).value,value);
            assert.equal((await game.snapshot(other)).game.ruleOptions.find(candidate => candidate.key===option.key).value,value);
        }
    }
    await checkAccessibleNames(page);
    await checkContrast(page,'configuration');
    // Native keyboard activation reaches the accurately named Apply button from its select.
    await page.fill('#rule-setup','improved');
    await page.evaluate(`document.querySelector('#rule-setup').focus()`);
    await page.send('Input.dispatchKeyEvent',{type:'keyDown',key:'Tab',code:'Tab',windowsVirtualKeyCode:9});
    await page.send('Input.dispatchKeyEvent',{type:'keyUp',key:'Tab',code:'Tab',windowsVirtualKeyCode:9});
    assert.equal(await page.evaluate(`document.activeElement.getAttribute('aria-label')`),'Apply Initial deal');
    await page.send('Input.dispatchKeyEvent',{type:'keyDown',key:'Enter',code:'Enter',windowsVirtualKeyCode:13,text:'\r',unmodifiedText:'\r'});
    await page.send('Input.dispatchKeyEvent',{type:'keyUp',key:'Enter',code:'Enter',windowsVirtualKeyCode:13});
    await page.wait(`document.querySelector('#rule-setup').closest('.rule-option').querySelector('button').disabled && document.querySelector('.save-status').textContent==='Saved'`,'keyboard option saved');
    await page.click('Start setup');
    await page.wait(`document.querySelectorAll('.game-actions .action-buttons button.secondary').length===3`,'improved private returns');
    const before = await game.snapshot(creator);
    assert.equal(before.game.stage,'SETUP_RETURN');
    const otherPage = await open(web+other.path);
    try {
        await otherPage.wait(`document.querySelector('.private-seat-link')`,'separate private seat');
        assert.match(await otherPage.evaluate(`document.querySelector('.private-seat-link summary').textContent`),/Bruno/);
        for (const index of [0,2]) {
            await page.evaluate(`document.querySelectorAll('.game-actions .action-buttons button.secondary')[${index}].click()`);
            await page.evaluate(`document.querySelector('.selected-action > button:last-child').click()`);
            await page.wait(`!document.querySelector('.selected-action') && document.querySelector('.pending-submission')?.textContent.includes('Your private return:')`,'private return saved');
            const state = await game.snapshot(creator);
            assert.equal(state.pendingReturn,before.game.hand[index].reference);
            assert.equal(state.revision,before.revision,'Private replacement does not commit a revision');
        }
        await page.send('Page.reload');
        await page.wait(`document.querySelector('.pending-submission')?.textContent.includes('Your private return:')`,'private return survives reload');
        const opponent = await game.snapshot(other);
        assert.equal(opponent.pendingReturn,null);
        assert.equal(opponent.game.activity.decisionEntry,before.game.activity.decisionEntry,'Readiness preserves decision identity');
        assert(!opponent.history.some(line=>line.includes('return-card')),'Unrevealed choice stays out of opponent history');
        assert(opponent.submitted.includes(creator.id));
        assert(!JSON.stringify(opponent.game.hand).includes(before.game.hand[2].reference));
        await page.evaluate(`document.querySelector('.private-seat-link').open=true`);
        const savedLink = await page.evaluate(`decodeURIComponent(document.querySelector('.private-seat-link a').getAttribute('href').split(',')[1])`);
        assert(savedLink.includes(creator.token));
        assert(!savedLink.includes(other.token));
        await checkAccessibleNames(page);
        await checkContrast(page,'private-return');
        const lastPage = await open(web+game.links[2].path);
        try {
            for (const seat of [otherPage,lastPage]) {
                await seat.wait(`document.querySelector('.game-actions .action-buttons button.secondary')`,'remaining private return');
                await seat.evaluate(`document.querySelector('.game-actions .action-buttons button.secondary').click()`);
                await seat.evaluate(`document.querySelector('.selected-action > button:last-child').click()`);
                await seat.wait(`!document.querySelector('.selected-action')`,'remaining return accepted');
            }
            await lastPage.wait(`document.querySelector('#bid')`,'improved setup completes into bidding');
            const completed=await game.snapshot(creator);
            assert.equal(completed.game.stage,'BIDDING');
            assert.equal(completed.pendingReturn,null);
            assert(completed.game.players.every(player=>player.handCount===2));
        } finally {
            lastPage.ws.close();
            await fetch(`${process.env.MAP_CDP_URL}/json/close/${lastPage.target.id}`);
        }
    } finally {
        otherPage.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${otherPage.target.id}`);
    }
    console.log('Passed: every individual setup option through live controls, keyboard Apply, separate private seats, replacement/reload/privacy, private link saving, accessible names and computed HTML contrast. Restart recovery is covered by service tests.');
}

export async function checkDecisionEvidence(page, web, baseline) {
    await page.send('Page.navigate',{url:web+'/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot==='function'`,'decision identity harness');
    let fixture = {...baseline,prompt:{messageId:'prompt.replace-event-token',players:[baseline.viewerId]},
        legalChoices:{commands:['replace-event-token pool','replace-event-token none']},
        game:{...baseline.game,activity:{...baseline.game.activity,choice:'replace-event-token',decisionPlayer:baseline.viewerId,decisionEntry:1000}}};
    const show = async () => {
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`document.querySelector('[data-test-revision="${fixture.revision}"]')`,'decision fixture');
    };
    await show();
    await page.evaluate(`document.querySelector('.game-actions .action-buttons button').click()`);
    await page.wait(`document.querySelector('.selected-action')`,'prepared replacement');
    fixture = {...fixture,revision:fixture.revision+1};
    await show();
    assert(await page.evaluate(`!!document.querySelector('.selected-action')`),'Polling retains prepared choice');
    fixture = {...fixture,revision:fixture.revision+1,game:{...fixture.game,activity:{...fixture.game.activity,decisionEntry:1001}}};
    await show();
    assert.equal(await page.evaluate(`document.querySelector('.selected-action')`),null,'Same-player same-prompt replacement clears obsolete choice');
    await page.evaluate(`document.querySelector('#tab-advances').click()`);
    for (const advance of baseline.game.catalog.advances) {
        await page.evaluate(`document.querySelector('[data-advance="${advance.id}"]').click()`);
        const text = await page.evaluate(`document.querySelector('.advance-benefits').textContent`);
        assert(text.length>15 && !text.includes('.effect'),'Every advance has readable benefits: '+advance.id);
    }
    await checkAccessibleNames(page);
    await checkContrast(page,'advance-detail');
    for (const id of ['PATRONAGE','WIND_WATERMILL','WRITTEN_RECORD','ENLIGHTENMENT','MASTER_ART']) {
        fixture={...fixture,revision:fixture.revision+1,game:{...fixture.game,rules:{...fixture.game.rules,
            patronage:'THREE_BOX_DISPLAY',windWatermill:'MARKET_ADJUSTMENT',writtenRecord:'TAKEOVER_TRADE',
            miseryRelief:'CATEGORY_COMPLETION',incomeRelief:'ENLIGHTENMENT',immediateMasterArtDiscard:true}}};
        await show();
        await page.evaluate(`document.querySelector('[data-advance="${id}"]').click()`);
        const text=await page.evaluate(`document.querySelector('.advance-benefits').textContent`);
        assert(!text.includes('.effect'),'Variant reference resolves: '+id);
        if(id==='WRITTEN_RECORD') assert(text.includes('force a card trade') && !text.includes('$10'));
        if(id==='ENLIGHTENMENT') assert(text.includes('Income phase') && !text.includes('Halves'));
    }
    await page.send('Page.navigate',{url:web+'/tests/ui-harness.html'});
    await page.wait(`typeof window.setUiSnapshot==='function'`,'rejection application harness');
    const reference = 'plague';
    const cardCommand = `play ${reference} area iv`;
    const cardFixture = {...baseline,prompt:{messageId:'prompt.play-cards',players:[baseline.viewerId]},
        legalChoices:{commands:[cardCommand,'pass']},game:{...baseline.game,stage:'PLAY_CARDS',
            hand:[{reference,definition:{id:'black-death',kind:'event',messageId:'card.black-death',shape:'SQUARE'}}],
            cardOffers:[{reference,playable:true,blockers:[],choices:[{kind:'AREA',value:'iv',command:cardCommand}],commodities:[],leaderDiscounts:[],provinceTargets:[],requiredTargetCount:null,protectionMax:null}]}};
    await page.evaluate(`window.setUiSnapshot(${JSON.stringify(cardFixture)})`);
    await page.wait(`document.querySelector('[data-hand-card="${reference}"]')`,'card rejection fixture');
    await page.evaluate(`document.querySelector('[data-hand-card="${reference}"]').click()`);
    await page.wait(`document.querySelector('.map-picker[aria-pressed=true]')`,'active area picker');
    await page.evaluate(`document.querySelector('.card-decision .target-choices button').click(); document.querySelector('#tab-markets').click()`);
    await page.evaluate(`window.uiNextError='UNREACHABLE_PROVINCE';window.uiErrorEntities=['acre',${JSON.stringify(baseline.viewerId)},'internal-fragment'];document.querySelector('.card-decision form > button:last-child').click()`);
    await page.wait(`document.querySelector('.decision-panel [role=alert]')`,'contextual rejection');
    assert.equal(await page.evaluate(`document.querySelector('#tab-markets').getAttribute('aria-selected')`),'true','Rejection does not redirect navigation');
    assert.equal(await page.evaluate(`document.querySelector('.card-decision form > button:last-child').disabled`),false,'Valid rejected choice remains editable');
    const message = await page.evaluate(`document.querySelector('.decision-panel [role=alert]').textContent`);
    assert(message.includes('Acre'),'Known rejected province is named');
    assert(!message.includes('internal-fragment'),'Raw identifiers stay out of ordinary errors');
    assert.equal(await page.evaluate(`window.uiCommands.length`),1,'Rejection never retries the action');
    console.log('Passed: polling preserves prepared decisions; identical nested prompts with new identities clear old work; all advance benefits render with accessible controls.');
}
