import {useEffect, useLayoutEffect, useRef, useState} from 'react';
import type {ApiError, Snapshot, TokenPurchaseQuoteResponse} from './api';
import {PlayerLabel, PlayerLabels, ProvinceLabel} from './IdentityLabel';
import {label, purchasePassLabel} from './labels';
import {t} from './messages';
import {DecisionActions} from './DecisionActions';
import {Controls} from './Controls';
import {CommandDock} from './CommandDock';
import {Activity, ActionResultNotice} from './Activity';
import {TableIcon} from './TableIcon';
import {FinalScores} from './FullGamePanels';
import {AdvanceTiles, PlayerOverview, Markets} from './TablePanels';
import {Hand} from './Hand';
import {CardInspectionDialog} from './CardInspectionDialog';
import {AdvanceDecision, CardDecision} from './ObjectDecisions';
import {BoardMap} from './map/BoardMap';
import {useMapSelection} from './map/MapSelection';
import type {Workspace} from './map/MapSelection';
import {ProvinceActions} from './map/ProvinceInspector';
import {maximumPartialPlacement} from './map/mapOrders';
import {decisionIdentity} from './decision';
import {DiagnosticMessage} from './DiagnosticMessage';
import {UrbanAscendancyQueue} from './UrbanAscendancyQueue';
import {AppLogo} from './AppLogo';
import {AccountControls} from './AccountControls';
import {useActionResult} from './useActionResult';
import {ReferenceCatalogContext, ReferenceOrderContext} from './HelpLink';
import {useStoredSetting} from './preferences';

export type PurchaseQuote = TokenPurchaseQuoteResponse;
export type QuotePurchase = (amount: number) => Promise<PurchaseQuote>;

const ACTIVITY_PIN_QUERY = '(min-width: 1400px)';
const MOBILE_QUERY = '(max-width: 700px)';

export function GameTable({snapshot, busy, saved, submit: sendCommand, connectionError, actionError, quotePurchase, editHistory}: {
    snapshot: Snapshot; busy: boolean; saved: boolean; submit: (command: string) => Promise<boolean>;
    connectionError: string; actionError: ApiError | null; quotePurchase: QuotePurchase;
    editHistory?: (fromEntry: number, lines: string[]) => Promise<boolean>;
}) {
    const spectator = snapshot.viewerId === null;
    const [commandsOpen, setCommandsOpen] = useState(false);
    const [scoresOpen, setScoresOpen] = useState(snapshot.game.stage === 'FINISHED');
    const finished = snapshot.game.stage === 'FINISHED';
    useEffect(() => setScoresOpen(finished), [finished]);
    const [sheetCollapsed, setSheetCollapsed] = useState(false);
    const {result, dismiss: dismissResult, submit: resolveCommand} = useActionResult(snapshot, sendCommand);
    const {selected, selectedArea, selectedCard, setSelectedCard, selectedAdvance, setSelectedAdvance,
        picker, workspace, setWorkspace, resetSelection, inspection, setInspection, selectedAction} = useMapSelection();
    const [pinActivity, setPinActivity] = useStoredSetting('pin-activity', false,
        (value): value is boolean => typeof value === 'boolean');
    const [wideScreen, setWideScreen] = useState(() => matchMedia(ACTIVITY_PIN_QUERY).matches);
    useEffect(() => {
        const media = matchMedia(ACTIVITY_PIN_QUERY);
        const update = () => setWideScreen(media.matches);
        update();
        media.addEventListener('change', update);
        return () => media.removeEventListener('change', update);
    }, []);
    const [mobile, setMobile] = useState(() => matchMedia(MOBILE_QUERY).matches);
    useEffect(() => {
        const media = matchMedia(MOBILE_QUERY);
        const update = () => setMobile(media.matches);
        media.addEventListener('change', update);
        return () => media.removeEventListener('change', update);
    }, []);
    useEffect(() => {
        if (!mobile && workspace === 'players') setWorkspace('map');
    }, [mobile, setWorkspace, workspace]);
    const activityPinned = pinActivity && wideScreen;
    const previousWorkspace = useRef<Exclude<Workspace, 'activity'>>('map');
    useLayoutEffect(() => {
        if (workspace !== 'activity') previousWorkspace.current = workspace;
        else if (activityPinned) setWorkspace(previousWorkspace.current);
    }, [activityPinned, setWorkspace, workspace]);
    const tabs: Workspace[] = [
        'map', 'markets', 'advances', ...(activityPinned ? [] : ['activity'] as const),
        ...(mobile ? ['players'] as const : []),
    ];
    function toggleActivityPin() {
        setPinActivity(!activityPinned);
        setWorkspace(activityPinned ? 'activity' : previousWorkspace.current);
    }
    const decisionKey = decisionIdentity(snapshot);
    const decisionScroll = useRef<HTMLDivElement>(null);
    useLayoutEffect(() => {
        if (selectedAction && decisionScroll.current) decisionScroll.current.scrollTop = 0;
    }, [selectedAction]);
    // Reset before new forms activate their automatic target pickers.
    useLayoutEffect(() => {
        resetSelection();
        if (document.activeElement instanceof HTMLElement &&
            document.activeElement.matches('.hand-card, .advance-tile')) document.activeElement.blur();
    }, [decisionKey, resetSelection]);
    useEffect(() => setSheetCollapsed(false), [selected, selectedArea, selectedCard, selectedAdvance, selectedAction]);
    const canAct = snapshot.viewerId !== null && (snapshot.prompt?.players.includes(snapshot.viewerId) ?? false);
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
        if (spectator) return false;
        const accepted = await resolveCommand(command);
        if (accepted && /^(pass$|play |place |move-marker )/.test(command)) {
            resetSelection();
        }
        return accepted;
    }
    const me = snapshot.game.players.find(player => player.id === snapshot.viewerId);
    const capital = me?.capital;
    const seatTitle = spectator ? t('table.spectating') : capital ? t(`capital.${capital}`) : me?.name;
    useEffect(() => {
        document.title = `AoR · ${snapshot.name}${seatTitle ? ` · ${seatTitle}` : ''}`;
        return () => { document.title = 'AoR'; };
    }, [seatTitle, snapshot.name]);
    const expansionPlayer = expansion ? snapshot.game.players.find(player => player.id === snapshot.game.activity?.actor) : undefined;
    const military = snapshot.game.effects.military;
    const inactive = snapshot.status === 'CANCELLED' || snapshot.status === 'ABANDONED';
    const province = snapshot.game.catalog?.provinces.find(candidate => candidate.id === selected);
    const expansionOffer = expanding && !picker
        ? snapshot.game.expansionOffers.find(offer => offer.destination === selected && offer.primaryAction !== 'INSPECT') : undefined;
    const reductionOffer = snapshot.game.markerReductionOffers?.find(offer => offer.command === selectedAction);
    const poolAfter = reductionOffer?.poolAfter ?? maximumPartialPlacement(expansionOffer)?.resultingPool ?? expansionOffer?.resultingPool;
    const cardAction = !spectator && selectedCard && snapshot.game.cardOffers.some(offer => offer.reference === selectedCard && offer.playable);
    const advance = snapshot.game.catalog?.advances.find(candidate => candidate.id === selectedAdvance);
    const advanceAction = !spectator && advance && snapshot.legalChoices.commands.includes(`buy-advance ${advance.letter}`);
    const cardReference = inspection?.kind === 'card' || inspection?.kind === 'history-card' ? inspection.id
        : !inspection && !cardAction ? selectedCard : '';
    const inspectedAdvance = inspection?.kind === 'advance' ? inspection.id
        : !inspection && !advanceAction ? selectedAdvance : '';
    const cardDefinition = inspection?.kind === 'history-card'
        ? snapshot.game.cardInspections?.find(card => card.definition.id === cardReference)?.definition
        : snapshot.game.hand.find(card => card.reference === cardReference)?.definition;
    const inspectedCard = cardDefinition?.kind === 'commodity' ? '' : cardReference;
    const closeInspection = () => setInspection({kind: 'closed'});
    const spending = !!me && !inactive && ((snapshot.legalChoices.bidMax != null && me.misery !== null) || canAct && (!!advanceAction || buyingAdvances ||
        snapshot.game.activity?.purchaseStep === 'RELIEF' || !!snapshot.game.shippingOffer ||
        snapshot.legalChoices.commands.some(command => /^(buy-card|cancel-adjustment|stabilize cash|patronize)( |$)/.test(command)) ||
        (!!cardAction && snapshot.game.cardOffers.some(offer => offer.reference === selectedCard && offer.protectionMax !== null))));
    const hasContext = !!(cardAction || advanceAction || selectedAction || (province && !picker && expanding));
    const objectAction = !!(cardAction || advanceAction);
    const withdrawing = snapshot.legalChoices.commands.includes('withdraw-bid');
    const undoDescription = withdrawing
        ? t(snapshot.game.stage === 'TOKEN_PURCHASE' ? 'controls.withdrawPurchase' : 'controls.withdrawBid')
        : t('history.undo');
    const selectedName = selectedCard ? snapshot.game.hand.find(card => card.reference === selectedCard)?.definition.messageId : null;
    const prompt = snapshot.prompt?.messageId.slice('prompt.'.length);
    const decision = inactive ? t('hosted.gameStatus', {status: t(`gameStatus.${snapshot.status}`)}) : finished ? t('game.finalScores') : prompt ? label(prompt, 'table.prompt') : t('game.waiting');
    const tools = <div className="table-tools">
        <details className="table-configuration"><summary>{t('table.configuration')}</summary>
            <dl className="detail-list">{snapshot.game.ruleOptions.map(option => <div key={option.key}>
                <dt>{t(`option.${option.key}`)}</dt><dd>{t(`optionValue.${option.value}`)}</dd>
            </div>)}</dl>
        </details>
        {!spectator && <CommandDock snapshot={snapshot} busy={busy} submit={submit} editHistory={editHistory}
            open={commandsOpen} onToggle={setCommandsOpen}/>}
    </div>;
    return <ReferenceCatalogContext.Provider value={snapshot.game.catalog}>
        <ReferenceOrderContext.Provider value={{viewerId: snapshot.viewerId,
            players: snapshot.game.players, turnOrder: snapshot.game.turnOrder,
            orderBoxes: snapshot.game.orderBoxes}}><main className={`game-shell game-table ${hasContext ? 'has-context' : ''} ${spectator ? 'spectator-table' : ''}`}>
        <div className="table-overview">
            <header className="game-header">
                <div className="game-brand"><h1><a className="games-home" href="/" aria-label={t('hosted.my-games')}><AppLogo/></a></h1><div className="game-heading"><strong className="game-name" title={snapshot.name}>{snapshot.name}</strong>
                    <p>{snapshot.game.turnNumber !== null && `${t('game.turn')} ${snapshot.game.turnNumber} · `}{snapshot.game.endingTriggered && !finished && <span className="ending-status" role="status" title={t('game.endingNotice')}>{t('game.finalTurn')} · </span>}<span className="epoch-progress">{t('game.epochProgress', {epoch: snapshot.game.currentEpoch, cards: snapshot.game.deckRemaining})}</span></p></div></div>
                <div className="header-stage"><strong>{t(`stage.${snapshot.game.stage}`)}</strong>{spectator && <span className="spectator-status">{t('table.spectating')}</span>}</div>
                {me && !snapshot.accountId && <details className="private-seat-link"><summary>{t('links.yourSeat').replace('{name}', me.name)}</summary>
                    <p>{t('links.keepPrivate')}</p><a className="button secondary" download="aor-private-seat.txt"
                        href={`data:text/plain;charset=utf-8,${encodeURIComponent(`${me.name}\n${window.location.href}\n`)}`}>{t('links.save')}</a>
                </details>}
                {finished && <button type="button" className="secondary final-scores-toggle" onClick={() => setScoresOpen(true)}>{t('game.finalScores')}</button>}
                <AccountControls showGames={false}/>
            </header>
            {inactive && <p className="ending-notice" role="status">{t('hosted.inactive', {status: t(`gameStatus.${snapshot.status}`).toLowerCase()})}</p>}
            {connectionError && <p className="error connection-error" role="alert">{t(`error.${connectionError}`)}</p>}
        </div>
        <div className="table-workspace" data-activity-pinned={activityPinned}>
            <section id="workspace-players" className="players-workspace" hidden={mobile && workspace !== 'players'}
                role={mobile ? 'tabpanel' : 'region'} aria-label={mobile ? undefined : t('table.players')}
                aria-labelledby={mobile ? 'tab-players' : undefined}>
                <PlayerOverview snapshot={snapshot}/>
                {tools}
            </section>
            <nav className="workspace-tabs" role="tablist" aria-label={t('table.workspace')} aria-orientation={mobile ? 'vertical' : 'horizontal'}>
                {tabs.map(tab => <button type="button" key={tab} role="tab" id={`tab-${tab}`} aria-selected={workspace === tab}
                    tabIndex={workspace === tab ? 0 : -1}
                    title={t(`table.${tab}`)} aria-label={t(`table.${tab}`)} aria-controls={`workspace-${tab}`} onClick={() => setWorkspace(tab)} onKeyDown={event => {
                        if (['ArrowRight', 'ArrowLeft', 'ArrowDown', 'ArrowUp'].includes(event.key)) {
                            event.preventDefault();
                            const next = tabs[(tabs.indexOf(tab) + (['ArrowRight', 'ArrowDown'].includes(event.key) ? 1 : tabs.length - 1)) % tabs.length];
                            setWorkspace(next); document.getElementById(`tab-${next}`)?.focus();
                        }
                        if (event.key === 'Home' || event.key === 'End') {
                            event.preventDefault();
                            const next = event.key === 'Home' ? tabs[0] : tabs[tabs.length - 1];
                            setWorkspace(next); document.getElementById(`tab-${next}`)?.focus();
                        }
                    }}><TableIcon kind={tab === 'markets' ? 'commodities' : tab}/><span>{t(`table.${tab}`)}</span>{tab === 'advances' && me && <small>{me.advances.length}</small>}</button>)}
            </nav>
            <div className="workspace-main" hidden={workspace === 'activity' || workspace === 'players'}>
                <div id="workspace-map" role="tabpanel" aria-labelledby="tab-map" hidden={workspace !== 'map'}>
                    <BoardMap snapshot={snapshot} busy={busy} submit={submit}/>
                </div>
                <div id="workspace-markets" role="tabpanel" aria-labelledby="tab-markets" hidden={workspace !== 'markets'}><Markets snapshot={snapshot}/></div>
                <div id="workspace-advances" role="tabpanel" aria-labelledby="tab-advances" hidden={workspace !== 'advances'}>{snapshot.game.effects.papalCategory && <p className="papal-decree-banner" role="status">
                    <strong>{t('game.forbiddenCategory')}</strong>: {label(snapshot.game.effects.papalCategory, 'category')}
                </p>}<AdvanceTiles snapshot={snapshot}/></div>
                {inspectedAdvance && !inspectedCard && <section className="inspection-panel" aria-label={t('table.inspection')}>
                    <AdvanceDecision mode="inspection" inspectedId={inspectedAdvance} closeInspection={closeInspection} snapshot={snapshot} busy={busy} submit={submit}/>
                </section>}
            </div>
            <Activity snapshot={snapshot} onInspectCard={id => setInspection({kind: 'history-card', id})}
                active={workspace === 'activity'} pinned={activityPinned} canPin={wideScreen} onTogglePin={toggleActivityPin}/>
            <div className="workspace-side">
                <aside className={`decision-panel decision ${hasContext ? 'has-context' : ''} ${sheetCollapsed ? 'sheet-collapsed' : ''}`} aria-label={t(spectator ? 'table.spectatorView' : 'table.currentDecision')}>
                    <DecisionActions pass={!spectator && !inactive && canAct && snapshot.legalChoices.commands.includes('pass') &&
                        <button type="button" className={`pass-action ${snapshot.prompt?.messageId.startsWith('prompt.purchase-') ? 'purchase-continuation' : ''}`} disabled={busy} onClick={() => { void submit('pass'); }}>{purchasePassLabel(snapshot)}</button>}>
                    <div className="decision-toolbar">
                        {!spectator && !inactive && (withdrawing || snapshot.undo?.available) && <button type="button"
                            className={`secondary undo-action ${withdrawing ? 'withdraw-bid' : ''}`} disabled={busy}
                            title={undoDescription} aria-label={withdrawing ? `${t('history.undo')}: ${undoDescription}` : undoDescription}
                            onClick={() => { void submit(withdrawing ? 'withdraw-bid' : 'undo'); }}>{t('history.undo')}</button>}
                    </div>
                    <div className="decision-scroll" ref={decisionScroll}>
                        <div className={`decision-heading ${objectAction ? 'object-heading' : ''}`}>
                            {spectator && <p className="eyebrow">{t('table.publicInformationOnly')}</p>}
                            {finished && <p className="eyebrow">{t('game.finished')}</p>}
                            {!inactive && !finished && <div className={`decision-status ${canAct ? 'is-ready' : 'is-waiting'}`} role="status">
                                <strong>{t(canAct ? 'table.yourMove' : 'prompt.waitingFor')}</strong>
                                {!canAct && snapshot.prompt && <PlayerLabels snapshot={snapshot} ids={snapshot.prompt.players}/>}
                            </div>}
                            <h2 id="decision-title" aria-live="polite" aria-atomic="true">{objectAction ? selectedName ? t(selectedName) : label(selectedAdvance, 'advance') : decision}</h2>
                            {!objectAction && <span className="mobile-selected">{province ? <ProvinceLabel snapshot={snapshot} id={province.id}/> : ''}</span>}
                            {hasContext && <button type="button" className="secondary sheet-toggle" aria-expanded={!sheetCollapsed}
                                aria-label={t(sheetCollapsed ? 'table.expandDecision' : 'table.minimizeDecision')}
                                aria-controls="decision-content" onClick={() => setSheetCollapsed(current => !current)}>
                                {sheetCollapsed ? '▴' : '▾'}
                            </button>}
                            {objectAction && <button type="button" className="close-detail" data-close-action aria-label={t('table.close')}
                                onClick={() => {if (cardAction) setSelectedCard(''); else setSelectedAdvance('');}}>×</button>}
                            {snapshot.game.activity?.actor && snapshot.game.activity.decisionPlayer && snapshot.game.activity.actor !== snapshot.game.activity.decisionPlayer && <p className="interrupt-owner">{t('table.resumes')}: <PlayerLabel snapshot={snapshot} id={snapshot.game.activity.actor}/></p>}
                        </div>
                    {expansionPlayer?.poolAvailable && <div className="pool-summary" role="status">
                        <PlayerLabel snapshot={snapshot} id={expansionPlayer.id}/>
                        <span>{t('table.poolTokens')}</span><strong><TableIcon kind="token"/><span data-pool-current>{expansionPlayer.poolTokens}</span>
                            {poolAfter != null && <> → <span data-pool-after>{poolAfter}</span></>}</strong>
                    </div>}
                    {expansionPlayer && military.length > 0 && <section className="military-summary"><strong>{t('table.militaryAdvantage')}</strong>
                        {snapshot.game.players.filter(player => military.some(play => play.player === player.id)).map(player => <p key={player.id}>
                            <PlayerLabel snapshot={snapshot} id={player.id}/>: {military.filter(play => play.player === player.id).map(play => label(play.event, 'event')).join(', ')}
                        </p>)}
                    </section>}
                    <div id="decision-content" className="decision-content">
                    {result && <ActionResultNotice event={result} snapshot={snapshot} onDismiss={dismissResult}/>}
                    {spending && me && <p className="action-cash">{t('controls.availableCash')}: <strong>${me.cash}</strong></p>}
                    {buyingAdvances && <p className="relief-rating-summary advance-relief-rating"><span>{t('controls.reliefRatingAvailable')}</span><strong>${snapshot.game.reliefRatingAvailable}</strong></p>}
                    {cardAction ? <CardDecision key={selectedCard} mode="action" snapshot={snapshot} busy={busy} submit={submit}/>
                        : advanceAction ? <AdvanceDecision mode="action" snapshot={snapshot} busy={busy} submit={submit}/>
                        : province && !picker && <ProvinceActions key={province.id} snapshot={snapshot} province={province} busy={busy} submit={submit}/>}
                    {!spectator && !inactive && <Controls key={decisionKey} snapshot={snapshot} busy={busy} submit={submit} quotePurchase={quotePurchase}/>}
                    {!spectator && !inactive && <UrbanAscendancyQueue snapshot={snapshot} busy={busy} submit={submit}/>}
                    {actionError && <DiagnosticMessage failure={actionError} snapshot={snapshot}/>}
                    {(busy || saved && !['BIDDING', 'TOKEN_PURCHASE'].includes(snapshot.game.stage)) && <p className="save-status" aria-live="polite">{t(busy ? 'status.working' : 'status.saved')}</p>}
                    </div>
                    </div>

                    </DecisionActions>
                </aside>
            </div>
            {!spectator && <Hand snapshot={snapshot} busy={busy}/>}
        </div>
        {scoresOpen && finished && <CardInspectionDialog label={t('game.finalScores')} close={() => setScoresOpen(false)} className="final-scores-dialog">
            <button type="button" className="close-detail" aria-label={t('table.close')} onClick={() => setScoresOpen(false)}>×</button>
            <FinalScores snapshot={snapshot}/>
        </CardInspectionDialog>}
        {inspectedCard && <CardInspectionDialog label={cardDefinition ? t(cardDefinition.messageId) : t('table.inspection')} close={closeInspection}>
            <CardDecision key={inspectedCard} mode="inspection" inspectedId={inspectedCard} historical={inspection?.kind === 'history-card'} closeInspection={closeInspection} snapshot={snapshot} busy={busy} submit={submit}/>
        </CardInspectionDialog>}
    </main></ReferenceOrderContext.Provider></ReferenceCatalogContext.Provider>;
}
