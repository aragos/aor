import {createRoot} from 'react-dom/client';
import {App} from '../src/App';
import type {Snapshot} from '../src/api';

declare global {
    interface Window {
        setUiSnapshot: (snapshot: Snapshot) => void;
        uiSnapshot?: Snapshot;
        uiCommands: {command: string; expectedRevision: number}[];
        uiNextError?: string;
        uiErrorEntities?: string[];
        uiRequests: number;
        uiQuoteRequests: {expectedRevision: number; amount: number}[];
        uiQuoteReplies: Record<number, unknown>;
        uiHoldQuotes: boolean;
        releaseUiQuotes: () => void;
    }
}

// Exercise App's real polling/submission/draft lifecycle with deterministic transport responses.
// These fixtures test presentation; live API tests separately establish rule validity and secrecy.
window.uiCommands = [];
window.uiRequests = 0;
window.uiQuoteRequests = [];
window.uiQuoteReplies = {};
window.uiHoldQuotes = false;
const heldQuotes: (() => void)[] = [];
window.releaseUiQuotes = () => {heldQuotes.splice(0).forEach(release => release());};
let mounted = false;
const root = createRoot(document.getElementById('root')!);
window.setUiSnapshot = snapshot => {
    window.uiSnapshot = snapshot;
    if (!mounted) {
        mounted = true;
        history.replaceState(null, '', `/games/${snapshot.gameId}#token=fixture`);
        root.render(<App/>);
    }
};
window.fetch = async (input, init) => {
    const path = String(input);
    if (!path.startsWith('/api/games/')) throw new Error(`Unexpected harness request: ${path}`);
    window.uiRequests++;
    if (path.endsWith('/quote-token-purchase')) {
        const body = JSON.parse(String(init?.body));
        window.uiQuoteRequests.push(body);
        // Supplied authoritative fixture quotes are captured before any held response is released.
        const response = window.uiQuoteReplies[body.amount];
        if (window.uiHoldQuotes) await new Promise<void>(resolve => heldQuotes.push(resolve));
        if (!response) return new Response(JSON.stringify({code: 'STALE_REVISION'}), {status: 409});
        return new Response(JSON.stringify(response));
    }
    if (init?.method === 'POST') {
        window.uiCommands.push(JSON.parse(String(init.body)));
        if (window.uiNextError) {
            const code = window.uiNextError;
            delete window.uiNextError;
            return new Response(JSON.stringify({code, entityIds: window.uiErrorEntities ?? []}), {status: code === 'STALE_REVISION' ? 409 : 400});
        }
    }
    return new Response(JSON.stringify(window.uiSnapshot), {headers: {'Content-Type': 'application/json'}});
};
