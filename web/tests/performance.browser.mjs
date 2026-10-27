import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {createGame, open} from './browser-support.mjs';

const web = process.env.MAP_WEB_URL;
const api = process.env.MAP_API_URL;
const cdp = process.env.MAP_CDP_URL;
const artifacts = process.env.AOR_BROWSER_ARTIFACTS;
const game = await createGame(6);
const pages = [];
let state = await game.snapshot(game.links[0]);
let actions = 0;
// Deterministic policy, authoritative live game: keep cards, buy available extra draws, stabilize
// in cash, and leave expansion empty. Stop within epoch 3, with every seat still active.
while (state.game.currentEpoch < 3 && actions < 1200) {
    assert.notEqual(state.game.stage,'FINISHED','Profile must reach epoch 3 before game ending');
    const actor = game.links.find(link=>state.prompt?.players.includes(link.id));
    assert(actor,`Live actor at ${state.game.stage}`);
    const own = await game.snapshot(actor);
    const commands = own.legalChoices.commands;
    const command = own.game.stage==='CONFIGURATION' ? 'start'
        : own.game.stage==='BIDDING' ? `bid-capital ${game.links.indexOf(actor)}`
        : own.game.stage==='TOKEN_PURCHASE' ? `buy-tokens ${game.links.indexOf(actor)}`
        : commands.find(command=>command.startsWith('choose-capital '))
            ?? commands.find(command=>command.startsWith('choose-turn-position '))
            ?? commands.find(command=>command==='buy-card')
            ?? commands.find(command=>command==='stabilize cash')
            ?? commands.find(command=>command==='pass') ?? commands[0];
    assert(command,`Available command at ${own.prompt?.messageId}`);
    await game.send(actor,command);
    state = await game.snapshot(game.links[0]);
    actions++;
}
assert.equal(state.game.currentEpoch,3);
assert.equal(state.game.players.filter(player=>player.misery!==null).length,6,'All six seats remain active');

const metrics = async page => Object.fromEntries((await page.send('Performance.getMetrics')).metrics.map(metric=>[metric.name,metric.value]));
const percentile = (values,p) => [...values].sort((a,b)=>a-b)[Math.ceil(values.length*p)-1];
const samples=[];
const interactions=[];
try {
    for (const link of game.links) {
        const page = await open(web+link.path);
        pages.push(page);
        await page.wait(`document.querySelector('.game-table')`,'late-game seat');
        await page.send('Performance.enable');
    }
    const before = await Promise.all(pages.map(metrics));
    for (let round=0;round<10;round++) {
        await Promise.all(game.links.map(async link=>{
            const start=performance.now();
            const response=await fetch(`${api}/api/games/${game.created.gameId}`,{headers:{Authorization:`Bearer ${link.token}`}});
            assert(response.ok);
            const body=await response.text();
            samples.push({latencyMs:performance.now()-start,bytes:Buffer.byteLength(body)});
        }));
    }
    for (const page of pages) {
        await page.send('Page.bringToFront');
        for (const tab of ['markets','advances','map']) {
            const target=await page.evaluate(`(() => {const element=document.querySelector('#tab-${tab}');element.scrollIntoView({block:'center'});const rect=element.getBoundingClientRect();return {x:rect.x+rect.width/2,y:rect.y+rect.height/2};})()`);
            const start=performance.now();
            await page.send('Input.dispatchMouseEvent',{type:'mousePressed',...target,button:'left',clickCount:1});
            await page.send('Input.dispatchMouseEvent',{type:'mouseReleased',...target,button:'left',clickCount:1});
            await page.wait(`document.querySelector('#tab-${tab}').getAttribute('aria-selected')==='true'`,'workspace interaction');
            await page.evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
            interactions.push(performance.now()-start);
        }
    }
    const after=await Promise.all(pages.map(metrics));
    const report={epoch:state.game.currentEpoch,turn:state.game.turnNumber,revision:state.revision,actions,
        historyEntries:state.history.length,privateHandCounts:state.game.players.map(player=>player.handCount),
        snapshot:{samples:samples.length,p50Ms:percentile(samples.map(sample=>sample.latencyMs),.5),p95Ms:percentile(samples.map(sample=>sample.latencyMs),.95),maxBytes:Math.max(...samples.map(sample=>sample.bytes))},
        interaction:{samples:interactions.length,p50Ms:percentile(interactions,.5),p95Ms:percentile(interactions,.95),note:'Driver click to selected tab and two rendered frames; includes CDP overhead.'},
        browserCpuSeconds:after.map((values,index)=>Object.fromEntries(['TaskDuration','ScriptDuration','LayoutDuration','RecalcStyleDuration'].map(name=>[name,values[name]-before[index][name]]))),
        notes:['Development Vite build; six polling seats; ten concurrent six-viewer snapshot batches.',
            'CPU deltas cover polling and interaction, not an isolated React commit.',
            'Low-conflict late-game position; no claim about dense competitions or production hardware.']};
    await writeFile(join(artifacts,'late-game-performance.json'),JSON.stringify(report,null,2));
    console.log(JSON.stringify(report));
} finally {
    for (const page of pages) {
        page.ws.close();
        await fetch(`${cdp}/json/close/${page.target.id}`);
    }
}
