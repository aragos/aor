import assert from 'node:assert/strict';
import {createGame, open} from './browser-support.mjs';

async function harness(page, web) {
    const previous = await page.evaluate('performance.timeOrigin');
    await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
    await page.wait(`performance.timeOrigin !== ${previous} && typeof window.setMapSnapshot === 'function'`, 'new lazy map harness');
    await page.evaluate(`(() => {
        const nativeFetch = window.fetch.bind(window);
        window.geometryRequests = [];
        window.geometryPending = [];
        window.fetch = (input, init) => {
            if (!new URL(String(input), location.href).pathname.endsWith('Geometry.json')) return nativeFetch(input, init);
            window.geometryRequests.push(String(input));
            return new Promise((resolve, reject) => {
                window.geometryPending.push({input, init, resolve, reject});
                init.signal.addEventListener('abort', () => reject(init.signal.reason), {once: true});
            });
        };
        window.releaseGeometry = async (index, fail = false) => {
            const pending = window.geometryPending[index];
            if (fail) pending.reject(new TypeError('Map connection interrupted'));
            else pending.resolve(await nativeFetch(pending.input, pending.init));
        };
    })()`);
    assert.equal(await page.evaluate('window.geometryRequests.length'), 0, 'Importing the map renderer does not fetch drawings');
}

export async function checkMapGeometry(page, web) {
    const standardGame = await createGame(3);
    const europeanGame = await createGame(3, {options: {map: 'european'}});
    const standard = await standardGame.snapshot(standardGame.links[0]);
    const european = {...await europeanGame.snapshot(europeanGame.links[0]), gameId: standard.gameId};
    await page.send('Page.bringToFront');
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false});
    await harness(page, web);
    await page.evaluate(`window.setMapSnapshot(${JSON.stringify(standard)})`);
    await page.wait(`window.geometryPending.length === 1 && document.querySelector('#bid')`, 'one StrictMode map request');
    assert.equal(await page.evaluate(`document.querySelector('.world-map')`), null);
    assert.match(await page.evaluate(`document.querySelector('.map-load-status [role=status]').textContent`), /Opening the map/);
    const loadingHeight = await page.evaluate(`document.querySelector('.map-load-status').getBoundingClientRect().height`);
    assert(loadingHeight > 100, 'Loading reserves a useful map viewport');
    await page.fill('#bid', '7');
    await page.evaluate(`window.releaseGeometry(0, true)`);
    await page.wait(`document.querySelector('.map-load-status [role=alert]')`, 'map load error');
    assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7');
    assert.equal(await page.evaluate(`document.querySelector('.map-load-status').getBoundingClientRect().height`), loadingHeight, 'Failures retain the map footprint');
    await page.click('Try again');
    await page.wait(`window.geometryPending.length === 2`, 'map retry requests the same drawing');
    await page.evaluate(`window.releaseGeometry(1)`);
    await page.wait(`document.querySelector('[data-map-geometry="ah1996-catalog-16:STANDARD"] .world-map')`, 'retried standard drawing');
    assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7');
    assert.equal(await page.evaluate('window.geometryRequests.every(url => url.endsWith("/boardGeometry.json"))'), true, 'A standard game downloads only its drawing');
    await page.evaluate(`document.querySelector('button[aria-label="Zoom in"]').click()`);
    const zoomedView = await page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox')`);
    await page.evaluate(`window.originalMap = document.querySelector('.world-map');
        window.setMapSnapshot(${JSON.stringify({...standard, revision: standard.revision + 1})})`);
    await page.wait(`document.querySelector('[data-test-revision="${standard.revision + 1}"]')`, 'accepted same-map poll');
    assert.equal(await page.evaluate(`window.originalMap === document.querySelector('.world-map')`), true, 'Polling keeps the map and viewport mounted');
    assert.equal(await page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox')`), zoomedView, 'Polling preserves the zoomed viewport');
    assert.equal(await page.evaluate('window.geometryRequests.length'), 2);

    await page.evaluate(`window.setMapSnapshot(${JSON.stringify(european)})`);
    await page.wait(`window.geometryPending.length === 3`, 'a different map loads its own drawing');
    assert.equal(await page.evaluate(`document.querySelector('.world-map')`), null, 'A new catalog never displays the previous drawing while loading');
    assert.equal(await page.evaluate(`document.querySelector('#bid').value`), '7', 'Map loading retains the independent action draft');
    await page.evaluate(`window.releaseGeometry(2)`);
    await page.wait(`document.querySelector('[data-map-geometry="ah1996-catalog-16:EUROPEAN"] .world-map')`, 'European drawing');
    assert.equal(await page.evaluate(`!!document.querySelector('[data-map-province="smolensk"]')`), false);
    await page.evaluate(`window.setMapSnapshot(${JSON.stringify(standard)})`);
    await page.wait(`document.querySelector('[data-map-geometry="ah1996-catalog-16:STANDARD"] .world-map')`, 'cached standard drawing');
    assert.equal(await page.evaluate('window.geometryRequests.length'), 3, 'Returning to a loaded drawing uses the cache');

    await harness(page, web);
    await page.evaluate(`window.setMapSnapshot(${JSON.stringify(standard)})`);
    await page.wait(`window.geometryPending.length === 1`, 'delayed standard request');
    await page.evaluate(`window.setMapSnapshot(${JSON.stringify(european)})`);
    await page.wait(`window.geometryPending.length === 2`, 'European request replaces a delayed standard load');
    await page.evaluate(`window.releaseGeometry(1)`);
    await page.wait(`document.querySelector('[data-map-geometry="ah1996-catalog-16:EUROPEAN"] .world-map')`, 'newer map request finishes first');
    await page.evaluate(`window.originalEuropeanMap = document.querySelector('.world-map'); window.releaseGeometry(0)`);
    await page.evaluate(`new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))`);
    assert.equal(await page.evaluate(`window.originalEuropeanMap === document.querySelector('.world-map')`), true);
    assert.equal(await page.evaluate(`document.querySelector('[data-map-geometry]').dataset.mapGeometry`), 'ah1996-catalog-16:EUROPEAN', 'A late drawing cannot replace the current map');
    console.log('Passed: only the selected drawing loads, StrictMode coalesces requests, failure/retry and polling preserve drafts, and map switches reject stale completions.');
}

if (process.argv[1]?.endsWith('/mapGeometry.browser.mjs')) {
    const page = await open('about:blank');
    try {await checkMapGeometry(page, process.env.MAP_WEB_URL);}
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
