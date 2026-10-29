import assert from 'node:assert/strict';
import {writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {open} from './browser-support.mjs';

// Only GET requests inspect authoritative state. Creation and every decision use visible controls;
// no command console, synthetic snapshots, state edits, or command API calls drive this game.
const web = process.env.MAP_WEB_URL ?? 'http://127.0.0.1:5191';
const api = process.env.MAP_API_URL ?? 'http://127.0.0.1:8095';
const cdp = process.env.MAP_CDP_URL ?? 'http://127.0.0.1:9351';
const artifacts = process.env.AOR_BROWSER_ARTIFACTS ?? '/private/tmp';
let page = await open(web);
const pages = new Map();
const landing = page;
const trace = [];
const stages = new Set();
const epochs = new Set();
let links = [];
let gameId;
let currentPlayer;
let latest;

async function click(selector, index = 0) {
    await page.wait(`(() => {const button = document.querySelectorAll(${JSON.stringify(selector)})[${index}]; return button && !button.disabled;})()`, `enabled control ${selector}[${index}]`);
    assert.equal(await page.evaluate(`(() => {const button = document.querySelectorAll(${JSON.stringify(selector)})[${index}]; if (!button || button.disabled) return false; button.click(); return true;})()`), true);
}

async function snapshot(link) {
    const response = await fetch(`${api}/api/games/${gameId}`, {headers: {Authorization: `Bearer ${link.token}`}});
    assert(response.ok, `Read snapshot: ${response.status}`);
    return response.json();
}

async function show(link, revision) {
    if (currentPlayer !== link.id) {
        if (!pages.has(link.id)) pages.set(link.id, await open(web + link.path));
        page = pages.get(link.id);
        currentPlayer = link.id;
    }
    await page.send('Page.bringToFront');
    await page.wait(`document.querySelector('.player-summary.is-viewer summary')?.getAttribute('aria-label')?.includes(${JSON.stringify(link.name + ',')}) && document.querySelector('.command-dock summary small')?.textContent.trim() === ${JSON.stringify('Revision ' + revision)}`, `${link.name} at revision ${revision}`);
}

function choose(state) {
    const commands = state.legalChoices.commands;
    if (state.game.stage === 'BIDDING') return `bid-capital ${3 - links.findIndex(link => link.id === state.viewerId)}`;
    if (state.game.stage === 'TOKEN_PURCHASE') return `buy-tokens ${links.findIndex(link => link.id === state.viewerId)}`;
    const capital = commands.find(command => command.startsWith('choose-capital '));
    if (capital) return capital;
    const position = commands.find(command => command.startsWith('choose-turn-position '));
    if (position) return position;
    const play = commands.find(command => command.startsWith('play '));
    if (play) {
        const parts = play.split(' ');
        const offer = state.game.cardOffers.find(candidate => candidate.reference === parts[1]);
        // The compact Pirates picker always binds the canonical plural form, even for one target.
        if (offer?.choices.some(choice => choice.kind === 'PROVINCES')) parts[2] = 'provinces';
        return parts.join(' ');
    }
    if (commands.includes('buy-card')) return 'buy-card';
    const advance = commands.find(command => command.startsWith('buy-advance '));
    if (advance) return advance;
    if (commands.includes('stabilize cash')) return 'stabilize cash';
    if (commands.includes('stabilize misery')) return 'stabilize misery';
    if (commands.includes('pass')) return 'pass';
    assert(commands.length > 0, `No visual action at ${state.prompt?.messageId}`);
    return commands[0];
}

const simple = ['pass', 'buy-card', 'buy-expansion-card', 'windmill', 'written-record', 'cathedral', 'stabilize cash', 'stabilize misery'];
function finite(commands) {
    return commands.filter(command => !simple.includes(command) &&
        ['written-record ', 'swap-order ', 'cancel-adjustment ', 'return-card ', 'adjust-market ', 'discard-card ', 'civil-war-loss ', 'cathedral ', 'patronize ', 'offer-card ', 'reduce-marker '].some(prefix => command.startsWith(prefix)));
}

async function playCard(state, command) {
    const reference = command.split(' ')[1];
    const offer = state.game.cardOffers.find(candidate => candidate.reference === reference);
    assert(offer?.playable, `Typed card offer authorizes ${command}`);
    await click(`[data-hand-card="${reference}"]`);
    await page.wait(`document.querySelector('.card-decision form')`, 'selected card draft');
    const targets = offer.choices.find(choice => choice.kind === 'PROVINCES');
    if (targets) {
        const selected = command.split(' ').slice(3);
        assert.equal(selected.length, offer.requiredTargetCount, 'Pirate draft contains the exact required target count');
        for (const province of selected) {
            const index = offer.provinceTargets.indexOf(province);
            assert(index >= 0, `Pirate target ${province} is eligible`);
            await click('.card-decision .target-choices > button:not(.map-picker)', index);
        }
    } else {
        const choices = offer.choices.filter(choice => choice.command !== null);
        const index = choices.findIndex(choice => choice.command === command);
        assert(index >= 0, `Projection has exact finite command ${command}`);
        const automatic = choices.length === 1 && ['NONE', 'COMMODITY'].includes(choices[0].kind);
        if (!automatic) await click('.card-decision .target-choices > button:not(.map-picker)', index);
    }
    await click('.card-decision form > button:last-child');
}

async function perform(state, command) {
    const [verb, argument] = command.split(' ');
    if (verb === 'bid-capital' || verb === 'buy-tokens') {
        await page.fill('#bid', argument);
        return click('.decision-panel form:has(#bid) button');
    }
    if (verb === 'choose-capital' || verb === 'choose-turn-position') {
        const options = state.legalChoices.commands.filter(choice => choice.startsWith(verb + ' '));
        await click('.capital-choices > button.secondary', options.indexOf(command));
        return click('.capital-choices > button:not(.secondary)');
    }
    if (verb === 'play') return playCard(state, command);
    if (verb === 'buy-advance') {
        await click('#tab-advances');
        const index = state.game.catalog.advances.findIndex(advance => advance.letter === argument);
        const id = state.game.catalog.advances[index]?.id;
        assert(id, `Advance letter ${argument} is in catalog`);
        await page.evaluate(`(() => {const button = [...document.querySelectorAll('.advance-tile')].find(button => button.querySelector('.advance-letter')?.textContent === ${JSON.stringify(argument)}); if (!button) throw Error('Missing advance tile'); button.click();})()`);
        return click('.advance-decision > button:last-child');
    }
    if (simple.includes(command)) {
        const offered = state.legalChoices.commands.filter(choice => simple.includes(choice));
        return click('.game-actions > .action-buttons > button:not(.secondary)', offered.indexOf(command));
    }
    const choices = finite(state.legalChoices.commands);
    if (choices.includes(command)) {
        await click('.game-actions > .action-buttons > button.secondary', choices.indexOf(command));
        return click('.game-actions .selected-action button');
    }
    if (verb === 'replace-event-tokens') {
        const selected = command.split(' ').slice(1);
        for (const province of state.game.replacementToken.provinces) {
            await click(`[data-replacement-province="${province}"] button:${selected.includes(province) ? 'first' : 'last'}-child`);
        }
        return click('.event-replacements > button:last-child');
    }
    if (verb === 'forgo-marker') return page.click('Forgo this marker');
    if (verb === 'cede-war') {
        const provinces = command.split(' ').slice(1);
        const candidates = state.game.catalog.provinces.filter(province => state.game.warCession.eligibleProvinces.includes(province.id));
        for (const province of provinces) await click('.province-checks input', candidates.findIndex(candidate => candidate.id === province));
        return click('.game-actions form:has(.province-checks) > button:last-child');
    }
    throw new Error(`No visual driver for supported action: ${command}`);
}

try {
    await page.wait('document.querySelector("#names")', 'visual game creation');
    await page.fill('#names', 'Ada\nBruno\nClara');
    await click('.landing form > button:last-child');
    await page.wait('document.querySelectorAll(".seat-links a").length === 3', 'three private seat links');
    const paths = await page.evaluate('[...document.querySelectorAll(".seat-links li")].map(row => ({name:row.querySelector("strong").textContent, path:row.querySelector("a").getAttribute("href")}))');
    gameId = paths[0].path.split('/')[2].split('#')[0];
    links = paths.map((link, index) => ({...link, id:`p${index + 1}`, token: new URLSearchParams(link.path.split('#')[1]).get('token')}));
    latest = await snapshot(links[0]);
    let lastTurn;
    for (let step = 0; step < 2500 && latest.game.stage !== 'FINISHED'; step++) {
        const actor = links.find(link => latest.prompt?.players.includes(link.id));
        assert(actor, `Authority stopped without a player at ${latest.game.stage}`);
        const state = await snapshot(actor);
        stages.add(state.game.stage); epochs.add(state.game.currentEpoch);
        await show(actor, state.revision);
        const command = choose(state);
        trace.push({revision:state.revision, turn:state.game.turnNumber, epoch:state.game.currentEpoch, player:actor.id, prompt:state.prompt.messageId, command, accepted:false});
        if (lastTurn !== state.game.turnNumber || step % 100 === 0) {
            console.log(`Full visual game: action ${step}, epoch ${state.game.currentEpoch}, turn ${state.game.turnNumber}, ${state.game.stage}`);
            lastTurn = state.game.turnNumber;
        }
        const simultaneous = ['bid-capital', 'buy-tokens', 'return-card'].includes(command.split(' ')[0]);
        const pending = simultaneous && state.prompt.players.length > 1;
        const expectedRevision = state.revision + (pending ? 0 : 1);
        await perform(state, command);
        const accepted = pending
            ? `document.querySelector('.player-summary.is-viewer .player-status')?.textContent === 'Submitted' && document.querySelector('.save-status')?.textContent === 'Saved'`
            : `document.querySelector('.command-dock summary small')?.textContent.trim() === ${JSON.stringify('Revision ' + expectedRevision)}`;
        await page.wait(`(${accepted}) || document.querySelector('.decision-panel .error')`, `accepted visual action ${command}`);
        const error = await page.evaluate('document.querySelector(".decision-panel .error")?.textContent ?? null');
        assert.equal(error, null, `Visual action rejected: ${command}`);
        latest = await snapshot(actor);
        assert.equal(latest.revision, expectedRevision, 'Submitted decisions wait for reveal; ordinary controls commit exactly once');
        if (pending) {
            assert(latest.submitted.includes(actor.id), 'Private submission has a submitted readiness indicator');
            if (command.startsWith('return-card ')) assert.equal(latest.pendingReturn, command.split(' ')[1]);
            else assert.equal(latest.pendingBid, Number(command.split(' ')[1]));
        } else {
            const expected = `${actor.id}: ${command}`;
            assert(latest.history.slice(state.history.length).some(line => line === expected || line.startsWith(expected + ' [')), `Control committed its exact canonical action: ${command}`);
        }
        assert.equal(await page.evaluate('document.querySelector(".command-dock").open'), false, 'Game never uses the command console');
        trace.at(-1).accepted = true;
    }
    assert.equal(latest.game.stage, 'FINISHED', 'The complete visual game reaches results');
    assert.equal(latest.game.scores.length, 3);
    const chaosEnding = latest.game.players.every(player => player.misery === null);
    if (chaosEnding) {
        assert(latest.game.scores.every(score => score.total === null && !score.winner), 'Chaos has no numeric score or winner');
    } else {
        assert(latest.game.scores.some(score => score.winner), 'Active final results identify a winner');
    }
    await page.wait('document.querySelectorAll(".final-scores tbody tr").length === 3', 'all three visible final score rows');
    await page.screenshot(join(artifacts, 'aor-fullgame-final.png'), 1440);
    console.log(`Complete visual game passed: ${trace.length} accepted actions, ${latest.game.turnNumber} turns, epochs ${[...epochs].join(',')}, ${latest.game.scores.length} final scores, ending ${chaosEnding ? 'Chaos' : 'deck exhaustion'}, final card round ${stages.has('FINAL_CARD_PLAY')}.`);
} catch (error) {
    await page.screenshot(join(artifacts, 'aor-fullgame-failure.png'), 1440).catch(() => {});
    console.error(`Fullgame failure after ${trace.length} actions: ${JSON.stringify(trace.at(-1))}`);
    throw error;
} finally {
    writeFileSync(join(artifacts, 'aor-fullgame-actions.json'), JSON.stringify({gameId, steps:trace, finalStage:latest?.game.stage, finalEpoch:latest?.game.currentEpoch, finalCardPlay:stages.has('FINAL_CARD_PLAY'), scores:latest?.game.scores}, null, 2));
    for (const opened of [landing, ...pages.values()]) {
        opened.ws.close();
        await fetch(`${cdp}/json/close/${opened.target.id}`).catch(() => {});
    }
}
