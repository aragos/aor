import assert from 'node:assert/strict';
import {createGame, open} from './browser-support.mjs';

export async function checkLiveCardMap(page, web) {
    await page.send('Emulation.setDeviceMetricsOverride', {width:1440,height:1000,deviceScaleFactor:1,mobile:false});
    let game;
    let player;
    for (let attempt = 0; attempt < 200 && !player; attempt++) {
        game = await createGame(3);
        for (const link of game.links) {
            const snapshot = await game.snapshot(link);
            if (snapshot.game.hand.some(card => card.definition.id === 'the-crusades')) {
                player = link;
                break;
            }
        }
    }
    assert(player, 'Find a real opening Crusades card through normal creation');
    for (const [index, link] of game.links.entries()) {
        await game.send(link, `bid-capital ${link.id === player.id ? 5 : index}`);
    }
    for (let step = 0; step < 80; step++) {
        const current = await game.snapshot(player);
        if (current.game.stage === 'PLAY_CARDS' && current.prompt.players.includes(player.id)) break;
        const actor = game.links.find(link => current.prompt?.players.includes(link.id));
        assert(actor, `Setup must progress from ${current.game.stage}`);
        const state = await game.snapshot(actor);
        const commands = state.legalChoices.commands;
        const command = state.game.stage === 'TOKEN_PURCHASE' ? `buy-tokens ${actor.id === player.id ? 1 : 0}`
            : commands.find(choice => choice.startsWith('choose-capital '))
                ?? commands.find(choice => choice.startsWith('choose-turn-position '))
                ?? commands.find(choice => choice === 'pass');
        assert(command, `Setup has an action at ${state.game.stage}`);
        await game.send(actor, command);
    }
    const before = await game.snapshot(player);
    assert.equal(before.game.stage, 'PLAY_CARDS');
    const card = before.game.hand.find(card => card.definition.id === 'the-crusades');
    const offer = before.game.cardOffers.find(offer => offer.reference === card.reference);
    assert(offer.playable);
    const targets = offer.choices.filter(choice => choice.kind === 'PROVINCE');
    assert(targets.length > 0, 'The real engine offers Crusades provinces');
    await page.send('Page.navigate', {url: web + player.path});
    await page.wait(`document.querySelector('[data-hand-card="${card.reference}"]')`, 'Live private hand');
    await page.evaluate(`document.querySelector('#tab-markets').click(); document.querySelector('[data-hand-card="${card.reference}"]').click()`);
    await page.wait(`document.querySelector('#tab-map[aria-selected=true]') && document.querySelector('[data-map-province="${targets[0].value}"].eligible')`, 'StrictMode live Crusades activates its map targets');
    assert.equal((await game.snapshot(player)).revision, before.revision, 'Opening the picker never plays the card');
    const point = await page.evaluate(`(() => {
        const path=document.querySelector('[data-map-province="${targets[0].value}"] .province-shape');
        path.scrollIntoView({block:'center',behavior:'instant'});
        const box=path.getBBox();
        for (const dx of [.5,.3,.7,.1,.9]) for (const dy of [.5,.3,.7,.1,.9]) {
            const local=new DOMPoint(box.x+box.width*dx,box.y+box.height*dy);
            const screen=local.matrixTransform(path.getScreenCTM());
            if (path.isPointInFill(local) && document.elementFromPoint(screen.x,screen.y)?.closest('[data-map-province="${targets[0].value}"]')) return {x:screen.x,y:screen.y};
        }
        throw new Error('An eligible Crusades province must have an unobstructed pointer target.');
    })()`);
    await page.send('Input.dispatchMouseEvent', {type:'mousePressed',...point,button:'left',clickCount:1});
    await page.send('Input.dispatchMouseEvent', {type:'mouseReleased',...point,button:'left',clickCount:1});
    await page.wait(`document.querySelector('.decision-panel .card-decision form > button:last-child:not([disabled])')`, 'Map selection prepares Crusades');
    assert.equal((await game.snapshot(player)).revision, before.revision, 'Selecting a province only edits the draft');
    await page.evaluate(`document.querySelector('.decision-panel .card-decision form > button:last-child').click()`);
    await page.wait(`!document.querySelector('[data-hand-card="${card.reference}"]')`, 'Real Crusades committed');
    const after = await game.snapshot(player);
    assert.equal(after.game.board.find(province => province.id === targets[0].value).marker.owner, player.id);
    console.log('Passed: live engine Crusades offers survive production StrictMode and a map target commits the exact province.');
}

if (process.argv[1]?.endsWith('/cardMap.browser.mjs')) {
    const page = await open('about:blank');
    try { await checkLiveCardMap(page, process.env.MAP_WEB_URL); }
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
