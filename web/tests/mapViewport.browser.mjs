import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';

const EPSILON = .01;

async function frame(page, description, fitted = false) {
    await page.wait(`(() => {
        const map = document.querySelector('.world-map');
        if (!map) return false;
        const [x, y, w, h] = map.getAttribute('viewBox').split(' ').map(Number);
        const rect = map.getBoundingClientRect();
        return rect.width > 0 && rect.height > 0 && Math.abs(w / h - rect.width / rect.height) < .00001;
    })()`, description + ' viewport aspect');
    const geometry = await page.evaluate(`(() => {
        const map = document.querySelector('.world-map');
        const [x, y, w, h] = map.getAttribute('viewBox').split(' ').map(Number);
        const [bx, by, bw, bh] = map.dataset.mapBounds.split(' ').map(Number);
        const rect = map.getBoundingClientRect();
        const transform = map.getScreenCTM();
        const first = new DOMPoint(bx, by).matrixTransform(transform);
        const last = new DOMPoint(bx + bw, by + bh).matrixTransform(transform);
        return {view: {x, y, w, h}, bounds: {x: bx, y: by, w: bw, h: bh},
            viewport: {left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom,
                width: rect.width, height: rect.height},
            renderedBoard: {left: first.x, top: first.y, right: last.x, bottom: last.y}};
    })()`);
    const {view, bounds, viewport, renderedBoard} = geometry;
    assert(view.x >= bounds.x - EPSILON && view.y >= bounds.y - EPSILON &&
        view.x + view.w <= bounds.x + bounds.w + EPSILON &&
        view.y + view.h <= bounds.y + bounds.h + EPSILON,
    `${description}: visible camera stays within board bounds: ${JSON.stringify(geometry)}`);
    assert(renderedBoard.left <= viewport.left + EPSILON && renderedBoard.top <= viewport.top + EPSILON &&
        renderedBoard.right >= viewport.right - EPSILON && renderedBoard.bottom >= viewport.bottom - EPSILON,
    `${description}: board fills the SVG without empty margins: ${JSON.stringify(geometry)}`);
    if (fitted) {
        const expectedWidth = Math.min(bounds.w, bounds.h * viewport.width / viewport.height);
        assert(Math.abs(view.w - expectedWidth) < EPSILON,
            `${description}: Fit uses the full width, bounded by the board height: ${JSON.stringify(geometry)}`);
    }
    return geometry;
}

async function dragToEdge(page, horizontal, vertical) {
    await page.send('Page.bringToFront');
    const point = await page.evaluate(`(() => {
        const map = document.querySelector('.world-map');
        window.mapPointerTrace?.abort();
        const trace = window.mapPointerTrace = new AbortController();
        window.mapPointerEvents = [];
        for (const type of ['pointerdown', 'pointermove', 'pointerup', 'pointercancel', 'gotpointercapture', 'lostpointercapture']) {
            map.addEventListener(type, event => window.mapPointerEvents.push({type, x: event.clientX,
                y: event.clientY, captured: map.hasPointerCapture(event.pointerId)}), {signal: trace.signal});
        }
        const rect = map.getBoundingClientRect();
        return {x: rect.left + rect.width * .2, y: rect.top + rect.height * .75};
    })()`);
    await page.send('Input.dispatchMouseEvent', {type: 'mousePressed', ...point, button: 'left', buttons: 1, clickCount: 1});
    // Start inside the SVG so the gesture crosses its threshold and captures the pointer.
    await page.send('Input.dispatchMouseEvent', {type: 'mouseMoved', x: point.x + 12, y: point.y,
        button: 'left', buttons: 1});
    // Chrome coalesces moves until its next frame; flush before moving again or releasing.
    await page.evaluate('new Promise(resolve => requestAnimationFrame(resolve))');
    await page.send('Input.dispatchMouseEvent', {type: 'mouseMoved', x: point.x + horizontal * 5000,
        y: point.y + vertical * 5000, button: 'left', buttons: 1});
    await page.evaluate('new Promise(resolve => requestAnimationFrame(resolve))');
    await page.send('Input.dispatchMouseEvent', {type: 'mouseReleased', x: point.x + horizontal * 5000,
        y: point.y + vertical * 5000, button: 'left', buttons: 0, clickCount: 1});
}

export async function checkMapViewport(page, web, baseline) {
    const european = await createGame(6, {options: {map: 'european'}});
    const variants = [baseline, await european.snapshot(european.links[0])];
    let revision = 34000;
    for (const variant of variants) {
        for (const [width, height, mobile] of [[1280, 900, false], [390, 844, true]]) {
            const label = `${variant.game.rules.map} ${width}×${height}`;
            await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
            await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
            await page.send('Page.bringToFront');
            await page.wait(`typeof window.setMapSnapshot === 'function'`, label + ' map harness');
            const fixture = {...variant, revision: ++revision,
                prompt: {messageId: 'prompt.expand', players: [variant.viewerId]},
                legalChoices: {commands: ['pass'], bidMin: null, bidMax: null},
                game: {...variant.game, stage: 'EXPANSION', turnNumber: variant.game.turnNumber ?? 1,
                    expansionOffers: [], cardOffers: [],
                    activity: {...variant.game.activity, actor: variant.viewerId,
                        decisionPlayer: variant.viewerId, choice: 'expand'}}};
            await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
            await frame(page, label + ' initial width fit', true);
            if (process.env.AOR_BROWSER_ARTIFACTS) {
                await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS,
                    `aor-map-width-fit-${variant.game.rules.map.toLowerCase()}-${width}.png`));
            }
            await page.evaluate(`document.querySelector('.world-map').dispatchEvent(new KeyboardEvent('keydown', {key: '+', bubbles: true}))`);
            for (const horizontal of [-1, 1]) {
                for (const vertical of [-1, 1]) {
                    await dragToEdge(page, horizontal, vertical);
                    const {view, bounds} = await frame(page, label + ' extreme drag');
                    const expectedX = horizontal > 0 ? bounds.x : bounds.x + bounds.w - view.w;
                    const expectedY = vertical > 0 ? bounds.y : bounds.y + bounds.h - view.h;
                    const reached = Math.abs(view.x - expectedX) < EPSILON && Math.abs(view.y - expectedY) < EPSILON;
                    if (!reached && process.env.AOR_BROWSER_ARTIFACTS) {
                        await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS,
                            `map-edge-failure-${variant.game.rules.map}-${width}-${horizontal}-${vertical}.png`));
                    }
                    assert(reached, label + ': captured drag reaches the requested board edges: ' +
                        JSON.stringify({horizontal, vertical, expectedX, expectedY, view, bounds,
                            events: await page.evaluate('window.mapPointerEvents')}));
                }
            }
            await page.evaluate(`document.querySelector('.world-map').dispatchEvent(new KeyboardEvent('keydown', {key: '-', bubbles: true}))`);
            await page.evaluate(`document.querySelector('.world-map').dispatchEvent(new KeyboardEvent('keydown', {key: '0', bubbles: true}))`);
            await frame(page, label + ' restored fit', true);

            await page.evaluate(`document.querySelector('.world-map').dispatchEvent(new KeyboardEvent('keydown', {key: '+', bubbles: true}))`);
            const beforePoll = await page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox')`);
            await page.evaluate(`window.setMapSnapshot(${JSON.stringify({...fixture, revision: ++revision})})`);
            await page.wait(`document.querySelector('[data-test-revision="${revision}"]')`, label + ' polling');
            assert.equal(await page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox')`), beforePoll,
                label + ': polling preserves zoom and origin');

            await page.evaluate(`document.querySelector('#tab-markets').click()`);
            await page.evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
            await page.evaluate(`document.querySelector('#tab-map').click()`);
            await frame(page, label + ' return from hidden workspace');
            assert.equal(await page.evaluate(`document.querySelector('.world-map').getAttribute('viewBox')`), beforePoll,
                label + ': a hidden workspace does not reset its view');

            if (!mobile) {
                await page.evaluate(`Object.assign(document.querySelector('.map-viewport').style,
                    {width: '220px', height: '400px', flex: '0 0 400px'})`);
                await frame(page, label + ' portrait resize');
                await page.evaluate(`document.querySelector('.world-map').dispatchEvent(new KeyboardEvent('keydown', {key: '0', bubbles: true}))`);
                await frame(page, label + ' portrait cover fit', true);
            }
            assert.equal(await page.evaluate('window.mapCommands.length'), 0, label + ': map framing never submits');
        }
    }
    console.log('Passed: width-fit map framing, all captured-drag edges, bounded portrait resize, both boards, polling and hidden-workspace continuity.');
}

if (process.argv[1]?.endsWith('/mapViewport.browser.mjs')) {
    const game = await createGame(6);
    const baseline = await game.snapshot(game.links[0]);
    const page = await open('about:blank');
    try { await checkMapViewport(page, process.env.MAP_WEB_URL, baseline); }
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
