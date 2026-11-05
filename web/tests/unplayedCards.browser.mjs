import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';

export async function checkUnplayedCards(page, web) {
    const game = await createGame(3);
    const baseline = await game.snapshot(game.links[0]);
    const opener = '[data-help-topic="unplayed-cards"]';
    const list = '#unplayed-cards [data-unplayed-card]';
    const ids = () => page.evaluate(`[...document.querySelectorAll('${list}')].map(card => card.dataset.unplayedCard)`);
    const inspect = async () => {
        await page.evaluate(`document.querySelector('${opener}').focus(); document.activeElement.click()`);
        await page.wait(`document.querySelector('.reference-dialog[open] #unplayed-cards')`, 'unplayed cards popup');
    };
    const close = async () => {
        await page.send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
        await page.send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
        await page.wait(`!document.querySelector('.reference-dialog')`, 'popup dismissal');
        assert.equal(await page.evaluate(`document.activeElement.matches('${opener}')`), true, 'Focus returns to the question mark');
    };
    await page.send('Page.navigate', {url: web + game.links[0].path});
    await page.wait(`document.querySelector('#bid')`, 'live capital bid');
    await page.fill('#bid', '7');
    for (const width of [1440, 390]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height: 1000, deviceScaleFactor: 1, mobile: width < 700});
        assert.equal(await page.evaluate(`document.querySelector('.epoch-progress ${opener}').textContent`), '?');
        await inspect();
        assert.deepEqual(await ids(), baseline.game.unplayedCards.map(card => card.id), 'Every public physical card appears, including held cards and duplicate commodities');
        assert.equal(await page.evaluate(`document.querySelector('.reference-dialog').scrollWidth <= document.querySelector('.reference-dialog').clientWidth`), true, 'List fits the popup');
        assert.equal(await page.evaluate(`document.querySelector('.reference-dialog').getBoundingClientRect().right <= innerWidth`), true, 'Popup stays inside the viewport');
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `unplayed-cards-${width}.png`));
        await close();
        assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7', 'Inspection preserves the bid draft');
    }
    for (const [index, link] of game.links.entries()) await game.send(link, `bid-capital ${3 - index}`);
    let actor;
    let before;
    let offered;
    for (let step = 0; step < 100; step++) {
        const current = await game.snapshot(game.links[0]);
        actor = game.links.find(link => current.prompt?.players.includes(link.id));
        assert(actor, `Live setup has an actor at ${current.game.stage}`);
        before = await game.snapshot(actor);
        offered = before.game.cardOffers.find(offer => offer.playable && offer.choices.length === 1 &&
            ['NONE', 'COMMODITY'].includes(offer.choices[0].kind) && offer.choices[0].command);
        if (offered) break;
        const command = before.game.stage === 'TOKEN_PURCHASE' ? `buy-tokens ${game.links.indexOf(actor) + 1}`
            : before.legalChoices.commands.find(command => command.startsWith('choose-capital '))
                ?? before.legalChoices.commands.find(command => command.startsWith('choose-turn-position '))
                ?? before.legalChoices.commands.find(command => command === 'pass')
                ?? before.legalChoices.commands[0];
        assert(command, `Live setup has a command at ${before.game.stage}`);
        await game.send(actor, command);
    }
    assert(offered, 'Reach a live simple card offer');
    const card = before.game.hand.find(card => card.reference === offered.reference);
    // A different seat uses the same path with a new fragment credential; mount its own document.
    await page.send('Page.navigate', {url: 'about:blank'});
    await page.wait(`location.href === 'about:blank'`, 'previous seat document closes');
    await page.send('Page.navigate', {url: web + actor.path});
    await page.wait(`document.querySelector('[data-hand-card="${card.reference}"]')`, 'live playable hand');
    await page.evaluate(`document.querySelector('[data-hand-card="${card.reference}"]').click()`);
    await page.wait(`document.querySelector('.play-card-action:not([disabled])')`, 'prepared live play');
    await inspect();
    await page.wait(`document.querySelector('${list}')`, 'public list before play');
    assert((await ids()).includes(card.definition.id));
    await close();
    assert.equal(await page.evaluate(`document.querySelector('.play-card-action').disabled`), false, 'Inspection preserves the prepared card');
    await page.evaluate(`document.querySelector('.play-card-action').click()`);
    await page.wait(`!document.querySelector('[data-hand-card="${card.reference}"]')`, 'successful card play');
    assert.equal(await page.evaluate(`document.querySelector('.card-decision')`), null, 'Successful play clears its action context');
    await inspect();
    assert(!(await ids()).includes(card.definition.id), 'Played card leaves the public list');
    const after = await game.snapshot(actor);
    assert.deepEqual(await ids(), after.game.unplayedCards.map(card => card.id));
    let nextCard;
    for (let step = 0; step < 100; step++) {
        const current = await game.snapshot(actor);
        const nextActor = game.links.find(link => current.prompt?.players.includes(link.id));
        assert(nextActor, 'Live play has an acting player');
        const next = await game.snapshot(nextActor);
        const choice = next.game.cardOffers.flatMap(offer => offer.playable
            ? offer.choices.filter(choice => choice.command).map(choice => ({offer, choice})) : [])[0];
        if (choice) {
            nextCard = next.game.hand.find(card => card.reference === choice.offer.reference);
            await game.send(nextActor, choice.choice.command);
            break;
        }
        const continuation = next.legalChoices.commands.find(command => command === 'pass')
            ?? next.legalChoices.commands[0];
        assert(continuation, 'Card rounds can progress to another live play');
        await game.send(nextActor, continuation);
    }
    assert(nextCard, 'Another live card play updates the open popup');
    const updated = await game.snapshot(actor);
    const expectedIds = updated.game.unplayedCards.map(card => card.id);
    await page.wait(`JSON.stringify([...document.querySelectorAll('${list}')].map(card => card.dataset.unplayedCard)) === ${JSON.stringify(JSON.stringify(expectedIds))}`, 'open list follows accepted polling');
    await close();
    console.log('Passed: public unplayed list, held copies, desktop/phone fit, Escape focus return, bid and live card draft preservation, successful play cleanup and live popup updates.');
}

if (process.argv[1]?.endsWith('/unplayedCards.browser.mjs')) {
    const page = await open('about:blank');
    try { await checkUnplayedCards(page, process.env.MAP_WEB_URL); }
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
