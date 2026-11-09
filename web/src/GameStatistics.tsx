import {useId, useLayoutEffect, useRef, useState} from 'react';
import type {GameStatisticsPoint, Snapshot} from './api';
import {PlayerLabel} from './IdentityLabel';
import {playerName} from './labels';
import {t} from './messages';

type Metric = 'score' | 'advances' | 'cash' | 'misery';
const metrics: Metric[] = ['score', 'advances', 'cash', 'misery'];
const colors = ['#1e8575', '#bd5b32', '#6369c3', '#a27916', '#bd4581', '#4385b5'];
const dashes = ['', '8 4', '3 3', '10 3 2 3', '6 3', '2 5'];

function positionLabel(point: GameStatisticsPoint): string {
    return point.finished ? t('game.finalPosition') : t('game.statisticsTurn', {turn: point.turnNumber});
}

/** Values come from authoritative replay; the browser only scales and renders the series. */
export function GameStatistics({snapshot}: {snapshot: Snapshot}) {
    const [metric, setMetric] = useState<Metric>('score');
    const id = useId();
    const chart = useRef<HTMLDivElement>(null);
    const [width, setWidth] = useState(650);
    useLayoutEffect(() => {
        if (!chart.current) return;
        const observer = new ResizeObserver(entries => {
            const available = entries[0]?.contentRect.width;
            if (available) setWidth(Math.max(260, available));
        });
        observer.observe(chart.current);
        return () => observer.disconnect();
    }, [snapshot.statistics?.length]);
    const points = snapshot.statistics ?? [];
    if (!points.length) return <p>{t('game.noStatistics')}</p>;
    const values = points.flatMap(point => point.players.flatMap(player => {
        const value = player[metric];
        return value === null ? [] : [value];
    }));
    const minimum = Math.min(0, ...values);
    const maximum = Math.max(1, ...values);
    const x = (index: number) => 60 + index * (width - 90) / Math.max(1, points.length - 1);
    const y = (value: number) => 225 - (value - minimum) * 195 / (maximum - minimum);
    const tickCount = Math.min(5, maximum - minimum + 1);
    const ticks = Array.from({length: tickCount}, (_, index) =>
        Math.round(minimum + (maximum - minimum) * index / (tickCount - 1)));
    const series = snapshot.game.players.map((player, index) => ({player,
        color: colors[index % colors.length], dash: dashes[index % dashes.length],
        values: points.map(point => point.players.find(value => value.player === player.id)?.[metric] ?? null)}));
    return <div className="game-statistics">
        <label className="statistics-metric" htmlFor={`${id}-metric`}>{t('game.statistic')}
            <select id={`${id}-metric`} value={metric} onChange={event => setMetric(event.target.value as Metric)}>
                {metrics.map(value => <option key={value} value={value}>{t(`game.${value}`)}</option>)}
            </select>
        </label>
        <p className="small muted">{t('game.statisticsSampling')}</p>
        <div ref={chart}><svg className="statistics-chart" viewBox={`0 0 ${width} 275`} role="img"
            aria-labelledby={`${id}-title ${id}-description`}>
            <title id={`${id}-title`}>{t('game.statisticOverTime', {metric: t(`game.${metric}`)})}</title>
            <desc id={`${id}-description`}>{t('game.statisticsChartDescription')}</desc>
            {ticks.map((value, index) => <g key={index} className="statistics-grid">
                <line x1="60" x2={width - 30} y1={y(value)} y2={y(value)}/>
                <text x="50" y={y(value) + 4} textAnchor="end">{Math.round(value)}</text>
            </g>)}
            {points.map((point, index) => <g key={index}>
                {(index === 0 || index === points.length - 1 || index % Math.ceil(points.length / (width < 450 ? 3 : 8)) === 0) &&
                    <text x={x(index)} y="250" textAnchor="middle">{point.finished ? t('game.finalPosition') : point.turnNumber}</text>}
            </g>)}
            {series.map(({player, values: samples, color, dash}) => {
                // A null value breaks the line: Chaos must never be drawn as zero or connected across.
                const path = samples.map((value, index) => value === null ? ''
                    : `${index === 0 || samples[index - 1] === null ? 'M' : 'L'}${x(index)},${y(value)}`).join(' ');
                return <g key={player.id} fill={color} stroke={color}>
                    <path d={path} fill="none" strokeWidth="2.5" strokeDasharray={dash}/>
                    {samples.map((value, index) => value !== null && <circle key={index} cx={x(index)} cy={y(value)} r="3.5">
                        <title>{playerName(snapshot, player.id)} · {positionLabel(points[index])}: {value}</title>
                    </circle>)}
                </g>;
            })}
        </svg></div>
        <ul className="statistics-legend plain-list">{series.map(({player, color, dash}) => <li key={player.id}>
            <svg width="30" height="12" aria-hidden="true"><line x1="0" x2="30" y1="6" y2="6" stroke={color} strokeWidth="3" strokeDasharray={dash}/></svg>
            <PlayerLabel snapshot={snapshot} id={player.id}/>
        </li>)}</ul>
        <details className="statistics-data"><summary>{t('game.statisticsData')}</summary>
            <div className="table-scroll"><table>
                <caption className="sr-only">{t('game.statisticOverTime', {metric: t(`game.${metric}`)})}</caption>
                <thead><tr><th scope="col">{t('game.turn')}</th>{series.map(({player}) =>
                    <th scope="col" key={player.id}><PlayerLabel snapshot={snapshot} id={player.id}/></th>)}</tr></thead>
                <tbody>{points.map((point, index) => <tr key={index}>
                    <th scope="row">{positionLabel(point)}</th>{series.map(({player, values: samples}) =>
                        <td key={player.id}>{samples[index] ?? t('game.chaos')}</td>)}
                </tr>)}</tbody>
            </table></div>
        </details>
    </div>;
}
