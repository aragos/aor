import assert from 'node:assert/strict';
import {test} from 'node:test';
import {readFile} from 'node:fs/promises';
import ts from 'typescript';

const messages = await readFile(new URL('../src/messages.ts', import.meta.url), 'utf8');
const hostedMessages = await readFile(new URL('../src/hostedMessages.ts', import.meta.url), 'utf8');
const hostedModule = ts.transpileModule(hostedMessages, {compilerOptions: {module: ts.ModuleKind.ESNext}}).outputText;
const hostedUrl = 'data:text/javascript;base64,' + Buffer.from(hostedModule).toString('base64');
const compiled = ts.transpileModule(messages, {compilerOptions: {module: ts.ModuleKind.ESNext}}).outputText
    .replace(/(['"])\.\/hostedMessages\1/, JSON.stringify(hostedUrl));
const {en, t} = await import('data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'));
const catalog = await readFile(new URL('../../core/src/commonMain/kotlin/dev/aragos/aor/CardEffectData.kt', import.meta.url), 'utf8');

test('every catalog event has readable effect text', () => {
    const eventIds = [...catalog.split('enum class EventId')[1].split('enum class LeaderId')[0].matchAll(/\("([a-z-]+)"\)/g)].map(match => match[1]);
    assert.equal(eventIds.length, 18);
    for (const id of eventIds) assert(en[`card.${id}.effect`]?.length > 30, `Missing event text for ${id}`);
});

test('every advance and configured effect variant has readable reference text', async () => {
    const source = await readFile(new URL('../../core/src/commonMain/kotlin/dev/aragos/aor/Advances.kt', import.meta.url), 'utf8');
    const ids = [...source.split('enum class AdvanceId')[1].split('enum class AdvanceCategory')[0].matchAll(/\s+([A-Z_]+)\('/g)].map(match=>match[1]);
    assert.equal(ids.length,26);
    const variants = {PATRONAGE:['PROTECTION_MONEY','THREE_BOX_DISPLAY'],WIND_WATERMILL:['TAKEOVER_TRADE','MARKET_ADJUSTMENT'],WRITTEN_RECORD:['LEADER_BONUS','TAKEOVER_TRADE']};
    for (const id of ids) {
        for (const suffix of variants[id] ?? ['']) {
            const key = `advance.${id}.effect${suffix ? '.'+suffix : ''}`;
            assert(en[key]?.length>20,`Missing advance text: ${key}`);
        }
    }
});

test('localized quantities and repeated placeholders interpolate without exposing keys', () => {
    assert.equal(t('board.tokenOccupancy',{owner:'Venice',old:1000,fresh:2}),'Venice, 1,000 established and 2 new tokens');
    assert.equal(t('controls.chooseCession',{count:2}),'Choose 2 provinces to cede');
});
