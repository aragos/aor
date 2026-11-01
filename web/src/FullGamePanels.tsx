import type {Snapshot} from './api';
import {PlayerLabel, PlayerLabels} from './IdentityLabel';
import {label} from './labels';
import {t} from './messages';
import {MiseryBox} from './MiseryBox';

export function FinalScores({snapshot}: {snapshot: Snapshot}) {
    if (snapshot.game.scores.length === 0) return null;
    const winners = snapshot.game.scores.filter(score => score.winner).map(score => score.player);
    return <section className="panel final-scores" aria-labelledby="scores-title">
        <p className="eyebrow">{t('game.finished')}</p><h2 id="scores-title">{t('game.finalScores')}</h2>
        <p className="winner-announcement">{winners.length ? <>{t(winners.length === 1 ? 'game.winner' : 'game.winners')}: <PlayerLabels snapshot={snapshot} ids={winners}/></> : t('game.noWinner')}</p>
        <div className="table-scroll"><table><thead><tr>{['player', 'advances', 'cash', 'misery', 'score'].map(key => <th key={key} scope="col">{t(`game.${key}`)}</th>)}</tr></thead>
            <tbody>{snapshot.game.scores.map(score => <tr key={score.player} className={score.winner ? 'my-row' : ''}>
                <td><PlayerLabel snapshot={snapshot} id={score.player}/></td><td>{score.advancePoints}</td><td>${score.cash}</td><td><MiseryBox value={score.misery} track={snapshot.game.catalog?.miseryTrack}/></td><td><strong>{score.total ?? '—'}</strong></td>
            </tr>)}</tbody></table></div>
    </section>;
}

export function TurnEffects({snapshot}: {snapshot: Snapshot}) {
    const effects = snapshot.game.effects;
    const war = snapshot.game.activeWar;
    const hasEffects = effects.papalCategory || effects.military.length > 0 ||
        effects.enlightenedRulers.length > 0 || war;
    return <section className="turn-effects" aria-label={t('game.thisTurn')}>
        {hasEffects ? <dl className="detail-list turn-effect-list">
            {effects.papalCategory && <div><dt>{t('game.forbiddenCategory')}</dt>
                <dd>{label(effects.papalCategory, 'category')}</dd></div>}
            {effects.military.map(play => <div key={play.event}><dt><PlayerLabel snapshot={snapshot} id={play.player}/></dt>
                <dd>{label(play.event, 'event')}</dd></div>)}
            {effects.enlightenedRulers.length > 0 && <div><dt>{t('game.enlightenedRulers')}</dt>
                <dd><PlayerLabels snapshot={snapshot} ids={effects.enlightenedRulers}/></dd></div>}
            {war && <div className="turn-effect-war"><dt>{t('game.activeWar')}</dt><dd>
                <span className="turn-war-participants"><PlayerLabel snapshot={snapshot} id={war.initiator}/>
                    <span>/</span><PlayerLabel snapshot={snapshot} id={war.opponent}/></span>
                <span>{t('game.warTies')}: {war.ties}</span>
            </dd></div>}
        </dl> : <p className="muted small">{t('game.noEffects')}</p>}
    </section>;
}
