import {createRoot} from 'react-dom/client';
import {StrictMode} from 'react';
import {Dashboard} from '../src/HostedApp';
import {AccountContext} from '../src/AccountControls';
import type {Account, GameSummary} from '../src/api';
import '../src/app.css';

declare global {
    interface Window {portalHasTurn: boolean; portalRequests: {path: string; body?: unknown}[]}
}
const me: Account = {id: 'uid-alice', handle: 'alice', admin: new URLSearchParams(location.search).get('admin') !== 'false', mayCreateGames: true};
const bob = {...me, id: 'uid-bob', handle: 'bob', admin: false, mayCreateGames: false};
const carol = {...bob, id: 'uid-carol', handle: 'carol'};
const games: GameSummary[] = ['MY_MOVE', 'ONGOING', 'FINISHED'].map((status, index) => ({
    id: `00000000-0000-0000-0000-00000000000${index}`, revision: 4, createdAt: Date.now(),
    name: ['Friday Renaissance', 'Mediterranean traders', 'Spring campaign'][index],
    status: status as GameSummary['status'], stage: index === 2 ? 'FINISHED' : 'EXPANSION',
    players: [me, bob, carol].map(player => ({id: player.id, name: `@${player.handle}`})), myMove: index === 0,
    actionPlayers: index === 0 ? [me.id, carol.id] : index === 1 ? [bob.id] : [],
}));
const otherGames: GameSummary[] = games.map((game, index) => ({
    ...game, id: `10000000-0000-0000-0000-00000000000${index}`,
    name: ['Eastern trade routes', 'Atlantic merchants', 'Last winter'][index],
    status: index === 2 ? 'FINISHED' : 'ONGOING', myMove: false,
    players: [bob, carol].map(player => ({id: player.id, name: `@${player.handle}`})),
    actionPlayers: index === 2 ? [] : [bob.id],
}));
window.portalRequests = [];
window.portalHasTurn = true;
window.fetch = async (input, init) => {
    const path = String(input);
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    window.portalRequests.push({path, body});
    let result: unknown;
    if (path === '/api/games' && init?.method === 'POST') {
        return new Response(JSON.stringify({code: 'STORAGE_UNAVAILABLE'}), {status: 503});
    }
    if (path.startsWith('/api/players?q=')) {
        const query = new URL(path, location.origin).searchParams.get('q') ?? '';
        result = [bob, carol].filter(player => player.handle.includes(query));
    } else if (path === '/api/admin/players') result = [me, bob, carol];
    else if (path.endsWith('/creation')) result = {...bob, mayCreateGames: true};
    else if (path.startsWith('/api/games?') || path.startsWith('/api/admin/games?')) {
        const params = new URL(path, location.origin).searchParams;
        const filter = params.get('status');
        const listed = path.startsWith('/api/admin') ? [...games, ...otherGames] : params.get('scope') === 'other' ? otherGames : games;
        result = {games: filter === 'my-move' && !window.portalHasTurn ? [] : listed.filter(game => filter === 'all' || filter === 'ongoing' && ['MY_MOVE', 'ONGOING'].includes(game.status) || game.status.toLowerCase().replace(/_/g, '-') === filter), nextCursor: null};
    } else if (path.includes('/log') || path.includes('/status')) result = {ok: true};
    else throw new Error(`Unexpected portal request: ${path}`);
    return new Response(JSON.stringify(result), {headers: {'Content-Type': 'application/json'}});
};
createRoot(document.getElementById('root')!).render(<StrictMode><AccountContext.Provider value={{handle: me.handle, signOut: () => {}}}><Dashboard me={me}/></AccountContext.Provider></StrictMode>);
