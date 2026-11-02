import assert from 'node:assert/strict';
import {join} from 'node:path';
import {open} from './browser-support.mjs';

export async function checkPortal(page, web) {
    await page.send('Page.navigate', {url: web + '/tests/portal-harness.html'});
    await page.wait(`document.querySelector('.portal') && document.querySelectorAll('.game-directory > li').length === 2`, 'own ongoing games');
    const rows = await page.evaluate(`[...document.querySelectorAll('.game-directory > li')].map(item => ({
        tag: item.firstElementChild.tagName, children: item.children.length,
        href: item.firstElementChild.getAttribute('href'), buttons: item.querySelectorAll('button').length,
        text: item.textContent, background: getComputedStyle(item.firstElementChild).backgroundColor,
    }))`);
    assert(rows.every(row => row.tag === 'A' && row.children === 1 && row.buttons === 0), 'Each game is one native link covering its row');
    assert.match(rows[0].text, /Friday Renaissance/);
    assert.match(rows[1].text, /Mediterranean traders/);
    assert(rows.every(row => row.href.startsWith('/games/') && row.text.includes('@alice') && !row.text.includes('00000000') && !row.text.includes('Created')), 'Rows show handles and state without IDs or dates');
    assert.notEqual(rows[0].background, rows[1].background, 'Awaiting and waiting rows have different backgrounds');
    const players = await page.evaluate(`[...document.querySelectorAll('.game-row')].map(row =>
        [...row.querySelectorAll('.game-row-player')].map(player => ({
            name: player.childNodes[0].textContent,
            acting: player.classList.contains('needs-action'),
            accessibleStatus: player.querySelector('.sr-only')?.textContent,
            weight: getComputedStyle(player).fontWeight,
        })))`);
    assert.deepEqual(players.map(row => row.filter(player => player.acting).map(player => player.name)),
        [['@alice', '@carol'], ['@bob']], 'Each row highlights all required players, including another player’s turn');
    assert(players.flat().filter(player => player.acting).every(player => player.accessibleStatus.includes('Action required') && Number(player.weight) >= 700),
        'Acting players have a visible emphasis and an accessible status');
    await page.evaluate(`document.querySelector('.game-row').focus()`);
    assert.equal(await page.evaluate(`document.activeElement === document.querySelector('.game-row')`), true, 'Game rows accept keyboard focus');
    await page.wait(`document.querySelector('link[rel=icon]')`, 'Game list favicon');
    const readyIcon = await page.evaluate(`document.querySelector('link[rel=icon]').href`);
    const iconSvg = await fetch(readyIcon).then(response=>response.text());
    assert(iconSvg.includes('#205c4b'), 'Any awaiting game colors the game-list favicon');
    assert.equal(await page.evaluate(`document.querySelectorAll('.games-panel .choice-buttons').length`), 0, 'Filters do not compete with game tabs');
    await page.evaluate(`document.querySelector('.game-list-filter select').value='my-move'; document.querySelector('.game-list-filter select').dispatchEvent(new Event('change', {bubbles:true}))`);
    await page.wait(`document.querySelectorAll('.game-directory > li').length === 1`, 'my move game filter');
    await page.reload();
    await page.wait(`document.querySelectorAll('.game-directory > li').length === 1 && document.querySelector('.game-list-filter select')?.value === 'my-move'`, 'persisted game filter');
    await page.evaluate(`document.querySelector('.game-list-filter select').value='finished'; document.querySelector('.game-list-filter select').dispatchEvent(new Event('change', {bubbles:true}))`);
    await page.wait(`document.querySelector('.game-directory').textContent.includes('Finished')`, 'finished games');
    assert.equal(await page.evaluate(`document.querySelector('link[rel=icon]').href`), readyIcon, 'Finished filter does not hide a turn in another game');
    assert.equal(await page.evaluate(`document.querySelectorAll('.game-row-player.needs-action').length`), 0, 'Finished games highlight no players');
    await page.evaluate(`document.querySelector('.create-registered > summary').click()`);
    await page.fill('input[type=search]', 'bo');
    await page.wait(`document.querySelector('[aria-label="Matching players"]')?.textContent.includes('@bob')`, 'registered player directory search');
    assert.equal(await page.evaluate(`document.querySelector('[aria-label="Matching players"]').textContent.includes('uid-bob')`), false, 'Search results omit UIDs');
    await page.click('Add');
    assert.equal(await page.evaluate(`document.querySelector('.create-registered .player-directory').textContent.includes('@bob')`), true, 'Selected players show unique handles');
    assert.equal(await page.evaluate(`document.querySelector('.create-registered .player-directory').textContent.includes('uid-')`), false, 'Selected players omit UIDs');
    await page.fill('input[type=search]', 'ca');
    await page.wait(`document.querySelector('[aria-label="Matching players"]')?.textContent.includes('@carol')`, 'third registered participant');
    await page.click('Add');
    assert.equal(await page.evaluate(`[...document.querySelectorAll('button')].find(button=>button.textContent==='Create game').disabled`), false);
    assert.equal(await page.evaluate(`document.querySelectorAll('.create-registered select').length`), 0, 'Creation options use visible buttons');
    await page.click('Other games');
    await page.wait(`document.querySelector('.game-directory').textContent.includes('Eastern trade routes')`, 'public game directory');
    assert.equal(await page.evaluate(`document.querySelector('.game-directory').textContent.includes('@alice')`), false, 'Other games excludes memberships');
    assert.equal(await page.evaluate(`document.querySelector('.game-list-filter select').value`), 'ongoing', 'Other games starts with its own filter');
    assert.equal(await page.evaluate(`document.querySelector('.game-list-filter option[value="my-move"]')`), null, 'Spectators have no My move filter');
    assert.equal(await page.evaluate(`document.querySelectorAll('.game-directory a.game-row').length`), 2, 'Other games open as spectator links');
    assert.equal(await page.evaluate(`document.querySelector('link[rel=icon]').href`), readyIcon, 'Spectating directory retains own-game turn indicator');
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-other-games-desktop.png'), 1280, 900);
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-other-games-phone.png'), 390, 844, true);
    assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth + 1`), true, 'Spectator directory fits a phone');
    await page.send('Emulation.clearDeviceMetricsOverride');
    await page.evaluate(`document.getElementById('games-tab-other').focus()`);
    await page.send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'ArrowLeft', code: 'ArrowLeft'});
    await page.send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'ArrowLeft', code: 'ArrowLeft'});
    await page.wait(`document.getElementById('games-tab-mine').getAttribute('aria-selected') === 'true' && document.querySelector('.game-directory').textContent.includes('Spring campaign')`, 'keyboard tab returns to saved own filter');
    await page.click('Administration');
    await page.evaluate(`document.querySelector('.game-list-filter select').value='all'; document.querySelector('.game-list-filter select').dispatchEvent(new Event('change', {bubbles:true}))`);
    await page.wait(`document.querySelectorAll('.admin-game').length === 6`, 'administrator list of every game');
    await page.evaluate(`document.querySelector('.admin-game > summary').click()`);
    await page.click('Abandon game');
    await page.wait(`window.portalRequests.some(request=>request.path.endsWith('/status') && request.body.action==='abandon' && request.body.expectedRevision===4)`, 'revision checked lifecycle action');
    await page.evaluate(`[...document.querySelectorAll('details > summary')].find(summary=>summary.textContent==='Player creation permissions').click()`);
    await page.wait(`document.querySelectorAll('.player-directory button').length >= 4`, 'administrator player directory');
    assert.equal(await page.evaluate(`[...document.querySelectorAll('.player-directory')].some(directory=>directory.textContent.includes('uid-'))`), false, 'Player directories omit UIDs for administrators');
    await page.click('Allow creation');
    await page.wait(`window.portalRequests.some(request=>request.path==='/api/admin/players/uid-bob/creation' && request.body.allowed===true)`, 'creator approval uses the selected identity');
    await page.send('Emulation.setDeviceMetricsOverride', {width:390, height:844, deviceScaleFactor:1, mobile:true});
    assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth + 1`), true, 'Portal fits a phone');
    await page.evaluate(`window.portalHasTurn=false; document.dispatchEvent(new Event('visibilitychange')); localStorage.removeItem('aor.setting.game-filter')`);
    await page.wait(`document.querySelector('link[rel=icon]').href !== ${JSON.stringify(readyIcon)}`, 'Game list becomes grayscale without awaiting games');
    await page.fill('#game-name', 'Friday with friends');
    await page.click('Create game');
    await page.wait(`window.portalRequests.some(request => request.path === '/api/games' && request.body?.name === 'Friday with friends')`, 'registered creation submits chosen name');
    await page.wait(`document.querySelector('.create-registered [role=alert]')`, 'creation failure surfaced');
    assert.equal(await page.evaluate(`document.querySelector('#game-name').value`), 'Friday with friends', 'Failed registered creation preserves the game name');
    await page.send('Emulation.clearDeviceMetricsOverride');
    await page.send('Page.navigate', {url: web + '/tests/portal-harness.html?admin=false'});
    await page.wait(`document.querySelector('.portal')`, 'regular registered player directory');
    assert.equal(await page.evaluate(`document.querySelectorAll('.game-list-tabs [role=tab]').length`), 2, 'Every registered player has Own games and Other games tabs');
    assert.equal(await page.evaluate(`document.getElementById('games-tab-admin')`), null, 'Administrative controls are separate from spectator access');
    await page.click('Other games');
    await page.wait(`document.querySelector('.game-directory').textContent.includes('Eastern trade routes')`, 'regular players can browse public games');
    console.log('Passed: spectator directory, unique player handles, independent turn favicon, creation, filters, administration, lifecycle and approvals.');
}

if (process.env.AOR_BROWSER_SUITE === 'portal.browser.mjs') {
    const page = await open('about:blank');
    try {await checkPortal(page, process.env.MAP_WEB_URL);}
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
