import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';

export async function checkMainView(page) {
    await page.wait(`document.querySelector('#bid')`, 'Initial capital bidding');
    for (const [width, height, mobile] of [[1280, 900, false], [390, 844, true]]) {
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile});
        await page.wait(`document.querySelector('.game-table').getBoundingClientRect().right <= innerWidth`, 'Table reflows');
        await page.wait(`document.querySelector('.workspace-tabs').getAttribute('aria-orientation') === '${mobile ? 'vertical' : 'horizontal'}'`, 'Workspace navigation reflows');
        assert.deepEqual(await page.evaluate(`[...document.querySelectorAll('.workspace-tabs button')].map(button => button.getAttribute('aria-label'))`),
            ['Map', 'Commodities', 'Advances', 'Activity', ...(mobile ? ['Players'] : [])]);
        assert.equal(await page.evaluate(`document.querySelector('.workspace-tabs').getAttribute('aria-orientation')`), mobile ? 'vertical' : 'horizontal');
        assert.equal(await page.evaluate(`(() => {
            const tabs=[...document.querySelectorAll('.workspace-tabs button')];
            return tabs.every((tab,index)=>{
                const icon=tab.querySelector('svg[aria-hidden="true"]');
                const rect=tab.getBoundingClientRect();
                return icon?.getBoundingClientRect().width>0 && rect.left>=0 && rect.right<=innerWidth &&
                    (index===0 || (${mobile}
                        ? tabs[index-1].getBoundingClientRect().bottom<=rect.top
                        : tabs[index-1].getBoundingClientRect().right<=rect.left));
            });
        })()`), true, 'Workspace icons fit without overlapping in their horizontal or vertical rail');
        await page.evaluate(`document.querySelector('#tab-markets').click()`);
        await page.wait(`document.querySelector('.market-tile')?.checkVisibility()`, 'Commodities populate during bidding');
        const initial = await page.snapshot();
        assert(initial.game.commodityPayouts.length > 0);
        assert.equal(await page.evaluate(`document.querySelectorAll('.market-tile').length`), initial.game.commodityPayouts.length);
        assert.equal(await page.evaluate(`document.querySelectorAll('.market-tile .owner-payout').length`), 0, 'Setup has no premature payout forecasts');
        assert.equal(await page.evaluate(`document.querySelector('.table-effects')`), null, 'Turn information has no bottom disclosure');
        if (mobile) await page.evaluate(`document.querySelector('#tab-players').click()`);
        for (const selector of ['.table-configuration', '.command-dock']) {
            await page.evaluate(`document.querySelector('${selector} > summary').scrollIntoView({block:'nearest',inline:'nearest',behavior:'instant'})`);
            assert.equal(await page.evaluate(`(() => {
                const summary=document.querySelector('${selector} > summary');
                const rect=summary.getBoundingClientRect();
                const hit=document.elementFromPoint(rect.left+rect.width/2,rect.top+rect.height/2);
                return rect.top>=0 && rect.bottom<=innerHeight && rect.height>=24 && summary.contains(hit);
            })()`), true, 'Game options and Commands remain readable and reachable');
        }
        await page.evaluate(`document.querySelector('.table-configuration > summary').click()`);
        await page.wait(`document.querySelector('.table-configuration').open`, 'Game options opens');
        assert.equal(await page.evaluate(`document.querySelectorAll('.table-configuration a').length`), 0,
            'In-game options contain settings without navigation links');
        assert.deepEqual(await page.evaluate(`[...document.querySelectorAll('.table-configuration button')].map(button => button.className)`),
            ['secondary abandon-game'], 'Abandonment is the only action tucked into Game options');
        assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
        if (process.env.AOR_BROWSER_ARTIFACTS) await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-main-view-${width}.png`));
        await page.evaluate(`document.querySelector('.table-configuration > summary').click(); document.querySelector('#tab-map').click()`);
    }
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1280, height: 900, deviceScaleFactor: 1, mobile: false});
    await page.evaluate(`document.querySelector('#tab-map').focus()`);
    await page.send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'ArrowRight', code: 'ArrowRight', windowsVirtualKeyCode: 39});
    await page.send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'ArrowRight', code: 'ArrowRight', windowsVirtualKeyCode: 39});
    await page.wait(`document.activeElement.id==='tab-markets' && document.activeElement.getAttribute('aria-selected')==='true'`, 'Named icon tabs retain keyboard navigation');
    await page.evaluate(`document.querySelector('#tab-map').click()`);
    console.log('Passed: accessible desktop tabs and phone icon rail with Players, setup commodities, link-free options and reachable game tools.');
}

if (process.argv[1]?.endsWith('/mainView.browser.mjs')) {
    const game = await createGame(6);
    const page = await open(process.env.MAP_WEB_URL + game.links[0].path);
    try {
        await checkMainView(page);
    } finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
