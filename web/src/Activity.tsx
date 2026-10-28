import {useEffect, useState} from 'react';
import {ResultNotice} from './DecisionControls';
import type {HistoryEventView, Snapshot, WarOutcomeView} from './api';
import {advanceName, label, playerName, provinceName} from './labels';
import {t} from './messages';
import {Commodity, Emblem} from './TablePanels';

function warHeading(snapshot: Snapshot, war: WarOutcomeView): string {
    return war.winner ? t('table.warWinner').replace('{capital}', playerName(snapshot, war.winner)) : t('table.warTied');
}

function dieColor(event: HistoryEventView, index: number): string {
    const recorded = event.dice[index]?.color;
    if (recorded) return recorded.toLowerCase();
    if (event.competition || event.detail === 'SHORTAGE') return ['white', 'black', 'green'][index];
    return event.war && index === 1 ? 'black' : 'white';
}

function EventContent({event, snapshot}: {event: HistoryEventView; snapshot: Snapshot}) {
    const actor = snapshot.game.players.find(player => player.id === event.actor);
    const target = snapshot.game.players.find(player => player.id === event.target);
    return <span className="activity-content">
        {actor && <span className="activity-actor"><Emblem player={actor} size={15}/><strong>{playerName(snapshot, actor.id)}</strong></span>}
        <span>{t(`activity.${event.kind}`)}</span>
        {event.competition && <strong>{t(event.competition.attackerWon ? 'table.competitionWon' : 'table.competitionLost')}</strong>}
        {event.war && <strong>{warHeading(snapshot, event.war)}</strong>}
        {event.provinces.map(province => <span className="event-entity" key={province}>{provinceName(province)}</span>)}
        {event.card && <span className="event-card">▤ {label(event.card, 'event')}</span>}
        {event.commodity && <Commodity commodity={event.commodity}/>}
        {event.advance && <span className="event-entity">{advanceName(event.advance)}</span>}
        {event.amount !== null && event.protectionCash == null && <strong>{event.kind === 'BID_CAPITAL' ? '$' : ''}{event.amount}</strong>}
        {event.protectionCash != null && <span className="event-detail">{t('activity.protectionCash')}: <strong>${event.protectionCash}</strong></span>}
        {event.leaderRebate != null && <span className="event-detail">{t('activity.leaderRebate')}: <strong>+${event.leaderRebate}</strong></span>}
        {target && <span className="activity-actor">→ <Emblem player={target} size={15}/>{playerName(snapshot, target.id)}</span>}
        {event.detail && <span className="event-detail">{label(event.detail)}</span>}
        {event.drawReason && <span className="event-detail">{t(`activity.drawReason.${event.drawReason}`)}</span>}
        {event.dice.map((die, index) => {
            const player = snapshot.game.players.find(candidate => candidate.id === die.player);
            const color = dieColor(event, index);
            return <span className={`event-die die-${color}`} key={index} aria-label={`${die.player ? `${playerName(snapshot, die.player)}: ` : ''}${t(`table.die.${color}`)} ${die.value}`}>
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
            {change.player !== event.actor && playerName(snapshot, change.player)} <strong>{change.amount < 0 ? '−' : '+'}${Math.abs(change.amount)}</strong>
        </span>)}
    </span>;
}

/** War results remain visible through subsequent card effects and cession decisions. */
export function WarNotice({snapshot}: {snapshot: Snapshot}) {
    const [dismissed, setDismissed] = useState('');
    const event = [...snapshot.events].reverse().find(candidate => candidate.war);
    if (!event?.war || event.id === dismissed) return null;
    const war = event.war;
    return <ResultNotice className="war-notice" dismiss={() => setDismissed(event.id)}>
        <strong>{warHeading(snapshot, war)}</strong>
        <dl className="detail-list">{[war.initiator, war.opponent].map((player, index) => {
            const die = event.dice.find(candidate => candidate.player === player);
            return <div key={player}>
                <dt>{playerName(snapshot, player)}</dt>
                <dd>{t(`table.die.${dieColor(event, index)}`)} {die?.value ?? '—'} → {t('table.warTotal')} <b>{war.totals[player]}</b></dd>
            </div>;
        })}</dl>
        <p>{war.winner ? `${t('table.warTransfers')}: ${war.requiredTransfers}` : t('table.warContinues')}</p>
    </ResultNotice>;
}

/** Cleared notices stay in Activity; polling preserves dismissal and new results remain visible. */
export function CompetitionNotice({snapshot}: {snapshot: Snapshot}) {
    const [dismissed, setDismissed] = useState<{scope: string; ids: string[]}>({scope: '', ids: []});
    const scope = `${snapshot.gameId}:${snapshot.game.turnNumber}`;
    if (snapshot.game.stage !== 'EXPANSION') return null;
    const events = snapshot.events.filter(event => event.competition &&
        event.turnNumber === snapshot.game.turnNumber && event.stage === 'EXPANSION' &&
        (dismissed.scope !== scope || !dismissed.ids.includes(event.id)));
    if (!events.length) return null;
    return <details className="competition-log" open><summary>{t('table.competitionResults')} · {events.length}</summary>
        <button type="button" className="secondary clear-competition-results" onClick={() => setDismissed(current => ({scope,
            ids: [...(current.scope === scope ? current.ids : []), ...events.map(event => event.id)]}))}>{t('table.clearCompetitionResults')}</button>
        {events.map(event => <article className={`competition-notice${event.competition?.attackerWon ? '' : ' lost'}`} key={event.id} data-competition-result={event.id}>
            <EventContent event={event} snapshot={snapshot}/>
        </article>)}
    </details>;
}

export function Activity({snapshot}: {snapshot: Snapshot}) {
    const events = snapshot.events.filter(event => event.kind !== 'PASS');
    const [open, setOpen] = useState(true);
    const [seenCount, setSeenCount] = useState(events.length);
    useEffect(() => {
        if (open) setSeenCount(events.length);
    }, [open, events.length]);
    const latest = events[events.length - 1];
    const unread = Math.max(0, events.length - seenCount);
    return <details className="table-activity" open={open} onToggle={event => {
        const next = event.currentTarget.open;
        setOpen(next);
    }}>
        <summary><span className="recent-activity" aria-live="polite">{latest ? <EventContent event={latest} snapshot={snapshot}/> : t('table.noActivity')}</span>
            <span className="activity-toggle">{t('table.activity')}{unread > 0 && <b className="unread-badge">{unread}</b>}</span></summary>
        <ol className="activity-list">{[...events].reverse().map(event => <li key={event.id} data-event-id={event.id}>
            <small>{event.turnNumber !== null ? `${t('game.turn')} ${event.turnNumber}` : t(`stage.${event.stage}`)}</small>
            <EventContent event={event} snapshot={snapshot}/>
        </li>)}</ol>
    </details>;
}
