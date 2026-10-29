import {PlayedLeaders} from './TablePanels';
import type {Snapshot} from './api';
import {label, playerName} from './labels';
import {t} from './messages';

export function FinalScores({snapshot}: {snapshot: Snapshot}) {
    if (snapshot.game.scores.length === 0) return null;
    const winners = snapshot.game.scores.filter(score => score.winner).map(score => playerName(snapshot, score.player));
    return <section className="panel final-scores" aria-labelledby="scores-title">
        <p className="eyebrow">{t('game.finished')}</p><h2 id="scores-title">{t('game.finalScores')}</h2>
        <p className="winner-announcement">{winners.length ? `${t(winners.length === 1 ? 'game.winner' : 'game.winners')}: ${winners.join(', ')}` : t('game.noWinner')}</p>
        <div className="table-scroll"><table><thead><tr>{['player', 'advances', 'cash', 'misery', 'score'].map(key => <th key={key} scope="col">{t(`game.${key}`)}</th>)}</tr></thead>
            <tbody>{snapshot.game.scores.map(score => <tr key={score.player} className={score.winner ? 'my-row' : ''}>
                <td>{playerName(snapshot, score.player)}</td><td>{score.advancePoints}</td><td>${score.cash}</td><td>{score.misery ?? t('game.chaos')}</td><td><strong>{score.total ?? '—'}</strong></td>
            </tr>)}</tbody></table></div>
    </section>;
}

export function TurnEffects({snapshot}: {snapshot: Snapshot}) {
    const effects = snapshot.game.effects;
    const adjustments = Object.entries(snapshot.game.adjustments).filter(([, markers]) => markers.length > 0);
    if (snapshot.game.turnNumber === null) return null;
    return <section className="panel turn-effects" aria-labelledby="effects-title">
        <h2 id="effects-title">{t('game.thisTurn')}</h2>
        <p className="small">{t('game.deckRemaining')}: <strong>{snapshot.game.deckRemaining}</strong></p>
        {snapshot.game.endingTriggered && snapshot.game.stage !== 'FINISHED' && <p className="ending-notice">{t('game.endingNotice')}</p>}
        {adjustments.length > 0 && <ul className="plain-list">{adjustments.map(([commodity, markers]) => <li key={commodity}>
            {label(commodity, 'commodity')}: {markers.map(marker => label(marker, 'adjustment')).join(', ')}
        </li>)}</ul>}
        {effects.papalCategory && <p>{t('game.forbiddenCategory')}: {label(effects.papalCategory, 'category')}</p>}
        {effects.military.map(play => <p key={play.event}>{playerName(snapshot, play.player)}: {label(play.event, 'event')}</p>)}
        {effects.enlightenedRulers.length > 0 && <p>{t('game.enlightenedRulers')}: {effects.enlightenedRulers.map(id => playerName(snapshot, id)).join(', ')}</p>}
        {snapshot.game.activeWar && <p>{t('game.activeWar')}: {playerName(snapshot, snapshot.game.activeWar.initiator)} / {playerName(snapshot, snapshot.game.activeWar.opponent)} · {t('game.warTies')}: {snapshot.game.activeWar.ties}</p>}
        <PlayedLeaders snapshot={snapshot}/>
        {adjustments.length === 0 && effects.leaders.length === 0 && !effects.papalCategory && effects.military.length === 0 && effects.enlightenedRulers.length === 0 && !snapshot.game.activeWar && <p className="muted small">{t('game.noEffects')}</p>}
    </section>;
}
