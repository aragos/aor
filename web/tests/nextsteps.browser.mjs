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
    await page.send('Page.navigate', {url:web+'/'});
    await page.wait(`document.querySelector('#names') && document.querySelector('#variant-initial-card')`, 'players and variants share creation');
    await page.fill('#names','Ada\nBruno\nClara');
    await page.fill('#game-name', 'Friday Renaissance');
    await page.evaluate(`document.querySelector('#variant-deck-modification button:last-child').click();
        document.querySelector('#variant-initial-card button:nth-child(3)').focus()`);
    await page.send('Input.dispatchKeyEvent',{type:'keyDown',key:'Enter',code:'Enter',windowsVirtualKeyCode:13,text:'\r',unmodifiedText:'\r'});
    await page.send('Input.dispatchKeyEvent',{type:'keyUp',key:'Enter',code:'Enter',windowsVirtualKeyCode:13});
    await page.evaluate(`document.querySelector('#variant-immediate-master-art-discard button:last-child').click()`);
    await checkAccessibleNames(page);
    await checkContrast(page,'creation');
    await page.click('Create game');
    await page.wait(`document.querySelectorAll('.seat-links a').length===3`, 'registered seats');
    const links = await page.evaluate(`[...document.querySelectorAll('.seat-links a')].map(link=>({path:link.getAttribute('href'),token:new URLSearchParams(link.hash.slice(1)).get('token')}))`);
    const [creator, other] = links;
    const api=process.env.MAP_API_URL;
    const id=creator.path.split('/')[2].split('#')[0];
    const snapshot=link=>fetch(api+'/api/games/'+id,{headers:{Authorization:'Bearer '+link.token}}).then(response=>response.json());
    const send=async(link,command)=>{
        const state=await snapshot(link);
        const response=await fetch(api+'/api/games/'+id+'/commands',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+link.token},body:JSON.stringify({expectedRevision:state.revision,command})});
        assert(response.ok,await response.clone().text()); return response.json();
    };
    const configured=await snapshot(creator);
    assert.equal(configured.game.rules.deckModification,true);
    assert.equal(configured.game.rules.initialCard,'NO_EVENTS');
    assert.equal(configured.game.rules.immediateMasterArtDiscard,true);
    assert.equal(configured.game.ruleOptions.length,4);
    await page.send('Page.navigate',{url:web+creator.path});
    await page.wait(`document.querySelector('.game-table')`,'configured game');
    assert.equal(await page.evaluate(`document.querySelector('.rule-options')`),null,'Variants are selected with registration');
    assert.equal(await page.evaluate(`[...document.querySelectorAll('button')].some(button => button.textContent === 'Start setup')`), false, 'Creation starts setup implicitly');
    await page.wait(`document.querySelector('#bid')`,'one-card setup proceeds to bidding');
    const dealt=await snapshot(creator);
    assert(dealt.game.players.every(player=>player.handCount===1));
    assert.notEqual(dealt.game.hand[0].definition.kind,'event');
    const otherPage=await open(web+other.path);
    try {
        await otherPage.wait(`document.querySelector('.private-seat-link')`,'separate private seat');
        assert.match(await otherPage.evaluate(`document.querySelector('.private-seat-link summary').textContent`),/Bruno/);
        for (const amount of [7,9]) {
            await page.fill('#bid',String(amount));
            await page.evaluate(`document.querySelector('.bid-action').click()`);
            await page.wait(`document.querySelector('.pending-submission')?.textContent.includes('$${amount}')`,'private bid saved');
            assert.equal((await snapshot(creator)).revision,dealt.revision,'Private replacement retains committed revision');
        }
        await page.reload();
        await page.wait(`document.querySelector('.pending-submission')?.textContent.includes('$9')`,'bid survives reload');
        assert.equal((await snapshot(other)).pendingBid,null,'Other seats cannot see the bid');
        await page.evaluate(`document.querySelector('.private-seat-link').open=true`);
        const saved=await page.evaluate(`decodeURIComponent(document.querySelector('.private-seat-link a').getAttribute('href').split(',')[1])`);
        assert(saved.includes(creator.token)); assert(!saved.includes(other.token));
        await checkAccessibleNames(page); await checkContrast(page,'private-bid');
        await send(other,'bid-capital 0'); await send(links[2],'bid-capital 0');
        assert.notEqual((await snapshot(creator)).game.stage,'BIDDING');
    } finally {
        otherPage.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${otherPage.target.id}`);
    }
    console.log('Passed: variants and player registration share creation; canonical options persist; one-card deal, private bids, replacement/reload/privacy, saved links and accessible controls.');
}

export async function checkDecisionEvidence(page, web, baseline) {
    await page.send('Page.navigate',{url:web+'/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot==='function'`,'decision identity harness');
    let fixture = {...baseline,prompt:{messageId:'prompt.replace-event-token',players:[baseline.viewerId]},
        legalChoices:{commands:['replace-event-tokens none']},
        game:{...baseline.game,replacementToken:{player:baseline.viewerId,provinces:['rome'],event:'BLACK_DEATH',poolTokens:1,poolCost:1,maxReplacements:1,stockTokens:0,misery:0,civilWarLossPending:false},activity:{...baseline.game.activity,choice:'replace-event-token',decisionPlayer:baseline.viewerId,decisionEntry:1000}}};
    const show = async () => {
        await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
        await page.wait(`document.querySelector('[data-test-revision="${fixture.revision}"]')`,'decision fixture');
    };
    await show();
    await page.evaluate(`document.querySelector('[data-replacement-province=rome] button:first-child').click()`);
    await page.wait(`document.querySelector('.event-replacements [aria-pressed=true]')`,'prepared replacement');
    fixture = {...fixture,revision:fixture.revision+1};
    await show();
    assert(await page.evaluate(`!!document.querySelector('.event-replacements [aria-pressed=true]')`),'Polling retains prepared choice');
    fixture = {...fixture,revision:fixture.revision+1,game:{...fixture.game,activity:{...fixture.game.activity,decisionEntry:1001}}};
    await show();
    assert.equal(await page.evaluate(`document.querySelector('.event-replacements [aria-pressed=true]')`),null,'Same-player same-prompt replacement clears obsolete choice');
    await page.evaluate(`document.querySelector('#tab-advances').click()`);
    for (const advance of baseline.game.catalog.advances) {
        await page.evaluate(`document.querySelector('[data-advance="${advance.id}"]').click()`);
        assert.equal(await page.evaluate(`!!document.querySelector('.inspection-panel .advance-benefits')`), true, 'Advance benefits stay in the game');
        assert.equal(await page.evaluate(`document.querySelector('.inspection-panel .advance-category-credit dd').textContent`), `$${advance.credit} from next turn`);
        assert.match(await page.evaluate(`document.querySelector('.inspection-panel .advance-category-credit dt').textContent`), /Credit to other .* advances/);
        assert.equal(await page.evaluate(`document.querySelector('.inspection-panel .advance-relief dd').textContent`), `$${advance.miseryRelief} this turn`);
        assert.equal(await page.evaluate(`document.querySelector('.inspection-panel details, .inspection-panel a, .inspection-panel .reference-link')`), null, 'Printed values and prices are visible without links or foldouts');
    }
    await checkAccessibleNames(page);
    await checkContrast(page,'advance-detail');
    console.log('Passed: decision lifetimes, contextual reference links, and all public advance data.');
}
