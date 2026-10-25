import {useEffect, useState} from 'react';
import type {ProvinceDefinition, PublicCatalog, Snapshot} from '../api';
import {label, playerName, provinceName} from '../labels';
import {t} from '../messages';
import {TableIcon} from '../TableIcon';
import {Commodity} from '../TablePanels';
import {PlayerPiece} from './MapSymbols';
import {provinceOrders} from './mapOrders';

export function neighbors(connections: PublicCatalog['landBorders'], province: string): string[] {
    return connections.flatMap(edge => edge.first === province ? [edge.second] : edge.second === province ? [edge.first] : []);
}

export function ProvinceInspector({snapshot, province, busy, submit, select}: {
    snapshot: Snapshot; province: ProvinceDefinition; busy: boolean;
    submit: (command: string) => Promise<boolean>; select: (province: string) => void;
}) {
    const [partial, setPartial] = useState('');
    useEffect(() => setPartial(''), [province.id]);
    const catalog = snapshot.game.catalog;
    if (!catalog) return null;
    const pieces = snapshot.game.board.find(pieces => pieces.id === province.id);
    const me = snapshot.game.players.find(player => player.id === snapshot.viewerId);
    const offer = snapshot.game.expansionOffers.find(candidate => candidate.destination === province.id);
    const primary = offer && offer.primaryAction !== 'INSPECT';
    const available = primary && offer.blockers.length === 0 && offer.resultingPool !== null;
    const partialOffer = offer?.partialPlacements.find(choice => String(choice.tokens) === partial);
    const command = offer?.primaryAction === 'ATTACK' ? `attack ${province.id}` : `place ${offer?.requiredTokens} ${province.id}`;
    const commands = provinceOrders(snapshot, province.id).filter(command => !command.startsWith('place ') && !command.startsWith('attack '));
    const links = (ids: string[]) => ids.length ? ids.map(id => <button type="button" className="map-link" key={id}
        onClick={() => select(id)}>{provinceName(id)}</button>) : '—';
    return <div className="province-inspector" aria-label={t('table.selectedProvince')}>
        <div>
            <p className="eyebrow">{primary ? offer.uncertain ? t('table.compete') : province.satellite ? t('action.place') : t('table.establishMarker') : t('table.province')}</p>
            <h2>{provinceName(province.id)}{province.capital && ' ♛'}</h2>
            <p className="map-facts">{province.commodities.map(commodity => <Commodity key={commodity} commodity={commodity}/>)}
                <span>{t('board.market')} {province.marketNumber}</span> · {province.area ? `${t('board.area')} ${province.area}` : label(province.overseas ?? '')}</p>
            {pieces?.marker ? <p className="occupancy"><svg width="24" height="24" viewBox="-24 -24 48 48" aria-hidden="true"><PlayerPiece size={40}
                capital={snapshot.game.players.find(player => player.id === pieces.marker?.owner)?.capital ?? null} fresh={pieces.marker.side === 'WHITE'}/></svg>
                {' '}{playerName(snapshot, pieces.marker.owner)} · {t(pieces.marker.side === 'WHITE' ? 'board.new' : 'board.old')} {t('board.marker')}</p>
                : pieces?.tokens.length ? <ul className="plain-list">{pieces.tokens.map(tokens => <li key={tokens.owner}>
                    {playerName(snapshot, tokens.owner)}: {tokens.coloured} {t('board.old')} + {tokens.white} {t('board.new')}
                </li>)}</ul> : <p className="muted">{t('table.unoccupied')}</p>}
        </div>
        <div className="map-orders">
            {primary && <>
                <div className="pool-change" aria-label={t('game.pool')}><span>{t('table.poolTokens')}</span><strong>{offer.availablePool} <span>→</span> {offer.resultingPool ?? '—'}</strong></div>
                <div className="commitment-preview"><span><TableIcon kind="token"/> <strong>{offer.requiredTokens}</strong><small>{t('table.poolTokens')}</small></span><span>→</span>
                    <span><svg width="32" height="36" viewBox="-28 -28 56 56" aria-hidden="true"><PlayerPiece capital={me?.capital ?? null} fresh count={offer.establishesMarker ? undefined : 1}/></svg>
                        <small>{offer.establishesMarker ? t(offer.uncertain ? 'table.uncertainMarker' : 'table.newMarker') : t(offer.uncertain ? 'table.uncertainTokens' : 'action.place')}</small></span></div>
                {offer.incomeBefore !== null && offer.incomeAfter !== null && <p className="value-change"><span>{t('table.income')}</span><strong>${offer.incomeBefore} → ${offer.incomeAfter}</strong></p>}
                {offer.uncertain && <p className="consequence">{t('table.competitionOutcome')}</p>}
                {offer.blockers.map(blocker => <p className="blocker" key={blocker}>{t(`error.${blocker}`)}</p>)}
                <button type="button" className="primary-expansion" disabled={busy || !available} onClick={() => { if (available) void submit(command); }}>
                    {offer.uncertain ? t('action.attack') : province.satellite ? t('action.place') : t('table.establishMarker')} · {offer.requiredTokens} {t('table.tokens')}
                </button>
                {offer.partialPlacements.length > 0 && <details className="partial-placement"><summary>{t('table.placeFewer')}</summary>
                    <form onSubmit={event => { event.preventDefault(); if (partialOffer) void submit(`place ${partialOffer.tokens} ${province.id}`); }}>
                        <label htmlFor="partial-count">{t('controls.tokenCount')}</label>
                        <select id="partial-count" value={partial} onChange={event => setPartial(event.target.value)} required>
                            <option value="">{t('table.chooseAmount')}</option>
                            {offer.partialPlacements.map(choice => <option key={choice.tokens} value={choice.tokens}>{choice.tokens}</option>)}
                        </select>
                        {partial && !partialOffer && <p className="blocker" role="status">{t('table.invalidatedChoice')}</p>}
                        {partialOffer && <p>{t('game.pool')}: {offer.availablePool} → {partialOffer.resultingPool}</p>}
                        <button disabled={busy || !partialOffer}>{t('action.place')}</button>
                    </form>
                </details>}
                <details><summary>{t('table.placementDetails')}</summary>
                    {offer.shipping && <dl className="detail-list"><div><dt>{t('table.destinationShipping')}</dt><dd>{offer.shipping.used} / {offer.shipping.capacity ?? '∞'}</dd></div><div><dt>{t('table.shippingRequired')}</dt><dd>{offer.shipping.required}</dd></div></dl>}
                    <p>{t('game.stockMarkers')}: {me?.stockMarkers}</p>
                </details>
            </>}
            <div className="action-buttons">{commands.map(command => <button type="button" key={command} className="secondary"
                disabled={busy} onClick={() => { void submit(command); }}>{label(command.split(' ')[0], 'action')}</button>)}</div>
            <details className="province-details"><summary>{t('table.provinceDetails')}</summary>
                {!!province.access.length && <p>{t('board.access')}: {province.access.map(requirement => label(requirement, 'access')).join('; ')}</p>}
                <p>{t('board.landNeighbors')}: {links(neighbors(catalog.landBorders, province.id))}</p>
                <p>{t('board.coastalNeighbors')}: {links(neighbors(catalog.coastalConnections, province.id))}</p>
                <p>{t('board.seas')}: {catalog.seaCoasts.filter(coast => coast.province === province.id).map(coast => label(coast.sea)).join(', ') || '—'}</p>
                <p>{t('board.supports')}: {links(catalog.satelliteSupports.filter(edge => edge.satellite === province.id).map(edge => edge.market))}</p>
                <p>{t('board.supportedBy')}: {links(catalog.satelliteSupports.filter(edge => edge.market === province.id).map(edge => edge.satellite))}</p>
            </details>
        </div>
    </div>;
}
