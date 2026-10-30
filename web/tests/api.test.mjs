import assert from 'node:assert/strict';
import {test} from 'node:test';
import {readFile} from 'node:fs/promises';
import ts from 'typescript';

const source = await readFile(new URL('../src/api.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, {compilerOptions: {module: ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2020}}).outputText;
const {ApiError,request,requestSnapshot,setAuthentication} = await import('data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'));

test('rejections retain diagnostic objects and reject malformed arguments', async () => {
    const original = globalThis.fetch;
    try {
        globalThis.fetch = async () => new Response(JSON.stringify({code:'UNREACHABLE_PROVINCE',entityIds:['acre',7,null],parameters:{required:5,available:'untrusted',bad:{}}}),{status:422});
        await assert.rejects(request('/api/games/test'),error=>{
            assert(error instanceof ApiError);
            assert.equal(error.code,'UNREACHABLE_PROVINCE');
            assert.deepEqual(error.entityIds,['acre']);
            assert.deepEqual(error.parameters,{required:5});
            return true;
        });
    } finally {globalThis.fetch=original;}
});

test('conditional reads retain snapshots on 304 and refresh the managed bearer token', async () => {
    const original = globalThis.fetch;
    try {
        setAuthentication(async () => 'refreshed-account-token');
        globalThis.fetch = async (path, init) => {
            assert.equal(init.headers.Authorization, 'Bearer refreshed-account-token');
            assert.equal(init.headers['If-None-Match'], '"private-version"');
            return new Response(null, {status:304, headers:{ETag:'"private-version"'}});
        };
        assert.deepEqual(await requestSnapshot('/api/games/test', undefined, '"private-version"'), {snapshot:null, etag:'"private-version"'});
    } finally {globalThis.fetch=original; setAuthentication();}
});

test('a stalled snapshot read times out so later background polls can recover', {timeout: 1000}, async context => {
    const original = globalThis.fetch;
    context.mock.timers.enable({apis: ['setTimeout']});
    try {
        globalThis.fetch = async () => new Promise(() => {});
        const read = requestSnapshot('/api/games/test', 'seat-token');
        const rejected = assert.rejects(read, error => error instanceof ApiError && error.code === 'NETWORK');

        context.mock.timers.tick(15000);

        await rejected;
    } finally {globalThis.fetch = original;}
});

test('snapshot deadlines include authentication before the fetch starts', {timeout: 1000}, async context => {
    const original = globalThis.fetch;
    context.mock.timers.enable({apis: ['setTimeout']});
    let fetched = false;
    try {
        setAuthentication(async () => new Promise(() => {}));
        globalThis.fetch = async () => {fetched = true; return new Response('{}');};
        const rejected = assert.rejects(requestSnapshot('/api/games/test'), error => error.code === 'NETWORK');

        context.mock.timers.tick(15000);

        await rejected;
        assert.equal(fetched, false);
    } finally {globalThis.fetch = original; setAuthentication();}
});

test('snapshot deadlines include a stalled response body', {timeout: 1000}, async context => {
    const original = globalThis.fetch;
    context.mock.timers.enable({apis: ['setTimeout']});
    let beginBody;
    const bodyStarted = new Promise(resolve => {beginBody = resolve;});
    try {
        globalThis.fetch = async () => ({headers: new Headers(), ok: true, status: 200,
            json: async () => {beginBody(); return new Promise(() => {});}});
        const rejected = assert.rejects(requestSnapshot('/api/games/test', 'seat-token'), error => error.code === 'NETWORK');
        await bodyStarted;

        context.mock.timers.tick(15000);

        await rejected;
    } finally {globalThis.fetch = original;}
});

test('canceling an obsolete read aborts its fetch', async () => {
    const original = globalThis.fetch;
    let requestSignal;
    try {
        globalThis.fetch = async (_, options) => {requestSignal = options.signal; return new Promise(() => {});};
        const controller = new AbortController();
        const rejected = assert.rejects(requestSnapshot('/api/games/test', 'seat-token', undefined, controller.signal), error => error.code === 'NETWORK');

        controller.abort();

        await rejected;
        assert.equal(requestSignal.aborted, true);
    } finally {globalThis.fetch = original;}
});

for (const phase of ['authentication', 'fetch', 'body']) {
    test(`a mutation stalled in ${phase} has an unknown outcome and is never replayed`, {timeout: 1000}, async context => {
        const original = globalThis.fetch;
        context.mock.timers.enable({apis: ['setTimeout']});
        let writes = 0, bodyStarted;
        const started = new Promise(resolve => {bodyStarted = resolve;});
        try {
            setAuthentication(phase === 'authentication' ? async () => new Promise(() => {}) : undefined);
            globalThis.fetch = async () => {
                writes++;
                if (phase === 'fetch') {bodyStarted(); return new Promise(() => {});}
                return {ok: true, json: () => {bodyStarted(); return new Promise(() => {});}};
            };
            const rejected = assert.rejects(request('/commands', undefined, {command: 'pass'}), error => error.code === 'OUTCOME_UNKNOWN');
            if (phase !== 'authentication') await started;
            context.mock.timers.tick(15000);
            await rejected;
            assert.equal(writes, phase === 'authentication' ? 0 : 1);
            context.mock.timers.tick(60000);
            assert.equal(writes, phase === 'authentication' ? 0 : 1);
        } finally {globalThis.fetch = original; setAuthentication();}
    });
}

test('a canceled directory read cannot publish a late response', async () => {
    const original = globalThis.fetch;
    let reply;
    try {
        globalThis.fetch = async () => new Promise(resolve => {reply = resolve;});
        const controller = new AbortController();
        const rejected = assert.rejects(request('/api/games', 'token', undefined, controller.signal), error => error.code === 'NETWORK');
        controller.abort();
        reply(new Response('{"games":["obsolete"]}'));
        await rejected;
    } finally {globalThis.fetch = original;}
});

test('a failed POST quote remains a read failure rather than an uncertain mutation', async () => {
    const original = globalThis.fetch;
    try {
        globalThis.fetch = async () => {throw new Error('offline');};
        await assert.rejects(request('/quote', 'token', {amount: 7}, undefined, {readOnly: true}), error => error.code === 'NETWORK');
    } finally {globalThis.fetch = original;}
});
