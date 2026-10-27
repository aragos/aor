import {useEffect, useState} from 'react';
import type {ProvinceDefinition, PublicCatalog, Snapshot} from '../api';
import {label, playerName, provinceName} from '../labels';
import {t} from '../messages';
import {TableIcon} from '../TableIcon';
import {Commodity} from '../TablePanels';
import {PlayerPiece} from './MapSymbols';
import {ChoiceButtons} from '../DecisionControls';
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
    const remainingPlacement = primary && offer.primaryAction === 'PLACE' && offer.resultingPool === null
        ? offer.partialPlacements.reduce<typeof offer.partialPlacements[number] | undefined>(
            (maximum, choice) => !maximum || choice.tokens > maximum.tokens ? choice : maximum, undefined)
        : undefined;
    const requiredTokens = remainingPlacement?.tokens ?? offer?.requiredTokens;
    const resultingPool = remainingPlacement?.resultingPool ?? offer?.resultingPool;
    const establishesMarker = !!offer?.establishesMarker && !remainingPlacement;
    const uncertain = !!offer?.uncertain && !remainingPlacement;
    const available = primary && (!!remainingPlacement || (offer.blockers.length === 0 && offer.resultingPool !== null));
    const partialOffer = offer?.partialPlacements.find(choice => String(choice.tokens) === partial);
    const command = offer?.primaryAction === 'ATTACK' && !remainingPlacement ? `attack ${province.id}` : `place ${requiredTokens} ${province.id}`;
    const commands = provinceOrders(snapshot, province.id);
    const links = (ids: string[]) => ids.length ? ids.map(id => <button type="button" className="map-link" key={id}
        onClick={() => select(id)}>{provinceName(id)}</button>) : '—';
    return <div className="province-inspector" aria-label={t('table.selectedProvince')}>
        <div>
            <p className="eyebrow">{remainingPlacement ? t('table.remainingTokens') : primary ? uncertain ? t('table.compete') : province.satellite ? t('action.place') : t('table.establishMarker') : t('table.province')}</p>
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
                <div className="pool-change" aria-label={t('game.pool')}><span>{t('table.poolTokens')}</span><strong>{offer.availablePool} <span>→</span> {resultingPool ?? '—'}</strong></div>
                <div className="commitment-preview"><span><TableIcon kind="token"/> <strong>{requiredTokens}</strong><small>{t('table.poolTokens')}</small></span><span>→</span>
                    <span><svg width="32" height="36" viewBox="-28 -28 56 56" aria-hidden="true"><PlayerPiece capital={me?.capital ?? null} fresh count={establishesMarker ? undefined : requiredTokens}/></svg>
                        <small>{establishesMarker ? t(uncertain ? 'table.uncertainMarker' : 'table.newMarker') : t(uncertain ? 'table.uncertainTokens' : 'action.place')}</small></span></div>
                {!remainingPlacement && offer.incomeBefore !== null && offer.incomeAfter !== null && <p className="value-change"><span>{t('table.income')}</span><strong>${offer.incomeBefore} → ${offer.incomeAfter}</strong></p>}
                {uncertain && <p className="consequence">{t('table.competitionOutcome')}</p>}
                {remainingPlacement && <p className="consequence">{t('table.markerNeedsTokens').replace('{count}', String(offer.requiredTokens))}</p>}
                {!remainingPlacement && offer.blockers.map(blocker => <p className="blocker" key={blocker}>{t(`error.${blocker}`)}</p>)}
                <button type="button" className="primary-expansion" disabled={busy || !available} onClick={() => { if (available) void submit(command); }}>
                    {remainingPlacement ? t('table.remainingTokens') : uncertain ? t('action.attack') : province.satellite ? t('action.place') : t('table.establishMarker')} · {requiredTokens} {t('table.tokens')}
                </button>
                {offer.partialPlacements.length > 0 && <details className="partial-placement"><summary>{t('table.placeFewer')}</summary>
                    <form onSubmit={event => { event.preventDefault(); if (partialOffer) void submit(`place ${partialOffer.tokens} ${province.id}`); }}>
                        <label htmlFor="partial-count">{t('controls.tokenCount')}</label>
                        <ChoiceButtons id="partial-count" value={partial} onChange={setPartial} disabled={busy}
                            choices={offer.partialPlacements.map(choice => ({value: String(choice.tokens), label: String(choice.tokens)}))}/>
                        {partial && !partialOffer && <p className="blocker" role="status">{t('table.invalidatedChoice')}</p>}
                        {partialOffer && <p>{t('game.pool')}: {offer.availablePool} → {partialOffer.resultingPool}</p>}
                        <button disabled={busy || !partialOffer}>{t('action.place')}</button>
                    </form>
                </details>}
                <details><summary>{t('table.placementDetails')}</summary>
                    {offer.shipping && <dl className="detail-list"><div><dt>{t('table.destinationShipping')}</dt><dd>{offer.shipping.used} / {offer.shipping.capacity ?? '∞'}</dd></div><div><dt>{t('table.shippingRequired')}</dt><dd>{remainingPlacement?.tokens ?? offer.shipping.required}</dd></div></dl>}
                    <p>{t('game.stockMarkers')}: {me?.stockMarkers}</p>
                </details>
            </>}
            <div className="action-buttons">{commands.filter(command => !command.startsWith('reduce-marker ')).map(command => <button type="button" key={command} className="secondary"
                disabled={busy} onClick={() => { void submit(command); }}>{label(command.split(' ')[0], 'action')}</button>)}</div>
            {commands.some(command => command.startsWith('reduce-marker ')) && <details className="marker-reduction">
                <summary>{t('action.reduceMarker')}</summary>
                {commands.filter(command => command.startsWith('reduce-marker ')).map(command => <button type="button" key={command}
                    className="secondary" disabled={busy} onClick={() => { void submit(command); }}>{t('table.confirm')}</button>)}
            </details>}
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
