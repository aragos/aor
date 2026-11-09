import {useState} from 'react';
import {GameStatistics} from './GameStatistics';
import type {Snapshot} from './api';
import {PlayerLabel, PlayerLabels} from './IdentityLabel';
import {t} from './messages';
import {MiseryBox} from './MiseryBox';

export function FinalScores({snapshot}: {snapshot: Snapshot}) {
    const [tab, setTab] = useState<'scores' | 'statistics'>('scores');
    if (snapshot.game.stage !== 'FINISHED' || snapshot.game.scores.length === 0) return null;
    const scores = [...snapshot.game.scores].sort((first, second) =>
        (second.total ?? -Infinity) - (first.total ?? -Infinity));
    const winners = snapshot.game.scores.filter(score => score.winner).map(score => score.player);
    return <section className="panel final-scores" aria-labelledby="scores-title">
        <p className="eyebrow">{t('game.finished')}</p><h2 id="scores-title">{t('game.finalScores')}</h2>
        <p className="winner-announcement">{winners.length ? <>{t(winners.length === 1 ? 'game.winner' : 'game.winners')}: <PlayerLabels snapshot={snapshot} ids={winners}/></> : t('game.noWinner')}</p>
        <nav className="result-tabs" role="tablist" aria-label={t('game.results')}>
            {(['scores', 'statistics'] as const).map(value => <button type="button" key={value}
                id={`result-tab-${value}`} role="tab" aria-selected={tab === value}
                aria-controls={`result-panel-${value}`} tabIndex={tab === value ? 0 : -1}
                onClick={() => setTab(value)} onKeyDown={event => {
                    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
                    event.preventDefault();
                    const next = event.key === 'Home' ? 'scores' : event.key === 'End' ? 'statistics'
                        : value === 'scores' ? 'statistics' : 'scores';
                    setTab(next);
                    document.getElementById(`result-tab-${next}`)?.focus();
                }}>{t(value === 'scores' ? 'game.finalScores' : 'game.statistics')}</button>)}
        </nav>
        <div id="result-panel-scores" role="tabpanel" aria-labelledby="result-tab-scores" hidden={tab !== 'scores'}>
        <div className="table-scroll"><table><thead><tr>{['player', 'advances', 'cash', 'misery', 'score'].map(key => <th key={key} scope="col">{t(`game.${key}`)}</th>)}</tr></thead>
            <tbody>{scores.map(score => <tr key={score.player} className={score.winner ? 'my-row' : ''}>
                <td><PlayerLabel snapshot={snapshot} id={score.player}/></td><td>{score.advancePoints}</td><td>${score.cash}</td><td><MiseryBox value={score.misery} track={snapshot.game.catalog?.miseryTrack}/></td><td><strong>{score.total ?? '—'}</strong></td>
            </tr>)}</tbody></table></div>
        </div>
        <div id="result-panel-statistics" role="tabpanel" aria-labelledby="result-tab-statistics" hidden={tab !== 'statistics'}>
            <GameStatistics snapshot={snapshot}/>
        </div>
    </section>;
}
