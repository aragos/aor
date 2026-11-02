import assert from 'node:assert/strict';
import {test} from 'node:test';
import {createGeometryLoader, geometryKey, UnsupportedMapGeometry} from '../src/map/mapGeometry.ts';

test('geometry requests share pending work and retain the loaded drawing without fetching other maps', async () => {
    const drawing = {frame: 'standard'};
    let requests = 0;
    let finish;
    const loader = createGeometryLoader({
        standard: () => {requests++; return new Promise(resolve => {finish = resolve;});},
        european: () => assert.fail('An unused map must not be fetched'),
    });
    assert.equal(requests, 0);
    const first = loader.load('standard');
    const second = loader.load('standard');
    assert.equal(first, second, 'StrictMode subscribers share one request');
    await Promise.resolve();
    assert.equal(requests, 1);
    finish(drawing);
    assert.equal(await first, drawing);
    assert.equal(loader.peek('standard'), drawing);
    assert.equal(await loader.load('standard'), drawing);
    assert.equal(requests, 1, 'Polling and remounting reuse the drawing');
});

test('a failed request can be explicitly retried', async () => {
    let requests = 0;
    const drawing = {frame: 'recovered'};
    const loader = createGeometryLoader({standard: async () => {
        if (++requests === 1) throw new Error('offline');
        return drawing;
    }});
    await assert.rejects(loader.load('standard'), /offline/);
    assert.equal(loader.peek('standard'), undefined);
    assert.equal(await loader.load('standard'), drawing);
    assert.equal(requests, 2);
});

test('recorded versions never fall back to a newer or different map', async () => {
    const loader = createGeometryLoader({'ah1996-catalog-15:STANDARD': async () => ({frame: 'current'})});
    assert.equal(geometryKey('ah1996-catalog-13', 'EUROPEAN'), 'ah1996-catalog-13:EUROPEAN');
    await assert.rejects(loader.load(geometryKey('ah1996-catalog-13', 'EUROPEAN')),
        UnsupportedMapGeometry);
    assert.equal(loader.peek('ah1996-catalog-15:STANDARD'), undefined);
});

test('out-of-order completions retain their own catalog and map identities', async () => {
    let finishStandard;
    let finishEuropean;
    const loader = createGeometryLoader({
        standard: () => new Promise(resolve => {finishStandard = resolve;}),
        european: () => new Promise(resolve => {finishEuropean = resolve;}),
    });
    const standard = loader.load('standard');
    const european = loader.load('european');
    await Promise.resolve();
    finishEuropean({frame: 'european'});
    await european;
    assert.equal(loader.peek('standard'), undefined);
    finishStandard({frame: 'standard'});
    await standard;
    assert.deepEqual(loader.peek('european'), {frame: 'european'});
    assert.deepEqual(loader.peek('standard'), {frame: 'standard'});
});
