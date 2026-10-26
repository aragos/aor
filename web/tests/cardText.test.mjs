import assert from 'node:assert/strict';
import {test} from 'node:test';
import {readFile} from 'node:fs/promises';
import ts from 'typescript';

const messages = await readFile(new URL('../src/messages.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(messages, {compilerOptions: {module: ts.ModuleKind.ESNext}}).outputText;
const {en} = await import('data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'));
const catalog = await readFile(new URL('../../core/src/commonMain/kotlin/dev/aragos/aor/CardEffectData.kt', import.meta.url), 'utf8');

test('every catalog event has readable effect text', () => {
    const eventIds = [...catalog.split('enum class EventId')[1].split('enum class LeaderId')[0].matchAll(/\("([a-z-]+)"\)/g)].map(match => match[1]);
    assert.equal(eventIds.length, 18);
    for (const id of eventIds) assert(en[`card.${id}.effect`]?.length > 30, `Missing event text for ${id}`);
});
