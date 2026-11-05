import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open, waitForMap} from './browser-support.mjs';
import {checkReferenceUx} from './referenceUx.browser.mjs';
import {checkCardAndPlayerDetails, checkReplacementContext} from './contextDetails.browser.mjs';

const escape = async page => {
    await page.send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
    await page.send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
};

async function checkMapToolbar(page, web) {
    const game = await createGame(3);
    const baseline = await game.snapshot(game.links[0]);
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false});
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`typeof window.setMapSnapshot === 'function'`, 'StrictMode toolbar harness');
    await page.evaluate(`window.setMapSnapshot(${JSON.stringify(baseline)})`);
    await waitForMap(page, baseline);
    await page.wait(`document.querySelector('.workspace-bar .map-zoom')`, 'map controls in the bar');
    assert.equal(await page.evaluate(`document.querySelector('.map-viewport .map-zoom')`), null);
    assert.equal(await page.evaluate(`document.querySelector('[role=tablist] .map-zoom')`), null, 'Map actions are outside the tab list');
    await page.fill('#bid', '7');
    const original = await page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox')`);
    await page.evaluate(`document.querySelector('.map-zoom [aria-label="Zoom in"]').click()`);
    await page.wait(`document.querySelector('.world-map').getAttribute('viewBox') !== ${JSON.stringify(original)}`, 'toolbar zoom');
    const zoomed = await page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox')`);
    await page.evaluate(`document.querySelector('#tab-markets').click()`);
    assert.equal(await page.evaluate(`document.querySelector('.map-zoom').checkVisibility()`), false, 'Controls hide with other workspaces');
    await page.evaluate(`document.querySelector('#tab-map').click()`);
    await page.wait(`document.querySelector('.map-zoom').checkVisibility()`, 'controls return with the map');
    assert.equal(await page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox')`), zoomed, 'Navigation preserves the camera');
    await page.click('Fit map');
    await page.wait(`document.querySelector('.world-map').getAttribute('viewBox') === ${JSON.stringify(original)}`, 'toolbar Fit');
    await page.evaluate(`document.querySelector('.map-zoom .help-link').focus(); document.activeElement.click()`);
    await page.wait(`document.querySelector('.reference-dialog #map-legend')`, 'map key opens from bar');
    await escape(page);
    await page.wait(`!document.querySelector('.reference-dialog')`, 'map key dismissal');
    assert.equal(await page.evaluate(`document.activeElement.matches('.map-zoom .help-link')`), true);
    for (const width of [1440, 900, 390]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height: 1000, deviceScaleFactor: 1, mobile: width < 700});
        await page.evaluate(`new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))`);
        const layout = await page.evaluate(`(() => {
            const bar = document.querySelector('.workspace-bar').getBoundingClientRect();
            const map = document.querySelector('.world-map').getBoundingClientRect();
            return {visible: document.querySelector('.map-zoom').checkVisibility(), barWidth: bar.width,
                threshold: 48 * parseFloat(getComputedStyle(document.documentElement).fontSize),
                barBottom: bar.bottom, mapTop: map.top};
        })()`);
        assert.equal(layout.visible, layout.barWidth > layout.threshold, JSON.stringify(layout));
        if (layout.visible) assert(layout.barBottom <= layout.mapTop + 1, 'Controls sit above the map');
        assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7', 'Resizing preserves the bid');
        assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `map-toolbar-${width}.png`));
    }
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false});
    await page.wait(`document.querySelector('.map-zoom').checkVisibility()`, 'wide bar controls');
    await page.evaluate(`document.querySelector('.game-table').style.width = '900px'`);
    await page.wait(`!document.querySelector('.map-zoom').checkVisibility()`, 'a narrow bar hides controls on a wide screen');
    await page.evaluate(`document.querySelector('.world-map').dispatchEvent(new KeyboardEvent('keydown', {key: '+', bubbles: true}))`);
    const narrow = await page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox')`);
    await page.evaluate(`document.querySelector('.world-map').dispatchEvent(new KeyboardEvent('keydown', {key: '0', bubbles: true}))`);
    await page.wait(`document.querySelector('.world-map').getAttribute('viewBox') !== ${JSON.stringify(narrow)}`, 'keyboard controls remain available');
    await page.evaluate(`document.querySelector('.game-table').style.removeProperty('width')`);
}

async function liveNoEffectOffer() {
    let game;
    let player;
    for (let attempt = 0; attempt < 100 && !player; attempt++) {
        game = await createGame(3);
        for (const link of game.links) {
            const snapshot = await game.snapshot(link);
            if (snapshot.game.hand.some(card => card.definition.id === 'rebellion')) {player = link; break;}
        }
    }
    assert(player, 'Find a real opening Rebellion');
    for (const [index, link] of game.links.entries()) await game.send(link, `bid-capital ${link.id === player.id ? 5 : index}`);
    for (let step = 0; step < 800; step++) {
        const current = await game.snapshot(player);
        const held = current.game.hand.find(card => card.definition.id === 'rebellion');
        const offer = current.game.cardOffers.find(offer => offer.reference === held?.reference && offer.playable);
        if (offer?.choices.some(choice => choice.noEffect)) return {game, player, before: current, card: held, offer};
        const actor = game.links.find(link => current.prompt?.players.includes(link.id));
        assert(actor, `Setup has an actor at ${current.game.stage}`);
        const snapshot = actor.id === player.id ? current : await game.snapshot(actor);
        const pirates = snapshot.game.hand.find(card => card.definition.id === 'pirates-vikings');
        const piracy = snapshot.game.cardOffers.find(offer => offer.reference === pirates?.reference && offer.playable);
        const capital = current.game.players.find(candidate => candidate.id === player.id).capital;
        if (piracy?.provinceTargets.includes(capital)) {
            // Remove the holder's own capital through an ordinary play. Foreign capitals are not
            // Rebellion targets, so its next real offer consumes the card without an effect.
            const targets = [capital, ...piracy.provinceTargets.filter(target => target !== capital)]
                .slice(0, piracy.requiredTargetCount);
            await game.send(actor, `play ${pirates.reference} ${targets.length === 1 ? 'province' : 'provinces'} ${targets.join(' ')}`);
            continue;
        }
        const command = snapshot.game.stage === 'TOKEN_PURCHASE' ? `buy-tokens ${game.links.indexOf(actor) + 1}`
            : actor.id === player.id && snapshot.legalChoices.commands.includes('choose-capital venice') ? 'choose-capital venice'
            : snapshot.legalChoices.commands.find(command => command.startsWith('choose-capital '))
                ?? snapshot.legalChoices.commands.find(command => command.startsWith('choose-turn-position '))
                ?? snapshot.legalChoices.commands.find(command => command === 'pass')
                ?? snapshot.legalChoices.commands.find(command => command === 'stabilize cash')
                ?? snapshot.legalChoices.commands[0];
        assert(command);
        await game.send(actor, command);
    }
    assert.fail('Reach the legal no-target Rebellion offer');
}

export async function checkUiPolish(page, web) {
    await checkMapToolbar(page, web);
    const {game, player, before, card, offer} = await liveNoEffectOffer();
    const command = offer.choices.find(choice => choice.noEffect).command;
    await page.send('Page.navigate', {url: web + '/tests/ui-harness.html'});
    await page.wait(`typeof window.setUiSnapshot === 'function'`, 'StrictMode action lifecycle harness');
    await page.evaluate(`window.setUiSnapshot(${JSON.stringify(before)})`);
    await page.wait(`document.querySelector('[data-hand-card="${card.reference}"]')`, 'real no-effect hand');
    await page.evaluate(`document.querySelector('[data-hand-card="${card.reference}"]').click()`);
    await page.wait(`document.querySelector('.card-play-warning[role=alert]')`, 'no-effect warning before play');
    assert.match(await page.evaluate(`document.querySelector('.card-play-warning').textContent`), /no legal target.*discards.*without applying/);
    assert.equal(await page.evaluate(`document.querySelector('.play-card-action').disabled`), false, 'The legal no-effect play remains available');
    assert.equal(await page.evaluate(`document.querySelector('.card-play-warning').nextElementSibling.classList.contains('play-card-action')`), true, 'Warning stays beside confirmation');
    for (const width of [1440, 390]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height: 1000, deviceScaleFactor: 1, mobile: width < 700});
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `no-effect-card-${width}.png`));
    }
    const requests = await page.evaluate('window.uiRequests');
    await page.wait(`window.uiRequests > ${requests}`, 'ordinary polling');
    assert.equal(await page.evaluate(`!!document.querySelector('.card-play-warning')`), true, 'Polling preserves the warning and draft');
    await page.evaluate(`document.querySelector('[data-help-topic="unplayed-cards"]').focus(); document.activeElement.click()`);
    await page.wait(`document.querySelector('.reference-dialog[open]')`, 'reference during no-effect draft');
    await escape(page);
    await page.wait(`!document.querySelector('.reference-dialog')`, 'reference returns to the draft');
    assert.equal(await page.evaluate(`document.querySelector('.play-card-action').disabled`), false);
    await page.evaluate(`window.uiNextError = 'CARD_ILLEGAL_TARGET'; document.querySelector('.play-card-action').click()`);
    await page.wait(`document.querySelector('.decision-error') || document.querySelector('.error[role=alert]')`, 'rejected play');
    assert.equal(await page.evaluate(`!!document.querySelector('.card-play-warning')`), true, 'Rejection preserves the warning and draft');
    const changed = {...before, revision: before.revision + 10000, game: {...before.game,
        cardOffers: before.game.cardOffers.map(candidate => candidate.reference === card.reference
            ? {...candidate, choices: candidate.choices.map(choice => ({...choice, noEffect: false}))} : candidate)}};
    await page.evaluate(`window.setUiSnapshot(${JSON.stringify(changed)})`);
    await page.wait(`!document.querySelector('.card-play-warning')`, 'accepted polling clears an obsolete warning');
    await page.evaluate(`window.setUiSnapshot(${JSON.stringify({...before, revision: changed.revision + 1})})`);
    await page.wait(`document.querySelector('.card-play-warning')`, 'warning follows the current offer');
    const after = await game.send(player, command);
    const accepted = {...after, revision: changed.revision + 2};
    await page.evaluate(`window.setUiSnapshot(${JSON.stringify(accepted)}); document.querySelector('.play-card-action').click()`);
    await page.wait(`!document.querySelector('[data-hand-card="${card.reference}"]') && !document.querySelector('.card-play-warning')`, 'successful play clears the draft and warning');
    assert.equal(await page.evaluate(`document.querySelector('.card-decision')`), null);
    const armor = before.game.cardInspections.find(card => card.definition.id === 'armor').definition;
    const voided = {...before, revision: changed.revision + 3, game: {...before.game,
        hand: [{reference: 'voided', definition: armor}], burdenReferences: ['voided'],
        cardOffers: [{...offer, reference: 'voided', playable: false, blockers: ['MISERY_BURDEN'], choices: []}]}};
    await page.evaluate(`window.setUiSnapshot(${JSON.stringify(voided)})`);
    await page.wait(`document.querySelector('[data-hand-card="voided"]')`, 'voided card fixture');
    await page.evaluate(`document.querySelector('[data-hand-card="voided"]').click()`);
    await page.wait(`document.querySelector('.card-inspection-dialog .blocker[role=alert]')`, 'voided card explanation');
    assert.match(await page.evaluate(`document.querySelector('.card-inspection-dialog .blocker').textContent`), /voided.*cannot be played.*stabilization/);
    assert.equal(await page.evaluate(`document.querySelector('.card-inspection-dialog .play-card-action')`), null);
    await escape(page);
    console.log('Passed: map controls share the bar, hide by available width, preserve camera/drafts, and retain keyboard access; live no-effect offers warn before play, follow polling, preserve rejected drafts and clear after success; voided cards explain their block.');
    return before;
}

if (process.argv[1]?.endsWith('/uiPolish.browser.mjs')) {
    const page = await open('about:blank');
    try {
        const baseline = await checkUiPolish(page, process.env.MAP_WEB_URL);
        const expansion = {
            ...baseline, game: {...baseline.game, stage: 'EXPANSION'},
        };
        await checkReferenceUx(page, process.env.MAP_WEB_URL, expansion);
        await checkCardAndPlayerDetails(page, process.env.MAP_WEB_URL, expansion);
        await checkReplacementContext(page, process.env.MAP_WEB_URL, expansion);
    } finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
