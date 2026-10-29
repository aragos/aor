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
        globalThis.fetch = async () => new Response(JSON.stringify({code:'UNREACHABLE_PROVINCE',entityIds:['acre',7,null]}),{status:422});
        await assert.rejects(request('/api/games/test'),error=>{
            assert(error instanceof ApiError);
            assert.equal(error.code,'UNREACHABLE_PROVINCE');
            assert.deepEqual(error.entityIds,['acre']);
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
