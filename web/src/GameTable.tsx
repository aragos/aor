import {useState} from 'react';
import type {Snapshot, TokenPurchaseQuoteResponse} from './api';
import {label, playerName} from './labels';
import {t} from './messages';
import {Controls} from './Controls';
import {CommandDock} from './CommandDock';
import {Activity} from './Activity';
import {TableIcon} from './TableIcon';
import {BoardPanel, FinalScores, TurnEffects} from './FullGamePanels';
import {AdvanceTiles, Hand, PlayerOverview, Markets} from './TablePanels';
import {AdvanceDecision, CardDecision} from './ObjectDecisions';
import {BoardMap} from './map/BoardMap';
import {useMapSelection} from './map/MapSelection';
import {ProvinceInspector} from './map/ProvinceInspector';

export type PurchaseQuote = TokenPurchaseQuoteResponse;
export type QuotePurchase = (amount: number) => Promise<PurchaseQuote>;


export function GameTable({snapshot, busy, saved, submit, connectionError, actionError, quotePurchase}: {
    snapshot: Snapshot; busy: boolean; saved: boolean; submit: (command: string) => Promise<boolean>;
    connectionError: string; actionError: string; quotePurchase: QuotePurchase;
}) {
    const [commandsOpen, setCommandsOpen] = useState(false);
    const {selected, setSelected, selectedCard, selectedAdvance, picker, workspace, setWorkspace} = useMapSelection();
    const me = snapshot.game.players.find(player => player.id === snapshot.viewerId);
    if (!me) return null;
    const canAct = snapshot.prompt?.players.includes(snapshot.viewerId) ?? false;
    const province = snapshot.game.catalog?.provinces.find(candidate => candidate.id === selected);
    const expansion = snapshot.game.stage === 'EXPANSION';
    const prompt = snapshot.prompt?.messageId.slice('prompt.'.length);
    const decision = snapshot.game.stage === 'FINISHED' ? t('game.finished') : prompt ? label(prompt, 'table.prompt') : t('game.waiting');
    return <main className="game-shell game-table">
        <header className="game-header">
            <div className="game-brand"><span className="brand-seal" aria-hidden="true">A</span><div><h1>{t('app.title')}</h1>
                <p>{snapshot.game.turnNumber !== null && `${t('game.turn')} ${snapshot.game.turnNumber} · `}{t('game.epoch')} {snapshot.game.currentEpoch}</p></div></div>
            <div className="header-stage"><strong>{t(`stage.${snapshot.game.stage}`)}</strong><span className={canAct ? 'your-move' : 'waiting-status'}>{canAct ? t('table.yourMove') : t('game.waiting')}</span></div>
            <button type="button" className="secondary commands-toggle" aria-expanded={commandsOpen} onClick={() => {setCommandsOpen(current => !current); if (!commandsOpen) setTimeout(() => document.querySelector('.command-dock')?.scrollIntoView({block: 'nearest'}), 0);}}>›_ {t('table.commands')}</button>
        </header>
        <PlayerOverview snapshot={snapshot}/>
        {connectionError && <p className="error connection-error" role="alert">{t(`error.${connectionError}`)}</p>}
        <div className="table-workspace">
            <div className="workspace-main">
                <nav className="workspace-tabs" role="tablist" aria-label={t('table.workspace')}>
                    {(['map', 'markets', 'advances'] as const).map(tab => <button type="button" key={tab} role="tab" id={`tab-${tab}`} aria-selected={workspace === tab}
                        aria-controls={`workspace-${tab}`} onClick={() => setWorkspace(tab)} onKeyDown={event => {
                            const tabs: ('map' | 'markets' | 'advances')[] = ['map', 'markets', 'advances'];
                            if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
                                event.preventDefault();
                                const next = tabs[(tabs.indexOf(tab) + (event.key === 'ArrowRight' ? 1 : 2)) % tabs.length];
                                setWorkspace(next); document.getElementById(`tab-${next}`)?.focus();
                            }
                        }}>{t(`table.${tab}`)}{tab === 'advances' && <small>{me.advances.length}</small>}</button>)}
                </nav>
                <div id="workspace-map" role="tabpanel" aria-labelledby="tab-map" hidden={workspace !== 'map'}>
                    <BoardMap snapshot={snapshot} busy={busy} submit={submit}/>
                    <BoardPanel snapshot={snapshot}/>
                </div>
                <div id="workspace-markets" role="tabpanel" aria-labelledby="tab-markets" hidden={workspace !== 'markets'}><Markets snapshot={snapshot}/></div>
                <div id="workspace-advances" role="tabpanel" aria-labelledby="tab-advances" hidden={workspace !== 'advances'}><AdvanceTiles snapshot={snapshot}/></div>
                <FinalScores snapshot={snapshot}/>
                <Activity snapshot={snapshot}/>
            </div>
            <aside className="decision-panel decision" aria-label={t('table.currentDecision')}>
                <div className="decision-heading"><p className="eyebrow">{canAct ? t('table.yourMove') : t('prompt.waitingFor')}</p>
                    {!canAct && snapshot.prompt && <strong>{snapshot.prompt.players.map(id => playerName(snapshot, id)).join(', ')}</strong>}
                    <h2>{decision}</h2>
                    {snapshot.game.activity?.actor && snapshot.game.activity.actor !== snapshot.game.activity.decisionPlayer && <p className="interrupt-owner">{t('table.resumes')}: {playerName(snapshot, snapshot.game.activity.actor)}</p>}
                </div>
                {expansion && <div className="pool-summary"><TableIcon kind="token"/><strong>{me.poolTokens}</strong><span>{t('table.poolTokens')}</span></div>}
                {selectedCard ? <CardDecision snapshot={snapshot} busy={busy} submit={submit}/>
                    : selectedAdvance ? <AdvanceDecision snapshot={snapshot} busy={busy} submit={submit}/>
                    : province && !picker && <ProvinceInspector snapshot={snapshot} province={province} busy={busy} submit={submit} select={setSelected}/>}
                <Controls snapshot={snapshot} busy={busy} submit={submit} quotePurchase={quotePurchase}/>
                {actionError && <p className="error" role="alert">{t(`error.${actionError}`)}</p>}
                <p className="save-status" aria-live="polite">{busy ? t('status.working') : saved ? t('status.saved') : '\u00a0'}</p>
                <div className="available-resources"><p className="eyebrow">{t('table.available')}</p><strong>${me.cash}</strong><small>{t('game.cash')}</small>
                    <details className="stock-details"><summary>{t('table.stock')}</summary><dl className="detail-list">
                        <div><dt>{t('game.tokens')}</dt><dd>{me.stockTokens}</dd></div><div><dt>{t('game.stockMarkers')}</dt><dd>{me.stockMarkers}</dd></div><div><dt>{t('game.boardMarkers')}</dt><dd>{me.boardMarkers}</dd></div>
                    </dl></details>
                </div>
            </aside>
        </div>
        <Hand snapshot={snapshot}/>
        <details className="table-effects"><summary>{t('game.thisTurn')} <span>{snapshot.game.deckRemaining} {t('game.cards').toLowerCase()}</span></summary><TurnEffects snapshot={snapshot}/></details>
        {snapshot.game.endingTriggered && snapshot.game.stage !== 'FINISHED' && <p className="ending-notice">{t('game.endingNotice')}</p>}
        <details className="table-configuration"><summary>{t('table.configuration')}</summary>
            <dl className="detail-list">{snapshot.game.ruleOptions.map(option => <div key={option.key}><dt>{t(`option.${option.key}`)}</dt><dd>{t(`optionValue.${option.value}`)}</dd></div>)}
                <div><dt>{t('controls.rebuiltMarkerBonus')}</dt><dd>{label(snapshot.game.rebuiltMarkerBonus)}</dd></div></dl>
            <a href="/">{t('game.new')}</a>
        </details>
        <CommandDock snapshot={snapshot} busy={busy} submit={submit} open={commandsOpen} onToggle={setCommandsOpen}/>
    </main>;
}
