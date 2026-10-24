import {useEffect, useRef, useState} from 'react';
import type {FormEvent} from 'react';
import {errorCode, request} from './api';
import type {CreatedGame, RebuiltMarkerBonus, Snapshot} from './api';
import {t} from './messages';
import {label, playerName} from './labels';
import {GameActions} from './GameActions';
import {AdvancesPanel, BoardPanel, FinalScores, TurnEffects} from './FullGamePanels';
import './app.css';

function ErrorMessage({code}: {code: string}) { return <p className="error" role="alert">{t(`error.${code}`)}</p>; }

function bidSubmitLabel(snapshot: Snapshot): string {
    const pending = snapshot.pendingBid !== null;
    if (snapshot.game.stage === 'TOKEN_PURCHASE') {
        return t(pending ? 'controls.replacePurchase' : 'controls.savePurchase');
    }
    return t(pending ? 'controls.replaceBid' : 'controls.saveBid');
}

function decisionTitle(snapshot: Snapshot): string {
    if (snapshot.game.stage === 'FINISHED') return t('game.finished');
    if (!snapshot.prompt) return t('game.waiting');
    return label(snapshot.prompt.messageId.slice('prompt.'.length), 'prompt');
}

function saveStatus(busy: boolean, saved: boolean): string {
    if (busy) return t('status.working');
    if (saved) return t('status.saved');
    return '\u00a0';
}

function CreateGame() {
    const [names, setNames] = useState('Alice\nBob\nCarol');
    const [created, setCreated] = useState<CreatedGame | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState('');
    async function create(event: FormEvent) {
        event.preventDefault(); setBusy(true); setError('');
        try { setCreated(await request<CreatedGame>('/api/games', undefined, {players: names.split('\n')})); }
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
            {error && <ErrorMessage code={error}/>}
            <button disabled={busy}>{t(busy ? 'status.working' : 'create.submit')}</button>
        </form>}
    </main>;
}

function Controls({snapshot, busy, submit}: {snapshot: Snapshot; busy: boolean; submit: (command: string) => Promise<boolean>}) {
    const [bid, setBid] = useState(String(snapshot.pendingBid ?? 0));
    const [rebuiltMarkerBonus, setRebuiltMarkerBonus] = useState(snapshot.game.rebuiltMarkerBonus);
    const [command, setCommand] = useState('');
    const previousRebuiltMarkerBonus = useRef(snapshot.game.rebuiltMarkerBonus);
    const previousBid = useRef(snapshot.pendingBid);
    const previousStage = useRef(snapshot.game.stage);
    useEffect(() => {
        const previous = previousRebuiltMarkerBonus.current;
        setRebuiltMarkerBonus(current => current === previous ? snapshot.game.rebuiltMarkerBonus : current);
        previousRebuiltMarkerBonus.current = snapshot.game.rebuiltMarkerBonus;
    }, [snapshot.game.rebuiltMarkerBonus]);
    useEffect(() => {
        if (previousStage.current !== snapshot.game.stage) {
            setBid(String(snapshot.pendingBid ?? 0));
            setCommand('');
            previousStage.current = snapshot.game.stage;
            previousBid.current = snapshot.pendingBid;
            return;
        }
        const previous = String(previousBid.current ?? 0);
        setBid(current => current === previous ? String(snapshot.pendingBid ?? 0) : current);
        previousBid.current = snapshot.pendingBid;
    }, [snapshot.pendingBid, snapshot.game.stage]);
    const stage = snapshot.game.stage;
    const capitalChoices = snapshot.legalChoices.commands.filter(choice => choice.startsWith('choose-capital '));
    const turnChoices = snapshot.legalChoices.commands.filter(choice => choice.startsWith('choose-turn-position '));
    const me = snapshot.game.players.find(player => player.id === snapshot.viewerId)!;
    const nameFor = (playerId: string) => playerName(snapshot, playerId);
    const isTokenPurchase = stage === 'TOKEN_PURCHASE';
    const canAct = snapshot.prompt?.players.includes(snapshot.viewerId) ?? false;
    const amount = Number(bid);
    const validPurchase = bid.trim() !== '' && Number.isInteger(amount) && Math.abs(amount) <= me.cash;
    const positionLabel = (slot: number) => {
        const order = snapshot.game.provisionalTieOrder;
        if (order.length === 0) return t('controls.firstPosition');
        if (slot === 1) return t('controls.before').replace('{name}', nameFor(order[0]));
        if (slot > order.length) return t('controls.after').replace('{name}', nameFor(order[order.length - 1]));
        return t('controls.between').replace('{before}', nameFor(order[slot - 2])).replace('{after}', nameFor(order[slot - 1]));
    };
    return <>
        {stage === 'CONFIGURATION' && snapshot.viewerId === snapshot.creatorId && <>
            <div className="rule-options">
                <label htmlFor="rule-preset">{t('controls.preset')}</label>
                <select id="rule-preset" defaultValue="" disabled={busy} onChange={event => {
                    const value = event.target.value;
                    event.target.value = '';
                    if (value) void submit(`set-option preset ${value}`);
                }}>
                    <option value="">{t('controls.choosePreset')}</option>
                    {['ah1996', 'second-edition', 'tournament'].map(value => <option key={value} value={value}>{t(`preset.${value}`)}</option>)}
                </select>
                {snapshot.game.ruleOptions.map(option => <label key={option.key}>
                    {t(`option.${option.key}`)}
                    <select value={option.value} disabled={busy} onChange={event => { void submit(`set-option ${option.key} ${event.target.value}`); }}>
                        {option.choices.map(value => <option key={value} value={value}>{t(`optionValue.${value}`)}</option>)}
                    </select>
                </label>)}
            </div>
            <form onSubmit={event => { event.preventDefault(); void submit(`set-option rebuilt-marker-bonus ${rebuiltMarkerBonus.toLowerCase()}`); }}>
                <label htmlFor="setup-rebuilt-marker-bonus">{t('controls.rebuiltMarkerBonus')}</label>
                <p id="rebuilt-marker-bonus-hint" className="muted small">{t('controls.rebuiltMarkerBonusHint')}</p>
                <div className="inline-form">
                    <select id="setup-rebuilt-marker-bonus" value={rebuiltMarkerBonus} onChange={event => setRebuiltMarkerBonus(event.target.value as RebuiltMarkerBonus)} aria-describedby="rebuilt-marker-bonus-hint">
                        {(['EXCLUDE', 'INCLUDE'] as const).map(rule => <option key={rule} value={rule}>{t(`rebuiltMarkerBonus.option.${rule}`)}</option>)}
                    </select>
                    <button className="secondary" disabled={busy}>{t('controls.saveRebuiltMarkerBonus')}</button>
                </div>
            </form>
            <button disabled={busy || rebuiltMarkerBonus !== snapshot.game.rebuiltMarkerBonus} onClick={() => { void submit('start'); }}>{t('controls.start')}</button>
        </>}
        {(stage === 'BIDDING' || isTokenPurchase) && me.misery !== null && <form onSubmit={event => { event.preventDefault(); void submit(`${isTokenPurchase ? 'buy-tokens' : 'bid-capital'} ${bid}`); }}>
            <label htmlFor="bid">{t(isTokenPurchase ? 'controls.tokenPurchase' : 'controls.bid')}</label>
            <div className="inline-form"><input id="bid" type="number" min={snapshot.legalChoices.bidMin ?? 0} max={snapshot.legalChoices.bidMax ?? 40} step={1} value={bid} onChange={event => setBid(event.target.value)} aria-describedby={isTokenPurchase ? 'purchase-hint purchase-preview' : 'bid-hint'} required/>
                <button disabled={busy || (isTokenPurchase && !validPurchase)}>
                    {bidSubmitLabel(snapshot)}
                </button>
            </div>
            {snapshot.pendingBid !== null && <p className="sealed">{t(isTokenPurchase ? 'controls.pendingPurchase' : 'controls.pending')}: {isTokenPurchase ? snapshot.pendingBid : `$${snapshot.pendingBid}`}</p>}
            <p id={isTokenPurchase ? 'purchase-hint' : 'bid-hint'} className="muted small">{t(isTokenPurchase ? 'controls.purchaseHint' : 'controls.bidHint')}</p>
            {isTokenPurchase && <div id="purchase-preview" className="purchase-preview" aria-live="polite">
                {validPurchase ? <dl className="resources">
                    <div><dt>{t('controls.purchaseCost')}</dt><dd>${Math.abs(amount)}</dd></div>
                    <div><dt>{t('controls.purchaseTokens')}</dt><dd>{Math.max(0, Math.min(me.stockTokens, amount))}</dd></div>
                    <div><dt>{t('controls.remainingCash')}</dt><dd>${me.cash - Math.abs(amount)}</dd></div>
                </dl> : <p className="small">{t('controls.purchaseRange').split('{cash}').join(String(me.cash))}</p>}
            </div>}
        </form>}
        {capitalChoices.length > 0 && <div className="capital-choices">{capitalChoices.map(choice => <button key={choice} disabled={busy} onClick={() => { void submit(choice); }}>{t(`capital.${choice.split(' ')[1]}`)}</button>)}</div>}
        {stage === 'TURN_ORDER_TIE' && <div className="turn-tie">
            <p className="muted">{t('controls.provisionalOrder')}: {snapshot.game.provisionalTieOrder.length ? snapshot.game.provisionalTieOrder.map(nameFor).join(' → ') : t('controls.noPositions')}</p>
            {turnChoices.length > 0 && <div className="capital-choices">{turnChoices.map(choice => <button key={choice} disabled={busy} onClick={() => { void submit(choice); }}>{positionLabel(Number(choice.split(' ')[1]))}</button>)}</div>}
        </div>}
        {(canAct || stage === 'SETUP_RETURN') && <GameActions key={`${snapshot.game.turnNumber}-${stage}-${snapshot.game.activity?.startEntry}-${snapshot.game.activity?.choice}-${snapshot.game.activity?.purchaseStep}-${me.advances.length}-${snapshot.game.hand.length}`} snapshot={snapshot} busy={busy} submit={submit}/>}
        {stage !== 'FINISHED' && <form className="console" onSubmit={async event => { event.preventDefault(); if (await submit(command)) setCommand(current => current === command ? '' : current); }}>
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
    const nameFor = (playerId: string) => playerName(snapshot, playerId);
    const finished = snapshot.game.stage === 'FINISHED';
    const sealedStatus = (playerId: string, stage: string) => snapshot.game.stage === stage
        ? t(snapshot.submitted.includes(playerId) ? 'game.submitted' : 'game.waiting') : '—';
    return <main className="game-shell">
        <header className="game-header"><div><p className="eyebrow">{t('game.seat')}: {me.name}</p><h1>{t('app.title')}</h1></div>
            <div className="header-meta"><span>{snapshot.game.turnNumber !== null && `${t('game.turn')} ${snapshot.game.turnNumber} · `}{t(`stage.${snapshot.game.stage}`)}</span><small>{t('game.epoch')}: {snapshot.game.currentEpoch}/3 · {t('game.revision')}: {snapshot.revision}</small><a href="/">{t('game.new')}</a></div></header>
        {connectionError && <ErrorMessage code={connectionError}/>}
        <div className="game-layout"><div className="main-column">
            <FinalScores snapshot={snapshot}/>
            <section className={`panel decision ${finished ? 'complete' : ''}`}>
                <p className="eyebrow">{snapshot.game.turnNumber !== null && `${t('game.turn')} ${snapshot.game.turnNumber} · `}{t(`stage.${snapshot.game.stage}`)}</p>
                <h2>{decisionTitle(snapshot)}</h2>
                {snapshot.prompt && <p className="muted">{t('prompt.waitingFor')}: {snapshot.prompt.players.map(nameFor).join(', ')}</p>}
                {snapshot.game.activity?.actor && snapshot.game.activity.actor !== snapshot.game.activity.decisionPlayer && <p className="muted small">{t('game.activityOwner')}: {nameFor(snapshot.game.activity.actor)}</p>}
                <p className="muted small">{t(`rebuiltMarkerBonus.${snapshot.game.rebuiltMarkerBonus}`)}</p>
                <Controls snapshot={snapshot} busy={busy} submit={submit}/>
                {actionError && <ErrorMessage code={actionError}/>}
                <p className="save-status" aria-live="polite">{saveStatus(busy, saved)}</p>
            </section>
            <section className="panel"><h2 id="table-title">{t('game.table')}</h2><div className="table-scroll" tabIndex={0} role="region" aria-labelledby="table-title"><table aria-labelledby="table-title"><thead><tr>
                {['player', 'capital', 'cash', 'pool', 'tokens', 'cards', 'misery', 'advances', 'bid', 'tokenPurchase'].map(key => <th key={key} scope="col">{t(`game.${key}`)}</th>)}
            </tr></thead><tbody>{snapshot.game.players.map(player => <tr key={player.id} className={player.id === me.id ? 'my-row' : ''}>
                <td><strong>{player.name}</strong></td><td>{player.capital ? t(`capital.${player.capital}`) : t('game.unassigned')}</td><td>${player.cash}</td><td>{player.poolTokens}</td><td>{player.stockTokens}</td><td>{player.handCount}</td>
                <td>{player.misery ?? t('game.chaos')}</td><td>{player.advances.length}/26</td>
                <td>{snapshot.game.revealedBids[player.id] !== undefined ? `$${snapshot.game.revealedBids[player.id]}` : sealedStatus(player.id, 'BIDDING')}</td>
                <td>{snapshot.game.revealedTokenPurchases[player.id] !== undefined ? snapshot.game.revealedTokenPurchases[player.id] : sealedStatus(player.id, 'TOKEN_PURCHASE')}</td>
            </tr>)}</tbody></table></div>
                {snapshot.game.capitalOrder.length > 0 && <p className="order"><span className="muted">{t('game.order')}: </span>{snapshot.game.capitalOrder.map(nameFor).join(' → ')}</p>}
            </section>
            {snapshot.game.turnOrder.length > 0 && <section className="panel turn-order">
                <h2>{t('game.turnOrder').replace('{turn}', String(snapshot.game.turnNumber))}</h2>
                <ol>{snapshot.game.turnOrder.map(playerId => <li key={playerId}>
                    <strong>{nameFor(playerId)}</strong><span>{t('game.orderBox')} {snapshot.game.orderBoxes[playerId]}</span>
                </li>)}</ol>
            </section>}
            <section className="panel"><h2>{t('game.resources')}</h2><dl className="resources">{([
                ['tokens', me.stockTokens], ['pool', me.poolTokens], ['stockMarkers', me.stockMarkers], ['boardMarkers', me.boardMarkers], ['misery', me.misery ?? t('game.chaos')], ['ships', me.ships.fleet === 'NONE' ? label(me.ships.fleet, 'fleet') : `${label(me.ships.fleet, 'fleet')} ${me.ships.level}`],
            ] as const).map(([key, value]) => <div key={key}><dt>{t(`game.${key}`)}</dt><dd>{value}</dd></div>)}</dl></section>
            <BoardPanel snapshot={snapshot}/>
            <AdvancesPanel snapshot={snapshot}/>
        </div><aside>
            <section className="panel hand"><h2>{t('game.hand')}</h2>{snapshot.game.hand.length ? snapshot.game.hand.map(card => <article className={`history-card ${card.definition.kind}`} key={card.reference}>
                <p className="eyebrow">{t(`kind.${card.definition.kind}`)}</p><h3>{t(card.definition.messageId)}</h3>
                <code>{card.reference}</code>{snapshot.game.burdenReferences.includes(card.reference) && <p className="burden-badge">{t('game.burden')}</p>}
            </article>) : <p className="muted">{t('game.noCards')}</p>}</section>
            <TurnEffects snapshot={snapshot}/>
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
