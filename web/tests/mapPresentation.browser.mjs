import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';

export async function checkMapPresentation(page, web) {
    for (const map of ['standard', 'european']) {
        const game = await createGame(6, {options: {map}});
        const snapshot = await game.snapshot(game.links[0]);
        assert.equal(snapshot.game.catalog.dataVersion, 'ah1996-catalog-16', 'New games use the corrected Portsmouth–York land border on the standard map');
        const stone = snapshot.game.catalog.provinces.find(province => province.commodities.includes('STONE'));
        const timber = snapshot.game.catalog.provinces.find(province => province.commodities.includes('TIMBER'));
        for (const [width, height, mobile] of [[1440, 980, false], [390, 844, true]]) {
            await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
            const previousDocument = await page.evaluate('performance.timeOrigin');
            await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
            await page.send('Page.bringToFront');
            await page.wait(`performance.timeOrigin !== ${previousDocument} && typeof window.setMapSnapshot === 'function'`, 'new map presentation harness');
            await page.evaluate(`window.setMapSnapshot(${JSON.stringify(snapshot)})`);
            await page.wait(`document.querySelector('[data-map-geometry="ah1996-catalog-16:${map.toUpperCase()}"] [data-support="cagliari genoa"]')`, 'Cagliari support paths');
            assert.equal(await page.evaluate(`document.querySelector('.table-configuration').textContent
                .includes('${map === 'standard' ? 'Original map' : 'European map'}')`), true,
                'Game options name the selected board');
            for (const [province, x, y] of [
                ['chester', 720, 603], ['cornwall', 718, 619], ['portsmouth', 735, 610],
            ]) {
                assert.equal(await page.evaluate(`document.querySelector('[data-coastal-space="${province}"] > path')
                    .isPointInFill(new DOMPoint(${x}, ${y}))`), true,
                    `${province} owns visible coastal water inside the enlarged Bristol Channel`);
                await page.evaluate(`document.querySelector('[data-coastal-space="${province}"] > path')
                    .dispatchEvent(new MouseEvent('click', {bubbles: true}))`);
                await page.wait(`document.querySelector('[data-map-province="${province}"][aria-pressed="true"]')`,
                    `Inspect ${province} through its Bristol Channel water`);
            }
            await page.evaluate(`document.querySelector('.world-map')
                .dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}))`);
            assert.equal(await page.evaluate(`document.querySelector('[data-locked-area="V"]') !== null`), true,
                'Area V has a whole-area access shade');
            assert(snapshot.game.lockedProvinces.includes('iceland') && snapshot.game.lockedProvinces.includes('west-africa'),
                'The live projection retains the insets\' navigation requirements');
            assert.equal(await page.evaluate(`document.querySelector(
                '[data-locked-province="iceland"], [data-locked-province="west-africa"]') !== null`), false,
                'Navigation requirements do not crosshatch Iceland or West Africa');
            assert.deepEqual(await page.evaluate(`[...document.querySelectorAll('[data-locked-overseas]')]
                .map(box => ({group: box.dataset.lockedOverseas,
                    x: box.x.baseVal.value, y: box.y.baseVal.value,
                    width: box.width.baseVal.value, height: box.height.baseVal.value}))
                .sort((a, b) => a.y - b.y)`), [
                    {group: 'NEW_WORLD', x: 0, y: 630, width: 190, height: 420},
                    {group: 'FAR_EAST', x: 0, y: 1050, width: 190, height: 600},
                ], 'Locked overseas hatching covers each complete box, including its header and gaps');
            const overseasIds = snapshot.game.catalog.provinces.filter(province => province.overseas)
                .map(province => province.id);
            assert.deepEqual(await page.evaluate(`[...document.querySelectorAll('[data-locked-province]')]
                .map(path => path.dataset.lockedProvince).filter(id => ${JSON.stringify(overseasIds)}.includes(id))`),
                [], 'Whole-box hatching replaces individual overseas province hatching');
            assert.equal(await page.evaluate(`getComputedStyle(document.querySelector('.map-access-shading')).pointerEvents`),
                'none', 'Access shading preserves province and commodity hit targets');
            const texture = () => page.evaluate(`(() => {
                const map = document.querySelector('.world-map');
                const pattern = map.querySelector('pattern[id$="-satellite"]');
                const scale = pattern.patternTransform.baseVal.consolidate().matrix.a * map.getScreenCTM().a;
                return {spacing: pattern.width.baseVal.value * scale,
                    radius: pattern.querySelector('circle').r.baseVal.value * scale,
                    satellite: pattern.querySelector('rect').getAttribute('fill'),
                    timber: map.querySelector('[data-map-province="${timber.id}"] .province-shape').getAttribute('fill'),
                    stone: map.querySelector('[data-map-province="${stone.id}"] .province-shape').getAttribute('fill')};
            })()`);
            await page.evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
            const fit = await texture();
            assert.notEqual(fit.satellite, fit.stone, 'Satellites and Stone have different base colors');
            const luminance = color => {
                const channels = color.slice(1).match(/../g).map(hex => parseInt(hex, 16) / 255)
                    .map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4);
                return channels[0] * .2126 + channels[1] * .7152 + channels[2] * .0722;
            };
            assert(luminance(fit.satellite) - luminance(fit.timber) > .15,
                'Timber has a visibly darker base than satellites');
            assert(Math.abs(fit.spacing - 7) < .01 && fit.radius >= .7 && fit.radius <= 1,
                'Satellite stippling remains small and consistent at Fit');
            await page.evaluate(`document.querySelector('.world-map').dispatchEvent(new KeyboardEvent('keydown', {key: '+', bubbles: true}))`);
            assert(Math.abs((await texture()).spacing - fit.spacing) < .01, 'Satellite texture keeps its screen spacing when zoomed');
            for (const target of ['genoa', 'marseilles']) {
                const length = await page.evaluate(`document.querySelector('[data-support="cagliari ${target}"] [marker-end]').getTotalLength()`);
                assert(length >= 39.99 && length <= 48.01, 'Cagliari support crossings remain visible');
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
                    ['britain', 700, 560, 650], ['shetland', 1010, 40, 600],
                    ['overseas', 95, 1140, 480], ['iceland', 200, 100, 500], ['west-africa', 145, 1940, 500],
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
            await page.evaluate(`window.setMapSnapshot(${JSON.stringify({...snapshot, revision: snapshot.revision + 999,
                game: {...snapshot.game, lockedProvinces: snapshot.game.lockedProvinces.filter(id => id !== inspected)}})})`);
            await page.wait(`document.querySelector('[data-test-revision="${snapshot.revision + 999}"]')`, 'Partial overseas access');
            assert.equal(await page.evaluate(`document.querySelector('[data-locked-overseas="FAR_EAST"], [data-locked-province="${inspected}"]') !== null`), false,
                'A partly unlocked overseas box leaves its accessible province unhatched');
            const stillLocked = snapshot.game.catalog.provinces.filter(province => province.overseas === 'FAR_EAST' &&
                province.id !== inspected && snapshot.game.lockedProvinces.includes(province.id)).map(province => province.id).sort();
            assert.deepEqual(await page.evaluate(`[...document.querySelectorAll('[data-locked-province]')]
                .map(path => path.dataset.lockedProvince).filter(id => ${JSON.stringify(stillLocked)}.includes(id)).sort()`),
                stillLocked, 'Partial overseas access retains individual shading for the remaining locked provinces');
            await page.evaluate(`window.setMapSnapshot(${JSON.stringify({...snapshot, revision: snapshot.revision + 1000,
                game: {...snapshot.game, lockedProvinces: []}})})`);
            await page.wait(`document.querySelector('[data-test-revision="${snapshot.revision + 1000}"]')`, 'Access advances acquired');
            assert.equal(await page.evaluate(`document.querySelectorAll('[data-locked-area], [data-locked-province], [data-locked-overseas], [data-area-lock], [data-overseas-lock]').length`),
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
            const assertLigurianJunction = async () => {
                assert.equal(await page.evaluate(`document.querySelector('[data-coastal-space="genoa"] > path')
                    .isPointInFill(new DOMPoint(1288.214, 1141.680))`), false,
                    'The former hooked Genoa sliver is removed, including outside the junction sectors');
                for (const province of ['marseilles', 'genoa', 'cagliari']) {
                    assert.match(await page.evaluate(`document.querySelector('[data-coastal-space="${province}"] > path').getAttribute('d')`),
                        /1254\.000 1152\.000/, `${province} uses the polished junction for this saved catalog`);
                }
            };
            await assertLigurianJunction();
            const yorkPath = await page.evaluate(`document.querySelector('[data-map-province="york"] .province-shape').getAttribute('d')`);
            const cornwallPath = await page.evaluate(`document.querySelector('[data-map-province="cornwall"] .province-shape').getAttribute('d')`);
            if (map === 'standard') {
                const legacyLandBorders = snapshot.game.catalog.landBorders.filter(edge =>
                    !([edge.first, edge.second].includes('portsmouth') && [edge.first, edge.second].includes('york')));
                const legacyCatalog = {...snapshot.game.catalog, dataVersion: 'ah1996-catalog-13',
                    landBorders: [...legacyLandBorders, {first: 'cornwall', second: 'wales'}],
                    coastalConnections: snapshot.game.catalog.coastalConnections.filter(edge =>
                        !(['cornwall', 'portsmouth'].some(province => [edge.first, edge.second].includes(province)) &&
                            [edge.first, edge.second].includes('chester')))};
                await page.evaluate(`window.setMapSnapshot(${JSON.stringify({...snapshot, revision: snapshot.revision + 1002,
                    game: {...snapshot.game, catalog: legacyCatalog}})})`);
                await page.wait(`document.querySelector('[data-map-geometry="ah1996-catalog-13:STANDARD"] .world-map')`, 'Saved catalog-13 drawing');
                await assertLigurianJunction();
                assert.notEqual(await page.evaluate(`document.querySelector('[data-map-province="cornwall"] .province-shape').getAttribute('d')`),
                    cornwallPath, 'Existing games retain the drawing matching their recorded topology');
                const catalog14 = {...snapshot.game.catalog, dataVersion: 'ah1996-catalog-14', landBorders: legacyLandBorders,
                    coastalConnections: snapshot.game.catalog.coastalConnections.filter(edge =>
                        !([edge.first, edge.second].includes('chester') && [edge.first, edge.second].includes('portsmouth')))};
                await page.evaluate(`window.setMapSnapshot(${JSON.stringify({...snapshot, revision: snapshot.revision + 1003,
                    game: {...snapshot.game, catalog: catalog14}})})`);
                await page.wait(`document.querySelector('[data-map-geometry="ah1996-catalog-14:STANDARD"] .world-map')`, 'Saved catalog-14 drawing');
                await assertLigurianJunction();
                if (process.env.AOR_BROWSER_ARTIFACTS && width === 1440) {
                    await page.evaluate(`(() => {
                        const map = document.querySelector('.world-map'), rect = map.getBoundingClientRect();
                        const w = 650, h = w * rect.height / rect.width;
                        map.setAttribute('viewBox', [1275 - w / 2, 1220 - h / 2, w, h].join(' '));
                    })()`);
                    await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-cagliari-catalog14-${map}.png`));
                }
                assert.notEqual(await page.evaluate(`document.querySelector('[data-map-province="cornwall"] .province-shape').getAttribute('d')`),
                    cornwallPath, 'Catalog-14 saves retain their narrower Bristol Channel');
                const catalog15 = {...snapshot.game.catalog, dataVersion: 'ah1996-catalog-15', landBorders: legacyLandBorders};
                await page.evaluate(`window.setMapSnapshot(${JSON.stringify({...snapshot, revision: snapshot.revision + 1005,
                    game: {...snapshot.game, catalog: catalog15}})})`);
                await page.wait(`document.querySelector('[data-map-geometry="ah1996-catalog-15:STANDARD"] .world-map')`, 'Saved catalog-15 drawing');
                await assertLigurianJunction();
                assert.notEqual(await page.evaluate(`document.querySelector('[data-map-province="york"] .province-shape').getAttribute('d')`),
                    yorkPath, 'Catalog-15 saves retain their recorded Portsmouth–York separation');
            } else {
                for (const version of [13, 14]) {
                    await page.evaluate(`window.setMapSnapshot(${JSON.stringify({...snapshot,
                        revision: snapshot.revision + 1000 + version,
                        game: {...snapshot.game, catalog: {...snapshot.game.catalog,
                            dataVersion: `ah1996-catalog-${version}`}}})})`);
                    await page.wait(`document.querySelector('[data-test-revision="${snapshot.revision + 1000 + version}"]')`,
                        `Unsupported catalog-${version} snapshot`);
                    await page.wait(`document.querySelector('.map-load-status [role=alert]')`,
                        `Unsupported catalog-${version} European drawing`);
                    assert.match(await page.evaluate(`document.querySelector('.map-load-status [role=alert]').textContent`),
                        /map is unavailable/, 'Unsupported combinations show an explicit diagnostic');
                    assert.equal(await page.evaluate(`document.querySelector('.world-map')`), null,
                        'An unsupported saved catalog never silently uses another drawing');
                    assert.equal(await page.evaluate(`document.querySelector('.map-load-status button')`), null,
                        'Unsupported drawings cannot be retried as temporary download failures');
                }
            }
            await page.evaluate(`window.setMapSnapshot(${JSON.stringify({...snapshot, revision: snapshot.revision + 1004})})`);
            await page.wait(`document.querySelector('[data-map-geometry="ah1996-catalog-16:${map.toUpperCase()}"] .world-map')`, 'Current catalog drawing');
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
