import assert from 'node:assert/strict';
import {join} from 'node:path';

export async function checkGameNaming(page, web) {
    const name = 'Les marchands du vendredi — Méditerranée & Renaissance';
    await page.send('Page.navigate', {url: web + '/play'});
    await page.wait(`document.querySelector('#game-name') && document.querySelector('#names')`, 'game naming form');
    assert.equal(await page.evaluate(`document.querySelector('#game-name').required`), true);
    assert.equal(await page.evaluate(`document.querySelector('#game-name').maxLength`), 80);
    await page.click('Create game');
    assert.equal(await page.evaluate(`document.querySelector('#game-name').checkValidity()`), false, 'An empty name cannot submit');
    await page.fill('#game-name', '   ');
    await page.click('Create game');
    await page.wait(`document.querySelector('[role=alert]')?.textContent.includes('game name')`, 'blank name rejected by server');
    assert.equal(await page.evaluate(`document.querySelector('#game-name').value`), '   ', 'Rejection preserves the creation draft');
    await page.fill('#game-name', `  ${name}  `);
    await page.click('Create game');
    await page.wait(`document.querySelectorAll('.seat-links a').length === 3`, 'named game created');
    assert.equal(await page.evaluate(`document.querySelector('.created-game-name').textContent`), name);
    const path = await page.evaluate(`document.querySelector('.seat-links a').getAttribute('href')`);
    await page.send('Page.navigate', {url: web + path});
    await page.wait(`document.querySelector('.game-name')`, 'game name in header');
    assert.equal(await page.evaluate(`document.querySelector('.game-name').textContent`), name);
    assert.equal(await page.evaluate(`document.querySelector('.game-header .games-home').getAttribute('href')`), '/play');
    assert.equal(await page.evaluate(`document.querySelector('.game-header .games-home').getAttribute('aria-label')`), 'My games',
        'The game icon is a named game-list link');
    assert((await page.evaluate('document.title')).includes(name));
    const snapshot = await page.snapshot();
    assert.equal(snapshot.name, name, 'Creation name comes from the durable snapshot');
    await page.reload();
    await page.wait(`document.querySelector('.game-name')?.textContent === ${JSON.stringify(name)}`, 'name after reload');
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-game-name-desktop.png'), 1280, 900);
    await page.screenshot(join(process.env.AOR_BROWSER_ARTIFACTS, 'aor-game-name-phone.png'), 390, 844, true);
    assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`), true, 'Long names fit a phone');
    assert.equal(await page.evaluate(`document.querySelector('.game-name').title`), name, 'Full name remains available when truncated');
    await page.evaluate(`document.querySelector('.games-home').focus()`);
    await page.send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13});
    await page.send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13});
    await page.wait(`location.pathname === '/play' && document.querySelector('#game-name')`, 'The game icon returns to the game landing page');
    console.log('Passed: creator naming, blank-name rejection and draft preservation, durable header/tab names and desktop/phone layout.');
}
