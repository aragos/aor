import {spawn} from 'node:child_process';
import {mkdtemp, mkdir, rm} from 'node:fs/promises';
import {createServer} from 'node:net';
import {tmpdir} from 'node:os';
import {resolve, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const temporary = await mkdtemp(resolve(tmpdir(), 'aor-browser-'));
const artifacts = resolve(process.env.AOR_BROWSER_ARTIFACTS ?? await mkdtemp(resolve(tmpdir(), 'aor-browser-artifacts-')));
const children = [];
let interrupted;
const interruption = new Promise((_, reject) => { interrupted = reject; });
const onSignal = signal => interrupted(new Error(`Browser checks interrupted by ${signal}`));
const handlers = new Map(['SIGINT', 'SIGTERM'].map(signal => [signal, () => onSignal(signal)]));
for (const [signal, handler] of handlers) process.on(signal, handler);

function launch(name, command, args, env = {}, cwd = root) {
    const child = spawn(command, args, {cwd, env: {...process.env, ...env}, detached: true, stdio: 'inherit'});
    let startupError;
    const done = new Promise(resolve => {
        child.once('error', error => {startupError = error; resolve({error});});
        child.once('exit', (code, signal) => resolve({code, signal}));
    });
    const tracked = {name, child, done, get startupError() {return startupError;}};
    children.push(tracked);
    console.log(`Started ${name}: PID ${child.pid}`);
    return tracked;
}
async function checked(tracked, timeout = 180000) {
    let timer;
    try {
        const result = await Promise.race([tracked.done, interruption, new Promise((_, reject) => {
            timer = setTimeout(() => reject(new Error(`${tracked.name} timed out`)), timeout);
        })]);
        if (result.error) throw result.error;
        if (result.code !== 0) throw new Error(`${tracked.name} exited: ${result.code ?? result.signal}`);
    } finally { clearTimeout(timer); }
}
async function port() {
    const server = createServer();
    await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
    const number = server.address().port;
    await new Promise(resolve => server.close(resolve));
    return number;
}
async function ready(url, tracked) {
    const expires = Date.now() + 90000;
    while (Date.now() < expires) {
        if (tracked.startupError) throw tracked.startupError;
        if (tracked.child.exitCode !== null || tracked.child.signalCode !== null) throw new Error(`${tracked.name} exited during startup`);
        try { if ((await fetch(url, {signal: AbortSignal.timeout(1000)})).ok) return; } catch {}
        await Promise.race([interruption, new Promise(resolve => setTimeout(resolve, 150))]);
    }
    throw new Error(`Timed out waiting for ${url}`);
}
function signalGroup(tracked, signal) {
    if (!tracked.child.pid) return;
    try { process.kill(-tracked.child.pid, signal); } catch (error) { if (error.code !== 'ESRCH') throw error; }
}
try {
    await mkdir(artifacts, {recursive: true});
    if (!process.env.AOR_BROWSER_SERVER) {
        await checked(launch('server distribution build', './gradlew', [':server:installDist', '--no-daemon']), 300000);
    }
    const [apiPort, webPort, cdpPort] = await Promise.all([port(), port(), port()]);
    const api = `http://127.0.0.1:${apiPort}`;
    const web = `http://127.0.0.1:${webPort}`;
    const cdp = `http://127.0.0.1:${cdpPort}`;
    const server = launch('isolated game server', process.env.AOR_BROWSER_SERVER ?? resolve(root, 'server/build/install/server/bin/server'), [], {
        AOR_HTTP_PORT: String(apiPort), AOR_DATA_DIR: resolve(temporary, 'games'),
    });
    const vite = launch('Vite', process.execPath, [resolve(dirname(fileURLToPath(import.meta.resolve('vite/package.json'))), 'bin/vite.js'), '--host', '127.0.0.1', '--port', String(webPort), '--strictPort'], {AOR_API_TARGET: api}, resolve(root, 'web'));
    const browser = launch('isolated Chrome', process.env.CHROME_BIN ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', [
        '--headless', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
        '--disable-background-networking', '--disable-component-update',
        '--remote-debugging-address=127.0.0.1', `--remote-debugging-port=${cdpPort}`,
        `--user-data-dir=${resolve(temporary, 'chrome')}`, 'about:blank',
    ]);
    await Promise.all([ready(`${api}/api/health`, server), ready(web, vite), ready(`${cdp}/json/version`, browser)]);
    await checked(launch('browser interactions', process.execPath, [resolve(root, 'web/tests/map.browser.mjs')], {
        MAP_API_URL: api, MAP_WEB_URL: web, MAP_CDP_URL: cdp, AOR_BROWSER_ARTIFACTS: artifacts,
    }), 180000);
    console.log(`Browser checks passed. Screenshots: ${artifacts}`);
} finally {
    // Signal only process groups created above; never discover or terminate unrelated processes.
    for (const tracked of [...children].reverse()) signalGroup(tracked, 'SIGTERM');
    await Promise.race([Promise.all(children.map(tracked => tracked.done)), new Promise(resolve => setTimeout(resolve, 3000))]);
    for (const tracked of [...children].reverse()) signalGroup(tracked, 'SIGKILL');
    await Promise.all(children.map(tracked => tracked.done));
    await rm(temporary, {recursive: true, force: true});
    for (const [signal, handler] of handlers) process.off(signal, handler);
    console.log(`Closed all ${children.length} tracked child processes; isolated game/browser data removed.`);
}
