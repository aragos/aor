import assert from 'node:assert/strict';
import {test} from 'node:test';
import {readFile} from 'node:fs/promises';
import ts from 'typescript';

const source = await readFile(new URL('../src/map/mapView.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, {compilerOptions: {module: ts.ModuleKind.ESNext}}).outputText;
const {fitMapView, clampMapView, panMapView, resizeMapView, zoomMapView} = await import(
    'data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'));
const bounds = {x: 20, y: 30, w: 2400, h: 1800};

function assertInside(view, viewport) {
    assert(view.x >= bounds.x && view.x + view.w <= bounds.x + bounds.w);
    assert(view.y >= bounds.y && view.y + view.h <= bounds.y + bounds.h);
    assert(Math.abs(view.w / view.h - viewport.width / viewport.height) < 1e-9);
}

test('initial wide-map framing fills the viewport width without empty margins', () => {
    const viewport = {width: 1200, height: 400};
    const view = fitMapView(bounds, viewport);
    assert.deepEqual(view, {x: 20, y: 530, w: 2400, h: 800});
    assertInside(view, viewport);
});

test('portrait framing covers the viewport without showing beyond the board', () => {
    const viewport = {width: 400, height: 800};
    const view = fitMapView(bounds, viewport);
    assert.deepEqual(view, {x: 770, y: 30, w: 900, h: 1800});
    assertInside(view, viewport);
});

test('dragging to any outer corner stops at both board edges', () => {
    const viewport = {width: 1000, height: 600};
    for (const x of [-10000, 10000]) {
        for (const y of [-10000, 10000]) {
            const view = clampMapView({x, y, w: 1200, h: 720}, bounds, viewport);
            assertInside(view, viewport);
            assert.equal(view.x, x < 0 ? bounds.x : bounds.x + bounds.w - view.w);
            assert.equal(view.y, y < 0 ? bounds.y : bounds.y + bounds.h - view.h);
        }
    }
});

test('zooming out stops before either viewport dimension can reveal empty space', () => {
    const viewport = {width: 450, height: 800};
    const view = zoomMapView({x: 900, y: 700, w: 500, h: 500 * 800 / 450}, bounds, viewport, 100);
    assert.equal(view.h, bounds.h);
    assertInside(view, viewport);
});

test('panning converts both CSS-pixel axes at the current zoom without changing scale', () => {
    const viewport = {width: 1000, height: 500};
    const view = {x: 400, y: 500, w: 1200, h: 600};
    const panned = panMapView(view, bounds, viewport, {x: 100, y: -50});
    assert.deepEqual(panned, {x: 520, y: 440, w: 1200, h: 600});
    assertInside(panned, viewport);
});

test('extreme scrolling stops at each board corner without exposing empty space', () => {
    const viewport = {width: 1000, height: 500};
    const view = {x: 400, y: 500, w: 1200, h: 600};
    for (const x of [-10000, 10000]) {
        for (const y of [-10000, 10000]) {
            const panned = panMapView(view, bounds, viewport, {x, y});
            assertInside(panned, viewport);
            assert.equal(panned.x, x < 0 ? bounds.x : bounds.x + bounds.w - panned.w);
            assert.equal(panned.y, y < 0 ? bounds.y : bounds.y + bounds.h - panned.h);
        }
    }
});

test('resizing a zoomed map retains its center when the new frame fits', () => {
    const view = {x: 500, y: 500, w: 1000, h: 600};
    const viewport = {width: 1000, height: 400};
    const resized = resizeMapView(view, bounds, viewport);
    assert.equal(resized.x + resized.w / 2, view.x + view.w / 2);
    assert.equal(resized.y + resized.h / 2, view.y + view.h / 2);
    assertInside(resized, viewport);
});

test('resizing to a narrow viewport clamps the visible height instead of exposing board margins', () => {
    const viewport = {width: 400, height: 800};
    const resized = resizeMapView({x: 20, y: 530, w: 2400, h: 800}, bounds, viewport);
    assertInside(resized, viewport);
});

test('anchored zoom preserves the pointed map coordinate away from constrained edges', () => {
    const viewport = {width: 1000, height: 500};
    const view = {x: 400, y: 500, w: 1200, h: 600};
    const anchor = {x: .2, y: .7};
    const zoomed = zoomMapView(view, bounds, viewport, .7, anchor);
    assert.equal(zoomed.x + zoomed.w * anchor.x, view.x + view.w * anchor.x);
    assert.equal(zoomed.y + zoomed.h * anchor.y, view.y + view.h * anchor.y);
    assertInside(zoomed, viewport);
});
