import {useEffect, useRef, useState} from 'react';
import type {FormEvent} from 'react';
import {errorCode, request} from './api';
import type {CreatedGame, Snapshot} from './api';
import {t} from './messages';
import './app.css';

function ErrorMessage({code}: {code: string}) { return <p className="error" role="alert">{t(`error.${code}`)}</p>; }

function CreateGame() {
    const [names, setNames] = useState('Alice\nBob\nCarol');
    const [epochs, setEpochs] = useState('3');
    const [created, setCreated] = useState<CreatedGame | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState('');
    async function create(event: FormEvent) {
        event.preventDefault(); setBusy(true); setError('');
        try { setCreated(await request<CreatedGame>('/api/games', undefined, {players: names.split('\n'), epochs: Number(epochs)})); }
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
            <label htmlFor="epochs">{t('create.epochs')}</label>
            <select id="epochs" value={epochs} onChange={event => setEpochs(event.target.value)}>{[1, 2, 3].map(n => <option key={n}>{n}</option>)}</select>
            {error && <ErrorMessage code={error}/>}
            <button disabled={busy}>{t(busy ? 'status.working' : 'create.submit')}</button>
        </form>}
    </main>;
}

function Controls({snapshot, busy, submit}: {snapshot: Snapshot; busy: boolean; submit: (command: string) => Promise<boolean>}) {
    const [bid, setBid] = useState(String(snapshot.pendingBid ?? 0));
    const [epochs, setEpochs] = useState(String(snapshot.game.epochs));
    const [command, setCommand] = useState('');
    const previousEpochs = useRef(snapshot.game.epochs);
    const previousBid = useRef(snapshot.pendingBid);
    useEffect(() => {
        const previous = String(previousEpochs.current);
        setEpochs(current => current === previous ? String(snapshot.game.epochs) : current);
        previousEpochs.current = snapshot.game.epochs;
    }, [snapshot.game.epochs]);
    useEffect(() => {
        const previous = String(previousBid.current ?? 0);
        setBid(current => current === previous ? String(snapshot.pendingBid ?? 0) : current);
        previousBid.current = snapshot.pendingBid;
    }, [snapshot.pendingBid]);
    const stage = snapshot.game.stage;
    const canChoose = snapshot.legalChoices.commands.some(choice => choice.startsWith('choose-capital '));
    return <>
        {stage === 'CONFIGURATION' && snapshot.viewerId === snapshot.creatorId && <>
            <form className="inline-form" onSubmit={event => { event.preventDefault(); void submit(`set-option epochs ${epochs}`); }}>
                <label htmlFor="setup-epochs">{t('controls.epochs')}</label>
                <select id="setup-epochs" value={epochs} onChange={event => setEpochs(event.target.value)}>{[1, 2, 3].map(n => <option key={n}>{n}</option>)}</select>
                <button className="secondary" disabled={busy}>{t('controls.saveEpochs')}</button>
            </form>
            <button disabled={busy || Number(epochs) !== snapshot.game.epochs} onClick={() => { void submit('start'); }}>{t('controls.start')}</button>
        </>}
        {stage === 'BIDDING' && <form onSubmit={event => { event.preventDefault(); void submit(`bid-capital ${bid}`); }}>
            <label htmlFor="bid">{t('controls.bid')}</label>
            <div className="inline-form"><input id="bid" type="number" min={0} max={40} step={1} value={bid} onChange={event => setBid(event.target.value)} required/>
                <button disabled={busy}>{t(snapshot.pendingBid === null ? 'controls.saveBid' : 'controls.replaceBid')}</button></div>
            {snapshot.pendingBid !== null && <p className="sealed">{t('controls.pending')}: ${snapshot.pendingBid}</p>}
            <p className="muted small">{t('controls.bidHint')}</p>
        </form>}
        {canChoose && <div className="capital-choices">{snapshot.legalChoices.commands.map(choice => <button key={choice} disabled={busy} onClick={() => { void submit(choice); }}>{t(`capital.${choice.split(' ')[1]}`)}</button>)}</div>}
        {stage !== 'SETUP_COMPLETE' && <form className="console" onSubmit={async event => { event.preventDefault(); if (await submit(command)) setCommand(current => current === command ? '' : current); }}>
            <label htmlFor="command">{t('controls.command')}</label><p className="muted small">{t('controls.commandHint')}</p>
            <div className="inline-form"><input id="command" className="command-input" value={command} onChange={event => setCommand(event.target.value)} placeholder={t('controls.commandPlaceholder')} autoComplete="off" required/>
                <button className="secondary" disabled={busy || !command.trim()}>{t('controls.send')}</button></div>
            {snapshot.legalChoices.commands.length > 0 && <div className="small muted choices">{t('controls.choices')}: <code>{snapshot.legalChoices.commands.join(' · ')}</code></div>}
        </form>}
    </>;
}

function Game({id, token}: {id: string; token: string}) {
    const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
    const [connectionError, setConnectionError] = useState('');
    const [actionError, setActionError] = useState('');
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
        setBusy(true); setActionError(''); setSaved(false);
        try {
            setSnapshot(await request<Snapshot>(`${path}/commands`, token, {expectedRevision: snapshot.revision, command}));
            setConnectionError(''); setSaved(true); return true;
        } catch (failure) {
            const code = errorCode(failure);
            setActionError(code);
            if (code === 'STALE_REVISION') {
                try { setSnapshot(await request<Snapshot>(path, token)); } catch (refreshFailure) { setConnectionError(errorCode(refreshFailure)); }
            }
            return false;
        } finally { busyRef.current = false; setBusy(false); }
    }

    if (!snapshot) return <main className="landing panel"><h1>{t('app.title')}</h1>{connectionError ? <ErrorMessage code={connectionError}/> : <p>{t('game.loading')}</p>}<a href="/">{t('game.new')}</a></main>;
    const me = snapshot.game.players.find(player => player.id === snapshot.viewerId)!;
    const nameFor = (playerId: string) => snapshot.game.players.find(player => player.id === playerId)?.name ?? playerId;
    const complete = snapshot.game.stage === 'SETUP_COMPLETE';
    return <main className="game-shell">
        <header className="game-header"><div><p className="eyebrow">{t('game.seat')}: {me.name}</p><h1>{t('app.title')}</h1></div>
            <div className="header-meta"><span>{t(`stage.${snapshot.game.stage}`)}</span><small>{t('game.epochs')}: {snapshot.game.epochs} · {t('game.revision')}: {snapshot.revision}</small><a href="/">{t('game.new')}</a></div></header>
        {connectionError && <ErrorMessage code={connectionError}/>}
        <div className="game-layout"><div className="main-column">
            <section className={`panel decision ${complete ? 'complete' : ''}`}>
                <p className="eyebrow">{t(`stage.${snapshot.game.stage}`)}</p>
                <h2>{complete ? t('game.complete') : snapshot.prompt ? t(snapshot.prompt.messageId) : ''}</h2>
                {complete ? <p>{t('game.completeDetail')}</p> : snapshot.prompt && <p className="muted">{t('prompt.waitingFor')}: {snapshot.prompt.players.map(nameFor).join(', ')}</p>}
                <Controls snapshot={snapshot} busy={busy} submit={submit}/>
                {actionError && <ErrorMessage code={actionError}/>}
                <p className="save-status" aria-live="polite">{busy ? t('status.working') : saved ? t('status.saved') : '\u00a0'}</p>
            </section>
            <section className="panel"><h2 id="table-title">{t('game.table')}</h2><div className="table-scroll" tabIndex={0} role="region" aria-labelledby="table-title"><table aria-labelledby="table-title"><thead><tr>
                {['player', 'capital', 'cash', 'cards', 'bid'].map(key => <th key={key} scope="col">{t(`game.${key}`)}</th>)}
            </tr></thead><tbody>{snapshot.game.players.map(player => <tr key={player.id} className={player.id === me.id ? 'my-row' : ''}>
                <td><strong>{player.name}</strong></td><td>{player.capital ? t(`capital.${player.capital}`) : t('game.unassigned')}</td><td>${player.cash}</td><td>{player.handCount}</td>
                <td>{snapshot.game.revealedBids[player.id] !== undefined ? `$${snapshot.game.revealedBids[player.id]}` : t(snapshot.submitted.includes(player.id) ? 'game.submitted' : 'game.waiting')}</td>
            </tr>)}</tbody></table></div>
                {snapshot.game.capitalOrder.length > 0 && <p className="order"><span className="muted">{t('game.order')}: </span>{snapshot.game.capitalOrder.map(nameFor).join(' → ')}</p>}
            </section>
            <section className="panel"><h2>{t('game.resources')}</h2><dl className="resources">{([
                ['tokens', me.stockTokens], ['pool', me.poolTokens], ['stockMarkers', me.stockMarkers], ['boardMarkers', me.boardMarkers], ['misery', me.misery ?? t('game.chaos')], ['ships', me.ships],
            ] as const).map(([key, value]) => <div key={key}><dt>{t(`game.${key}`)}</dt><dd>{value}</dd></div>)}</dl></section>
        </div><aside>
            <section className="panel hand"><h2>{t('game.hand')}</h2>{snapshot.game.hand.length ? snapshot.game.hand.map(card => <article className={`history-card ${card.definition.kind}`} key={card.reference}>
                <p className="eyebrow">{t(`kind.${card.definition.kind}`)}</p><h3>{t(card.definition.messageId)}</h3>
            </article>) : <p className="muted">{t('game.noCards')}</p>}</section>
            <section className="panel history"><h2>{t('game.history')}</h2><p className="muted small">{t('game.historyHint')}</p><pre aria-label={t('game.history')}>{snapshot.history.join('\n')}</pre></section>
        </aside></div>
    </main>;
}

export function App() {
    const match = /^\/games\/([0-9a-f-]+)\/?$/.exec(window.location.pathname);
    if (!match) return <CreateGame/>;
    const token = new URLSearchParams(window.location.hash.slice(1)).get('token');
    return token ? <Game id={match[1]} token={token}/> : <main className="landing panel"><h1>{t('app.title')}</h1><p role="alert">{t('game.missingToken')}</p><a href="/">{t('game.new')}</a></main>;
}
