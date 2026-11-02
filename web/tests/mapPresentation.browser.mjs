import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';

export async function checkMapPresentation(page, web) {
    for (const map of ['standard', 'european']) {
        const game = await createGame(6, {options: {map}});
        const snapshot = await game.snapshot(game.links[0]);
        assert.equal(snapshot.game.catalog.dataVersion, 'ah1996-catalog-14', 'New games use corrected Cornwall connections');
        const stone = snapshot.game.catalog.provinces.find(province => province.commodities.includes('STONE'));
        for (const [width, height, mobile] of [[1440, 980, false], [390, 844, true]]) {
            await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
            await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
            await page.send('Page.bringToFront');
            await page.wait(`typeof window.setMapSnapshot === 'function'`, 'map presentation harness');
            await page.evaluate(`window.setMapSnapshot(${JSON.stringify(snapshot)})`);
            await page.wait(`document.querySelector('[data-support="cagliari genoa"]')`, 'Cagliari support paths');
            assert.equal(await page.evaluate(`document.querySelector('[data-locked-area="V"]') !== null`), true,
                'Area V has a whole-area access shade');
            assert(snapshot.game.lockedProvinces.includes('iceland') && snapshot.game.lockedProvinces.includes('west-africa'),
                'The live projection retains the insets\' navigation requirements');
            assert.equal(await page.evaluate(`document.querySelector(
                '[data-locked-province="iceland"], [data-locked-province="west-africa"]') !== null`), false,
                'Navigation requirements do not crosshatch Iceland or West Africa');
            const lockedOverseas = snapshot.game.catalog.provinces.filter(province => province.overseas &&
                snapshot.game.lockedProvinces.includes(province.id)).map(province => province.id).sort();
            assert.deepEqual(await page.evaluate(`[...document.querySelectorAll('[data-locked-province]')]
                .map(path => path.dataset.lockedProvince).filter(id => ${JSON.stringify(lockedOverseas)}.includes(id)).sort()`),
                lockedOverseas, 'Both overseas groups have access shading from the live projection');
            assert.equal(await page.evaluate(`getComputedStyle(document.querySelector('.map-access-shading')).pointerEvents`),
                'none', 'Access shading preserves province and commodity hit targets');
            const texture = () => page.evaluate(`(() => {
                const map = document.querySelector('.world-map');
                const pattern = map.querySelector('pattern[id$="-satellite"]');
                const scale = pattern.patternTransform.baseVal.consolidate().matrix.a * map.getScreenCTM().a;
                return {spacing: pattern.width.baseVal.value * scale,
                    radius: pattern.querySelector('circle').r.baseVal.value * scale,
                    satellite: pattern.querySelector('rect').getAttribute('fill'),
                    stone: map.querySelector('[data-map-province="${stone.id}"] .province-shape').getAttribute('fill')};
            })()`);
            await page.evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
            const fit = await texture();
            assert.notEqual(fit.satellite, fit.stone, 'Satellites and Stone have different base colors');
            assert(Math.abs(fit.spacing - 7) < .01 && fit.radius >= .7 && fit.radius <= 1,
                'Satellite stippling remains small and consistent at Fit');
            await page.evaluate(`document.querySelector('.world-map').dispatchEvent(new KeyboardEvent('keydown', {key: '+', bubbles: true}))`);
            assert(Math.abs((await texture()).spacing - fit.spacing) < .01, 'Satellite texture keeps its screen spacing when zoomed');
            for (const target of ['genoa', 'marseilles']) {
                const length = await page.evaluate(`document.querySelector('[data-support="cagliari ${target}"] [marker-end]').getTotalLength()`);
                assert(length >= 47.99 && length <= 48.01, 'Both Cagliari arrows have a visible 48-unit crossing');
            }
            const protrudingShafts = await page.evaluate(`[...document.querySelectorAll('[data-support]')].filter(arrow => {
                const centerline = arrow.querySelector('[marker-end]');
                const length = centerline.getTotalLength(), tip = centerline.getPointAtLength(length);
                const before = centerline.getPointAtLength(length - 1);
                const normal = {x: tip.y - before.y, y: before.x - tip.x};
                return [...arrow.querySelectorAll('path:not([marker-end])')].some(shaft =>
                    [-2, 0, 2].some(offset => shaft.isPointInStroke(new DOMPoint(tip.x + offset * normal.x, tip.y + offset * normal.y))));
            }).map(arrow => arrow.dataset.support)`);
            assert.deepEqual(protrudingShafts, [], 'Support shafts and halos stop before the narrow arrow tip');
            await page.evaluate(`document.querySelector('.world-map').dispatchEvent(new KeyboardEvent('keydown', {key: '0', bubbles: true}))`);
            if (process.env.AOR_BROWSER_ARTIFACTS) {
                await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-satellites-${map}-${width}.png`));
                for (const [region, x, y, w] of [
                    ['britain', 700, 560, 650], ['iceland', 200, 100, 500], ['west-africa', 145, 1940, 500],
                ]) {
                    await page.evaluate(`(() => {
                        const map = document.querySelector('.world-map'), rect = map.getBoundingClientRect();
                        const h = ${w} * rect.height / rect.width;
                        map.setAttribute('viewBox', [${x} - ${w} / 2, ${y} - h / 2, ${w}, h].join(' '));
                        for (const pattern of map.querySelectorAll('pattern')) {
                            pattern.setAttribute('patternTransform', 'scale(' + ${w} / rect.width + ')');
                        }
                    })()`);
                    await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-${region}-${map}-${width}.png`));
                }
                await page.evaluate(`(() => {
                    const map = document.querySelector('.world-map'), rect = map.getBoundingClientRect();
                    const w = 650, h = w * rect.height / rect.width;
                    map.setAttribute('viewBox', [1275 - w / 2, 1220 - h / 2, w, h].join(' '));
                    // Match the camera's screen-sized texture when framing a detail capture.
                    map.querySelector('pattern[id$="-satellite"]').setAttribute('patternTransform', 'scale(' + w / rect.width + ')');
                })()`);
                await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-cagliari-${map}-${width}.png`));
                await page.evaluate(`(() => {
                    const map = document.querySelector('.world-map'), rect = map.getBoundingClientRect();
                    const w = 170, h = w * rect.height / rect.width;
                    map.setAttribute('viewBox', [1275 - w / 2, 1155 - h / 2, w, h].join(' '));
                    map.querySelector('pattern[id$="-satellite"]').setAttribute('patternTransform', 'scale(' + w / rect.width + ')');
                })()`);
                await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-arrow-tips-${map}-${width}.png`));
            }
            await page.evaluate(`document.querySelector('[data-help-topic="map-legend"]').click()`);
            await page.wait(`document.querySelector('#map-legend')`, 'Map key shares the satellite texture');
            assert.equal(await page.evaluate(`document.querySelector('#reference-satellite rect').getAttribute('fill')`), fit.satellite);
            await page.evaluate(`document.querySelector('.reference-popup > .reference-link').click()`);
            await page.click('Shipping');
            await page.wait(`document.querySelector('#shipping-track .reference-track')`, 'Shipping help');
            const help = await page.evaluate(`document.querySelector('#shipping-track').textContent`);
            assert.match(help, /\$10.*at most once per turn/);
            assert.match(help, /coastal-water spaces.*each destination province per turn/);
            assert.match(help, /overseas provinces.*not a token limit.*capacity is unlimited/);
            assert.equal(await page.evaluate('document.documentElement.scrollWidth <= innerWidth'), true, 'Help fits the viewport');
            if (process.env.AOR_BROWSER_ARTIFACTS) {
                await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-shipping-help-${map}-${width}.png`));
            }
            await page.evaluate(`document.querySelector('.reference-dialog .close-detail').click()`);
            const inspected = snapshot.game.catalog.provinces.find(province => province.overseas === 'FAR_EAST').id;
            await page.evaluate(`document.querySelector('[data-map-province="${inspected}"]').dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true}))`);
            await page.wait(`document.querySelector('[data-map-province="${inspected}"][aria-pressed="true"]')`,
                'Locked provinces remain inspectable');
            await page.evaluate(`window.setMapSnapshot(${JSON.stringify({...snapshot, revision: snapshot.revision + 1000,
                game: {...snapshot.game, lockedProvinces: []}})})`);
            await page.wait(`document.querySelector('[data-test-revision="${snapshot.revision + 1000}"]')`, 'Access advances acquired');
            assert.equal(await page.evaluate(`document.querySelectorAll('[data-locked-area], [data-locked-province], [data-area-lock], [data-overseas-lock]').length`),
                0, 'Authoritative access removes hatching and locks on the next poll');
            assert.equal(await page.evaluate(`document.querySelector('[data-map-province="${inspected}"]').getAttribute('aria-pressed')`),
                'true', 'Access updates preserve province inspection');
            const openedProvince = snapshot.game.catalog.provinces.find(province => province.area === 'V').id;
            const partialLocks = snapshot.game.lockedProvinces.filter(id => id !== openedProvince);
            await page.evaluate(`window.setMapSnapshot(${JSON.stringify({...snapshot, revision: snapshot.revision + 1001,
                game: {...snapshot.game, lockedProvinces: partialLocks}})})`);
            await page.wait(`document.querySelector('[data-test-revision="${snapshot.revision + 1001}"]')`, 'Partial regional access');
            assert.equal(await page.evaluate(`document.querySelector('[data-locked-area="V"], [data-locked-province="${openedProvince}"]') !== null`),
                false, 'A partly open area does not shade its unlocked province');
            const remaining = snapshot.game.catalog.provinces.filter(province => province.area === 'V' &&
                partialLocks.includes(province.id)).map(province => province.id).sort();
            assert.deepEqual(await page.evaluate(`[...document.querySelectorAll('[data-locked-province]')]
                .map(path => path.dataset.lockedProvince).filter(id => ${JSON.stringify(remaining)}.includes(id)).sort()`),
                remaining, 'Remaining locked provinces retain their own land and coastal shading');
            assert.deepEqual(await page.evaluate('window.mapCommands'), [], 'Map and help inspection never submits');
            const cornwallPath = await page.evaluate(`document.querySelector('[data-map-province="cornwall"] .province-shape').getAttribute('d')`);
            const legacyCatalog = {...snapshot.game.catalog, dataVersion: 'ah1996-catalog-13',
                landBorders: [...snapshot.game.catalog.landBorders, {first: 'cornwall', second: 'wales'}],
                coastalConnections: snapshot.game.catalog.coastalConnections.filter(edge =>
                    ![edge.first, edge.second].includes('cornwall') || ![edge.first, edge.second].includes('chester'))};
            await page.evaluate(`window.setMapSnapshot(${JSON.stringify({...snapshot, revision: snapshot.revision + 1002,
                game: {...snapshot.game, catalog: legacyCatalog}})})`);
            await page.wait(`document.querySelector('[data-test-revision="${snapshot.revision + 1002}"]')`, 'Saved catalog-13 game');
            assert.notEqual(await page.evaluate(`document.querySelector('[data-map-province="cornwall"] .province-shape').getAttribute('d')`),
                cornwallPath, 'Existing games retain the drawing matching their recorded topology');
            await page.evaluate(`window.setMapSnapshot(${JSON.stringify({...snapshot, revision: snapshot.revision + 1003})})`);
            await page.wait(`document.querySelector('[data-test-revision="${snapshot.revision + 1003}"]')`, 'Current catalog game');
            assert.equal(await page.evaluate(`document.querySelector('[data-map-province="cornwall"] .province-shape').getAttribute('d')`),
                cornwallPath, 'Returning to a current game restores the corrected water channel');
        }
    }
    console.log('Passed: satellite texture, clean support-arrow tips, access hatching/unlocking, polling-safe inspection and shipping help on both maps at desktop/phone widths.');
}

if (process.argv[1]?.endsWith('/mapPresentation.browser.mjs')) {
    const page = await open('about:blank');
    try { await checkMapPresentation(page, process.env.MAP_WEB_URL); }
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
