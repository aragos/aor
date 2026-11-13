import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync, readdirSync, writeFileSync} from 'node:fs';
import {createServer} from 'node:http';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {open} from './browser-support.mjs';

// Only constructed JVM fixtures and compiled, model-free inference code are served here. This
// temporary test server supplies no application asset, trained actor, game store or activation.
const library = fileURLToPath(new URL('../../ai/build/dist/js/productionLibrary/', import.meta.url));
const fixturePath = fileURLToPath(new URL('../../ai/build/test-fixtures/expansion-browser-parity.json', import.meta.url));
const fixtureBytes = readFileSync(fixturePath);
const fixture = JSON.parse(fixtureBytes);
assert.equal(fixture.kind, 'expansion-browser-parity-1');
const modules = new Map(readdirSync(library).filter(name => name.endsWith('.mjs')).sort()
    .map(name => [name, readFileSync(join(library, name))]));
const server = createServer((request, response) => {
    const name = new URL(request.url, 'http://127.0.0.1').pathname.slice(1);
    const bytes = modules.get(name);
    if (!bytes) {response.writeHead(404); response.end(); return;}
    response.writeHead(200, {'Content-Type': 'text/javascript', 'Access-Control-Allow-Origin': process.env.MAP_WEB_URL});
    response.end(bytes);
});
let page;
try {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const moduleUrl = `http://127.0.0.1:${server.address().port}/aor-ai.mjs`;
    page = await open(process.env.MAP_WEB_URL);
    await page.send('Page.navigate', {url: process.env.MAP_WEB_URL + '/play'});
    await page.wait(`document.querySelector('#names')`, 'production StrictMode app mounted');
    const actual = await page.evaluate(`(async () => {
        const module = await import(${JSON.stringify(moduleUrl)});
        const cases = ${JSON.stringify(fixture.cases)};
        return cases.map(item => JSON.parse(module.evaluateExpansionPolicy(
            JSON.stringify(item.projection), JSON.stringify(item.model), item.baselineCommand, item.uniform)));
    })()`);
    assert.equal(actual.length, fixture.cases.length);
    let maximumAbsoluteError = 0;
    const numeric = (left, right, label) => {
        assert(Number.isFinite(left) && Number.isFinite(right), label);
        const error = Math.abs(left - right);
        maximumAbsoluteError = Math.max(maximumAbsoluteError, error);
        assert(error <= 2e-12, `${label}: ${left} != ${right}`);
    };
    for (const [index, item] of fixture.cases.entries()) {
        const expected = item.expected;
        const result = actual[index];
        assert.deepEqual(Object.keys(result).sort(), Object.keys(expected).sort(), item.id);
        assert.equal(result.command, expected.command, item.id);
        assert.equal(result.index, expected.index, item.id);
        for (const field of ['logits', 'probabilities']) {
            assert.equal(result[field].length, expected[field].length, `${item.id}.${field}`);
            result[field].forEach((value, row) => numeric(value, expected[field][row], `${item.id}.${field}[${row}]`));
        }
        for (const field of ['value', 'logProbability']) numeric(result[field], expected[field], `${item.id}.${field}`);
    }
    const sha = bytes => createHash('sha256').update(bytes).digest('hex');
    const proof = {kind: 'expansion-native-production-chrome-parity-1', passed: true,
        fixtureSha256: sha(fixtureBytes), cases: actual.length, maximumAbsoluteError,
        commandAndIndexExactlyEqual: true,
        scope: 'JVM versus production Kotlin/JS forward and selection at the same native uniform; signed-seed transformation is covered separately by shared unit tests.',
        productionModules: Object.fromEntries([...modules].map(([name, bytes]) => [name, sha(bytes)])),
        constructedFixturesOnly: true, trainedExpansionAssetInstalled: false};
    writeFileSync(join(process.env.AOR_BROWSER_ARTIFACTS, 'expansion-native-chrome-parity.json'), JSON.stringify(proof, null, 2) + '\n', {flag: 'wx'});
    console.log(JSON.stringify(proof));
} finally {
    page?.ws.close();
    await new Promise(resolve => server.close(resolve));
}
