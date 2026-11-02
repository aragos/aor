import assert from 'node:assert/strict';
import {createGame, open} from './browser-support.mjs';

async function hoverArea(page, area) {
    const point = await page.evaluate(`(() => {
        const map = document.querySelector('.world-map');
        const badge = document.querySelector('[data-map-area-label="${area}"] .area-label-bg');
        const x = badge.x.baseVal.value + badge.width.baseVal.value / 2;
        const y = badge.y.baseVal.value + badge.height.baseVal.value / 2;
        const rect = map.getBoundingClientRect(), height = 700 * rect.height / rect.width;
        map.setAttribute('viewBox', [x - 350, y - height / 2, 700, height].join(' '));
        const point = new DOMPoint(x, y).matrixTransform(map.getScreenCTM());
        return {x: point.x, y: point.y};
    })()`);
    await page.send('Input.dispatchMouseEvent', {type: 'mouseMoved', ...point});
    await page.evaluate('new Promise(resolve => requestAnimationFrame(resolve))');
    return point;
}

export async function checkInactiveAreas(page, web) {
    for (const map of ['standard', 'european']) {
        for (const count of [3, 4, 5, 6]) {
            const game = await createGame(count, {options: {map}});
            const snapshot = await game.snapshot(game.links[0]);
            const active = [...new Set(snapshot.game.catalog.provinces.map(province => province.area))]
                .filter(Boolean).sort();
            for (const width of [1280, 390]) {
                const description = `${map}, ${count} players, ${width}px`;
                await page.send('Emulation.setDeviceMetricsOverride', {
                    width, height: 900, deviceScaleFactor: 1, mobile: width === 390,
                });
                await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
                await page.send('Page.bringToFront');
                await page.wait(`typeof window.setMapSnapshot === 'function'`, description);
                await page.evaluate(`window.setMapSnapshot(${JSON.stringify(snapshot)})`);
                await page.wait(`document.querySelector('.world-map')`, description + ' map');
                assert.deepEqual(await page.evaluate(`[...document.querySelectorAll('[data-map-area-label][role="button"]')]
                    .map(element => element.dataset.mapAreaLabel)`), active, description + ': only active badges are controls');
                const inactive = await page.evaluate(`[...document.querySelectorAll('[data-map-area-label]')]
                    .filter(element => !element.hasAttribute('role')).map(element => element.dataset.mapAreaLabel)`);
                for (const area of inactive) {
                    const point = await hoverArea(page, area);
                    assert.equal(await page.evaluate(`document.querySelector('[data-area-outline="${area}"], [data-area-dimming]') !== null`),
                        false, description + ': inactive hover cannot highlight or dim the map');
                    assert.equal(await page.evaluate(`document.querySelector('[data-map-area-label="${area}"]').hasAttribute('tabindex')`),
                        false, description + ': inactive badge cannot receive keyboard focus');
                    await page.send('Input.dispatchMouseEvent', {type: 'mousePressed', ...point, button: 'left', clickCount: 1});
                    await page.send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...point, button: 'left', clickCount: 1});
                    assert.equal(await page.evaluate(`document.querySelector('[data-map-area-label][aria-pressed="true"]') !== null`),
                        false, description + ': inactive click cannot select an area');
                }
                await hoverArea(page, active[0]);
                await page.wait(`document.querySelector('[data-area-outline="${active[0]}"], [data-area-dimming="${active[0]}"]')`,
                    description + ': active hover still highlights');
                await page.evaluate(`document.querySelector('[data-map-area-label][tabindex="0"]').focus()`);
                for (const area of active) {
                    assert.equal(await page.evaluate('document.activeElement.dataset.mapAreaLabel'), area,
                        description + ': keyboard traversal visits active areas only');
                    await page.send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'ArrowRight'});
                    await page.send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'ArrowRight'});
                }
                assert.equal(await page.evaluate('document.activeElement.dataset.mapAreaLabel'), active[0],
                    description + ': keyboard traversal wraps within active areas');
                assert.deepEqual(await page.evaluate('window.mapCommands'), [], description + ': inspection never submits');
            }
        }
    }
    console.log('Passed: inactive area badges ignore hover/click/focus; active hover and keyboard traversal work on both maps, 3–6 players, desktop and phone.');
}

if (process.argv[1]?.endsWith('/inactiveAreas.browser.mjs')) {
    const page = await open('about:blank');
    try { await checkInactiveAreas(page, process.env.MAP_WEB_URL); }
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
