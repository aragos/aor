import {createRoot} from 'react-dom/client';
import {Dashboard} from '../src/HostedApp';
import type {Account, GameSummary} from '../src/api';
import '../src/app.css';

declare global {
    interface Window {portalRequests: {path: string; body?: unknown}[]}
}
const me: Account = {id: 'uid-alice', handle: 'alice', displayName: 'Alice', admin: true, mayCreateGames: true};
const bob = {...me, id: 'uid-bob', handle: 'bob', displayName: 'Bob', admin: false, mayCreateGames: false};
const carol = {...bob, id: 'uid-carol', handle: 'carol', displayName: 'Carol'};
const games: GameSummary[] = ['MY_MOVE', 'ONGOING', 'FINISHED'].map((status, index) => ({
    id: `00000000-0000-0000-0000-00000000000${index}`, revision: 4, createdAt: Date.now(),
    status: status as GameSummary['status'], stage: index === 2 ? 'FINISHED' : 'EXPANSION',
    players: [me, bob, carol].map(player => ({id: player.id, name: player.displayName})), myMove: index === 0,
}));
window.portalRequests = [];
window.fetch = async (input, init) => {
    const path = String(input);
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    window.portalRequests.push({path, body});
    let result: unknown;
    if (path.startsWith('/api/players?q=')) {
        const query = new URL(path, location.origin).searchParams.get('q') ?? '';
        result = [bob, carol].filter(player => player.handle.includes(query));
    } else if (path === '/api/admin/players') result = [me, bob, carol];
    else if (path.endsWith('/creation')) result = {...bob, mayCreateGames: true};
    else if (path.startsWith('/api/games?') || path.startsWith('/api/admin/games?')) {
        const filter = new URL(path, location.origin).searchParams.get('status');
        result = {games: games.filter(game => filter === 'all' || filter === 'ongoing' && ['MY_MOVE', 'ONGOING'].includes(game.status) || game.status.toLowerCase().replace(/_/g, '-') === filter), nextCursor: null};
    } else if (path.includes('/log') || path.includes('/status')) result = {ok: true};
    else throw new Error(`Unexpected portal request: ${path}`);
    return new Response(JSON.stringify(result), {headers: {'Content-Type': 'application/json'}});
};
createRoot(document.getElementById('root')!).render(<Dashboard me={me}/>);
