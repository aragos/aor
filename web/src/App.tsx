import {useEffect, useRef, useState} from 'react';
import type {FormEvent} from 'react';
import {ApiError, errorCode, request} from './api';
import type {CreatedGame, Snapshot} from './api';
import {t} from './messages';
import {defaultVariants, Variants} from './Variants';
import {GameTable} from './GameTable';
import type {PurchaseQuote} from './GameTable';
import {MapSelectionProvider} from './map/MapSelection';
import './app.css';

function ErrorMessage({code}: {code: string}) { return <p className="error" role="alert">{t(`error.${code}`)}</p>; }

function CreateGame() {
    const [names, setNames] = useState('Alice\nBob\nCarol');
    const [variants, setVariants] = useState(defaultVariants);
    const [created, setCreated] = useState<CreatedGame | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState('');
    async function create(event: FormEvent) {
        event.preventDefault(); setBusy(true); setError('');
        try { setCreated(await request<CreatedGame>('/api/games', undefined, {players: names.split('\n'), options: variants})); }
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
            {error && <ErrorMessage code={error}/>}
            <button disabled={busy}>{t(busy ? 'status.working' : 'create.submit')}</button>
        </form>}
    </main>;
}

function Game({id, token}: {id: string; token: string}) {
    const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
    const [connectionError, setConnectionError] = useState('');
    const [actionError, setActionError] = useState<ApiError | null>(null);
    const [busy, setBusy] = useState(false);
    const [saved, setSaved] = useState(false);
    const busyRef = useRef(false);
    const sequence = useRef(0);
    const path = `/api/games/${id}`;

    useEffect(() => {
        let active = true;
        let timer: ReturnType<typeof setTimeout>;
        async function poll() {
            if (!busyRef.current) {
                const current = ++sequence.current;
                try {
                    const next = await request<Snapshot>(path, token);
                    if (active && current === sequence.current) { setSnapshot(next); setConnectionError(''); }
                } catch (failure) { if (active && current === sequence.current) setConnectionError(errorCode(failure)); }
            }
            if (active) timer = setTimeout(() => { void poll(); }, 1000);
        }
        void poll();
        return () => { active = false; clearTimeout(timer); };
    }, [path, token]);

    async function submit(command: string): Promise<boolean> {
        if (!snapshot || busyRef.current) return false;
        busyRef.current = true; ++sequence.current;
        setBusy(true); setActionError(null); setSaved(false);
        try {
            setSnapshot(await request<Snapshot>(`${path}/commands`, token, {expectedRevision: snapshot.revision, command}));
            setConnectionError(''); setSaved(true); return true;
        } catch (failure) {
            const code = errorCode(failure);
            setActionError(failure instanceof ApiError ? failure : new ApiError(code));
            if (code === 'STALE_REVISION') {
                try { setSnapshot(await request<Snapshot>(path, token)); } catch (refreshFailure) { setConnectionError(errorCode(refreshFailure)); }
            }
            return false;
        } finally { busyRef.current = false; setBusy(false); }
    }

    if (!snapshot) return <main className="landing panel"><h1>{t('app.title')}</h1>{connectionError ? <ErrorMessage code={connectionError}/> : <p>{t('game.loading')}</p>}<a href="/">{t('game.new')}</a></main>;
    return <MapSelectionProvider><GameTable snapshot={snapshot} busy={busy} saved={saved}
        submit={submit} connectionError={connectionError} actionError={actionError}
        quotePurchase={amount => request<PurchaseQuote>(`${path}/quote-token-purchase`, token, {expectedRevision: snapshot.revision, amount})}/></MapSelectionProvider>;
}

export function App() {
    const match = /^\/games\/([0-9a-f-]+)\/?$/.exec(window.location.pathname);
    if (!match) return <CreateGame/>;
    const token = new URLSearchParams(window.location.hash.slice(1)).get('token');
    return token ? <Game id={match[1]} token={token}/> : <main className="landing panel"><h1>{t('app.title')}</h1><p role="alert">{t('game.missingToken')}</p><a href="/">{t('game.new')}</a></main>;
}
