import {useEffect, useLayoutEffect, useState} from 'react';
import type {ApiError, Snapshot, TokenPurchaseQuoteResponse} from './api';
import {label, playerName, provinceName} from './labels';
import {t} from './messages';
import {Controls} from './Controls';
import {CommandDock} from './CommandDock';
import {Activity, ActionResultNotice} from './Activity';
import {TableIcon} from './TableIcon';
import {FinalScores, TurnEffects} from './FullGamePanels';
import {AdvanceTiles, Emblem, Hand, PlayerOverview, Markets} from './TablePanels';
import {AdvanceDecision, CardDecision} from './ObjectDecisions';
import {BoardMap} from './map/BoardMap';
import {useMapSelection} from './map/MapSelection';
import {ProvinceActions, ProvinceInspector} from './map/ProvinceInspector';
import {decisionIdentity} from './decision';
import {DiagnosticMessage} from './DiagnosticMessage';
import {UrbanAscendancyQueue} from './UrbanAscendancyQueue';
import {HelpLink} from './HelpLink';
import {AccountControls} from './AccountControls';
import {useActionResult} from './useActionResult';

export type PurchaseQuote = TokenPurchaseQuoteResponse;
export type QuotePurchase = (amount: number) => Promise<PurchaseQuote>;

export function GameTable({snapshot, busy, saved, submit: sendCommand, connectionError, actionError, quotePurchase, editHistory}: {
    snapshot: Snapshot; busy: boolean; saved: boolean; submit: (command: string) => Promise<boolean>;
    connectionError: string; actionError: ApiError | null; quotePurchase: QuotePurchase;
    editHistory?: (fromEntry: number, lines: string[]) => Promise<boolean>;
}) {
    const [commandsOpen, setCommandsOpen] = useState(false);
    const [sheetCollapsed, setSheetCollapsed] = useState(false);
    const {result, dismiss: dismissResult, submit: resolveCommand} = useActionResult(snapshot, sendCommand);
    const {selected, selectedArea, selectedCard, setSelectedCard, selectedAdvance,
        picker, workspace, setWorkspace, resetSelection, inspection, setInspection} = useMapSelection();
    const decisionKey = decisionIdentity(snapshot);
    // Reset before new forms activate their automatic target pickers.
    useLayoutEffect(() => {
        resetSelection();
        if (document.activeElement instanceof HTMLElement &&
            document.activeElement.matches('.hand-card, .advance-tile')) document.activeElement.blur();
    }, [decisionKey, resetSelection]);
    useEffect(() => setSheetCollapsed(false), [selected, selectedArea, selectedCard, selectedAdvance]);
    const canAct = snapshot.prompt?.players.includes(snapshot.viewerId) ?? false;
    const expansion = snapshot.game.stage === 'EXPANSION';
    const expanding = canAct && expansion && snapshot.game.activity?.actor === snapshot.viewerId;
    useEffect(() => {
        if (expansion) setWorkspace('map');
    }, [expansion, expanding, setWorkspace]);
    const buyingAdvances = canAct && snapshot.game.activity?.purchaseStep === 'ADVANCES';
    useEffect(() => {
        if (buyingAdvances) setWorkspace('advances');
    }, [buyingAdvances, setWorkspace]);
    useEffect(() => {
        if (selectedCard && !snapshot.game.hand.some(card => card.reference === selectedCard)) setSelectedCard('');
    }, [selectedCard, setSelectedCard, snapshot.game.hand]);
    useEffect(() => {
        if (inspection?.kind === 'card' && !snapshot.game.hand.some(card => card.reference === inspection.id)) setInspection(null);
    }, [inspection, setInspection, snapshot.game.hand]);
    async function submit(command: string) {
        const accepted = await resolveCommand(command);
        if (accepted && (command === 'pass' || command.startsWith('play '))) {
            resetSelection();
        }
        return accepted;
    }
    const me = snapshot.game.players.find(player => player.id === snapshot.viewerId);
    const capital = me?.capital;
    const seatTitle = capital ? t(`capital.${capital}`) : me?.name;
    useEffect(() => {
        document.title = seatTitle ? `AoR · ${seatTitle}` : 'AoR';
        return () => { document.title = 'AoR'; };
    }, [seatTitle]);
    if (!me) return null;
    const expansionPlayer = expansion ? snapshot.game.players.find(player => player.id === snapshot.game.activity?.actor) : undefined;
    const military = snapshot.game.effects.military;
    const finished = snapshot.game.stage === 'FINISHED';
    const inactive = snapshot.status === 'CANCELLED' || snapshot.status === 'ABANDONED';
    const province = snapshot.game.catalog?.provinces.find(candidate => candidate.id === selected);
    const inspectedCard = inspection?.kind === 'card' || inspection?.kind === 'history-card' ? inspection.id : !inspection ? selectedCard : '';
    const inspectedAdvance = inspection?.kind === 'advance' ? inspection.id : !inspection ? selectedAdvance : '';
    const inspectedProvince = inspection?.kind === 'province'
        ? snapshot.game.catalog?.provinces.find(candidate => candidate.id === inspection.id)
        : !inspection && !picker ? province : undefined;
    const hasInspection = !!(inspectedCard || inspectedAdvance || inspectedProvince);
    const closeInspection = () => setInspection({kind: 'closed'});
    const cardAction = selectedCard && snapshot.game.cardOffers.some(offer => offer.reference === selectedCard && offer.playable);
    const advance = snapshot.game.catalog?.advances.find(candidate => candidate.id === selectedAdvance);
    const advanceAction = advance && snapshot.legalChoices.commands.includes(`buy-advance ${advance.letter}`);
    const spending = !inactive && ((snapshot.legalChoices.bidMax != null && me.misery !== null) || canAct && (!!advanceAction || buyingAdvances ||
        snapshot.game.activity?.purchaseStep === 'RELIEF' || !!snapshot.game.shippingOffer ||
        snapshot.legalChoices.commands.some(command => /^(buy-card|buy-expansion-card|cancel-adjustment|stabilize cash|patronize)( |$)/.test(command)) ||
        (!!cardAction && snapshot.game.cardOffers.some(offer => offer.reference === selectedCard && offer.protectionMax !== null))));
    const hasContext = !!(cardAction || advanceAction || (province && !picker && expanding));
    const selectedName = selectedCard ? snapshot.game.hand.find(card => card.reference === selectedCard)?.definition.messageId : null;
    const prompt = snapshot.prompt?.messageId.slice('prompt.'.length);
    const decision = inactive ? t('hosted.gameStatus', {status: t(`gameStatus.${snapshot.status}`)}) : finished ? t('game.finalScores') : prompt ? label(prompt, 'table.prompt') : t('game.waiting');
    return <main className={`game-shell game-table ${hasContext ? 'has-context' : ''}`}>
        <header className="game-header">
            <div className="game-brand"><div><h1>{t('app.title')}</h1>
                <p>{snapshot.game.turnNumber !== null && `${t('game.turn')} ${snapshot.game.turnNumber} · `}{t('game.epoch')} {snapshot.game.currentEpoch}</p></div></div>
            <div className="header-stage"><strong>{t(`stage.${snapshot.game.stage}`)}</strong>{!finished && <span className={canAct ? 'your-move' : 'waiting-status'}>{canAct ? t('table.yourMove') : t('game.waiting')}</span>}</div>
            {!snapshot.accountId && <details className="private-seat-link"><summary>{t('links.yourSeat').replace('{name}', me.name)}</summary>
                <p>{t('links.keepPrivate')}</p><a className="button secondary" download="aor-private-seat.txt"
                    href={`data:text/plain;charset=utf-8,${encodeURIComponent(`${me.name}\n${window.location.href}\n`)}`}>{t('links.save')}</a>
            </details>}
            <AccountControls/>
            <HelpLink label="AoR help"/>
        </header>
        <PlayerOverview snapshot={snapshot}/>
        {inactive && <p className="ending-notice" role="status">{t('hosted.inactive', {status: t(`gameStatus.${snapshot.status}`).toLowerCase()})}</p>}
        {connectionError && <p className="error connection-error" role="alert">{t(`error.${connectionError}`)}</p>}
        <FinalScores snapshot={snapshot}/>
        <div className="table-workspace">
            <div className="workspace-main">
                <nav className="workspace-tabs" role="tablist" aria-label={t('table.workspace')}>
                    {(['map', 'markets', 'advances'] as const).map(tab => <button type="button" key={tab} role="tab" id={`tab-${tab}`} aria-selected={workspace === tab}
                        tabIndex={workspace === tab ? 0 : -1}
                        aria-controls={`workspace-${tab}`} onClick={() => setWorkspace(tab)} onKeyDown={event => {
                            const tabs: ('map' | 'markets' | 'advances')[] = ['map', 'markets', 'advances'];
                            if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
                                event.preventDefault();
                                const next = tabs[(tabs.indexOf(tab) + (event.key === 'ArrowRight' ? 1 : 2)) % tabs.length];
                                setWorkspace(next); document.getElementById(`tab-${next}`)?.focus();
                            }
                            if (event.key === 'Home' || event.key === 'End') {
                                event.preventDefault();
                                const next = event.key === 'Home' ? 'map' : 'advances';
                                setWorkspace(next); document.getElementById(`tab-${next}`)?.focus();
                            }
                        }}>{t(`table.${tab}`)}{tab === 'advances' && <small>{me.advances.length}</small>}</button>)}
                </nav>
                <div id="workspace-map" role="tabpanel" aria-labelledby="tab-map" hidden={workspace !== 'map'}>
                    <BoardMap snapshot={snapshot} busy={busy} submit={submit}/>
                </div>
                <div id="workspace-markets" role="tabpanel" aria-labelledby="tab-markets" hidden={workspace !== 'markets'}><Markets snapshot={snapshot}/></div>
                <div id="workspace-advances" role="tabpanel" aria-labelledby="tab-advances" hidden={workspace !== 'advances'}><AdvanceTiles snapshot={snapshot}/></div>
                {hasInspection && <section className="inspection-panel" aria-label={t('table.inspection')}>
                    {inspectedCard ? <CardDecision key={inspectedCard} mode="inspection" inspectedId={inspectedCard} historical={inspection?.kind === 'history-card'} closeInspection={closeInspection} snapshot={snapshot} busy={busy} submit={submit}/>
                        : inspectedAdvance ? <AdvanceDecision mode="inspection" inspectedId={inspectedAdvance} closeInspection={closeInspection} snapshot={snapshot} busy={busy} submit={submit}/>
                        : inspectedProvince && <ProvinceInspector snapshot={snapshot} province={inspectedProvince} select={id => setInspection({kind: 'province', id})}/>}
                </section>}
            </div>
            <div className="workspace-side">
            <aside className={`decision-panel decision ${hasContext ? 'has-context' : ''} ${sheetCollapsed ? 'sheet-collapsed' : ''}`} aria-label={t('table.currentDecision')}>
                <div className="decision-heading"><p className="eyebrow">{finished ? t('game.finished') : canAct ? t('table.yourMove') : t('prompt.waitingFor')}</p>
                    {!canAct && snapshot.prompt && <strong>{snapshot.prompt.players.map(id => playerName(snapshot, id)).join(', ')}</strong>}
                    <h2 aria-live="polite" aria-atomic="true">{decision}</h2>
                    <span className="mobile-selected">{selectedName ? t(selectedName) : selectedAdvance ? label(selectedAdvance, 'advance') : province ? provinceName(province.id) : ''}</span>
                    {hasContext && <button type="button" className="secondary sheet-toggle" aria-expanded={!sheetCollapsed} aria-controls="decision-content" onClick={() => setSheetCollapsed(current => !current)}>{t(sheetCollapsed ? 'table.expandDecision' : 'table.minimizeDecision')}</button>}
                    {snapshot.game.activity?.actor && snapshot.game.activity.actor !== snapshot.game.activity.decisionPlayer && <p className="interrupt-owner">{t('table.resumes')}: {playerName(snapshot, snapshot.game.activity.actor)}</p>}
                </div>
                {expansionPlayer?.poolAvailable && <div className="pool-summary" role="status" aria-label={`${playerName(snapshot, expansionPlayer.id)} · ${t('table.remainingPoolTokens')}: ${expansionPlayer.poolTokens}`}>
                    <Emblem player={expansionPlayer} size={30}/>
                    <div className="expansion-player"><span>{playerName(snapshot, expansionPlayer.id)}</span><small>{t('table.remainingPoolTokens')}</small></div>
                    <strong><TableIcon kind="token"/>{expansionPlayer.poolTokens}</strong>
                </div>}
                {expansionPlayer && military.length > 0 && <section className="military-summary"><strong>{t('table.militaryAdvantage')}</strong>
                    {snapshot.game.players.filter(player => military.some(play => play.player === player.id)).map(player => <p key={player.id}>
                        <Emblem player={player} size={15}/> {playerName(snapshot, player.id)}: {military.filter(play => play.player === player.id).map(play => label(play.event, 'event')).join(', ')}
                    </p>)}
                    <small>{t('table.militaryMatchups')}</small>
                </section>}
                <div id="decision-content" className="decision-content">
                {result && <ActionResultNotice event={result} snapshot={snapshot} onDismiss={dismissResult}/>}
                {spending && <p className="action-cash">{t('controls.availableCash')}: <strong>${me.cash}</strong></p>}
                {cardAction ? <CardDecision key={selectedCard} mode="action" snapshot={snapshot} busy={busy} submit={submit}/>
                    : advanceAction ? <AdvanceDecision mode="action" snapshot={snapshot} busy={busy} submit={submit}/>
                    : province && !picker && <ProvinceActions snapshot={snapshot} province={province} busy={busy} submit={submit}/>}
                {snapshot.undo?.available && <button type="button" className="secondary undo-action" disabled={busy}
                    onClick={() => { void submit('undo'); }}>{t('history.undo')}</button>}
                {!inactive && <Controls key={decisionKey} snapshot={snapshot} busy={busy} submit={submit} quotePurchase={quotePurchase}/>}
                {!inactive && <UrbanAscendancyQueue snapshot={snapshot} busy={busy} submit={submit}/>}
                {actionError && <DiagnosticMessage failure={actionError} snapshot={snapshot}/>}
                <p className="save-status" aria-live="polite">{busy ? t('status.working') : saved ? t('status.saved') : '\u00a0'}</p>
                </div>
            </aside>
            <Activity snapshot={snapshot} onInspectCard={id => setInspection({kind: 'history-card', id})}/>
            </div>
        </div>
        <Hand snapshot={snapshot}/>
        <details className="table-effects"><summary>{t('game.thisTurn')} <span>{snapshot.game.deckRemaining} {t('game.cards').toLowerCase()}</span>
            {snapshot.game.activeWar && <span className="effect-badge">{t('game.activeWar')}: {playerName(snapshot, snapshot.game.activeWar.initiator)} / {playerName(snapshot, snapshot.game.activeWar.opponent)}</span>}
            {snapshot.game.effects.papalCategory && <span className="effect-badge">{t('game.forbiddenCategory')}: {label(snapshot.game.effects.papalCategory, 'category')}</span>}
        </summary><TurnEffects snapshot={snapshot}/></details>
        {snapshot.game.endingTriggered && snapshot.game.stage !== 'FINISHED' && <p className="ending-notice">{t('game.endingNotice')}</p>}
        <details className="table-configuration"><summary>{t('table.configuration')}</summary>
            <dl className="detail-list">{snapshot.game.ruleOptions.map(option => <div key={option.key}><dt>{t(`option.${option.key}`)}</dt><dd>{t(`optionValue.${option.value}`)}</dd></div>)}
</dl>
            <a href="/">{t('game.new')}</a>
            <HelpLink topic="variants" label="Variant descriptions" compact={false}/>
        </details>
        <CommandDock snapshot={snapshot} busy={busy} submit={submit} editHistory={editHistory} open={commandsOpen} onToggle={setCommandsOpen}/>
    </main>;
}
