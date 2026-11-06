import {useEffect, useLayoutEffect, useRef, useState} from 'react';
import type {ReactNode} from 'react';
import type {ApiError, Snapshot, RewindTarget, TokenPurchaseQuoteResponse} from './api';
import {LabelMessage, PlayerLabel, PlayerLabels, ProvinceLabel} from './IdentityLabel';
import {label, purchasePassLabel} from './labels';
import {t} from './messages';
import {DecisionActions, PlanningActions} from './DecisionActions';
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
import {ActivityPlanner, useActivityPlanner} from './ActivityPlanner';
import type {QuotePlan, WritePlan} from './ActivityPlanner';
import {AppLogo} from './AppLogo';
import {AccountControls} from './AccountControls';
import {useActionResult} from './useActionResult';
import {HelpLink, ReferenceCatalogContext, ReferenceGameContext} from './HelpLink';
import {useStoredSetting} from './preferences';
import {AbandonmentStatus, AbandonmentTools} from './Abandonment';
import type {WriteAbandonment} from './Abandonment';
import {RewindReview} from './RewindReview';
import type {LoadRewindTargets} from './RewindReview';

export type PurchaseQuote = TokenPurchaseQuoteResponse;
export type QuotePurchase = (amount: number) => Promise<PurchaseQuote>;

const ACTIVITY_PIN_QUERY = '(min-width: 1400px)';
const MOBILE_QUERY = '(max-width: 700px)';

export function GameTable({snapshot: liveSnapshot, busy: requestBusy, saved, submit: sendCommand, connectionError, actionError, quotePurchase, editHistory, writePlan, quotePlan, loadRewindTargets, writeAbandonment, botControls}: {
    snapshot: Snapshot; busy: boolean; saved: boolean; submit: (command: string) => Promise<boolean>;
    connectionError: string; actionError: ApiError | null; quotePurchase: QuotePurchase;
    editHistory?: (fromEntry: number, lines: string[]) => Promise<boolean>;
    writePlan?: WritePlan; quotePlan?: QuotePlan; loadRewindTargets?: LoadRewindTargets; writeAbandonment?: WriteAbandonment;
    botControls?: ReactNode;
}) {
    const planner = useActivityPlanner(liveSnapshot, writePlan, quotePlan);
    const snapshot = planner.effective;
    const busy = requestBusy || planner.loading || !!liveSnapshot.planning?.saved?.armed;
    const actuallyActing = liveSnapshot.viewerId !== null && (liveSnapshot.prompt?.players.includes(liveSnapshot.viewerId) ?? false);
    const spectator = snapshot.viewerId === null;
    const [expansionPass, setExpansionPass] = useState<number | null>(null);
    const [rewindTarget, setRewindTarget] = useState<RewindTarget | null>(null);
    useEffect(() => {if (liveSnapshot.status && liveSnapshot.status !== 'ACTIVE') setRewindTarget(null);}, [liveSnapshot.status]);
    const [commandsOpen, setCommandsOpen] = useState(false);
    const [mapControlsHost, setMapControlsHost] = useState<HTMLDivElement | null>(null);
    const [scoresOpen, setScoresOpen] = useState(snapshot.game.stage === 'FINISHED');
    const finished = snapshot.game.stage === 'FINISHED';
    useEffect(() => setScoresOpen(finished), [finished]);
    const [sheetCollapsed, setSheetCollapsed] = useState(false);
    const {result, dismiss: dismissResult, submit: resolveCommand} = useActionResult(liveSnapshot, sendCommand);
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
        update();
        media.addEventListener('change', update);
        return () => media.removeEventListener('change', update);
    }, []);
    useEffect(() => {
        if (!mobile && workspace === 'players') setWorkspace('map');
    }, [mobile, setWorkspace, workspace]);
    const activityPinned = pinActivity && wideScreen;
    const actionsCollapsed = mobile && sheetCollapsed;
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
    const decisionKey = planner.open
        ? `${liveSnapshot.gameId}:${liveSnapshot.viewerId}:${liveSnapshot.game.turnNumber}:${liveSnapshot.game.stage}:${liveSnapshot.undo?.editRevision}:plan:${planner.actions.length}:${snapshot.prompt?.messageId ?? ''}`
        : decisionIdentity(snapshot);
    const decisionScroll = useRef<HTMLDivElement>(null);
    useLayoutEffect(() => {
        if (selectedAction && decisionScroll.current) decisionScroll.current.scrollTop = 0;
    }, [selectedAction]);
    // Reset before new forms activate their automatic target pickers.
    useLayoutEffect(() => {
        resetSelection();
        setExpansionPass(null);
        setSheetCollapsed(false);
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
    async function submit(command: string, confirmedPass = false) {
        if (spectator) return false;
        if (!confirmedPass && command.trim() === 'pass' && snapshot.game.stage === 'EXPANSION' &&
            snapshot.game.activity?.actor === snapshot.viewerId &&
            snapshot.game.expansionOffers.some(offer => offer.primaryAction !== 'INSPECT' &&
                offer.reachable && offer.establishesMarker && offer.blockers.length === 0 &&
                offer.resultingPool !== null)) {setExpansionPass(snapshot.revision); return false;}
        if (planner.open) {
            const appended = await planner.append(command);
            if (appended) resetSelection();
            return appended;
        }
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
    const competitionProvince = snapshot.game.activity?.competitionProvince;
    const decisionTitle = prompt === 'cathedral' && competitionProvince
        ? <LabelMessage text={t('table.prompt.cathedralProvince')}
            values={{province: <ProvinceLabel snapshot={snapshot} id={competitionProvince}/>}}/>
        : prompt ? label(prompt, 'table.prompt') : t('game.waiting');
    const decision = inactive ? t('hosted.gameStatus', {status: t(`gameStatus.${snapshot.status}`)}) : finished ? t('game.finalScores') : decisionTitle;
    const tools = <div className="table-tools">
        <details className="table-configuration"><summary>{t('table.configuration')}</summary>
            <dl className="detail-list">{snapshot.game.ruleOptions.map(option => <div key={option.key}>
                <dt>{t(`option.${option.key}`)}</dt><dd>{t(`optionValue.${option.value}`)}</dd>
            </div>)}</dl>
            {writeAbandonment && <AbandonmentTools snapshot={liveSnapshot} busy={requestBusy} write={writeAbandonment}/>}
            {botControls}
        </details>
        {!spectator && <CommandDock snapshot={snapshot} busy={busy} submit={submit} editHistory={editHistory}
            open={commandsOpen} onToggle={setCommandsOpen} loadRewindTargets={loadRewindTargets} reviewRewind={setRewindTarget}/>}
    </div>;
    return <ReferenceCatalogContext.Provider value={snapshot.game.catalog}>
        <ReferenceGameContext.Provider value={{viewerId: liveSnapshot.viewerId,
            players: liveSnapshot.game.players.every(player => player.capital !== null) ? liveSnapshot.game.players : [], turnOrder: liveSnapshot.game.turnOrder,
            orderBoxes: liveSnapshot.game.orderBoxes, commodityCards: liveSnapshot.game.commodityCards,
            commodityPayouts: liveSnapshot.game.commodityPayouts,
            unplayedCards: liveSnapshot.game.unplayedCards}}><main className={`game-shell game-table ${hasContext ? 'has-context' : ''} ${spectator ? 'spectator-table' : ''}`}>
        {expansionPass !== null && <CardInspectionDialog label={t('action.pass')} close={() => setExpansionPass(null)}>
            <h2>{t('action.pass')}</h2><p>{t('controls.confirmExpansionPass')}</p>
            {expansionPass !== snapshot.revision && <p className="blocker">{t('history.staleDraft')}</p>}
            <div className="button-row"><button type="button" disabled={busy || expansionPass !== snapshot.revision} onClick={async () => {
                if (await submit('pass', true)) setExpansionPass(null);
            }}>{t('controls.passAnyway')}</button><button type="button" className="secondary" onClick={() => setExpansionPass(null)}>{t('abandonment.back')}</button></div>
        </CardInspectionDialog>}
        {rewindTarget && <RewindReview snapshot={liveSnapshot} target={rewindTarget} busy={busy}
            close={() => setRewindTarget(null)} submit={submit}/>}
        <div className="table-overview">
            <header className="game-header">
                <div className="game-brand"><h1><a className="games-home" href="/" aria-label={t('hosted.my-games')}><AppLogo/></a></h1><div className="game-heading"><strong className="game-name" title={snapshot.name}>{snapshot.name}</strong>
                    <p>{snapshot.game.turnNumber !== null && `${t('game.turn')} ${snapshot.game.turnNumber} · `}{snapshot.game.endingTriggered && !finished && <span className="ending-status" role="status" title={t('game.endingNotice')}>{t('game.finalTurn')} · </span>}<span className="epoch-progress"><HelpLink topic="unplayed-cards" compact={false} label={t('reference.unplayedCardsTitle')}>{t('game.epochProgress', {epoch: snapshot.game.currentEpoch, cards: snapshot.game.deckRemaining})}</HelpLink></span></p></div></div>
                <div className="header-stage"><strong>{t(`stage.${snapshot.game.stage}`)}</strong>{spectator && <span className="spectator-status">{t('table.spectating')}</span>}</div>
                {me && !snapshot.accountId && <details className="private-seat-link"><summary>{t('links.yourSeat').replace('{name}', me.name)}</summary>
                    <p>{t('links.keepPrivate')}</p><a className="button secondary" download="aor-private-seat.txt"
                        href={`data:text/plain;charset=utf-8,${encodeURIComponent(`${me.name}\n${window.location.href}\n`)}`}>{t('links.save')}</a>
                </details>}
                {finished && <button type="button" className="secondary final-scores-toggle" onClick={() => setScoresOpen(true)}>{t('game.finalScores')}</button>}
                <AccountControls showGames={false}/>
            </header>
            {inactive && <p className="ending-notice" role="status">{snapshot.status === 'ABANDONED' && snapshot.events.some(event => event.kind === 'ABANDONMENT_AGREE' && event.detail === 'UNANIMOUS') ? t('abandonment.ended') : t('hosted.inactive', {status: t(`gameStatus.${snapshot.status}`).toLowerCase()})}</p>}
            {connectionError && <p className="error connection-error" role="alert">{t(`error.${connectionError}`)}</p>}
        </div>
        <div className="table-workspace" data-activity-pinned={activityPinned}>
            <section id="workspace-players" className="players-workspace" hidden={mobile && workspace !== 'players'}
                role={mobile ? 'tabpanel' : 'region'} aria-label={mobile ? undefined : t('table.players')}
                aria-labelledby={mobile ? 'tab-players' : undefined}>
                <PlayerOverview snapshot={liveSnapshot}/>
                {tools}
            </section>
            <div className="workspace-bar">
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
                <div className="map-controls" ref={setMapControlsHost} hidden={workspace !== 'map'}/>
            </div>
            <div className="workspace-main" hidden={workspace === 'activity' || workspace === 'players'}>
                <div id="workspace-map" role="tabpanel" aria-labelledby="tab-map" hidden={workspace !== 'map'}>
                    <BoardMap snapshot={liveSnapshot} busy={busy} submit={submit} controlsHost={mapControlsHost}/>
                </div>
                <div id="workspace-markets" role="tabpanel" aria-labelledby="tab-markets" hidden={workspace !== 'markets'}><Markets snapshot={liveSnapshot}/></div>
                <div id="workspace-advances" role="tabpanel" aria-labelledby="tab-advances" hidden={workspace !== 'advances'}>{snapshot.game.effects.papalCategory && <p className="papal-decree-banner" role="status">
                    <strong>{t('game.forbiddenCategory')}</strong>: {label(snapshot.game.effects.papalCategory, 'category')}
                </p>}<AdvanceTiles snapshot={snapshot}/></div>
                {inspectedAdvance && !inspectedCard && <section className="inspection-panel" aria-label={t('table.inspection')}>
                    <AdvanceDecision mode="inspection" inspectedId={inspectedAdvance} closeInspection={closeInspection} snapshot={snapshot} busy={busy} submit={submit}/>
                </section>}
            </div>
            <Activity snapshot={liveSnapshot} onInspectCard={id => setInspection({kind: 'history-card', id})}
                active={workspace === 'activity'} pinned={activityPinned} canPin={wideScreen} onTogglePin={toggleActivityPin}/>
            <div className="workspace-side">
                <aside className={`decision-panel decision ${hasContext ? 'has-context' : ''} ${actionsCollapsed ? 'sheet-collapsed' : ''}`} aria-label={t(spectator ? 'table.spectatorView' : 'table.currentDecision')}>
                    <DecisionActions pass={!spectator && !inactive && canAct && snapshot.legalChoices.commands.includes('pass') &&
                        <button type="button" className={`pass-action ${snapshot.prompt?.messageId.startsWith('prompt.purchase-') ? 'purchase-continuation' : ''}`} disabled={busy} onClick={() => { void submit('pass'); }}>{planner.open && <>{t('plan.add')} · </>}{purchasePassLabel(snapshot)}</button>}>
                    <div className={`decision-toolbar ${objectAction ? 'object-heading' : ''}`}>
                        <h2 id="decision-title" aria-live="polite" aria-atomic="true">{objectAction ? selectedName ? t(selectedName) : label(selectedAdvance, 'advance') : decision}</h2>
                        {!spectator && !inactive && !planner.open && (withdrawing || snapshot.undo?.available) && <button type="button"
                            className={`secondary undo-action ${withdrawing ? 'withdraw-bid' : ''}`} disabled={busy}
                            title={undoDescription} aria-label={withdrawing ? `${t('history.undo')}: ${undoDescription}` : undoDescription}
                            onClick={() => {
                                if (!withdrawing && snapshot.undo.capability === 'PREFIX_REWIND' && snapshot.undo.latestTarget) setRewindTarget(snapshot.undo.latestTarget);
                                else void submit(withdrawing ? 'withdraw-bid' : 'undo');
                            }}>{t('history.undo')}</button>}
                        {mobile && <button type="button" className="secondary sheet-toggle" aria-expanded={!actionsCollapsed}
                            aria-controls="decision-review decision-footer" onClick={() => setSheetCollapsed(current => !current)}>
                            {t(actionsCollapsed ? 'table.expandDecision' : 'table.minimizeDecision')}
                        </button>}
                        {objectAction && <button type="button" className="close-detail" data-close-action aria-label={t('table.close')}
                            onClick={() => {if (cardAction) setSelectedCard(''); else setSelectedAdvance('');}}>×</button>}
                    </div>
                    <div id="decision-review" className="decision-scroll" ref={decisionScroll}>
                        <div className="decision-heading">
                            {spectator && <p className="eyebrow">{t('table.publicInformationOnly')}</p>}
                            {finished && <p className="eyebrow">{t('game.finished')}</p>}
                            {!inactive && !finished && <div className={`decision-status ${actuallyActing ? 'is-ready' : 'is-waiting'}`} role="status">
                                <strong>{t(actuallyActing ? 'table.yourMove' : 'prompt.waitingFor')}</strong>
                                {!actuallyActing && liveSnapshot.prompt && <span className="waiting-players"><PlayerLabels snapshot={liveSnapshot} ids={liveSnapshot.prompt.players}/></span>}
                            </div>}
                            {!objectAction && <span className="mobile-selected">{province ? <ProvinceLabel snapshot={snapshot} id={province.id}/> : ''}</span>}
                            {snapshot.game.activity?.actor && snapshot.game.activity.decisionPlayer && snapshot.game.activity.actor !== snapshot.game.activity.decisionPlayer && <p className="interrupt-owner">{t('table.resumes')}: <PlayerLabel snapshot={snapshot} id={snapshot.game.activity.actor}/></p>}
                        </div>
                    <AbandonmentStatus snapshot={liveSnapshot} busy={requestBusy} write={writeAbandonment}/>
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
                    {!spectator && !inactive && <ActivityPlanner snapshot={liveSnapshot} planner={planner} busy={requestBusy}/>}
                    {spending && me && <p className="action-cash">{t('controls.availableCash')}: <strong>${me.cash}</strong></p>}
                    {buyingAdvances && !advanceAction && <p className="relief-rating-summary advance-relief-rating"><span>{t('controls.reliefRatingAvailable')}</span><strong>${snapshot.game.reliefRatingAvailable}</strong></p>}
                    <PlanningActions active={planner.open}>{cardAction ? <CardDecision key={selectedCard} mode="action" snapshot={snapshot} busy={busy} submit={submit}/>
                        : advanceAction ? <AdvanceDecision mode="action" snapshot={snapshot} busy={busy} submit={submit}/>
                        : province && !picker && <ProvinceActions key={province.id} snapshot={snapshot} province={province} busy={busy} submit={submit}/>}
                    {!spectator && !inactive && <Controls key={decisionKey} snapshot={snapshot} busy={busy || !!liveSnapshot.planning?.saved?.armed} submit={submit} quotePurchase={quotePurchase}/>}
                    </PlanningActions>
                    {actionError && <DiagnosticMessage failure={actionError} snapshot={snapshot}/>}
                    {(requestBusy || saved && !planner.supported && !['BIDDING', 'TOKEN_PURCHASE'].includes(snapshot.game.stage)) && <p className="save-status" aria-live="polite">{t(requestBusy ? 'status.working' : 'status.saved')}</p>}
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
    </main></ReferenceGameContext.Provider></ReferenceCatalogContext.Provider>;
}
