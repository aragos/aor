// Compare the exported asset on the release JS and JVM targets across two complete live-rule games.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {join} from 'node:path';
import {createInterface} from 'node:readline';
import {pathToFileURL} from 'node:url';

const [bundle, count, shippedAsset, candidateAsset, market] = process.argv.slice(2);
const {HardBotSelector} = await import(pathToFileURL(join(bundle, 'browser', 'js', 'aor-ai.mjs')));
const policies = [new HardBotSelector(readFileSync(shippedAsset, 'utf8'), Number(count), false),
    new HardBotSelector(readFileSync(candidateAsset, 'utf8'), Number(count), market === 'true')];
const authority = spawn('java', ['-Xmx384m', '-XX:ActiveProcessorCount=2', '-cp',
    join(bundle,'browser','lib','*'), join(bundle,'CheckCheckpointParity.java'),
    count, shippedAsset, candidateAsset, market], {stdio: ['pipe', 'pipe', 'inherit']});
const done = new Promise((resolve, reject) => authority.once('exit', code =>
    code === 0 ? resolve() : reject(new Error('Game authority exited with ' + code))));
let decisions = 0;
let changed = 0;
const timings = [[], []];
try {
    for await (const line of createInterface({input: authority.stdout})) {
        const row = JSON.parse(line);
        const args = [JSON.stringify(row.observation), row.initiative && JSON.stringify(row.initiative),
            row.finish && JSON.stringify(row.finish), row.seed];
        for (const i of decisions % 2 === 0 ? [0, 1] : [1, 0]) {
            const start = performance.now();
            const selected = policies[i].choose(...args);
            if (decisions >= 100) timings[i].push(performance.now() - start);
            assert.equal(selected, i === 0 ? row.old : row.market, 'Browser / JVM disagreement');
        }
        changed += Number(row.old !== row.market);
        decisions++;
        authority.stdin.write(row.market + '\n');
    }
    await done;
    assert(decisions > 100);
    const p95Ms = timings.map(sample => sample.sort((a,b) => a-b)[Math.floor(sample.length*.95)]);
    console.log(JSON.stringify({decisions, changed, p95Ms, withinBudget: p95Ms[1] <= 1.10 * p95Ms[0]}));
} finally {
    authority.stdin.end();
    if (authority.exitCode === null) authority.kill();
}
