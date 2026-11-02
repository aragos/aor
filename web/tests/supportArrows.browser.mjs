import assert from 'node:assert/strict';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';

const regions = {
    britain: ['cornwall', 'portsmouth', 'wales', 'chester', 'waterford', 'london', 'york'],
    scandinavia: ['malmo', 'kongsberg', 'stockholm', 'copenhagen', 'lubeck', 'stettin'],
    central: ['st-gall', 'salzburg', 'nuremberg', 'prague', 'vienna', 'venice', 'milan', 'esseg'],
    spain: ['leon', 'toulouse', 'basque', 'montpellier', 'barcelona', 'palma', 'valencia'],
    levant: ['jerusalem', 'acre', 'levant', 'aleppo', 'suez', 'cyprus'],
    france: ['st-malo', 'paris', 'lyons', 'bordeaux', 'genoa', 'cagliari', 'marseilles'],
    east: ['smolensk', 'novgorod', 'kiev', 'kaffa', 'tana', 'abagia', 'poti', 'kamyshin', 'sarai'],
    balkans: ['gallipoli', 'varna', 'constantinople', 'salonika', 'athens', 'corfu', 'durazzo', 'belgrade'],
    africa: ['oran', 'fez', 'algiers', 'barca', 'tripoli', 'libya', 'alexandria'],
};

async function frameRegion(page, ids, horizontalPadding = 180, verticalPadding = 160) {
    await page.evaluate(`(() => {
        const svg = document.querySelector('.world-map');
        const points = ${JSON.stringify(ids)}.flatMap(id => {
            const label = document.querySelector('[data-province-label="' + id + '"]');
            if (!label) return [];
            const point = new DOMPoint(0, 0).matrixTransform(label.transform.baseVal.consolidate().matrix);
            return [[point.x, point.y]];
        });
        const minX = Math.min(...points.map(p => p[0])) - ${horizontalPadding};
        const maxX = Math.max(...points.map(p => p[0])) + ${horizontalPadding};
        const minY = Math.min(...points.map(p => p[1])) - ${verticalPadding};
        const maxY = Math.max(...points.map(p => p[1])) + ${verticalPadding};
        const rect = svg.getBoundingClientRect(), ratio = rect.width / rect.height;
        let width = maxX-minX, height = maxY-minY;
        if (width / height < ratio) width = height * ratio; else height = width / ratio;
        svg.setAttribute('viewBox', [((minX+maxX)-width)/2, ((minY+maxY)-height)/2, width, height].join(' '));
    })()`);
}

function occupiedMap(snapshot) {
    const capitals = snapshot.game.catalog.provinces.flatMap(province => province.capital ? [province.capital] : []);
    const board = snapshot.game.catalog.provinces.map((province, index) => {
        const owner = snapshot.game.players[index % snapshot.game.players.length].id;
        return {id: province.id, marker: province.satellite ? null : {owner, side: 'COLOURED'},
            tokens: province.satellite ? [{owner, coloured: 1, white: 0}] : []};
    });
    return {...snapshot, revision: snapshot.revision + 1000, game: {...snapshot.game, board,
        players: snapshot.game.players.map((player, index) => ({...player, capital: capitals[index],
            boardMarkers: board.filter(province => province.marker?.owner === player.id).length,
            stockMarkers: 25 - board.filter(province => province.marker?.owner === player.id).length}))}};
}

/** Review actual catalog arrows on reduced and complete boards without advancing the game. */
export async function checkSupportArrows(page, web) {
    const baseline = process.env.AOR_SUPPORT_BASELINE === '1';
    const stage = baseline ? 'before' : 'after';
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1600, height: 1100, deviceScaleFactor: 1, mobile: false});
    for (const map of ['STANDARD', 'EUROPEAN']) {
        for (const count of [3, 4, 5, 6]) {
            const game = await createGame(count, {options: {map: map.toLowerCase()}});
            const snapshot = await game.snapshot(game.links[0]);
            assert.equal(snapshot.game.rules.map, map);
            await page.send('Page.navigate', {url: web + '/tests/map-harness.html'});
            await page.wait(`typeof window.setMapSnapshot === 'function'`, 'support-arrow harness');
            await page.evaluate(`window.setMapSnapshot(${JSON.stringify(snapshot)})`);
            const expected = snapshot.game.catalog.satelliteSupports.map(edge => `${edge.satellite} ${edge.market}`).sort();
            await page.wait(`document.querySelectorAll('[data-support]').length === ${expected.length}`, 'catalog support arrows render');
            const rendered = await page.evaluate(`(() => {
                const svg = document.querySelector('.world-map');
                const frame = svg.querySelector('clipPath[id$="-frame"] path');
                const inside = (id, point) => [...document.querySelectorAll('[data-map-province="' + id + '"] > .province-shape, [data-coastal-space="' + id + '"] > path:first-of-type')].some(path => path.isPointInFill(new DOMPoint(...point)));
                return [...svg.querySelectorAll('[data-support]')].map(group => {
                    const path = group.querySelector('[marker-end]');
                    const values = path.getAttribute('d').match(/-?\\d+(?:\\.\\d+)?/g).map(Number);
                    const start = values.slice(0, 2), end = values.slice(-2);
                    const [source, target] = group.dataset.support.split(' ');
                    return {edge: group.dataset.support, path: path.getAttribute('d'),
                        sourceOwned: inside(source, start), targetOwned: inside(target, end),
                        length: path.getTotalLength(), values: values.length,
                        frameOwned: Array.from({length: 101}, (_, index) => path.getPointAtLength(path.getTotalLength() * index / 100)).every(point => frame.isPointInFill(point)),
                        visible: getComputedStyle(group).display !== 'none' && getComputedStyle(group).visibility !== 'hidden'};
                });
            })()`);
            assert.deepEqual(rendered.map(arrow => arrow.edge).sort(), expected, `${map} ${count}-player arrows match the live catalog`);
            assert(rendered.every(arrow => arrow.sourceOwned && arrow.targetOwned), 'Every arrow retains its satellite-to-market direction');
            assert(rendered.every(arrow => arrow.visible && arrow.length >= 24), 'Catalogued arrows remain visible');
            if (!baseline) {
                assert.deepEqual(rendered.filter(arrow => arrow.values !== 4).map(arrow => arrow.edge), [], 'Every support arrow uses one straight shaft');
                assert.deepEqual(rendered.filter(arrow => arrow.length > 48.01).map(arrow => arrow.edge), [], 'Support stays a short local crossing, without long straight detours');
                assert.deepEqual(rendered.filter(arrow => !arrow.frameOwned).map(arrow => arrow.edge), [], 'No shaft disappears beyond the board frame');
            }
            console.log(`Support arrows: ${map}, ${count} players, ${rendered.length} routes, ${rendered.filter(arrow => arrow.values > 4).length} bent, max ${Math.round(Math.max(...rendered.map(arrow => arrow.length)))} units`);
            if (count !== 6 || !process.env.AOR_BROWSER_ARTIFACTS) continue;
            await page.evaluate(`(() => {const svg = document.querySelector('.world-map'); svg.setAttribute('viewBox', svg.dataset.mapBounds);})()`);
            await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-support-${stage}-${map.toLowerCase()}-overview.png`));
            for (const [occupancy, fixture] of [['clear', snapshot], ['occupied', occupiedMap(snapshot)]]) {
                if (occupancy === 'occupied') {
                    await page.evaluate(`window.setMapSnapshot(${JSON.stringify(fixture)})`);
                    await page.wait(`document.querySelector('[data-test-revision="${fixture.revision}"]')`, 'Occupied support-arrow fixture');
                    assert(await page.evaluate(`document.querySelectorAll('[data-piece-kind="marker"]').length > 0`), 'Occupied captures include actual rendered markers');
                }
                const suffix = occupancy === 'clear' ? '' : '-occupied';
                const reviewRegions = occupancy === 'clear' ? Object.entries(regions)
                    : ['britain', 'central', 'france', 'levant'].map(name => [name, regions[name]]);
                for (const [name, ids] of reviewRegions) {
                    await frameRegion(page, ids);
                    await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-support-${stage}-${map.toLowerCase()}-${name}${suffix}.png`));
                }
                await page.send('Emulation.setDeviceMetricsOverride', {width: 390, height: 844, deviceScaleFactor: 1, mobile: true});
                await page.wait(`document.querySelector('#tab-players')`, 'Phone arrow review');
                for (const [name, ids] of [['britain', regions.britain], ['levant', regions.levant]]) {
                    await frameRegion(page, ids, 90, 100);
                    await page.viewportScreenshot(join(process.env.AOR_BROWSER_ARTIFACTS, `aor-support-${stage}-${map.toLowerCase()}-${name}${suffix}-phone.png`));
                }
                await page.send('Emulation.setDeviceMetricsOverride', {width: 1600, height: 1100, deviceScaleFactor: 1, mobile: false});
            }
            assert.equal(await page.evaluate('window.mapCommands.length'), 0, 'Arrow review never submits a game action');
        }
    }
    console.log('Passed: support catalog/direction/visibility across both maps and 3–6 players, with overview and regional visual evidence.');
}

if (process.argv[1]?.endsWith('/supportArrows.browser.mjs')) {
    const page = await open('about:blank');
    try { await checkSupportArrows(page, process.env.MAP_WEB_URL); }
    finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
