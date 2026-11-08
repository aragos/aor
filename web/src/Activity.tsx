import type {ReactNode} from 'react';
import {ResultNotice} from './DecisionControls';
import type {HistoryEventView, Snapshot, WarOutcomeView} from './api';
import {advanceName, label, provinceName} from './labels';
import {t} from './messages';
import {Commodity, Emblem} from './TablePanels';
import {CapitalLabel, LabelMessage} from './IdentityLabel';
import {HelpLink} from './HelpLink';
import {MiseryBox} from './MiseryBox';
import {activityRows} from './activityRows';
import {useMapSelection} from './map/MapSelection';

function eventPlayer(event: HistoryEventView, id: string) {
    return event.participants?.find(player => player.id === id) ??
        {id, name: t('game.player'), capital: null};
}

function eventName(event: HistoryEventView, id: string): string {
    const player = eventPlayer(event, id);
    return player.capital ? provinceName(player.capital) : player.name;
}

function warHeading(event: HistoryEventView, war: WarOutcomeView) {
    return war.winner ? <LabelMessage text={t('table.warWinner')} values={{capital: <CapitalLabel capital={eventPlayer(event, war.winner).capital}>{eventName(event, war.winner)}</CapitalLabel>}}/> : t('table.warTied');
}

function dieColor(event: HistoryEventView, index: number): string {
    const recorded = event.dice[index]?.color;
    if (recorded) return recorded.toLowerCase();
    if (event.competition || event.detail === 'SHORTAGE') return ['white', 'black', 'green'][index];
    return event.war && index === 1 ? 'black' : 'white';
}

function CashPayment({event, player, amount, children}: {
    event: HistoryEventView; player: string; amount: number; children?: ReactNode;
}) {
    const participant = eventPlayer(event, player);
    const cash = amount === 0 ? '$0' : `${amount < 0 ? '−' : '+'}$${Math.abs(amount)}`;
    return <span className="event-cash-change" data-cash-player={player}>
        <CapitalLabel capital={participant.capital}>{eventName(event, player)}</CapitalLabel>
        <strong>{cash}</strong>{children}
    </span>;
}

function ActivityCard({cardId, onInspectCard, inspection}: {
    cardId: string; onInspectCard?: (cardId: string) => void;
    inspection?: Snapshot['game']['cardInspections'][number];
}) {
    const name = inspection ? t(inspection.definition.messageId) : label(cardId, 'event');
    const commodities = inspection?.commodities ?? [];
    if (commodities.length) return <span className="event-card">▤ {name}{commodities.map(commodity => <HelpLink key={commodity} topic={`commodity-${commodity.toLowerCase()}`} label={`${label(commodity, 'commodity')} · ${t('table.fullPayouts')}`}/>)}</span>;
    return onInspectCard
        ? <button type="button" className="event-card" onClick={() => onInspectCard(cardId)}>▤ {name}</button>
        : <span className="event-card">▤ {name}</span>;
}

/** History navigation inspects a province without editing a prepared action or target picker. */
function ActivityProvince({id, catalog}: {id: string; catalog: Snapshot['game']['catalog']}) {
    const {setWorkspace, setInspection} = useMapSelection();
    const province = catalog?.provinces.find(candidate => candidate.id === id);
    return <span className="event-province-reference"><button type="button" className="event-entity event-province" data-activity-province={id}
        onClick={() => {setInspection({kind: 'province', id}); setWorkspace('map');}}>
        <CapitalLabel capital={province?.capital ?? null}>{provinceName(id)}</CapitalLabel>
    </button>{province?.commodities.map(commodity => <Commodity key={commodity} commodity={commodity}/>)}</span>;
}

/** Capital priority belongs to this recorded tie, independent of later rolls and assignments. */
function CapitalTieConsequence({event}: {event: HistoryEventView}) {
    if (event.kind !== 'ROLL' || event.detail !== 'CAPITAL_TIE') return null;
    const values = [...new Set(event.dice.map(die => die.value))].sort((left, right) => right - left);
    return <span className="event-detail capital-choice-order">
        {t('activity.capitalChoiceOrder')}: {values.map((value, index) => {
            const players = event.dice.filter(die => die.value === value)
                .flatMap(die => die.player ? [eventPlayer(event, die.player)] : []);
            return <span key={value}>
                {index > 0 && ' → '}{players.map((player, playerIndex) => <span key={player.id}>
                    {playerIndex > 0 && ' / '}<CapitalLabel capital={player.capital}>{eventName(event, player.id)}</CapitalLabel>
                </span>)}{players.length > 1 && ` (${t('activity.capitalTieAgain')})`}
            </span>;
        })}
    </span>;
}

function eventAmount(event: HistoryEventView): string | number | null {
    if (event.amount === null || event.protectionCash != null) return null;
    switch (event.kind) {
        case 'BID_CAPITAL': return `$${event.amount}`;
        case 'PLACE':
        case 'ESTABLISH_MARKER':
        case 'COMPETITION_RESULT': return t('activity.tokenCount', {count: event.amount});
        case 'CHOOSE_TURN_POSITION': return t('activity.position', {position: event.amount});
        default: return event.amount;
    }
}

function EventContent({event, track, onInspectCard, cardInspections, marketRoll = false, cause}: {
    cardInspections?: Snapshot['game']['cardInspections'];
    marketRoll?: boolean; cause?: HistoryEventView;
    event: HistoryEventView; track: Snapshot['game']['catalog']; onInspectCard?: (cardId: string) => void;
}) {
    const actor = event.actor ? eventPlayer(event, event.actor) : null;
    const target = event.target ? eventPlayer(event, event.target) : null;
    const cardId = event.card;
    const cardInspection = cardInspections?.find(card => card.definition.id === cardId);
    const continuesCompetition = event.competition && cause && ['ATTACK', 'PLACE'].includes(cause.kind);
    return <div className="activity-content">
        {!marketRoll && <div className="event-summary">
            {actor && !continuesCompetition && <span className="activity-actor"><CapitalLabel capital={actor.capital}><strong>{eventName(event, actor.id)}</strong></CapitalLabel></span>}
            {!continuesCompetition && <span>{t(`activity.${event.kind}`)}</span>}
            {!continuesCompetition && event.provinces.map(province => <ActivityProvince key={province} id={province} catalog={track}/>)}
            {cardId && <ActivityCard cardId={cardId} onInspectCard={onInspectCard} inspection={cardInspection}/>}
            {event.commodity && cardInspection?.definition.kind !== 'commodity' && <Commodity commodity={event.commodity}/>}
            {event.advance && <span className="event-entity">{advanceName(event.advance)}</span>}
            {eventAmount(event) !== null && (!continuesCompetition || event.amount !== cause.amount) && <strong>{eventAmount(event)}</strong>}
            {target && <span className="activity-actor">→ <CapitalLabel capital={target.capital}>{eventName(event, target.id)}</CapitalLabel></span>}
            {event.competition && <span className="event-opponents">
                {t('activity.against')} {event.competition.defenders.map((defender, index) => <span key={defender}>
                    {index > 0 && ', '}<CapitalLabel capital={eventPlayer(event, defender).capital}>{eventName(event, defender)}</CapitalLabel>
                </span>)}
            </span>}
            {event.detail && !marketRoll && event.detail !== 'COMPETITION' && <span className="event-detail">{label(event.detail)}</span>}
            {event.drawReason && <span className="event-detail">{t(`activity.drawReason.${event.drawReason}`)}</span>}
        </div>}
        {(event.competition || event.war || event.dice.length > 0 || event.marketRoll) && <div className="event-resolution">
            {event.competition && <strong>{t(event.competition.attackerWon ? 'table.competitionWon' : 'table.competitionLost')}</strong>}
            {event.war && <strong>{warHeading(event, event.war)}</strong>}
            {event.dice.map((die, index) => {
                const player = die.player ? eventPlayer(event, die.player) : null;
                const color = dieColor(event, index);
                return <span className={`event-die die-${color}`} key={index} aria-label={`${die.player ? `${eventName(event, die.player)}: ` : ''}${t(`table.die.${color}`)} ${die.value}`}>
                    {player && <Emblem player={player} size={13}/>}⚄ {t(`table.die.${color}`)} {die.value}
                </span>;
            })}
            {event.marketRoll && <span className="event-detail market-roll-meaning">
                {t('activity.marketRoll', {total: event.marketRoll.total,
                    commodities: event.marketRoll.commodities.map(commodity => label(commodity, 'commodity')).join(', '),
                    adjustment: t(`adjustment.${event.marketRoll.adjustment}`).toLowerCase()})}
                {event.marketRoll.cancelled.length > 0 && ` · ${t('activity.cancelledOpposite', {
                    commodities: event.marketRoll.cancelled.map(commodity => label(commodity, 'commodity')).join(', ')})}`}
            </span>}
        </div>}
        <CapitalTieConsequence event={event}/>
        <EventConsequences event={event} track={track} onInspectCard={onInspectCard} cardInspections={cardInspections}/>
    </div>;
}

function EventConsequences({event, track, onInspectCard, cardInspections}: {
    event: HistoryEventView; track: Snapshot['game']['catalog'];
    onInspectCard?: (cardId: string) => void; cardInspections?: Snapshot['game']['cardInspections'];
}) {
    const cardId = event.card;
    const players = [...new Set([
        ...(event.reliefPayment && event.actor ? [event.actor] : []),
        ...(event.tokenPurchases?.map(purchase => purchase.player) ?? []),
        ...(event.cashChanges?.map(change => change.player) ?? []),
        ...(event.miseryChanges?.map(change => change.player) ?? []),
        ...(event.positionChanges?.map(change => change.player) ?? []),
        ...(event.tokenChanges?.map(change => change.player) ?? []),
    ])];
    const cashChanges = event.cashChanges?.filter(change => {
        if (event.reliefPayment && change.player === event.actor && change.amount === -event.reliefPayment.cashCost) return false;
        return !event.tokenPurchases?.some(purchase => purchase.player === change.player && change.amount === -purchase.cashCost);
    });
    if (!players.length && event.protectionCash == null && event.leaderRebate == null) return null;
    return <div className={`event-consequences${event.tokenPurchases?.length ? ' token-purchase-round' : ''}`}>
        {event.protectionCash != null && <span className="event-detail">{t('activity.protectionCash')}: <strong>${event.protectionCash}</strong></span>}
        {event.leaderRebate != null && <span className="event-detail">{t('activity.leaderRebate')}: <strong>+${event.leaderRebate}</strong></span>}
        {players.map(player => <div className="event-player-effects" key={player} data-effects-player={player}>
            {event.reliefPayment && event.actor === player && <span className="event-relief-payment">
                <CashPayment event={event} player={event.actor} amount={-event.reliefPayment.cashCost}>
                    <span>+ {event.reliefPayment.ratingSpent} {t('controls.mrUsed')}</span>
                </CashPayment>
                {' · '}{t('activity.reliefRatingPool', {available: event.reliefPayment.ratingAvailableBefore})}
                {event.reliefPayment.ratingSources.length > 0 && <span>
                    {' · '}{t('activity.reliefRatingSources')}: {event.reliefPayment.ratingSources.map(source =>
                        `${advanceName(source.advance)} ${source.amount}`).join(', ')}
                </span>}
            </span>}
            {event.tokenPurchases?.filter(purchase => purchase.player === player).map(purchase => <span className="token-purchase-row" data-token-purchase-player={purchase.player} key={purchase.player}>
                <CashPayment event={event} player={purchase.player} amount={-purchase.cashCost}>
                    <span>{t('activity.tokenPurchaseBid', {amount: signedAmount(purchase.amount)})}</span>
                    <span>{t('activity.tokensReceived', {tokens: purchase.tokensReceived})}</span>
                </CashPayment>
            </span>)}
            {cashChanges?.filter(change => change.player === player).map(change => <CashPayment key={change.player} event={event} player={change.player} amount={change.amount}>
                {event.income?.filter(income => income.player === player).map(income => <span className="event-income-sources" key={income.player}>
                    {t('activity.baseIncome')}: ${income.baseIncome}
                    {income.middleClassBonus > 0 && <> · <strong>{advanceName('MIDDLE_CLASS')}</strong>: +${income.middleClassBonus}</>}
                    {income.interestProfit > 0 && <> · <strong>{advanceName('INTEREST_PROFIT')}</strong>: +${income.interestProfit}</>}
                </span>)}
            </CashPayment>)}
            {event.miseryChanges?.filter(change => change.player === player).map(change => <span className="event-misery-change" key={change.player}>
                <CapitalLabel capital={eventPlayer(event, change.player).capital}>{eventName(event, change.player)}</CapitalLabel>
                <strong>{change.spaces < 0 ? '−' : '+'}{Math.abs(change.spaces)}</strong> {t('game.misery').toLowerCase()}
                <span>(<MiseryBox value={change.fromValue} track={track?.miseryTrack}/> → <MiseryBox value={change.toValue} track={track?.miseryTrack}/>)</span>
                {change.reasons?.map((reason, index) => <span className="event-detail" key={index}>
                    {reason.advance && `${advanceName(reason.advance)} · `}
                    {reason.card && reason.card !== cardId && <ActivityCard cardId={reason.card} onInspectCard={onInspectCard} inspection={cardInspections?.find(card => card.definition.id === reason.card)}/>}
                    {t(`activity.miseryReason.${reason.kind}`)}
                    {reason.spaces != null && (change.reasons?.length ?? 0) > 1 && ` (${t('activity.ruleContribution', {spaces: signedAmount(reason.spaces)})})`}
                </span>)}
            </span>)}
            {event.positionChanges?.filter(change => change.player === player).map(change => <span className="event-position-change" key={change.player}>
                <CapitalLabel capital={eventPlayer(event, change.player).capital}>{eventName(event, change.player)}</CapitalLabel>
                <span>{t('activity.positionChange', {before: change.before, after: change.after})}</span>
            </span>)}
            {event.tokenChanges?.filter(change => change.player === player).map(change => <span className="event-token-change" key={change.player}>
                <CapitalLabel capital={eventPlayer(event, change.player).capital}>{eventName(event, change.player)}</CapitalLabel>{' '}
                <strong>{t('activity.tokenPool', {amount: signedAmount(change.poolDelta)})}</strong>
                {' · '}{t('activity.tokenStock', {amount: signedAmount(change.stockDelta)})}
            </span>)}
        </div>)}
    </div>;
}

function signedAmount(value: number): string {
    return `${value < 0 ? '−' : '+'}${Math.abs(value)}`;
}

function viewerOutcome(event: HistoryEventView, viewerId: string | null): string {
    if (viewerId === null) return '';
    if (event.competition) {
        if (event.actor === viewerId) return event.competition.attackerWon ? ' won' : ' lost';
        if (event.competition.defenders.includes(viewerId)) {
            return event.competition.attackerWon ? ' lost' : ' won';
        }
        return '';
    }
    if (event.war && ![event.war.initiator, event.war.opponent].includes(viewerId)) return '';
    if (!event.war?.winner) return '';
    return event.war.winner === viewerId ? ' won' : ' lost';
}

/** Only the submitting player's successful action supplies this receipt; history never reopens it. */
export function ActionResultNotice({event, snapshot, onDismiss}: {
    event: HistoryEventView; snapshot: Snapshot; onDismiss: () => void;
}) {
    const {competition, war} = event;
    if (!competition && !war) return null;
    const outcome = viewerOutcome(event, snapshot.viewerId);
    return <ResultNotice className={`action-result-notice ${competition ? 'competition-notice' : 'war-notice'}${outcome}`} dismiss={onDismiss}>
        <EventContent event={event} track={snapshot.game.catalog}/>
        {war && <>
            <dl className="detail-list">{[war.initiator, war.opponent].map((player, index) => {
                const die = event.dice.find(candidate => candidate.player === player);
                return <div key={player}>
                    <dt><CapitalLabel capital={eventPlayer(event, player).capital}>{eventName(event, player)}</CapitalLabel></dt>
                    <dd>{t(`table.die.${dieColor(event, index)}`)} {die?.value ?? '—'} → {t('table.warTotal')} <b>{war.totals[player]}</b></dd>
                </div>;
            })}</dl>
            <p>{war.winner ? `${t('table.warTransfers')}: ${war.requiredTransfers}` : t('table.warContinues')}</p>
        </>}
    </ResultNotice>;
}

export function Activity({snapshot, onInspectCard, active, pinned, canPin, onTogglePin}: {
    snapshot: Snapshot; onInspectCard: (cardId: string) => void;
    active: boolean; pinned: boolean; canPin: boolean; onTogglePin: () => void;
}) {
    const rows = activityRows(snapshot.events).filter(row => row[0].kind !== 'PASS');
    const pinLabel = t(pinned ? 'table.unpinActivity' : 'table.pinActivity');
    return <section id="workspace-activity" className="table-activity" hidden={!active && !pinned}
        role={pinned ? 'region' : 'tabpanel'} aria-label={pinned ? t('table.activity') : undefined}
        aria-labelledby={pinned ? undefined : 'tab-activity'}>
        {canPin && <button type="button" className="secondary activity-pin" title={pinLabel}
            aria-label={pinLabel} onClick={onTogglePin}>
            <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
                <rect x="2" y="3" width="16" height="14" rx="1"/>
                <path d="M7 3v14"/>
                <path d={pinned ? 'm11 7 3 3-3 3' : 'm14 7-3 3 3 3'}/>
            </svg>
            <span>{pinLabel}</span>
        </button>}
        <div className="activity-body">
            {rows.length === 0 && <p>{t('table.noActivity')}</p>}
            <ol className="activity-list" tabIndex={0} aria-label={t('table.recentActivity')}>{[...rows].reverse().map(([event, ...following]) => <li key={event.id} data-event-id={event.id}>
                <small>{event.turnNumber !== null ? `${t('game.turn')} ${event.turnNumber}` : t(`stage.${event.stage}`)}</small>
                <div className={event.marketRoll ? 'market-roll-round' : 'activity-group'}>
                    {event.marketRoll && <span>{t('activity.marketAdjustments')}</span>}
                    {[event, ...following].map((member, index) => <div key={member.id}
                        className={index > 0 && member.kind !== event.kind ? 'activity-following' : undefined}
                        data-activity-member={member.id} data-market-roll-id={member.marketRoll ? member.id : undefined}>
                        <EventContent event={member} track={snapshot.game.catalog} cardInspections={snapshot.game.cardInspections}
                            onInspectCard={onInspectCard} marketRoll={!!member.marketRoll} cause={index > 0 ? event : undefined}/>
                    </div>)}
                </div>
            </li>)}</ol>
        </div>
    </section>;
}
