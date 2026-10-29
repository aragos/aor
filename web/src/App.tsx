import {lazy, Suspense, useEffect, useRef, useState} from 'react';
import type {FormEvent} from 'react';
import {ApiError, errorCode, request, requestSnapshot} from './api';
import type {ApplicationConfig, CreatedGame, Snapshot} from './api';
import {t} from './messages';
import {defaultVariants, validVariants, Variants} from './Variants';
import {isUndoPolicy, useStoredSetting} from './preferences';
import {ChoiceButtons} from './DecisionControls';
import {HelpLink} from './HelpLink';
import {GameTable} from './GameTable';
import type {PurchaseQuote} from './GameTable';
import {MapSelectionProvider} from './map/MapSelection';
import {hasPlayerAction, setTurnFavicon} from './favicon';
import {startPolling} from './polling';
import './app.css';

const HostedApp = lazy(() => import('./HostedApp'));
const ReferencePage = lazy(() => import('./ReferencePage'));

function ErrorMessage({code}: {code: string}) { return <p className="error" role="alert">{t(`error.${code}`)}</p>; }

function CreateGame() {
    const [names, setNames] = useState('Alice\nBob\nCarol');
    const [undoPolicy, setUndoPolicy] = useStoredSetting('undo-policy', 'regular-turn', isUndoPolicy);
    const [variants, setVariants] = useStoredSetting('variants', defaultVariants, validVariants);
    const [created, setCreated] = useState<CreatedGame | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState('');
    async function create(event: FormEvent) {
        event.preventDefault(); setBusy(true); setError('');
        try { setCreated(await request<CreatedGame>('/api/games', undefined, {players: names.split('\n'), options: variants, undoPolicy})); }
        catch (failure) { setError(errorCode(failure)); }
        finally { setBusy(false); }
    }
    return <main className="landing panel">
        <p className="eyebrow">{t('app.subtitle')}</p>
        <h1>{t('app.title')}</h1>
        {created ? <>
            <h2>{t('links.title')}</h2><p className="muted">{t('links.description')}</p>
            <ul className="seat-links">{created.links.map(link => <li key={link.id}>
                <strong>{link.name}</strong><a className="button" href={link.path} target="_blank" rel="noreferrer" aria-label={t('links.openFor').replace('{name}', link.name)}>{t('links.open')}</a>
            </li>)}</ul>
            <button className="secondary" onClick={() => setCreated(null)}>{t('links.new')}</button>
        </> : <form onSubmit={create}>
            <h2>{t('create.title')}</h2><p className="muted">{t('create.description')}</p>
            <label htmlFor="names">{t('create.players')}</label>
            <textarea id="names" rows={6} value={names} onChange={event => setNames(event.target.value)} required/>
            <p className="muted small">{t('create.threeEpochs')}</p>
            <Variants value={variants} onChange={setVariants} disabled={busy}/>
            <p>{t('history.policy')} <HelpLink topic="history" label="Undo policies"/></p><ChoiceButtons id="undo-policy" value={undoPolicy} onChange={setUndoPolicy} disabled={busy}
                choices={['regular-turn', 'competitive', 'friendly-turn', 'friendly-game'].map(value => ({value, label: t(`undoPolicy.${value}`)}))}/>
            {error && <ErrorMessage code={error}/>}
            <button disabled={busy}>{t(busy ? 'status.working' : 'create.submit')}</button>
        </form>}
    </main>;
}

function Game({id, token, pollInterval = 1000}: {id: string; token?: string; pollInterval?: number}) {
    const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
    const [connectionError, setConnectionError] = useState('');
    const [actionError, setActionError] = useState<ApiError | null>(null);
    const [busy, setBusy] = useState(false);
    const [saved, setSaved] = useState(false);
    const busyRef = useRef(false);
    const sequence = useRef(0);
    const etag = useRef<string>();
    const path = `/api/games/${id}`;

    function acceptSnapshot(next: Snapshot) {
        setTurnFavicon(hasPlayerAction(next));
        setSnapshot(next);
    }

    useEffect(() => {
        let active = true;
        let readSequence = 0;
        setTurnFavicon(false);
        const stop = startPolling({intervalMs: pollInterval, paused: () => busyRef.current,
            poll: async signal => {
                const current = ++sequence.current;
                readSequence = current;
                const next = await requestSnapshot(path, token, etag.current, signal);
                if (active && !signal.aborted && current === sequence.current) {
                    etag.current = next.etag;
                    if (next.snapshot) acceptSnapshot(next.snapshot);
                    setConnectionError('');
                }
            }, onError: failure => {
                if (active && readSequence === sequence.current) setConnectionError(errorCode(failure));
            }});
        return () => {active = false; stop(); setTurnFavicon(false);};
    }, [path, token, pollInterval]);

    async function mutate(endpoint: string, operation: Record<string, unknown>): Promise<boolean> {
        if (!snapshot || busyRef.current) return false;
        busyRef.current = true; ++sequence.current;
        etag.current = undefined;
        setBusy(true); setActionError(null); setSaved(false);
        try {
            acceptSnapshot(await request<Snapshot>(`${path}/${endpoint}`, token, {expectedRevision: snapshot.revision, ...operation}));
            setConnectionError(''); setSaved(true); return true;
        } catch (failure) {
            const code = errorCode(failure);
            setActionError(failure instanceof ApiError ? failure : new ApiError(code));
            if (code === 'STALE_REVISION') {
                try {
                    const refreshed = await requestSnapshot(path, token);
                    if (refreshed.snapshot) acceptSnapshot(refreshed.snapshot);
                } catch (refreshFailure) {setConnectionError(errorCode(refreshFailure));}
            }
            return false;
        } finally { busyRef.current = false; setBusy(false); }
    }

    if (!snapshot) return <main className="landing panel"><h1>{t('app.title')}</h1>{connectionError ? <ErrorMessage code={connectionError}/> : <p>{t('game.loading')}</p>}<a href="/">{t('game.new')}</a></main>;
    return <MapSelectionProvider><GameTable snapshot={snapshot} busy={busy} saved={saved}
        submit={command => mutate('commands', {command})} editHistory={(fromEntry, lines) => mutate('history', {fromEntry, lines})} connectionError={connectionError} actionError={actionError}
        quotePurchase={amount => request<PurchaseQuote>(`${path}/quote-token-purchase`, token, {expectedRevision: snapshot.revision, amount})}/></MapSelectionProvider>;
}

export function App() {
    const [config, setConfig] = useState<ApplicationConfig | null>(null);
    const [error, setError] = useState('');
    const reference = window.location.pathname === '/help';
    useEffect(() => {
        if (!reference) void request<ApplicationConfig>('/api/config').then(setConfig).catch(failure => setError(errorCode(failure)));
    }, [reference]);
    if (reference) return <Suspense fallback={<main className="landing panel"><p>{t('game.loading')}</p></main>}><ReferencePage/></Suspense>;
    const match = /^\/games\/([0-9a-f-]+)\/?$/.exec(window.location.pathname);
    if (!config) return <main className="landing panel"><h1>{t('app.title')}</h1>{error ? <ErrorMessage code={error}/> : <p>{t('game.loading')}</p>}</main>;
    if (config.authMode === 'firebase') return <Suspense fallback={<main className="landing panel"><p>{t('game.loading')}</p></main>}><HostedApp config={config} game={match ? <Game id={match[1]} pollInterval={config.pollIntervalMs}/> : null}/></Suspense>;
    if (!match) return <CreateGame/>;
    const token = new URLSearchParams(window.location.hash.slice(1)).get('token');
    return token ? <Game id={match[1]} token={token}/> : <main className="landing panel"><h1>{t('app.title')}</h1><p role="alert">{t('game.missingToken')}</p><a href="/">{t('game.new')}</a></main>;
}
