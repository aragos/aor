import assert from 'node:assert/strict';

export async function checkPortal(page, web) {
    await page.send('Page.navigate', {url: web + '/tests/portal-harness.html'});
    await page.wait(`document.querySelector('.portal') && document.querySelectorAll('.game-directory > li').length === 2`, 'own ongoing games');
    const rows = await page.evaluate(`[...document.querySelectorAll('.game-directory > li')].map(item => ({
        tag: item.firstElementChild.tagName, children: item.children.length,
        href: item.firstElementChild.getAttribute('href'), buttons: item.querySelectorAll('button').length,
        text: item.textContent, background: getComputedStyle(item.firstElementChild).backgroundColor,
    }))`);
    assert(rows.every(row => row.tag === 'A' && row.children === 1 && row.buttons === 0), 'Each game is one native link covering its row');
    assert(rows.every(row => row.href.startsWith('/games/') && row.text.includes('@alice') && !row.text.includes('00000000') && !row.text.includes('Created')), 'Rows show handles and state without IDs or dates');
    assert.notEqual(rows[0].background, rows[1].background, 'Awaiting and waiting rows have different backgrounds');
    await page.evaluate(`document.querySelector('.game-row').focus()`);
    assert.equal(await page.evaluate(`document.activeElement === document.querySelector('.game-row')`), true, 'Game rows accept keyboard focus');
    await page.wait(`document.querySelector('link[rel=icon]')`, 'Game list favicon');
    const readyIcon = await page.evaluate(`document.querySelector('link[rel=icon]').href`);
    const iconSvg = await fetch(readyIcon).then(response=>response.text());
    assert(iconSvg.includes('#205c4b'), 'Any awaiting game colors the game-list favicon');
    await page.click('My move');
    await page.wait(`document.querySelectorAll('.game-directory > li').length === 1`, 'my move game filter');
    await page.reload();
    await page.wait(`document.querySelectorAll('.game-directory > li').length === 1 && [...document.querySelectorAll('button')].some(button=>button.textContent==='My move' && button.getAttribute('aria-pressed')==='true')`, 'persisted game filter');
    await page.click('Finished');
    await page.wait(`document.querySelector('.game-directory').textContent.includes('Finished')`, 'finished games');
    assert.equal(await page.evaluate(`document.querySelector('link[rel=icon]').href`), readyIcon, 'Finished filter does not hide a turn in another game');
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
    await page.click('All games');
    await page.click('All');
    await page.wait(`document.querySelectorAll('.admin-game').length === 3`, 'administrator list of every game');
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
    console.log('Passed: unique player handles, independent turn favicon, creation, filters, administration, lifecycle and approvals.');
}
