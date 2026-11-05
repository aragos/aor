import {Fragment, useEffect, useRef, useState} from 'react';
import type {FormEvent, ReactNode} from 'react';
import {initializeApp} from 'firebase/app';
import {browserLocalPersistence, createUserWithEmailAndPassword, getAuth, GoogleAuthProvider, onIdTokenChanged,
    sendEmailVerification, sendPasswordResetEmail, setPersistence, signInWithEmailAndPassword,
    signInWithPopup, signOut} from 'firebase/auth';
import type {Auth, User} from 'firebase/auth';
import {errorCode, request, setAuthentication} from './api';
import type {Account, ApplicationConfig, CreatedGame, GamePage, GameSummary, Session} from './api';
import {ChoiceButtons} from './DecisionControls';
import {defaultVariants, validVariants, Variants} from './Variants';
import {useStoredSetting, isUndoPolicy, isGameFilter} from './preferences';
import {t} from './messages';
import {useTurnFavicon} from './favicon';
import {HelpLink} from './HelpLink';
import {AppLogo} from './AppLogo';
import {AccountContext, AccountControls} from './AccountControls';
import {startPolling} from './polling';

// Google's pre-approved light button from https://developers.google.com/identity/branding-guidelines.
const googleSignInButton = new URL('./assets/google-sign-in.png', import.meta.url).href;

const errors: Record<string, string> = {
    'auth/invalid-credential': t('hosted.the-email-or-password-was'),
    'auth/email-already-in-use': t('hosted.an-account-already-uses-that'),
    'auth/invalid-email': t('hosted.enter-a-valid-email-address'),
    'auth/weak-password': t('hosted.choose-a-stronger-password-with'),
    'auth/too-many-requests': t('hosted.too-many-attempts-please-try'),
    'auth/network-request-failed': t('hosted.cannot-reach-the-signin-service'),
    'auth/operation-not-allowed': t('hosted.sign-in-method-not-enabled'),
    'auth/popup-blocked': t('hosted.google-popup-blocked'),
    'auth/popup-closed-by-user': '',
    'auth/cancelled-popup-request': '',
    'auth/unauthorized-domain': t('hosted.sign-in-domain-not-authorized'),
    'auth/account-exists-with-different-credential': t('hosted.use-existing-sign-in-method'),
    HANDLE_UNAVAILABLE: t('hosted.that-handle-is-already-registered'),
    INVALID_ACCOUNT: t('hosted.use-a-324-character-handle'),
    GAME_CREATION_FORBIDDEN: t('hosted.an-administrator-needs-to-approve'),
    UNREGISTERED_PLAYER: t('hosted.one-of-these-players-is'),
    STALE_REVISION: t('hosted.the-game-changed-refresh-the'),
    HISTORY_RANDOMNESS_CHANGED: t('hosted.the-replacement-must-preserve-all'),
    INVALID_GAME_LOG: t('hosted.the-replacement-log-does-not'),
    UNAUTHORIZED: t('hosted.your-session-expired-sign-in'),
    EMAIL_NOT_VERIFIED: t('hosted.verify-your-email-address-before'),
    NETWORK: t('hosted.cannot-reach-the-game-server'),
    OUTCOME_UNKNOWN: t('error.OUTCOME_UNKNOWN'),
    HISTORY_IDENTITY_UNVERIFIABLE: t('error.HISTORY_IDENTITY_UNVERIFIABLE'),
};
function message(failure: unknown): string {
    const code = typeof failure === 'object' && failure !== null && 'code' in failure ? String(failure.code) : '';
    return errors[code] ?? t('hosted.the-operation-could-not-be');
}
function Problem({value}: {value: string}) {return value ? <p className="error" role="alert">{value}</p> : null;}

function SignIn({auth}: {auth: Auth}) {
    const [mode, setMode] = useState('sign-in');
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState('');
    const [notice, setNotice] = useState('');
    async function submit(event: FormEvent) {
        event.preventDefault(); setBusy(true); setError(''); setNotice('');
        try {
            if (mode === 'sign-up') {
                const result = await createUserWithEmailAndPassword(auth, email.trim(), password);
                await sendEmailVerification(result.user);
            } else await signInWithEmailAndPassword(auth, email.trim(), password);
        } catch (failure) {setError(message(failure));}
        finally {setBusy(false);}
    }
    async function reset() {
        setBusy(true); setError('');
        try {await sendPasswordResetEmail(auth, email.trim()); setNotice(t('hosted.if-that-address-has-an'));}
        catch (failure) {setError(message(failure));}
        finally {setBusy(false);}
    }
    async function google() {
        setBusy(true); setError(''); setNotice('');
        try {await signInWithPopup(auth, new GoogleAuthProvider());}
        catch (failure) {setError(message(failure));}
        finally {setBusy(false);}
    }
    return <main className="landing panel"><p className="eyebrow">{t('app.subtitle')}</p><h1><AppLogo/></h1>
        <Problem value={error}/><p role="status">{notice}</p>
        <button type="button" className="google-sign-in" disabled={busy} aria-busy={busy} onClick={() => {void google();}}>
            <img src={googleSignInButton} alt="" width={198} height={44}/><span className="sr-only">{t('hosted.sign-in-with-google')}</span>
        </button>
        <p className="auth-divider muted">{t('hosted.or-use-email')}</p>
        <ChoiceButtons label={t('hosted.account')} value={mode} onChange={setMode} disabled={busy} choices={[{value: 'sign-in', label: t('hosted.sign-in')}, {value: 'sign-up', label: t('hosted.create-account')}]}/>
        <form onSubmit={submit} className="account-form">
            <label>{t('hosted.email')}<input type="email" autoComplete="email" value={email} onChange={event => setEmail(event.target.value)} required maxLength={254}/></label>
            <label>{t('hosted.password')}<input type="password" autoComplete={mode === 'sign-up' ? 'new-password' : 'current-password'} value={password} onChange={event => setPassword(event.target.value)} required minLength={mode === 'sign-up' ? 12 : undefined} maxLength={128}/></label>
            {mode === 'sign-up' && <p className="muted small">{t('hosted.use-at-least-12-characters')}</p>}
            <button disabled={busy}>{busy ? t('hosted.working') : mode === 'sign-up' ? t('hosted.create-account') : t('hosted.sign-in')}</button>
            <button type="button" className="secondary" disabled={busy || !email.trim()} onClick={() => {void reset();}}>{t('hosted.reset-password')}</button>
        </form>
    </main>;
}

function Register({refresh}: {refresh: () => Promise<void>}) {
    const [handle, setHandle] = useState('');
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState('');
    async function register(event: FormEvent) {
        event.preventDefault(); setBusy(true); setError('');
        try {await request('/api/players/register', undefined, {handle}); await refresh();}
        catch (failure) {setError(message(failure));}
        finally {setBusy(false);}
    }
    return <main className="landing panel"><h1>{t('hosted.choose-your-player-identity')}</h1><form className="account-form" onSubmit={register}>
        <label>{t('hosted.public-handle')}<input value={handle} autoComplete="username" onChange={event => setHandle(event.target.value)} pattern="[A-Za-z][A-Za-z0-9\-]{2,23}" minLength={3} maxLength={24} required/></label>
        <p className="muted small">{t('hosted.a-unique-handle-lets-other')}</p>
        <Problem value={error}/><button disabled={busy}>{busy ? t('hosted.working') : t('hosted.register-player')}</button>
    </form></main>;
}

function VerifyEmail({user, refresh}: {user: User; refresh: () => Promise<void>}) {
    const [notice, setNotice] = useState('');
    const [error, setError] = useState('');
    const [busy, setBusy] = useState(false);
    async function action(resend: boolean) {
        setBusy(true); setError(''); setNotice('');
        try {
            if (resend) {await sendEmailVerification(user); setNotice(t('hosted.verification-email-sent'));}
            else {await user.reload(); await user.getIdToken(true); await refresh(); setNotice(t('hosted.if-verification-is-complete-your'));}
        } catch (failure) {setError(message(failure));}
        finally {setBusy(false);}
    }
    return <main className="landing panel"><h1>{t('hosted.verify-your-email')}</h1><p>{t('hosted.verificationInstructions', {email: user.email ?? ''})}</p>
        <Problem value={error}/><p role="status">{notice}</p>
        <div className="button-row"><button disabled={busy} onClick={() => {void action(false);}}>{t('hosted.ive-verified-my-email')}</button>
            <button className="secondary" disabled={busy} onClick={() => {void action(true);}}>{t('hosted.resend-email')}</button></div>
    </main>;
}

function RegisteredGameForm({me}: {me: Account}) {
    const [name, setName] = useState('');
    const [players, setPlayers] = useState<Account[]>([me]);
    const [query, setQuery] = useState('');
    const [matches, setMatches] = useState<Account[]>([]);
    const [variants, setVariants] = useStoredSetting('variants', defaultVariants, validVariants);
    const [undoPolicy, setUndoPolicy] = useStoredSetting('undo-policy', 'regular-turn', isUndoPolicy);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState('');
    useEffect(() => {
        let current = true;
        if (query.trim().length < 2) {setMatches([]); return;}
        const timer = setTimeout(() => {
            void request<Account[]>(`/api/players?q=${encodeURIComponent(query)}`).then(results => {if (current) setMatches(results);})
                .catch(failure => {if (current) setError(message(failure));});
        }, 250);
        return () => {current = false; clearTimeout(timer);};
    }, [query]);
    function addPlayer(player: Account) {
        if (busy || players.length >= 6) return;
        setPlayers(current => current.some(chosen => chosen.id === player.id) ? current : [...current, player]);
        setQuery('');
    }
    async function create(event: FormEvent) {
        event.preventDefault(); setBusy(true); setError('');
        try {
            const result = await request<CreatedGame>('/api/games', undefined, {name: name.trim(), players: players.map(player => player.id), options: variants, undoPolicy});
            window.location.assign(`/games/${result.gameId}`);
        } catch (failure) {setError(message(failure)); setBusy(false);}
    }
    return <details className="panel create-registered"><summary>{t('hosted.create-a-game')}</summary>
        {!me.mayCreateGames ? <p>{t('hosted.an-administrator-must-approve-your')}</p> : <form onSubmit={create}>
            <label htmlFor="game-name">{t('create.gameName')}</label>
            <input id="game-name" value={name} onChange={event => setName(event.target.value)} maxLength={80} required disabled={busy}/>
            <h2>{t('hosted.players')}</h2><p>{t('hosted.choose-36-registered-players-including')}</p>
            <ul className="player-directory">{players.map(player => <li key={player.id}><span><strong>@{player.handle}</strong></span>
                {player.id !== me.id && <button type="button" className="secondary" disabled={busy} onClick={() => setPlayers(players.filter(candidate => candidate.id !== player.id))}>{t('hosted.remove')}</button>}</li>)}</ul>
            <label>{t('hosted.find-a-player-by-handle')}<input type="search" value={query} maxLength={60} onChange={event => setQuery(event.target.value)}/></label>
            {query.trim().length >= 2 && <ul className="player-directory" aria-label={t('hosted.matching-players')}>{matches.filter(player => !players.some(chosen => chosen.id === player.id)).map(player => <li key={player.id}>
                <button type="button" className="player-handle" disabled={busy || players.length >= 6} onClick={() => addPlayer(player)}>@{player.handle}</button>
                <button type="button" className="secondary" disabled={busy || players.length >= 6} onClick={() => addPlayer(player)}>{t('hosted.add')}</button>
            </li>)}</ul>}
            <details className="creation-settings"><summary>{t('table.configuration')}</summary>
            <Variants value={variants} onChange={setVariants} disabled={busy}/>
            <p>{t('history.policy')} <HelpLink topic="history" label="Undo policies"/></p><ChoiceButtons label={t('history.policy')} value={undoPolicy} onChange={setUndoPolicy} disabled={busy} choices={['regular-turn', 'competitive', 'friendly-turn', 'linear-game'].map(value => ({value, label: t(`undoPolicy.${value}`)}))}/>
            {undoPolicy === 'linear-game' && <p className="small">{t('history.linearDescription')}</p>}</details>
            <Problem value={error}/><button disabled={busy || players.length < 3}>{busy ? t('hosted.creating') : t('hosted.create-game')}</button>
        </form>}
    </details>;
}

function download(name: string, value: unknown) {
    const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2) + '\n'], {type: 'application/json'}));
    const link = document.createElement('a'); link.href = url; link.download = name; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function AdminGame({game, refresh}: {game: GameSummary; refresh: () => void}) {
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState('');
    const [notice, setNotice] = useState('');
    const [replacement, setReplacement] = useState<string[] | null>(null);
    const path = `/api/admin/games/${game.id}`;
    async function run(action: () => Promise<void>) {
        setBusy(true); setError(''); setNotice('');
        try {await action();} catch (failure) {setError(message(failure));}
        finally {setBusy(false);}
    }
    async function replace(dryRun: boolean) {
        if (!replacement) return;
        await request(`${path}/log`, undefined, {expectedRevision: game.revision, entries: replacement, dryRun}, undefined, {readOnly: dryRun});
        setNotice(dryRun ? t('hosted.this-log-replays-successfully-with') : t('hosted.log-upgraded-the-previous-file'));
        if (!dryRun) {setReplacement(null); refresh();}
    }
    return <details className="admin-game"><summary className={`game-row ${game.myMove ? 'needs-action' : 'waiting'}`} aria-label={`${t('hosted.manage-game')}: ${game.players.map(player => player.name).join(', ')}`}><GameRow game={game}/></summary><p className="small">{game.id} · {t('game.revision')} {game.revision}</p>
        <div className="button-row"><button className="secondary" disabled={busy} onClick={() => {void run(async () => download(`${game.id}.json`, await request(`${path}/log`)));}}>{t('hosted.download-log')}</button>
            {['cancel', 'abandon'].map(action => <button key={action} className="secondary" disabled={busy || game.status === 'CANCELLED' || game.status === 'ABANDONED'} onClick={() => {void run(async () => {
                await request(`${path}/status`, undefined, {expectedRevision: game.revision, action}); refresh();
            });}}>{action === 'cancel' ? t('hosted.cancel-game') : t('hosted.abandon-game')}</button>)}
        </div>
        <details><summary>{t('hosted.upgrade-command-log')}</summary><p>{t('hosted.upload-an-exported-log-with')}</p>
            <label>{t('hosted.replacement-log')}<input type="file" accept=".json,application/json" disabled={busy} onChange={event => {
                const file = event.target.files?.[0]; setReplacement(null); setError('');
                if (!file) return;
                if (file.size > 8 * 1024 * 1024) {setError(t('hosted.logs-must-be-smaller-than')); return;}
                void file.text().then(text => {
                    const value: unknown = JSON.parse(text);
                    const entries = typeof value === 'object' && value !== null && 'entries' in value ? value.entries : null;
                    if (!Array.isArray(entries) || !entries.every(entry => typeof entry === 'string')) throw new Error(t('hosted.invalid-log'));
                    setReplacement(entries);
                }).catch(() => setError(t('hosted.choose-a-json-log-containing')));
            }}/></label>
            <div className="button-row"><button className="secondary" disabled={busy || !replacement} onClick={() => {void run(() => replace(true));}}>{t('hosted.validate')}</button>
                <button disabled={busy || !replacement} onClick={() => {void run(() => replace(false));}}>{t('hosted.apply-upgrade')}</button></div>
        </details>
        <details><summary>{t('hosted.permanently-purge-storage')}</summary><p>{t('hosted.this-deletes-the-game-and')}</p>
            <button className="danger" disabled={busy} onClick={() => {
                if (window.prompt(t('hosted.type-the-full-game-id')) !== game.id) return;
                void run(async () => {await request(`${path}/purge`, undefined, {expectedRevision: game.revision}); refresh();});
            }}>{t('hosted.purge-game')}</button>
        </details><Problem value={error}/><p role="status">{notice}</p>
    </details>;
}

function AdminPlayers() {
    const [players, setPlayers] = useState<Account[]>([]);
    const [error, setError] = useState('');
    const [busy, setBusy] = useState('');
    async function load() {try {setPlayers(await request<Account[]>('/api/admin/players'));} catch (failure) {setError(message(failure));}}
    async function approve(player: Account) {
        setBusy(player.id); setError('');
        try {await request(`/api/admin/players/${encodeURIComponent(player.id)}/creation`, undefined, {allowed: !player.mayCreateGames}); await load();}
        catch (failure) {setError(message(failure));} finally {setBusy('');}
    }
    return <details className="panel" onToggle={event => {if (event.currentTarget.open) void load();}}><summary>{t('hosted.player-creation-permissions')}</summary>
        <p className="small">{t('hosted.with-the-default-approvedonly-policy')}</p>
        <Problem value={error}/><ul className="player-directory">{players.map(player => <li key={player.id}><span>@{player.handle}</span>
            <button className="secondary" disabled={!!busy || player.admin} onClick={() => {void approve(player);}}>{player.mayCreateGames ? t('hosted.revoke-creation') : t('hosted.allow-creation')}</button></li>)}</ul>
    </details>;
}

function GameRow({game}: {game: GameSummary}) {
    return <><strong className="game-row-status">{game.consentResponse ? <>{t('abandonment.respond')}{game.turnRequired && ` · ${t(`gameStatus.${game.status}`)}`}</> : t(`gameStatus.${game.status}`)}</strong>
        <span className="game-row-description"><strong className="game-row-name">{game.name}</strong><span className="game-row-players">{game.players.map((player, index) => <Fragment key={player.id}>
            {index > 0 && ' · '}<span className={game.actionPlayers.includes(player.id) ? 'game-row-player needs-action' : 'game-row-player'}>
                {player.name}{game.actionPlayers.includes(player.id) && <span className="sr-only"> — {t('hosted.action-required')}</span>}
            </span></Fragment>)}</span></span>
        <span className="game-row-stage">{game.stage && t(`stage.${game.stage}`)}</span></>;
}

export function Dashboard({me}: {me: Account}) {
    const [hasTurn, setHasTurn] = useState(false);
    useTurnFavicon(hasTurn);
    useEffect(() => startPolling({intervalMs: 30000, backgroundIntervalMs: 30000,
        paused: () => false, onError: () => {},
        poll: async signal => {
            const result = await request<GamePage>('/api/games?status=my-move&limit=1', undefined, undefined, signal);
            if (!signal.aborted) setHasTurn(result.games.length > 0);
        },
    }), [me.id]);
    const [scope, setScope] = useState('mine');
    const [ownFilter, setOwnFilter] = useStoredSetting('game-filter', 'ongoing', isGameFilter);
    const [browseFilter, setBrowseFilter] = useState('ongoing');
    const [page, setPage] = useState<GamePage>({games: [], nextCursor: null});
    const [error, setError] = useState('');
    const [busy, setBusy] = useState(false);
    const [refresh, setRefresh] = useState(0);
    const [cursor, setCursor] = useState<string | null>(null);
    const all = scope === 'admin' && me.admin;
    const selectedFilter = scope === 'mine' ? ownFilter : browseFilter;
    const filter = selectedFilter === 'unavailable' && !all ? 'ongoing' : selectedFilter;
    const scopes = ['mine', 'other', ...(me.admin ? ['admin'] : [])];
    const scopeLabel = (value: string) => t(`hosted.${value === 'mine' ? 'own-games' : value === 'other' ? 'other-games' : 'administration'}`);
    function selectScope(value: string) {
        if (scope === value) return;
        setScope(value); setCursor(null); setPage({games: [], nextCursor: null}); setError(''); setBusy(true);
    }
    function selectFilter(value: string) {
        if (scope === 'mine') setOwnFilter(value); else setBrowseFilter(value);
        setCursor(null);
    }
    useEffect(() => startPolling({intervalMs: 30000, backgroundIntervalMs: 30000,
        paused: () => false, onError: failure => setError(message(failure)),
        poll: async signal => {
            setBusy(true);
            try {
                const next = await request<GamePage>(`${all ? '/api/admin/games' : '/api/games'}?scope=${scope === 'other' ? 'other' : 'own'}&status=${encodeURIComponent(filter)}${cursor ? `&after=${encodeURIComponent(cursor)}` : ''}`, undefined, undefined, signal);
                if (!signal.aborted) {setPage(next); setError('');}
            } finally {if (!signal.aborted) setBusy(false);}
        },
    }), [scope, all, filter, cursor, refresh]);
    async function exportAll() {
        setBusy(true); setError('');
        try {
            const games: unknown[] = []; let after: string | null = null;
            do {
                const next: {games: unknown[]; nextCursor: string | null} = await request(`/api/admin/logs?limit=20${after ? `&after=${encodeURIComponent(after)}` : ''}`);
                games.push(...next.games); after = next.nextCursor;
            } while (after);
            download('aor-game-logs.json', {exportedAt: new Date().toISOString(), games});
        } catch (failure) {setError(message(failure));} finally {setBusy(false);}
    }
    return <main className="portal"><header className="portal-header"><div><p className="eyebrow"><AppLogo/></p><h1>{t('hosted.games')}</h1></div><AccountControls showGames={false}/></header>
        <section className="panel games-panel">
            <div className="game-list-toolbar">
                <nav className="game-list-tabs" role="tablist" aria-label={t('hosted.games')}>
                    {scopes.map(value => <button key={value} type="button" role="tab" id={`games-tab-${value}`}
                        aria-selected={scope === value} aria-controls="games-list" tabIndex={scope === value ? 0 : -1}
                        onClick={() => selectScope(value)} onKeyDown={event => {
                            if (['ArrowRight', 'ArrowLeft', 'Home', 'End'].includes(event.key)) {
                                event.preventDefault();
                                const next = event.key === 'Home' ? scopes[0] : event.key === 'End' ? scopes[scopes.length - 1]
                                    : scopes[(scopes.indexOf(value) + (event.key === 'ArrowRight' ? 1 : scopes.length - 1)) % scopes.length];
                                selectScope(next); document.getElementById(`games-tab-${next}`)?.focus();
                            }
                        }}>{scopeLabel(value)}</button>)}
                </nav>
                <label className="game-list-filter">{t('hosted.status-filter')}
                    <select value={filter} onChange={event => selectFilter(event.target.value)}>
                        {['ongoing', ...(scope === 'mine' ? ['my-move'] : []), 'finished', 'cancelled', 'abandoned', ...(all ? ['unavailable'] : []), 'all'].map(value =>
                            <option key={value} value={value}>{t(`gameStatus.${value.replace(/-/g, '_').toUpperCase()}`)}</option>)}
                    </select>
                </label>
            </div>
            <div id="games-list" role="tabpanel" aria-labelledby={`games-tab-${scope}`} aria-busy={busy}>
                {scope === 'other' && <p className="muted spectator-list-note">{t('hosted.spectator-list-note')}</p>}
                {all && <div className="button-row"><button className="secondary" disabled={busy} onClick={() => {void exportAll();}}>{t('hosted.export-all-command-logs')}</button></div>}
                <Problem value={error}/>{busy && !page.games.length && <p role="status">{t('hosted.loading')}</p>}
                {!busy && !page.games.length && <p>{t('hosted.no-games-match-this-filter')}</p>}
                <ul className="game-directory">{page.games.map(game => <li key={game.id}>
                    {all ? <AdminGame game={game} refresh={() => setRefresh(value => value + 1)}/>
                        : <a className={`game-row ${game.myMove ? 'needs-action' : 'waiting'}`} href={`/games/${game.id}`}><GameRow game={game}/></a>}
                </li>)}</ul>
                <div className="button-row">{cursor && <button className="secondary" disabled={busy} onClick={() => setCursor(null)}>{t('hosted.first-page')}</button>}
                    {page.nextCursor && <button className="secondary" disabled={busy} onClick={() => setCursor(page.nextCursor)}>{t('hosted.next-page')}</button>}</div>
            </div>
        </section>
        <RegisteredGameForm me={me}/>
        {all && <AdminPlayers/>}
    </main>;
}

export default function HostedApp({config, game}: {config: ApplicationConfig; game: ReactNode}) {
    const [auth] = useState(() => getAuth(initializeApp(config.firebase ?? {})));
    const [user, setUser] = useState<User | null>(null);
    const [session, setSession] = useState<Session | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    const sessionRef = useRef(session);
    sessionRef.current = session;
    const identity = useRef<string | null>(null);
    const refreshSequence = useRef(0);
    async function refresh(next: User | null = auth.currentUser) {
        const sequence = ++refreshSequence.current;
        const changed = identity.current !== (next?.uid ?? null);
        identity.current = next?.uid ?? null;
        setUser(next);
        if (changed || !next) {setSession(null); setError(''); setLoading(!!next);}
        if (!next) {setLoading(false); return;}
        try {
            const value = await request<Session>('/api/me');
            if (sequence !== refreshSequence.current || auth.currentUser?.uid !== next.uid) return;
            setSession(value); setError('');
        } catch (failure) {
            if (sequence !== refreshSequence.current || auth.currentUser?.uid !== next.uid) return;
            if (['UNAUTHORIZED', 'EMAIL_NOT_VERIFIED', 'GAME_MEMBERSHIP_REQUIRED'].includes(errorCode(failure))) {
                setSession(null); setError(message(failure));
            } else if (changed || !sessionRef.current) setError(message(failure));
        } finally {
            if (sequence === refreshSequence.current) setLoading(false);
        }
    }
    useEffect(() => {
        setAuthentication(async () => auth.currentUser?.getIdToken());
        const unsubscribe = onIdTokenChanged(auth, next => {void refresh(next);});
        void setPersistence(auth, browserLocalPersistence).catch(() => { /* Firebase retains an in-memory session when storage is unavailable. */ });
        return () => {++refreshSequence.current; unsubscribe(); setAuthentication();};
    }, [auth]);
    const ready = !loading && !error && user && session?.emailVerified && session.account;
    const logOut = () => {void signOut(auth).catch(failure => setError(message(failure)));};
    return <AccountContext.Provider value={ready ? {handle: ready.handle, signOut: logOut} : null}>
        {user && !ready && <nav className="account-controls account-pending" aria-label={t('hosted.account')}><a href="/">{t('hosted.my-games')}</a>
            <button className="secondary" onClick={logOut}>{t('hosted.sign-out')}</button></nav>}
        {loading ? <main className="landing panel"><p>{t('hosted.loading-your-account')}</p></main> : !user ? <SignIn auth={auth}/>
            : error ? <main className="landing panel"><Problem value={error}/><button onClick={() => {void refresh();}}>{t('hosted.retry')}</button></main>
            : !session?.emailVerified ? <VerifyEmail user={user} refresh={() => refresh()}/>
            : !session.account ? <Register refresh={() => refresh()}/>
            : <div key={user.uid}>{game ?? <Dashboard me={session.account}/>}</div>}</AccountContext.Provider>;
}
