import {useState} from 'react';
import type {ProvinceDefinition, PublicCatalog, Snapshot} from '../api';
import {label, playerName, provinceName} from '../labels';
import {CommodityIcon, PlayerPiece} from './MapSymbols';
import {placementOrder, provinceOrders} from './mapOrders';

export function neighbors(connections: PublicCatalog['landBorders'], province: string): string[] {
    return connections.flatMap(edge => edge.first === province ? [edge.second] : edge.second === province ? [edge.first] : []);
}

export function ProvinceInspector({snapshot, province, busy, submit, select}: {
    snapshot: Snapshot; province: ProvinceDefinition; busy: boolean;
    submit: (command: string) => Promise<boolean>; select: (province: string) => void;
}) {
    const [count, setCount] = useState('1');
    const catalog = snapshot.game.catalog!;
    const pieces = snapshot.game.board.find(pieces => pieces.id === province.id);
    const me = snapshot.game.players.find(player => player.id === snapshot.viewerId)!;
    const commands = provinceOrders(snapshot, province.id);
    const canPlace = commands.some(command => command.startsWith('place '));
    const order = placementOrder(province.id, count, me.poolTokens);
    const links = (ids: string[]) => ids.length ? ids.map(id => <button type="button" className="map-link" key={id}
        onClick={() => select(id)}>{provinceName(id)}</button>) : '—';
    return <div className="province-inspector" aria-label="Selected province">
        <div>
            <h3>{provinceName(province.id)}{province.capital && ' ♛'}</h3>
            <p className="map-facts">Market <strong>{province.marketNumber}</strong> · {province.satellite ? 'Satellite' : province.commodities.map(commodity =>
                <span key={commodity}><svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><CommodityIcon commodity={commodity}/></svg>{label(commodity, 'commodity')}</span>)}
                {' · '}{province.area ? `Area ${province.area}` : label(province.overseas ?? '')}</p>
            {pieces?.marker ? <p><svg width="30" height="30" viewBox="-24 -24 48 48" aria-hidden="true"><PlayerPiece size={40}
                capital={snapshot.game.players.find(player => player.id === pieces.marker?.owner)?.capital ?? null} fresh={pieces.marker.side === 'WHITE'}/></svg>
                {' '}{playerName(snapshot, pieces.marker.owner)} · {pieces.marker.side === 'WHITE' ? 'new' : 'established'} marker</p>
                : pieces?.tokens.length ? <ul className="plain-list">{pieces.tokens.map(tokens => <li key={tokens.owner}>
                    {playerName(snapshot, tokens.owner)}: {tokens.coloured} established + {tokens.white} new tokens
                </li>)}</ul> : <p className="muted">Unoccupied</p>}
            {!!province.access.length && <p className="small">Access: {province.access.map(requirement => label(requirement, 'access')).join('; ')}</p>}
            <details><summary>Connections</summary>
                <p>Land: {links(neighbors(catalog.landBorders, province.id))}</p>
                <p>Coastal steps: {links(neighbors(catalog.coastalConnections, province.id))}</p>
                <p>Seas: {catalog.seaCoasts.filter(coast => coast.province === province.id).map(coast => label(coast.sea)).join(', ') || '—'}</p>
                <p>Supports: {links(catalog.satelliteSupports.filter(edge => edge.satellite === province.id).map(edge => edge.market))}</p>
                <p>Supported by: {links(catalog.satelliteSupports.filter(edge => edge.market === province.id).map(edge => edge.satellite))}</p>
            </details>
        </div>
        <div className="map-orders">
            <h3>Orders</h3>
            {canPlace && <form onSubmit={event => {event.preventDefault(); if (order && !busy) void submit(order);}}>
                <label>Tokens to place<input type="number" min={1} max={me.poolTokens} step={1} required value={count} onChange={event => setCount(event.target.value)}/></label>
                <button disabled={busy || !order}>Place tokens</button>
                <p className="small muted">{me.poolTokens} in your pool. Routes and the full placement are checked when submitted.</p>
            </form>}
            <div className="action-buttons">{commands.filter(command => !command.startsWith('place ')).map(command => <button key={command}
                disabled={busy} onClick={() => {void submit(command);}}>{label(command.split(' ')[0], 'action')}</button>)}</div>
            {!commands.length && <p className="small muted">No direct order is available here. Card and multi-province choices can be selected using “Choose on map” in their forms.</p>}
        </div>
    </div>;
}
