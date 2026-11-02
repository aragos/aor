import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {test} from 'node:test';

for (const map of ['BoardGeometry', 'EuropeanBoardGeometry']) {
    test(`supported saved-game ${map} drawings retain the polished Ligurian junction`, async () => {
        const current = JSON.parse(await readFile(new URL(`../src/map/${map[0].toLowerCase() + map.slice(1)}.json`, import.meta.url)));
        for (const version of [13, 14]) {
            const legacy = JSON.parse(await readFile(new URL(`../src/map/catalog${version}${map}.json`, import.meta.url)));
            for (const province of ['marseilles', 'genoa', 'cagliari']) {
                assert.match(legacy.coasts[province], /1254\.000 1152\.000/, `${province} meets the polished three-way junction`);
                assert.match(legacy.provinces[province].outline, /1254\.000 1152\.000/);
            }
            for (const route of ['cagliari marseilles', 'cagliari genoa']) {
                assert.equal(legacy.supports[route], current.supports[route], 'Support arrows cross the polished borders');
            }
            assert.notEqual(legacy.provinces.cornwall.path, current.provinces.cornwall.path,
                'Presentation changes retain the distinct British topology of existing games');
        }
    });
}
