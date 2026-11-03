import type {ReactNode} from 'react';
import {ResultNotice} from './DecisionControls';
import type {HistoryEventView, Snapshot, WarOutcomeView} from './api';
import {advanceName, label, provinceName} from './labels';
import {t} from './messages';
import {Commodity, Emblem} from './TablePanels';
import {CapitalLabel, LabelMessage} from './IdentityLabel';
import {HelpLink} from './HelpLink';
import {MiseryBox} from './MiseryBox';

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

function ActivityCard({cardId, onInspectCard, commodities = []}: {cardId: string; onInspectCard?: (cardId: string) => void; commodities?: string[]}) {
    if (commodities.length) return <span className="event-card">▤ {label(cardId, 'event')}{commodities.map(commodity => <HelpLink key={commodity} topic={`commodity-${commodity.toLowerCase()}`} label={`${label(commodity, 'commodity')} · ${t('table.fullPayouts')}`}/>)}</span>;
    return onInspectCard
        ? <button type="button" className="event-card" onClick={() => onInspectCard(cardId)}>▤ {label(cardId, 'event')}</button>
        : <span className="event-card">▤ {label(cardId, 'event')}</span>;
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

function EventContent({event, track, onInspectCard, cardInspections}: {
    cardInspections?: Snapshot['game']['cardInspections'];
    event: HistoryEventView; track: Snapshot['game']['catalog']; onInspectCard?: (cardId: string) => void;
}) {
    const actor = event.actor ? eventPlayer(event, event.actor) : null;
    const target = event.target ? eventPlayer(event, event.target) : null;
    const cardId = event.card;
    const cashChanges = event.cashChanges?.filter(change => {
        if (event.reliefPayment && change.player === event.actor && change.amount === -event.reliefPayment.cashCost) return false;
        return !event.tokenPurchases?.some(purchase => purchase.player === change.player && change.amount === -purchase.cashCost);
    });
    return <span className="activity-content">
        {actor && <span className="activity-actor"><CapitalLabel capital={actor.capital}><strong>{eventName(event, actor.id)}</strong></CapitalLabel></span>}
        <span>{t(`activity.${event.kind}`)}</span>
        {event.competition && <strong>{t(event.competition.attackerWon ? 'table.competitionWon' : 'table.competitionLost')}</strong>}
        {event.war && <strong>{warHeading(event, event.war)}</strong>}
        {event.provinces.map(province => <span className="event-entity" key={province}><CapitalLabel capital={track?.provinces.find(candidate => candidate.id === province)?.capital ?? null}>{provinceName(province)}</CapitalLabel></span>)}
        {cardId && <ActivityCard cardId={cardId} onInspectCard={onInspectCard} commodities={cardInspections?.find(card => card.definition.id === cardId)?.commodities}/>}
        {event.commodity && <Commodity commodity={event.commodity}/>}
        {event.advance && <span className="event-entity">{advanceName(event.advance)}</span>}
        {event.amount !== null && event.protectionCash == null && <strong>{event.kind === 'BID_CAPITAL' ? '$' : ''}{event.amount}</strong>}
        {event.protectionCash != null && <span className="event-detail">{t('activity.protectionCash')}: <strong>${event.protectionCash}</strong></span>}
        {event.leaderRebate != null && <span className="event-detail">{t('activity.leaderRebate')}: <strong>+${event.leaderRebate}</strong></span>}
        {target && <span className="activity-actor">→ <CapitalLabel capital={target.capital}>{eventName(event, target.id)}</CapitalLabel></span>}
        {event.detail && <span className="event-detail">{label(event.detail)}</span>}
        {event.drawReason && <span className="event-detail">{t(`activity.drawReason.${event.drawReason}`)}</span>}
        {event.dice.map((die, index) => {
            const player = die.player ? eventPlayer(event, die.player) : null;
            const color = dieColor(event, index);
            return <span className={`event-die die-${color}`} key={index} aria-label={`${die.player ? `${eventName(event, die.player)}: ` : ''}${t(`table.die.${color}`)} ${die.value}`}>
                {player && <Emblem player={player} size={13}/>}⚄ {t(`table.die.${color}`)} {die.value}
            </span>;
        })}
        <CapitalTieConsequence event={event}/>
        {event.marketRoll && <span className="event-detail market-roll-meaning">
            {t('activity.marketRoll', {total: event.marketRoll.total,
                commodities: event.marketRoll.commodities.map(commodity => label(commodity, 'commodity')).join(', '),
                adjustment: t(`adjustment.${event.marketRoll.adjustment}`).toLowerCase()})}
            {event.marketRoll.cancelled.length > 0 && ` · ${t('activity.cancelledOpposite', {
                commodities: event.marketRoll.cancelled.map(commodity => label(commodity, 'commodity')).join(', ')})}`}
        </span>}
        {event.reliefPayment && event.actor && <span className="event-relief-payment">
            <CashPayment event={event} player={event.actor} amount={-event.reliefPayment.cashCost}>
                <span>+ {event.reliefPayment.ratingSpent} {t('controls.mrUsed')}</span>
            </CashPayment>
            {' · '}{t('activity.reliefRatingPool', {available: event.reliefPayment.ratingAvailableBefore})}
            {event.reliefPayment.ratingSources.length > 0 && <span>
                {' · '}{t('activity.reliefRatingSources')}: {event.reliefPayment.ratingSources.map(source =>
                    `${advanceName(source.advance)} ${source.amount}`).join(', ')}
            </span>}
        </span>}
        {event.tokenPurchases && event.tokenPurchases.length > 0 && <span className="token-purchase-round">
            {event.tokenPurchases.map(purchase => <span className="token-purchase-row" data-token-purchase-player={purchase.player} key={purchase.player}>
                <CashPayment event={event} player={purchase.player} amount={-purchase.cashCost}>
                    <span>{t('activity.tokenPurchaseBid', {amount: signedAmount(purchase.amount)})}</span>
                    <span>{t('activity.tokensReceived', {tokens: purchase.tokensReceived})}</span>
                </CashPayment>{' '}
            </span>)}
        </span>}
        {cashChanges?.map(change => <CashPayment key={change.player} event={event} player={change.player} amount={change.amount}/>)}
        {event.miseryChanges?.map(change => <span className="event-misery-change" key={change.player}>
            {(change.player !== event.actor || event.miseryChanges.length > 1) && <CapitalLabel capital={eventPlayer(event, change.player).capital}>{eventName(event, change.player)}</CapitalLabel>}
            <strong>{change.spaces < 0 ? '−' : '+'}{Math.abs(change.spaces)}</strong> {t('game.misery').toLowerCase()}
            <span>(<MiseryBox value={change.fromValue} track={track?.miseryTrack}/> → <MiseryBox value={change.toValue} track={track?.miseryTrack}/>)</span>
            {change.reasons?.map((reason, index) => <span className="event-detail" key={index}>
                {reason.advance && `${advanceName(reason.advance)} · `}
                {reason.card && reason.card !== cardId && <ActivityCard cardId={reason.card} onInspectCard={onInspectCard}/>}
                {t(`activity.miseryReason.${reason.kind}`)}
                {reason.spaces != null && (change.reasons?.length ?? 0) > 1 && ` (${t('activity.ruleContribution', {spaces: signedAmount(reason.spaces)})})`}
            </span>)}
        </span>)}
        {event.positionChanges?.map(change => <span className="event-position-change" key={change.player}>
            <CapitalLabel capital={eventPlayer(event, change.player).capital}>{eventName(event, change.player)}</CapitalLabel>
            <span>{t('activity.positionChange', {before: change.before, after: change.after})}</span>
        </span>)}
        {event.tokenChanges?.map(change => <span className="event-token-change" key={change.player}>
            <CapitalLabel capital={eventPlayer(event, change.player).capital}>{eventName(event, change.player)}</CapitalLabel>{' '}
            <strong>{t('activity.tokenPool', {amount: signedAmount(change.poolDelta)})}</strong>
            {' · '}{t('activity.tokenStock', {amount: signedAmount(change.stockDelta)})}
        </span>)}
    </span>;
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
    const events = snapshot.events.filter(event => event.kind !== 'PASS');
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
        </button>}
        <div className="activity-body">
            {events.length === 0 && <p>{t('table.noActivity')}</p>}
            <ol className="activity-list" tabIndex={0} aria-label={t('table.recentActivity')}>{[...events].reverse().map(event => <li key={event.id} data-event-id={event.id}>
                <small>{event.turnNumber !== null ? `${t('game.turn')} ${event.turnNumber}` : t(`stage.${event.stage}`)}</small>
                <EventContent event={event} track={snapshot.game.catalog} cardInspections={snapshot.game.cardInspections} onInspectCard={onInspectCard}/>
            </li>)}</ol>
        </div>
    </section>;
}
