import assert from 'node:assert/strict';
import {waitForMap} from './browser-support.mjs';

export async function checkExpansionDiagnostics(page, web, baseline) {
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait("typeof window.setMapSnapshot === 'function'", 'expansion diagnostics harness');
    const viewer = baseline.viewerId;
    const original = baseline.game.expansionOffers.find(offer =>
        offer.primaryAction === 'PLACE' && offer.establishesMarker && offer.blockers.length === 0);
    assert(original, 'A legal market offer supplies the province fixture');
    const province = original.destination;
    let revision = 19000;
    async function show(inspection, candidate = false) {
        const fixture = {...baseline, revision: ++revision,
            prompt: {messageId: 'prompt.expand', players: [viewer]},
            legalChoices: {commands: ['pass'], bidMin: null, bidMax: null},
            game: {...baseline.game, stage: 'EXPANSION',
                activity: {...baseline.game.activity, actor: viewer, decisionPlayer: viewer},
                expansionOffers: candidate ? [inspection] : [],
                expansionInspections: [inspection],
                players: baseline.game.players.map(player => player.id === viewer
                    ? {...player, poolTokens: inspection.availablePool} : player),
                board: inspection.primaryAction === 'INSPECT'
                    ? [...baseline.game.board.filter(holding => holding.id !== inspection.destination),
                        {id: inspection.destination, marker: {owner: viewer, side: 'COLOURED'}, tokens: []}]
                    : baseline.game.board,
            }};
        await page.evaluate('window.setMapSnapshot(' + JSON.stringify(fixture) + ')');
        await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, 'expansion diagnostic fixture');
        await waitForMap(page, fixture);
        await page.evaluate('document.querySelector(' + JSON.stringify('[data-map-province="' + inspection.destination + '"]') +
            ').dispatchEvent(new KeyboardEvent("keydown",{key:"Enter",bubbles:true}))');
    }
    const blocked = {...original, resultingPool: null, partialPlacements: [], missingAccess: []};

    await show({...blocked, availablePool: 0, blockers: ['INSUFFICIENT_EXPANSION_TOKENS']});
    assert.match(await page.evaluate('document.querySelector(".province-actions [data-expansion-blocker]").textContent'),
        /Needs.*tokens.*have 0/i);
    assert.equal(await page.evaluate('document.querySelector(".decision-actions .primary-expansion")'), null,
        'A filtered destination has an explanation without a placement control');
    assert.equal(await page.evaluate('document.querySelector(' +
        JSON.stringify('[data-map-province="' + province + '"]') + ').classList.contains("eligible")'), false,
        'An impossible commitment is not highlighted as eligible');

    await show({...blocked, blockers: ['SHIPPING_CAPACITY_EXCEEDED'],
        shipping: {required: 4, used: 2, capacity: 2}});
    assert.match(await page.evaluate('document.querySelector(".province-actions [data-expansion-blocker]").textContent'),
        /Needs 4 tokens.*Already shipped here: 2.*capacity per turn: 2/i);
    assert.equal(await page.evaluate('document.querySelector(".province-actions button")'), null);

    const china = baseline.game.catalog.provinces.find(candidate => candidate.id === 'china');
    assert(china);
    await show({...blocked, destination: 'china', reachable: false,
        blockers: ['PROVINCE_ACCESS_DENIED'], missingAccess: ['OCEAN_NAVIGATION']}, true);
    assert.match(await page.evaluate('document.querySelector(".province-actions [data-expansion-blocker]").textContent'),
        /Missing access: Ocean Navigation/);
    assert.equal(await page.evaluate('document.querySelector(".decision-actions .primary-expansion")'), null);

    const partial = {...original, requiredTokens: 4, availablePool: 2, resultingPool: null,
        blockers: ['INSUFFICIENT_EXPANSION_TOKENS'], missingAccess: [],
        partialPlacements: [{tokens: 1, resultingPool: 1}, {tokens: 2, resultingPool: 0}]};
    await show(partial, true);
    assert.match(await page.evaluate('document.querySelector(".province-actions .expansion-availability").textContent'),
        /Needs 4 tokens; you have 2/i);
    assert.match(await page.evaluate('document.querySelector(".province-actions .consequence").textContent'), /marker requires 4/);
    assert.equal(await page.evaluate('document.querySelector(".primary-expansion").disabled'), false);
    await page.evaluate('document.querySelector(".primary-expansion").click()');
    assert.equal(await page.evaluate('window.mapCommands.at(-1)'), 'place 2 ' + province);

    await show({...original, primaryAction: 'INSPECT', requiredTokens: 0,
        resultingPool: null, partialPlacements: [], blockers: [], reachable: false}, true);
    assert.match(await page.evaluate('document.querySelector(".province-actions .expansion-availability").textContent'),
        /already fill this province/i);
    assert.equal(await page.evaluate('document.querySelector(".province-actions button")'), null);
    console.log('Passed: unavailable expansion explanations, filtered candidate safety, named missing access, partial placement and controlled status.');
}
