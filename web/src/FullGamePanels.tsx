import {useState} from 'react';
import type {ProvinceDefinition, ProvinceView, PublicCatalog, Snapshot} from './api';
import {label, playerName, provinceName} from './labels';
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
        {effects.leaders.length > 0 && <div className="leader-list"><h3>{t('game.leaders')}</h3>{effects.leaders.map(leader => <article key={leader.leader}>
            <strong>{label(leader.leader, 'leader')}</strong><p className="small">{playerName(snapshot, leader.owner)} · {leader.displayBox ? `${t('game.displayBox')} ${leader.displayBox}` : label(leader.role, 'leaderRole')}{leader.role === 'EXPOSED' && snapshot.game.rules.patronage === 'PROTECTION_MONEY' && ` · ${t('game.protection')}: $${leader.protectionCash}`}</p>
            {leader.patronageClaimants.length > 0 && <p className="small">{t('game.claimants')}: {leader.patronageClaimants.map(id => playerName(snapshot, id)).join(', ')}</p>}
        </article>)}</div>}
        {adjustments.length === 0 && effects.leaders.length === 0 && !effects.papalCategory && effects.military.length === 0 && effects.enlightenedRulers.length === 0 && !snapshot.game.activeWar && <p className="muted small">{t('game.noEffects')}</p>}
    </section>;
}

function provinceNeighbors(
    connections: PublicCatalog['landBorders'],
    id: string,
): string {
    return connections.flatMap(connection => {
        if (connection.first === id) return [connection.second];
        if (connection.second === id) return [connection.first];
        return [];
    }).map(provinceName).join(', ');
}

function ProvinceOccupants({snapshot, pieces}: {snapshot: Snapshot; pieces?: ProvinceView}) {
    if (pieces?.marker) {
        const marker = pieces.marker;
        const isNew = marker.side === 'WHITE';
        return <span className={isNew ? 'new-piece' : ''}>
            {playerName(snapshot, marker.owner)} · {t('board.marker')}
            {isNew && ` · ${t('board.new')}`}
        </span>;
    }
    if (!pieces?.tokens.length) return <>—</>;
    return <>{pieces.tokens.map(holding => <div key={holding.owner}>
        {playerName(snapshot, holding.owner)}: {holding.coloured > 0 && `${holding.coloured} ${t('board.old')}`}
        {holding.coloured > 0 && holding.white > 0 && ' + '}
        {holding.white > 0 && <span className="new-piece">{holding.white} {t('board.new')}</span>}
    </div>)}</>;
}

function ProvinceRow({snapshot, catalog, province}: {
    snapshot: Snapshot;
    catalog: PublicCatalog;
    province: ProvinceDefinition;
}) {
    const pieces = snapshot.game.board.find(holding => holding.id === province.id);
    const supports = catalog.satelliteSupports
        .filter(link => link.satellite === province.id)
        .map(link => provinceName(link.market));
    return <tr>
        <td>
            <details>
                <summary>{provinceName(province.id)}{province.capital && ' ★'}</summary>
                <p className="small">{t('board.area')}: {province.area ?? label(province.overseas ?? '')}</p>
                <p className="small">{t('board.landNeighbors')}: {provinceNeighbors(catalog.landBorders, province.id) || '—'}</p>
                {province.coastal && <p className="small">
                    {t('board.coastalNeighbors')}: {provinceNeighbors(catalog.coastalConnections, province.id) || '—'}
                </p>}
                {supports.length > 0 && <p className="small">{t('board.supports')}: {supports.join(', ')}</p>}
                {province.access.length > 0 && <p className="small">
                    {t('board.access')}: {province.access.map(requirement => label(requirement, 'access')).join(', ')}
                </p>}
                <code>{province.id}</code>
            </details>
        </td>
        <td>{province.marketNumber}{province.satellite && ` · ${t('board.satellite')}`}</td>
        <td>{province.commodities.map(commodity => label(commodity, 'commodity')).join(', ') || '—'}</td>
        <td><ProvinceOccupants snapshot={snapshot} pieces={pieces}/></td>
    </tr>;
}

export function BoardPanel({snapshot}: {snapshot: Snapshot}) {
    const [filter, setFilter] = useState('');
    const [occupiedOnly, setOccupiedOnly] = useState(false);
    const [area, setArea] = useState('');
    const catalog = snapshot.game.catalog;
    if (!catalog || snapshot.game.turnNumber === null) return null;
    const provinces = catalog.provinces.filter(province => {
        const occupied = snapshot.game.board.some(pieces => pieces.id === province.id);
        return (!occupiedOnly || occupied) && (!area || (province.area ?? province.overseas) === area) &&
            `${provinceName(province.id)} ${province.commodities.map(commodity => label(commodity, 'commodity')).join(' ')}`.toLowerCase().includes(filter.toLowerCase());
    });
    const areas = [...new Set(catalog.provinces.map(province => province.area ?? province.overseas).filter(Boolean))];
    return <section className="panel board-panel" aria-labelledby="board-title">
        <details><summary id="board-title">{t('table.boardDetails')}</summary>
        <div className="board-filters">
            <label>
                {t('board.search')}
                <input type="search" value={filter} onChange={event => setFilter(event.target.value)}/>
            </label>
            <label>
                {t('board.area')}
                <select value={area} onChange={event => setArea(event.target.value)}>
                    <option value="">{t('board.allAreas')}</option>
                    {areas.map(value => <option key={value} value={value!}>{value}</option>)}
                </select>
            </label>
            <label className="check-label">
                <input type="checkbox" checked={occupiedOnly} onChange={event => setOccupiedOnly(event.target.checked)}/>
                {t('board.occupiedOnly')}
            </label>
        </div>
        
        <div className="table-scroll" tabIndex={0} role="region" aria-labelledby="board-title">
            <table className="board-table">
                <thead>
                    <tr>{['province', 'market', 'commodity', 'pieces'].map(key =>
                        <th key={key} scope="col">{t(`board.${key}`)}</th>
                    )}</tr>
                </thead>
                <tbody>{provinces.map(province =>
                    <ProvinceRow key={province.id} snapshot={snapshot} catalog={catalog} province={province}/>
                )}</tbody>
            </table>
        </div>
        {provinces.length === 0 && <p className="muted">{t('board.noMatches')}</p>}
        </details>
    </section>;
}

