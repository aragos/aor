import {useId, useState} from 'react';
import {ResultNotice} from './DecisionControls';
import type {HistoryEventView, Snapshot, WarOutcomeView} from './api';
import {advanceName, label, provinceName} from './labels';
import {t} from './messages';
import {Commodity, Emblem} from './TablePanels';

function eventPlayer(event: HistoryEventView, id: string) {
    return event.participants?.find(player => player.id === id) ??
        {id, name: t('game.player'), capital: null};
}

function eventName(event: HistoryEventView, id: string): string {
    const player = eventPlayer(event, id);
    return player.capital ? provinceName(player.capital) : player.name;
}

function warHeading(event: HistoryEventView, war: WarOutcomeView): string {
    return war.winner ? t('table.warWinner').replace('{capital}', eventName(event, war.winner)) : t('table.warTied');
}

function dieColor(event: HistoryEventView, index: number): string {
    const recorded = event.dice[index]?.color;
    if (recorded) return recorded.toLowerCase();
    if (event.competition || event.detail === 'SHORTAGE') return ['white', 'black', 'green'][index];
    return event.war && index === 1 ? 'black' : 'white';
}

function EventContent({event, onInspectCard}: {
    event: HistoryEventView; onInspectCard?: (cardId: string) => void;
}) {
    const actor = event.actor ? eventPlayer(event, event.actor) : null;
    const target = event.target ? eventPlayer(event, event.target) : null;
    const cardId = event.card;
    return <span className="activity-content">
        {actor && <span className="activity-actor"><Emblem player={actor} size={15}/><strong>{eventName(event, actor.id)}</strong></span>}
        <span>{t(`activity.${event.kind}`)}</span>
        {event.competition && <strong>{t(event.competition.attackerWon ? 'table.competitionWon' : 'table.competitionLost')}</strong>}
        {event.war && <strong>{warHeading(event, event.war)}</strong>}
        {event.provinces.map(province => <span className="event-entity" key={province}>{provinceName(province)}</span>)}
        {cardId && (onInspectCard
            ? <button type="button" className="event-card" onClick={() => onInspectCard(cardId)}>▤ {label(cardId, 'event')}</button>
            : <span className="event-card">▤ {label(cardId, 'event')}</span>)}
        {event.commodity && <Commodity commodity={event.commodity}/>}
        {event.advance && <span className="event-entity">{advanceName(event.advance)}</span>}
        {event.amount !== null && event.protectionCash == null && <strong>{event.kind === 'BID_CAPITAL' ? '$' : ''}{event.amount}</strong>}
        {event.protectionCash != null && <span className="event-detail">{t('activity.protectionCash')}: <strong>${event.protectionCash}</strong></span>}
        {event.leaderRebate != null && <span className="event-detail">{t('activity.leaderRebate')}: <strong>+${event.leaderRebate}</strong></span>}
        {target && <span className="activity-actor">→ <Emblem player={target} size={15}/>{eventName(event, target.id)}</span>}
        {event.detail && <span className="event-detail">{label(event.detail)}</span>}
        {event.drawReason && <span className="event-detail">{t(`activity.drawReason.${event.drawReason}`)}</span>}
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
        {event.cashChanges?.map(change => <span className="event-cash-change" key={change.player}>
            {change.player !== event.actor && eventName(event, change.player)} <strong>{change.amount < 0 ? '−' : '+'}${Math.abs(change.amount)}</strong>
        </span>)}
        {event.miseryChanges?.map(change => <span className="event-misery-change" key={change.player}>
            {change.player !== event.actor && eventName(event, change.player)}
            <strong>{change.spaces < 0 ? '−' : '+'}{Math.abs(change.spaces)}</strong> {t('game.misery').toLowerCase()}
            <span>({change.fromValue ?? t('game.chaos')} → {change.toValue ?? t('game.chaos')})</span>
        </span>)}
    </span>;
}

function viewerOutcome(event: HistoryEventView, viewerId: string): string {
    if (event.competition) {
        if (event.actor === viewerId) return event.competition.attackerWon ? ' won' : ' lost';
        if (event.competition.defenders.includes(viewerId)) {
            return event.competition.attackerWon ? ' lost' : ' won';
        }
        return '';
    }
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
        <EventContent event={event}/>
        {war && <>
            <dl className="detail-list">{[war.initiator, war.opponent].map((player, index) => {
                const die = event.dice.find(candidate => candidate.player === player);
                return <div key={player}>
                    <dt>{eventName(event, player)}</dt>
                    <dd>{t(`table.die.${dieColor(event, index)}`)} {die?.value ?? '—'} → {t('table.warTotal')} <b>{war.totals[player]}</b></dd>
                </div>;
            })}</dl>
            <p>{war.winner ? `${t('table.warTransfers')}: ${war.requiredTransfers}` : t('table.warContinues')}</p>
        </>}
    </ResultNotice>;
}

export function Activity({snapshot, onInspectCard}: {
    snapshot: Snapshot; onInspectCard: (cardId: string) => void;
}) {
    const events = snapshot.events.filter(event => event.kind !== 'PASS');
    const [open, setOpen] = useState(true);
    const contentId = useId();
    const toggleLabel = t(open ? 'table.hideActivity' : 'table.showActivity');
    return <section className="table-activity" aria-label={t('table.activity')}>
        <button type="button" className="secondary activity-toggle" title={toggleLabel}
            aria-label={toggleLabel} aria-expanded={open} aria-controls={contentId}
            onClick={() => setOpen(current => !current)}>
            <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
                <path d="M3 4h10M3 8h10M3 12h6"/>
                <path d={open ? 'm11 17 3-3 3 3' : 'm11 14 3 3 3-3'}/>
            </svg>
        </button>
        <div id={contentId} hidden={!open}>
            {events.length === 0 && <p>{t('table.noActivity')}</p>}
            <ol className="activity-list">{[...events].reverse().map(event => <li key={event.id} data-event-id={event.id}>
                <small>{event.turnNumber !== null ? `${t('game.turn')} ${event.turnNumber}` : t(`stage.${event.stage}`)}</small>
                <EventContent event={event} onInspectCard={onInspectCard}/>
            </li>)}</ol>
        </div>
    </section>;
}
