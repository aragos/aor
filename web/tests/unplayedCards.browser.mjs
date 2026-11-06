import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';

export async function checkUnplayedCards(page, web) {
    await page.send('Page.bringToFront');
    const game = await createGame(3);
    const baseline = await game.snapshot(game.links[0]);
    const opener = '[data-help-topic="unplayed-cards"]';
    const list = '#unplayed-cards [data-unplayed-card]';
    const detail = '.card-inspection-dialog[open]:not(:has(#unplayed-cards))';
    const ids = () => page.evaluate(`[...document.querySelectorAll('${list}')].map(card => card.dataset.unplayedCard)`);
    const escape = async () => {
        await page.send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
        await page.send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
    };
    const inspect = async () => {
        await page.evaluate(`document.querySelector('${opener}').focus(); document.activeElement.click()`);
        await page.wait(`document.querySelector('.reference-dialog[open] #unplayed-cards')`, 'unplayed cards popup');
    };
    const close = async () => {
        await escape();
        await page.wait(`!document.querySelector('.reference-dialog')`, 'popup dismissal');
        assert.equal(await page.evaluate(`document.activeElement.matches('${opener}')`), true, 'Focus returns to the card-count link');
    };
    const inspectCard = async (info, keyboard = false, width) => {
        const button = `${list}[data-unplayed-card="${info.definition.id}"] button`;
        const name = await page.evaluate(`document.querySelector('${button}').textContent`);
        await page.evaluate(`document.querySelector('${button}').focus()`);
        assert.equal(await page.evaluate(`document.activeElement.matches('${button}')`), true, 'The unplayed card accepts keyboard focus');
        if (keyboard) {
            await page.send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Enter', code: 'Enter', text: '\r', unmodifiedText: '\r', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13});
            await page.send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13});
        } else {
            // Native input gives the nested modal its own user activation for Escape dismissal.
            const point = await page.evaluate(`(() => {
                const target = document.querySelector('${button}');
                target.scrollIntoView({block: 'center', behavior: 'instant'});
                const bounds = target.getBoundingClientRect();
                return {x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2};
            })()`);
            await page.send('Input.dispatchMouseEvent', {type: 'mousePressed', ...point, button: 'left', clickCount: 1});
            await page.send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...point, button: 'left', clickCount: 1});
        }
        await page.wait(`document.querySelector('${detail}')`, `${info.definition.kind} inspection`);
        assert.equal(await page.evaluate(`document.querySelectorAll('.card-inspection-dialog[open]').length`), 2, 'Card details retain the unplayed list underneath');
        assert.equal(await page.evaluate(`document.querySelector('${detail} .play-card-action')`), null, 'Looking up a card never offers to play it');
        if (info.definition.kind === 'commodity') {
            const first = info.commodities[0].toLowerCase();
            await page.wait(`document.querySelector('${detail} #commodity-${first} .payout-box')`, 'Commodity payout information');
            const counts = baseline.game.commodityCards.find(count => count.commodity === info.commodities[0]);
            assert.deepEqual(await page.evaluate(`[...document.querySelectorAll('${detail} .commodity-card-counts dd')].map(value => Number(value.textContent))`), counts.epochs, 'Commodity inspection uses the live card counts');
            if (info.commodities.length > 1) {
                const choices = `${detail} .commodity-reference-choices button`;
                assert.equal(await page.evaluate(`document.querySelectorAll('${choices}').length`), info.commodities.length, 'Shared cards expose every commodity');
                await page.evaluate(`document.querySelectorAll('${choices}')[1].click()`);
                await page.wait(`document.querySelector('${detail} #commodity-${info.commodities[1].toLowerCase()} .payout-box')`, 'Shared commodity switches inside the same popup');
                assert.equal(await page.evaluate(`document.querySelectorAll('.card-inspection-dialog[open]').length`), 2, 'Switching commodities keeps one detail popup');
            } else {
                assert.equal(await page.evaluate(`document.querySelector('${detail} .commodity-reference-choices')`), null, 'Single commodity needs no chooser');
            }
        } else {
            assert.equal(await page.evaluate(`document.querySelector('${detail} h2').textContent`), name, 'Card inspection identifies the clicked card');
            if (info.definition.kind === 'event') {
                assert(await page.evaluate(`document.querySelector('${detail} .card-event-text')?.textContent.length > 0`), 'Events show their existing rules text');
            } else {
                assert.deepEqual(await page.evaluate(`[...document.querySelectorAll('${detail} .leader-discount-detail .discount-amount strong')].map(amount => amount.textContent)`), info.leaderDiscounts.map(discount => `−$${discount.effectiveDiscount}`), 'Leaders show authoritative advance discounts');
            }
        }
        assert.equal(await page.evaluate(`(() => {const dialog = document.querySelector('${detail}'); return dialog.scrollWidth <= dialog.clientWidth && dialog.getBoundingClientRect().right <= innerWidth;})()`), true, 'Card information fits the viewport');
        if (width && (info.definition.kind === 'event' || info.commodities.length > 1)) {
            await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `unplayed-${info.definition.kind}-inspection-${width}.png`));
        }
        await escape();
        await page.wait(`!document.querySelector('${detail}')`, 'Card details close back to the unplayed list');
        assert.equal(await page.evaluate(`document.activeElement.matches('${button}')`), true, 'Focus returns to the clicked card');
        assert.equal(await page.evaluate(`document.querySelectorAll('.card-inspection-dialog[open]').length`), 1);
    };
    const publicInspections = baseline.game.cardInspections.filter(info => baseline.game.unplayedCards.some(card => card.id === info.definition.id));
    const samples = [
        publicInspections.find(info => info.definition.kind === 'event'),
        publicInspections.find(info => info.definition.kind === 'leader' && info.leaderDiscounts.length),
        publicInspections.find(info => info.commodities.length === 1),
        publicInspections.find(info => info.commodities.length > 1),
    ];
    assert(samples.every(Boolean), 'The live public list contains events, leaders, and single/shared commodities');
    await page.send('Page.navigate', {url: web + game.links[0].path});
    await page.wait(`document.querySelector('#bid')`, 'live capital bid');
    await page.fill('#bid', '7');
    for (const width of [1440, 390]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height: 1000, deviceScaleFactor: 1, mobile: width < 700});
        assert.match(await page.evaluate(`document.querySelector('.epoch-progress ${opener}').textContent`), /Epoch .*cards remaining/);
        assert.equal(await page.evaluate(`document.querySelector('.epoch-progress .help-link')`), null, 'The counter is its own lookup link');
        await inspect();
        assert.deepEqual(await ids(), baseline.game.unplayedCards.map(card => card.id), 'Every public physical card appears, including held cards and duplicate commodities');
        assert.equal(await page.evaluate(`document.querySelector('.reference-dialog').scrollWidth <= document.querySelector('.reference-dialog').clientWidth`), true, 'List fits the popup');
        assert.equal(await page.evaluate(`document.querySelector('.reference-dialog').getBoundingClientRect().right <= innerWidth`), true, 'Popup stays inside the viewport');
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `unplayed-cards-${width}.png`));
        for (const info of samples) await inspectCard(info, info.definition.kind === 'event', width);
        await close();
        assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7', 'Inspection preserves the bid draft');
        assert.equal((await game.snapshot(game.links[0])).revision, baseline.revision, 'Inspecting cards never changes the game');
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
    for (const kind of ['event', 'commodity']) {
        const info = before.game.cardInspections.find(info => info.definition.kind === kind &&
            before.game.unplayedCards.some(card => card.id === info.definition.id));
        assert(info, `A live ${kind} remains available for inspection`);
        await inspectCard(info);
    }
    await close();
    assert.equal(await page.evaluate(`document.querySelector('.play-card-action').disabled`), false, 'Inspection preserves the prepared card');
    assert.equal((await game.snapshot(actor)).revision, before.revision, 'Card lookup leaves the prepared play uncommitted');
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
    console.log('Passed: public unplayed list, event/leader/commodity inspection, shared commodity switching, desktop/phone fit, keyboard and Escape focus return, bid and live card draft preservation, successful play cleanup and live popup updates.');
}

if (process.argv[1]?.endsWith('/unplayedCards.browser.mjs')) {
    const page = await open('about:blank');
    try { await checkUnplayedCards(page, process.env.MAP_WEB_URL); }
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
