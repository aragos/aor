import type {Snapshot} from './api';
import {PlayerLabel, PlayerLabels} from './IdentityLabel';
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
