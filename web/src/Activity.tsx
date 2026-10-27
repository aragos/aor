import {useEffect, useState} from 'react';
import {ResultNotice} from './DecisionControls';
import type {HistoryEventView, Snapshot, WarOutcomeView} from './api';
import {advanceName, label, playerName, provinceName} from './labels';
import {t} from './messages';
import {Commodity, Emblem} from './TablePanels';

function warHeading(snapshot: Snapshot, war: WarOutcomeView): string {
    return war.winner ? t('table.warWinner').replace('{capital}', playerName(snapshot, war.winner)) : t('table.warTied');
}

function EventContent({event, snapshot}: {event: HistoryEventView; snapshot: Snapshot}) {
    const actor = snapshot.game.players.find(player => player.id === event.actor);
    const target = snapshot.game.players.find(player => player.id === event.target);
    const money = ['BID_CAPITAL', 'BUY_CARD', 'BUY_ADVANCE', 'UPGRADE_SHIPS', 'STABILIZE', 'PATRONIZE', 'BUY_EXPANSION_CARD'].includes(event.kind);
    return <span className="activity-content">
        {actor && <span className="activity-actor"><Emblem player={actor} size={15}/><strong>{playerName(snapshot, actor.id)}</strong></span>}
        <span>{t(`activity.${event.kind}`)}</span>
        {event.competition && <strong>{t(event.competition.attackerWon ? 'table.competitionWon' : 'table.competitionLost')}</strong>}
        {event.war && <strong>{warHeading(snapshot, event.war)}</strong>}
        {event.provinces.map(province => <span className="event-entity" key={province}>{provinceName(province)}</span>)}
        {event.card && <span className="event-card">▤ {label(event.card, 'event')}</span>}
        {event.commodity && <Commodity commodity={event.commodity}/>}
        {event.advance && <span className="event-entity">{advanceName(event.advance)}</span>}
        {event.amount !== null && <strong>{money ? '$' : ''}{event.amount}</strong>}
        {target && <span className="activity-actor">→ <Emblem player={target} size={15}/>{playerName(snapshot, target.id)}</span>}
        {event.detail && <span className="event-detail">{label(event.detail)}</span>}
        {event.dice.map((die, index) => {
            const player = snapshot.game.players.find(candidate => candidate.id === die.player);
            return <span className="event-die" key={index} aria-label={`${die.player ? `${playerName(snapshot, die.player)}: ` : ''}${t('table.die')} ${die.value}`}>
                {player && <Emblem player={player} size={13}/>}⚄ {die.value}
            </span>;
        })}
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
        <dl className="detail-list">{[war.initiator, war.opponent].map(player => <div key={player}>
            <dt>{playerName(snapshot, player)}</dt>
            <dd>{t('table.die')} {event.dice.find(die => die.player === player)?.value ?? '—'} → {t('table.warTotal')} <b>{war.totals[player]}</b></dd>
        </div>)}</dl>
        <p>{war.winner ? `${t('table.warTransfers')}: ${war.requiredTransfers}` : t('table.warContinues')}</p>
    </ResultNotice>;
}

/** Keep every competition result available until the current expansion phase ends. */
export function CompetitionNotice({snapshot}: {snapshot: Snapshot}) {
    if (snapshot.game.stage !== 'EXPANSION') return null;
    const events = snapshot.events.filter(event => event.competition &&
        event.turnNumber === snapshot.game.turnNumber && event.stage === 'EXPANSION');
    if (!events.length) return null;
    return <details className="competition-log" open><summary>{t('table.competitionResults')} · {events.length}</summary>
        {events.map(event => <article className="competition-notice" key={event.id} data-competition-result={event.id}>
            <EventContent event={event} snapshot={snapshot}/>
        </article>)}
    </details>;
}

export function Activity({snapshot}: {snapshot: Snapshot}) {
    const [open, setOpen] = useState(false);
    const [seenCount, setSeenCount] = useState(snapshot.events.length);
    useEffect(() => {
        if (open) setSeenCount(snapshot.events.length);
    }, [open, snapshot.events.length]);
    const latest = snapshot.events[snapshot.events.length - 1];
    const unread = Math.max(0, snapshot.events.length - seenCount);
    return <details className="table-activity" open={open} onToggle={event => {
        const next = event.currentTarget.open;
        setOpen(next);
    }}>
        <summary><span className="recent-activity" aria-live="polite">{latest ? <EventContent event={latest} snapshot={snapshot}/> : t('table.noActivity')}</span>
            <span className="activity-toggle">{t('table.activity')}{unread > 0 && <b className="unread-badge">{unread}</b>}</span></summary>
        <ol className="activity-list">{[...snapshot.events].reverse().map(event => <li key={event.id} data-event-id={event.id}>
            <small>{event.turnNumber !== null ? `${t('game.turn')} ${event.turnNumber}` : t(`stage.${event.stage}`)}</small>
            <EventContent event={event} snapshot={snapshot}/>
        </li>)}</ol>
    </details>;
}
