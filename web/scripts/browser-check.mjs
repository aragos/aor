import {spawn, execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {access, mkdtemp, mkdir, rm} from 'node:fs/promises';
import {createServer} from 'node:net';
import {tmpdir} from 'node:os';
import {resolve, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {browserOptions} from './browser-options.mjs';
import {resetBrowserSession} from '../tests/browser-support.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const {suites, timeout} = browserOptions(process.argv.slice(2), process.env, resolve(root, 'web/tests'));
// Catch misspelled suite names before building or starting the isolated services.
await Promise.all(suites.map(suite => access(suite.path)));
const temporary = await mkdtemp(resolve(tmpdir(), 'aor-browser-'));
const artifacts = resolve(process.env.AOR_BROWSER_ARTIFACTS ?? await mkdtemp(resolve(tmpdir(), 'aor-browser-artifacts-')));
const children = [];
let interrupted;
const interruption = new Promise((_, reject) => { interrupted = reject; });
// Signals can arrive between awaited operations; keep rejection handled until the next race.
void interruption.catch(() => {});
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
async function ownedProcesses() {
    const groups = new Set(children.flatMap(tracked => tracked.child.pid ? [tracked.child.pid] : []));
    if (!groups.size) return [];
    const {stdout} = await promisify(execFile)('ps', ['-axo', 'pid=,pgid=,stat='], {timeout: 5000});
    return stdout.trim().split('\n').flatMap(line => {
        const [pid, group, state] = line.trim().split(/\s+/);
        return groups.has(Number(group)) && !state?.startsWith('Z') ? [{pid: Number(pid), group: Number(group)}] : [];
    });
}
function signalOwned(pid, signal) {
    try { process.kill(pid, signal); }
    catch (error) { if (error.code !== 'ESRCH') console.warn(`Could not send ${signal} to owned PID ${pid}: ${error.code}`); }
}
async function cleanup() {
    let leftovers;
    try {
        // Resolve membership before sending signals. Only groups created by launch are eligible;
        // avoid signalling completed build groups or a process discovered by its executable name.
        let owned = await ownedProcesses();
        for (const process of owned.reverse()) signalOwned(process.pid, 'SIGTERM');
        const deadline = Date.now() + 3000;
        while (owned.length && Date.now() < deadline) {
            await new Promise(resolve => setTimeout(resolve, 100));
            owned = await ownedProcesses();
        }
        for (const process of owned) signalOwned(process.pid, 'SIGKILL');
        for (let attempt = 0; attempt < 20; attempt++) {
            leftovers = await ownedProcesses();
            if (!leftovers.length) break;
            await new Promise(resolve => setTimeout(resolve, 100));
        }
        if (leftovers.length) throw new Error(`Owned browser-check processes remain: ${leftovers.map(process => process.pid).join(', ')}`);
        console.log(`Verified all ${children.length} owned process groups closed.`);
    } finally {
        await rm(temporary, {recursive: true, force: true});
        for (const [signal, handler] of handlers) process.off(signal, handler);
        console.log('Removed isolated game/browser data.');
    }
}

let failure;
try {
    await mkdir(artifacts, {recursive: true});
    if (!process.env.AOR_BROWSER_SERVER) {
        await checked(launch('server distribution build', './gradlew', [':server:installDist']), 300000);
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
    for (const [index, suite] of suites.entries()) {
        if (index) await Promise.race([resetBrowserSession(cdp, [web, api]), interruption]);
        await checked(launch(suite.name, process.execPath, [suite.path], {
            MAP_API_URL: api, MAP_WEB_URL: web, MAP_CDP_URL: cdp,
            AOR_BROWSER_ARTIFACTS: artifacts, AOR_BROWSER_SUITE: suite.name,
        }), timeout);
    }
    console.log(`Browser checks passed. Screenshots: ${artifacts}`);
} catch (error) {
    failure = error;
    throw error;
} finally {
    try {
        await cleanup();
    } catch (error) {
        if (failure) throw new AggregateError([failure, error], 'Browser checks and cleanup failed');
        throw error;
    }
}
