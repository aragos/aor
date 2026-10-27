import assert from 'node:assert/strict';
import {test} from 'node:test';
import {readFile} from 'node:fs/promises';
import ts from 'typescript';

const source = await readFile(new URL('../src/api.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, {compilerOptions: {module: ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2020}}).outputText;
const {ApiError,request} = await import('data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'));

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
