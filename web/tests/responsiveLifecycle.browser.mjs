import assert from 'node:assert/strict';
import {createGame, open} from './browser-support.mjs';

/** A resize between rendering and subscribing must use the latest media state. */
export async function checkResponsiveLifecycle(page, web) {
    const game = await createGame(3);
    const snapshot = await game.snapshot(game.links[0]);
    await page.send('Page.bringToFront');
    for (const width of [390, 1440]) {
        await page.send('Emulation.setDeviceMetricsOverride', {
            width, height: 1000, deviceScaleFactor: 1, mobile: width < 700,
        });
        const previousDocument = await page.evaluate('performance.timeOrigin');
        await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
        await page.wait(`performance.timeOrigin !== ${previousDocument} && typeof window.setMapSnapshot === 'function'`, 'new responsive lifecycle harness');
        const mobile = width < 700;
        await page.evaluate(`(() => {
            window.nativeMatchMedia = window.matchMedia.bind(window);
            window.mobileMediaReads = 0;
            window.matchMedia = query => {
                const media = window.nativeMatchMedia(query);
                // StrictMode renders twice before subscribing. A resize invalidates both reads.
                if (query === '(max-width: 700px)' && window.mobileMediaReads++ < 2) {
                    return {matches: ${!mobile}};
                }
                return media;
            };
            window.setMapSnapshot(${JSON.stringify(snapshot)});
        })()`);
        try {
            await page.wait(`window.mobileMediaReads >= 4`, 'StrictMode media subscriptions');
            await page.evaluate(`new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))`);
            assert.equal(await page.evaluate(`!!document.querySelector('#tab-players')`), mobile,
                'The Players tab reflects a resize before subscription without another media event');
            assert.equal(await page.evaluate(`document.querySelector('.workspace-tabs').getAttribute('aria-orientation')`),
                mobile ? 'vertical' : 'horizontal');
        } finally {await page.evaluate(`window.matchMedia = window.nativeMatchMedia`);}
    }
    console.log('Passed: StrictMode media subscriptions recover render-time resize races in both directions.');
}

if (process.argv[1]?.endsWith('/responsiveLifecycle.browser.mjs')) {
    const page = await open('about:blank');
    try {await checkResponsiveLifecycle(page, process.env.MAP_WEB_URL);}
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
