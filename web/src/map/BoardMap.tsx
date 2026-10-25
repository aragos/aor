import {useId, useMemo, useRef, useState} from 'react';
import type {PointerEvent as ReactPointerEvent} from 'react';
import type {ProvinceDefinition, ProvinceView, Snapshot} from '../api';
import {label, provinceName} from '../labels';
import {areaColors, CommodityIcon, commodityColors, PlayerPiece} from './MapSymbols';
import {useMapSelection} from './MapSelection';
import {neighbors} from './ProvinceInspector';
import geometryData from './boardGeometry.json';
import './map.css';

interface Geometry {
    bounds: number[];
    frame: string;
    provinces: Record<string, {path: string; label: number[]; area: string}>;
    coasts: Record<string, string>;
    areas: Record<string, {path: string; label: number[]}>;
    seas: Record<string, {path: string; label: number[]; color: string}>;
    terrain: string;
}
const geometry: Geometry = geometryData;
const initialView = {x: geometry.bounds[0], y: geometry.bounds[1], w: geometry.bounds[2], h: geometry.bounds[3]};

function ProvinceLabel({province, pieces, snapshot}: {
    province: ProvinceDefinition; pieces?: ProvinceView; snapshot: Snapshot;
}) {
    const {label: [x, y]} = geometry.provinces[province.id];
    const name = provinceName(province.id);
    const words = name.length > 14 ? name.split(' ') : [name];
    const groups = pieces?.tokens.flatMap(holding => [
        ...(holding.coloured ? [{owner: holding.owner, count: holding.coloured, fresh: false}] : []),
        ...(holding.white ? [{owner: holding.owner, count: holding.white, fresh: true}] : []),
    ]) ?? [];
    const width = 32 + province.commodities.length * 27;
    return <g transform={`translate(${x} ${y})`} className="province-label" data-province-label={province.id} pointerEvents="none">
        {['iceland', 'west-africa'].includes(province.id) && <text y={-45} textAnchor="middle" fontSize={11} fill="#526b68" letterSpacing={1.2}>INSET</text>}
        <text textAnchor="middle" fontSize={18} fontWeight={650} fill="#263c3c">
            {words.map((word, index) => <tspan key={index} x={0} y={-25 - (words.length - index - 1) * 20}>{word}</tspan>)}
        </text>
        {province.capital && <path d="M-10 -53L-6 -46L0 -56L6 -46L10 -53L8 -42H-8Z" fill="#8f6b21"/>}
        <rect x={-width / 2} y={-12} width={width} height={33} rx={7} fill="#fffdf5" stroke="#b5b6a3" strokeWidth={.8}/>
        <text x={-width / 2 + 16} y={13} textAnchor="middle" fontSize={27} fontWeight={750} fill="#263c3c">{province.marketNumber}</text>
        {province.commodities.map((commodity, index) => <CommodityIcon key={commodity} commodity={commodity} x={-width / 2 + 31 + index * 27} y={-8} size={25}/>)}
        {pieces?.marker && <PlayerPiece x={0} y={43} size={32} fresh={pieces.marker.side === 'WHITE'}
            capital={snapshot.game.players.find(player => player.id === pieces.marker?.owner)?.capital ?? null}/>}
        {groups.map((group, index) => <PlayerPiece key={`${group.owner}-${group.fresh}`} size={27}
            x={(index % 4 - (Math.min(4, groups.length - Math.floor(index / 4) * 4) - 1) / 2) * 31}
            y={43 + Math.floor(index / 4) * 30} count={group.count} fresh={group.fresh}
            capital={snapshot.game.players.find(player => player.id === group.owner)?.capital ?? null}/>)}
        {!!province.access.length && <g transform={`translate(${width / 2 + 7} -5)`} fill="none" stroke="#6b603f" strokeWidth={2}>
            <rect x={0} y={7} width={11} height={10} rx={1} fill="#fff8dc"/><path d="M2 7V4a3.5 3.5 0 0 1 7 0v3"/>
        </g>}
    </g>;
}

function occupancyDescription(snapshot: Snapshot, pieces?: ProvinceView): string {
    const owner = (id: string) => snapshot.game.players.find(player => player.id === id)?.name ?? id;
    if (pieces?.marker) return `${owner(pieces.marker.owner)}, ${pieces.marker.side === 'WHITE' ? 'new' : 'established'} marker`;
    return pieces?.tokens.map(tokens => `${owner(tokens.owner)}, ${tokens.coloured} established and ${tokens.white} new tokens`).join('; ') || 'unoccupied';
}

export function BoardMap({snapshot, busy}: {
    snapshot: Snapshot; busy: boolean; submit: (command: string) => Promise<boolean>; error?: string; status?: string;
}) {
    const [search, setSearch] = useState('');
    const [area, setArea] = useState('');
    const [showSupport, setShowSupport] = useState(true);
    const [view, setView] = useState(initialView);
    const svg = useRef<SVGSVGElement>(null);
    const drag = useRef<{x: number; y: number; view: typeof view; moved: boolean} | null>(null);
    const suppressClick = useRef(false);
    const {picker, setPicker, selected, setSelected, setSelectedCard, setSelectedAdvance} = useMapSelection();
    const prefix = useId().replace(/:/g, '');
    const catalog = snapshot.game.catalog;
    const definitions = useMemo(() => new Map(catalog?.provinces.map(province => [province.id, province])), [catalog]);
    const occupancy = useMemo(() => new Map(snapshot.game.board.map(pieces => [pieces.id, pieces])), [snapshot.game.board]);
    if (!catalog) return null;
    const landNeighbors = new Set(neighbors(catalog.landBorders, selected));
    const coastNeighbors = new Set(neighbors(catalog.coastalConnections, selected));
    const selectedSeas = new Set(catalog.seaCoasts.filter(coast => coast.province === selected).map(coast => coast.sea));
    const matches = (id: string) => {
        const definition = definitions.get(id);
        return !!definition && (!area || (definition.area ?? definition.overseas) === area) &&
            `${provinceName(id)} ${definition.commodities.join(' ')}`.toLowerCase().includes(search.toLowerCase());
    };
    const choose = (id: string) => {
        setSelected(id);
        if (!picker) { setSelectedCard(''); setSelectedAdvance(''); }
        if (!busy && picker?.eligible.includes(id)) picker.pick(id);
    };
    const focusProvince = (id: string) => {
        choose(id);
        const [x, y] = geometry.provinces[id].label;
        setView({x: x - 450, y: y - 320, w: 900, h: 640});
    };
    const zoom = (factor: number) => setView(current => {
        const width = Math.max(500, Math.min(initialView.w, current.w * factor));
        const height = current.h * width / current.w;
        return {x: current.x + (current.w - width) / 2, y: current.y + (current.h - height) / 2, w: width, h: height};
    });
    const pointerDown = (event: ReactPointerEvent<SVGSVGElement>) => {
        if (event.button !== 0) return;
        suppressClick.current = false;
        drag.current = {x: event.clientX, y: event.clientY, view, moved: false};
    };
    const pointerMove = (event: ReactPointerEvent<SVGSVGElement>) => {
        const start = drag.current;
        if (!start || !svg.current) return;
        const dx = event.clientX - start.x, dy = event.clientY - start.y;
        if (Math.abs(dx) + Math.abs(dy) < 5 && !start.moved) return;
        start.moved = true;
        svg.current.setPointerCapture(event.pointerId);
        suppressClick.current = true;
        const rect = svg.current.getBoundingClientRect();
        const scale = Math.min(rect.width / start.view.w, rect.height / start.view.h);
        setView({...start.view, x: Math.max(initialView.x - start.view.w / 2, Math.min(initialView.x + initialView.w - start.view.w / 2, start.view.x - dx / scale)),
            y: Math.max(initialView.y - start.view.h / 2, Math.min(initialView.y + initialView.h - start.view.h / 2, start.view.y - dy / scale))});
    };
    const activeAreas = new Set(catalog.provinces.map(province => province.area ?? province.overseas));
    return <section className="interactive-board" id="interactive-board" aria-label="The world">
        <div className="map-toolbar">
            <label>Find a province or commodity<input type="search" value={search} onChange={event => setSearch(event.target.value)} placeholder="Province or commodity"/></label>
            <label>Area<select value={area} onChange={event => setArea(event.target.value)}><option value="">All areas</option>
                {[...activeAreas].map(area => <option key={area} value={area!}>{label(area!)}</option>)}</select></label>
            <label>Go to<select aria-label="Go to province" value="" onChange={event => {if (event.target.value) focusProvince(event.target.value);}}>
                <option value="">Select a province</option>{catalog.provinces.filter(province => matches(province.id)).map(province => <option key={province.id} value={province.id}>{provinceName(province.id)}</option>)}
            </select></label>
            <div className="map-zoom"><button type="button" className="secondary" aria-label="Zoom in" onClick={() => zoom(.7)}>+</button>
                <button type="button" className="secondary" aria-label="Zoom out" onClick={() => zoom(1.4)}>−</button>
                <button type="button" className="secondary" onClick={() => setView(initialView)}>Fit map</button></div>
        </div>
        {picker && <div className="map-pick-banner" role="status"><strong>{picker.title}</strong>
            <button type="button" className="secondary" onClick={() => {setPicker(null); document.querySelector('.decision')?.scrollIntoView({behavior: 'smooth'});}}>Return to order</button></div>}
        {(search || area) && <p className="small" role="status">{catalog.provinces.filter(province => matches(province.id)).length} matching provinces</p>}
        <div className="map-viewport">
            <svg ref={svg} className="world-map" viewBox={`${view.x} ${view.y} ${view.w} ${view.h}`} role="group" aria-label="Interactive game map" fillRule="evenodd"
                onPointerDown={pointerDown} onPointerMove={pointerMove} onPointerUp={() => {drag.current = null;}}
                onPointerCancel={() => {drag.current = null; suppressClick.current = true;}}>
                <defs>
                    <clipPath id={`${prefix}-frame`}><path d={geometry.frame}/></clipPath>
                    <pattern id={`${prefix}-satellite`} width="12" height="12" patternUnits="userSpaceOnUse"><rect width="12" height="12" fill="#e0e4d9"/><circle cx="4" cy="4" r=".8" fill="#879481"/></pattern>
                    <marker id={`${prefix}-arrow`} viewBox="0 0 24 24" refX="21" refY="12" markerWidth="22" markerHeight="22" markerUnits="userSpaceOnUse" orient="auto"><path d="M2 2L21 12L2 22Z" fill="#263d4b" stroke="#fffdf3" strokeWidth="2"/></marker>
                    {catalog.provinces.filter(province => province.commodities.length > 1).map(province => <linearGradient id={`${prefix}-${province.id}`} key={province.id}>
                        {province.commodities.flatMap((commodity, index) => [<stop key={`${commodity}-start`} offset={`${index / province.commodities.length * 100}%`} stopColor={commodityColors[commodity][0]}/>,
                            <stop key={`${commodity}-end`} offset={`${(index + 1) / province.commodities.length * 100}%`} stopColor={commodityColors[commodity][0]}/>])}
                    </linearGradient>)}
                </defs>
                <rect x={initialView.x - 2000} y={initialView.y - 2000} width={initialView.w + 4000} height={initialView.h + 4000} fill="#eae8dd"/>
                <g clipPath={`url(#${prefix}-frame)`}>
                    <path d={geometry.frame} fill="#90b8cb"/>
                    {Object.entries(geometry.seas).map(([sea, shape]) => <g key={sea} data-sea={sea}><title>{label(sea)}</title><path d={shape.path} fill={shape.color} stroke="#739eac" strokeWidth={1}/>
                        <text x={shape.label[0]} y={shape.label[1]} textAnchor="middle" fontSize={20} fill="#315d70" fontWeight={selectedSeas.has(sea) ? 750 : 500}>{label(sea)}</text></g>)}
                    <path d={geometry.terrain} fill="#e3e3d5" stroke="#b3b7a5"/>
                    {Object.entries(geometry.coasts).map(([id, path]) => definitions.has(id) && <path key={id} d={path} data-coastal-space={id}
                        fill={id === selected || coastNeighbors.has(id) ? '#9adddf' : '#d2efed'} stroke="#56929c" strokeWidth={1.4}
                        onClick={() => {if (!suppressClick.current) choose(id);}}><title>{provinceName(id)} coastal water</title></path>)}
                    <g fill="#f6f3e8" stroke="#a5b5aa" strokeWidth={1.5} pointerEvents="none">
                        <rect x={3} y={630} width={182} height={405} rx={8}/><rect x={3} y={1050} width={182} height={600} rx={8}/>
                    </g>
                    {Object.entries(geometry.provinces).map(([id, shape]) => {
                        const definition = definitions.get(id);
                        const pieces = occupancy.get(id);
                        const picked = picker?.selected.includes(id);
                        const eligible = picker?.eligible.includes(id);
                        return <g key={id} data-map-province={id} role={definition ? 'button' : undefined}
                            tabIndex={definition ? 0 : undefined} aria-pressed={definition ? id === selected : undefined}
                            aria-label={definition ? `${provinceName(id)}, market ${definition.marketNumber}, ${definition.satellite ? 'satellite' : definition.commodities.map(value => label(value)).join(', ')}, ${occupancyDescription(snapshot, pieces)}` : undefined}
                            className={`map-province ${definition ? 'active' : 'inactive'} ${definition && !matches(id) ? 'map-filtered' : ''} ${picked ? 'picked' : ''} ${eligible ? 'eligible' : ''}`}
                            onClick={() => {if (definition && !suppressClick.current) choose(id);}} onKeyDown={event => {
                                if (definition && (event.key === 'Enter' || event.key === ' ')) {event.preventDefault(); suppressClick.current = false; choose(id);}
                            }}>
                            <title>{provinceName(id)}{definition ? '' : ' · not used with this player count'}</title>
                            <path className="province-shape" d={shape.path}
                                fill={!definition ? '#daddd1' : definition.satellite ? `url(#${prefix}-satellite)` : definition.commodities.length > 1 ? `url(#${prefix}-${id})` : commodityColors[definition.commodities[0]][0]}
                                stroke="#777d6c" strokeWidth={1.4}/>
                            {(id === selected || picked || eligible || landNeighbors.has(id)) && <path d={shape.path} fill="none" pointerEvents="none"
                                stroke={id === selected || picked ? '#143e4e' : eligible ? '#2b6d9a' : '#fffdf3'} strokeWidth={id === selected || picked ? 6 : 4}
                                strokeDasharray={eligible && !picked && id !== selected ? '8 5' : undefined}/>}
                        </g>;
                    })}
                    {Object.entries(geometry.areas).map(([area, shape]) => <g key={area} pointerEvents="none" opacity={activeAreas.has(area) ? 1 : .3} data-map-area={area}>
                        <path d={shape.path} fill="none" stroke="#fffdf3" strokeWidth={9} strokeLinejoin="round"/>
                        <path d={shape.path} fill="none" stroke={areaColors[area]} strokeWidth={4.5} strokeLinejoin="round"/>
                        <rect x={shape.label[0] - 34} y={shape.label[1] - 18} width={68} height={36} rx={6} fill={areaColors[area]} stroke="#fffdf3" strokeWidth={2.5}/>
                        <text x={shape.label[0]} y={shape.label[1] + 7} fontSize={21} fontWeight={750} fill="#fffdf3" textAnchor="middle">{area}</text>
                    </g>)}
                    {showSupport && catalog.satelliteSupports.map(edge => {
                        const [x1, y1] = geometry.provinces[edge.satellite].label, [x2, y2] = geometry.provinces[edge.market].label;
                        const distance = Math.hypot(x2 - x1, y2 - y1), dx = (x2 - x1) / distance, dy = (y2 - y1) / distance;
                        const gap = Math.min(58, distance * .34);
                        // Vertical arrows pass beside the name and pieces below the market value.
                        const offset = Math.abs(dx) < .35 ? 28 : 0;
                        const d = `M${x1 + dx * gap - dy * offset} ${y1 + dy * gap + dx * offset}L${x2 - dx * gap - dy * offset} ${y2 - dy * gap + dx * offset}`;
                        return <g key={`${edge.satellite}-${edge.market}`} data-support={`${edge.satellite} ${edge.market}`} pointerEvents="none">
                            <path d={d} fill="none" stroke="#fffdf3" strokeWidth={7}/><path d={d} fill="none" stroke="#263d4b" strokeWidth={3.5} markerEnd={`url(#${prefix}-arrow)`}/>
                        </g>;
                    })}
                    {catalog.provinces.map(province => <ProvinceLabel key={province.id} province={province} pieces={occupancy.get(province.id)} snapshot={snapshot}/>)}
                    <g pointerEvents="none" fill="#315d70" fontSize={18} fontWeight={700} textAnchor="middle"><text x={95} y={660}>NEW WORLD</text><text x={95} y={1080}>FAR EAST</text></g>
                </g>
                <path d={geometry.frame} fill="none" stroke="#84958b" strokeWidth={2} pointerEvents="none"/>
            </svg>
        </div>
        <details className="commodity-legend"><summary>Map key</summary>        <div className="map-legend">
            <span><svg viewBox="-24 -24 48 48" width="24" height="24" aria-hidden="true"><PlayerPiece capital="barcelona" size={40}/></svg>Marker</span>
            <span><svg viewBox="-24 -24 48 48" width="24" height="24" aria-hidden="true"><PlayerPiece capital="venice" count={2} size={40}/></svg>Tokens · count</span>
            <span><svg viewBox="-24 -24 48 48" width="24" height="24" aria-hidden="true"><PlayerPiece capital="barcelona" fresh size={40}/></svg>New · white face</span>
            <span>Filled · established</span><span className="satellite-key">Satellite</span><span className="coast-key">Coastal water</span>
            <label className="check-label"><input type="checkbox" checked={showSupport} onChange={event => setShowSupport(event.target.checked)}/>Support arrows</label>
        </div>
<div className="map-legend">{Object.keys(commodityColors).map(commodity => <span key={commodity}>
            <svg width="24" height="24" viewBox="0 0 24 24" aria-hidden="true"><CommodityIcon commodity={commodity}/></svg>{label(commodity, 'commodity')}</span>)}</div>

        </details>

    </section>;
}
