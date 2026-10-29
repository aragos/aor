import assert from 'node:assert/strict';
import {test} from 'node:test';
import {readFile} from 'node:fs/promises';
import ts from 'typescript';

const source=await readFile(new URL('../src/history.ts',import.meta.url),'utf8');
const compiled=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2020}}).outputText;
const {displayHistoryLine}=await import('data:text/javascript;base64,'+Buffer.from(compiled).toString('base64'));
const players={p1:'London',p2:'Paris'};

test('player aliases cover actors, targets, attributed dice and edit authors',()=>{
    assert.equal(displayHistoryLine('p1: play hand-1 player p2',players),'London: play hand-1 player Paris');
    assert.equal(displayHistoryLine('!roll war p1=6 p2=2',players),'!roll war London=6 Paris=2');
    assert.equal(displayHistoryLine('# revision 8 by p1: undo 42',players),'# revision 8 by London: undo 42');
});

test('aliases preserve reference fragments and unknown identifiers',()=>{
    assert.equal(displayHistoryLine('!draw p1 hand-p1 commodity-p2',players),'!draw London hand-p1 commodity-p2');
    assert.equal(displayHistoryLine('p10: swap-order p2',players),'p10: swap-order Paris');
    assert.equal(displayHistoryLine('p1: pass',{}),'p1: pass');
});

test('player labels remain literal text',()=>{
    assert.equal(displayHistoryLine('p1: pass',{p1:'Ada $1 (East)'}),'Ada $1 (East): pass');
});

test('identifiers are escaped before matching',()=>{
    assert.equal(displayHistoryLine('pX1: pass',{'p.1':'Ada'}),'pX1: pass');
    assert.equal(displayHistoryLine('p.1: pass',{'p.1':'Ada'}),'Ada: pass');
});
