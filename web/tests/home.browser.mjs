import assert from 'node:assert/strict';
import {join} from 'node:path';
import {open} from './browser-support.mjs';
import {checkGameNaming} from './gameNaming.browser.mjs';

const page = await open('about:blank');
const web = process.env.MAP_WEB_URL;
try {
    await page.send('Emulation.setScriptExecutionDisabled', {value: true});
    await page.send('Page.navigate', {url: web + '/'});
    await page.wait(`document.querySelector('#home-title') && document.readyState === 'complete'`, 'public homepage without JavaScript');
    const content = await page.evaluate('document.body.innerText');
    assert.match(content, /AoR online/);
    assert.match(content, /strategy board game/);
    assert.match(content, /3–6 players/i);
    assert.match(content, /Games are saved as you play/);
    assert.equal(await page.evaluate(`document.querySelector('form, input[type=password]')`), null);
    assert.equal(await page.evaluate(`document.querySelector('a[href="/privacy.html"]').textContent`), 'Privacy policy');
    assert.equal(await page.evaluate(`document.querySelector('a[href="mailto:aor-online@googlegroups.com"]').textContent`), 'Contact');
    for (const [name, width, height] of [['desktop', 1280, 1000], ['phone', 390, 844]]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile: name === 'phone'});
        assert.equal(await page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'), true);
        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `public-home-${name}.png`));
    }
    console.log('Passed: application purpose, game features, support and privacy are public without JavaScript or sign-in.');

    await page.send('Emulation.setScriptExecutionDisabled', {value: false});
    await page.evaluate(`document.querySelector('.home-cta').click()`);
    await page.wait(`location.pathname === '/play' && document.querySelector('#game-name')`, 'Play online opens the local game lobby');
    await page.send('Emulation.clearDeviceMetricsOverride');
    await checkGameNaming(page, web);
} finally {
    page.ws.close();
    await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
}
