import assert from 'node:assert/strict';
import {test} from 'node:test';
import {browserOptions} from '../scripts/browser-options.mjs';

test('default browser suite preserves the full interaction gate', () => {
    assert.deepEqual(browserOptions([], {}, '/tests'), {
        suites: [{name: 'map.browser.mjs', path: '/tests/map.browser.mjs'}],
        timeout: 300000,
    });
});

test('legacy suite and timeout environment settings remain supported', () => {
    assert.deepEqual(browserOptions([], {
        AOR_BROWSER_SUITE: 'history.browser.mjs', AOR_BROWSER_TIMEOUT_MS: '600000',
    }, '/tests'), {
        suites: [{name: 'history.browser.mjs', path: '/tests/history.browser.mjs'}],
        timeout: 600000,
    });
});

test('positional suites run in order and override the environment selection', () => {
    const options = browserOptions(['history.browser.mjs', 'european-map.browser.mjs'], {
        AOR_BROWSER_SUITE: 'map.browser.mjs',
    }, '/tests');
    assert.deepEqual(options.suites.map(suite => suite.name), [
        'history.browser.mjs', 'european-map.browser.mjs',
    ]);
});

test('invalid timeout values fail instead of creating immediate or overflowing timers', () => {
    for (const timeout of ['', 'oops', '0', '-1', '0.5', 'Infinity', '2147483648']) {
        assert.throws(() => browserOptions([], {AOR_BROWSER_TIMEOUT_MS: timeout}, '/tests'),
            /AOR_BROWSER_TIMEOUT_MS/);
    }
});

test('suite paths must identify a browser test in the tests directory', () => {
    for (const suite of ['../map.browser.mjs', '/tmp/map.browser.mjs', 'map.test.mjs', '']) {
        assert.throws(() => browserOptions([suite], {}, '/tests'), /suite filename/);
    }
});
