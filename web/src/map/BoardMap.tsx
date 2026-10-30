import {useEffect, useId, useLayoutEffect, useMemo, useRef, useState} from 'react';
import type {KeyboardEvent as ReactKeyboardEvent, PointerEvent as ReactPointerEvent} from 'react';
import type {ProvinceDefinition, ProvinceView, Snapshot} from '../api';
import {label, playerName, provinceName} from '../labels';
import {t} from '../messages';
import {HelpLink} from '../HelpLink';
import {AccessLock, areaColors, CommodityIcon, commodityColors, PlayerPiece} from './MapSymbols';
import {useMapSelection} from './MapSelection';
import {neighbors} from './ProvinceInspector';
import geometryData from './boardGeometry.json';
import europeanGeometryData from './europeanBoardGeometry.json';
import './map.css';

interface Geometry {
    bounds: number[];
    frame: string;
    provinces: Record<string, {path: string; outline: string; label: number[]; area: string;
        pieces: Record<string, {size: number; fit: string; crowded: boolean; points: number[][]}>}>;
    coasts: Record<string, string>;
    areas: Record<string, {path: string; label: number[]}>;
    seas: Record<string, {path: string; label: number[]; labelAngle: number; color: string; area: string | null}>;
    terrain: string;
    supports: Record<string, string>;
}
interface MapView {x: number; y: number; w: number; h: number}

/** Trim unused eastern provinces on smaller boards without changing the province geometry. */
function mapBounds(provinces: ProvinceDefinition[], geometry: Geometry): MapView {
    const initialView = {x: geometry.bounds[0], y: geometry.bounds[1], w: geometry.bounds[2], h: geometry.bounds[3]};
    const right = Math.max(...provinces.flatMap(province =>
        (geometry.provinces[province.id]?.path.match(/-?\d+(?:\.\d+)?/g) ?? [])
            .filter((_, index) => index % 2 === 0).map(Number)));
    return {...initialView, w: Math.min(initialView.w, Number.isFinite(right) ? right + 12 : initialView.w)};
}

function clampView(view: MapView, bounds: MapView): MapView {
    const w = Math.min(bounds.w, Math.max(500, view.w)), h = w * bounds.h / bounds.w;
    return {w, h, x: Math.max(bounds.x, Math.min(bounds.x + bounds.w - w, view.x)),
        y: Math.max(bounds.y, Math.min(bounds.y + bounds.h - h, view.y))};
}

/** Land and coastal water share one province's shading, selection, and target emphasis. */
function ProvinceHighlight({path, reachable, selected, eligible, neighbor, emphasized, outline = true, className = 'province-highlight', strokeWidth}: {
    path: string; reachable?: boolean; selected: boolean; eligible: boolean;
    neighbor: boolean; emphasized: boolean; outline?: boolean; className?: string; strokeWidth?: number;
}) {
    return <>
        {reachable !== undefined && <path className="reach-shade" d={path}
            fill={reachable ? '#ffffff' : '#142a31'} opacity={reachable ? .25 : .3} pointerEvents="none"/>}
        {outline && eligible && <path className="eligible-shade" d={path} fill="#1f83a6" opacity={selected ? .32 : .23} pointerEvents="none"/>}
        {outline && (selected || eligible || neighbor || emphasized) && <path className={className} d={path} fill="none" pointerEvents="none"
            stroke={selected || emphasized ? '#143e4e' : eligible ? '#2b6d9a' : '#fffdf3'}
            strokeWidth={strokeWidth ?? (selected ? 6 : 4)}/>}
    </>;
}

function highlightPriority(state: {selected: boolean; emphasized: boolean; eligible: boolean; neighbor: boolean}): number {
    return state.selected ? 4 : state.emphasized ? 3 : state.eligible ? 2 : state.neighbor ? 1 : 0;
}

function ProvinceLabel({province, pieces, snapshot, geometry, dimmed = false}: {
    province: ProvinceDefinition; pieces?: ProvinceView; snapshot: Snapshot; geometry: Geometry; dimmed?: boolean;
}) {
    const {label: [x, y]} = geometry.provinces[province.id];
    const name = provinceName(province.id);
    const words = name.length > 14 ? name.split(' ') : [name];
    const groups = pieces?.tokens.flatMap(holding => [
        ...(holding.coloured ? [{owner: holding.owner, count: holding.coloured, fresh: false}] : []),
        ...(holding.white ? [{owner: holding.owner, count: holding.white, fresh: true}] : []),
    ]) ?? [];
    const layout = geometry.provinces[province.id].pieces[String(pieces?.marker ? 1 : groups.length)];
    const piecePosition = (index: number) => ({x: layout.points[index][0] - x, y: layout.points[index][1] - y});
    const width = 32 + province.commodities.length * 27;
    return <g transform={`translate(${x} ${y})`} className="province-label" data-province-label={province.id} pointerEvents="none" opacity={dimmed ? .45 : 1}>
        {['iceland', 'west-africa'].includes(province.id) && <text y={-45} textAnchor="middle" fontSize={11} fill="#526b68" letterSpacing={1.2}>{t('board.inset')}</text>}
        {!layout?.crowded && <><text className="province-name" textAnchor="middle" fontSize={18} fontWeight={650} fill="#263c3c">
            {/* Match the geometry generator's width budget across platform font substitutions. */}
            {words.map((word, index) => <tspan key={index} x={0} y={-25 - (words.length - index - 1) * 20}
                textLength={word.length * 9.5} lengthAdjust="spacingAndGlyphs">{word}</tspan>)}
        </text>
        <g className="market-label" data-capital-market={province.capital ? province.id : undefined}>
            <rect x={-width / 2} y={-12} width={width} height={33} rx={7} fill={province.capital ? '#263c3c' : '#fffdf5'} stroke="#b5b6a3" strokeWidth={.8}/>
            <text x={-width / 2 + 16} y={13} textAnchor="middle" fontSize={27} fontWeight={750} fill={province.capital ? '#fffdf5' : '#263c3c'}>{province.marketNumber}</text>
            {province.commodities.map((commodity, index) => <CommodityIcon key={commodity} commodity={commodity} x={-width / 2 + 31 + index * 27} y={-8} size={25}/>)}
        </g></>}
        {pieces?.marker && <PlayerPiece {...piecePosition(0)} size={layout.size} fresh={pieces.marker.side === 'WHITE'}
            capital={snapshot.game.players.find(player => player.id === pieces.marker?.owner)?.capital ?? null}/>}
        {groups.map((group, index) => <PlayerPiece key={`${group.owner}-${group.fresh}`} size={Math.min(27, layout.size)}
            {...piecePosition(index)} count={group.count} fresh={group.fresh}
            capital={snapshot.game.players.find(player => player.id === group.owner)?.capital ?? null}/>)}
    </g>;
}

function occupancyDescription(snapshot: Snapshot, pieces?: ProvinceView): string {
    const owner = (id: string) => playerName(snapshot, id);
    if (pieces?.marker) return t('board.markerOccupancy', {owner: owner(pieces.marker.owner), age: t(pieces.marker.side === 'WHITE' ? 'board.new' : 'board.old')});
    return pieces?.tokens.map(tokens => t('board.tokenOccupancy', {owner: owner(tokens.owner), old: tokens.coloured, fresh: tokens.white})).join('; ') || t('table.unoccupied');
}

export function BoardMap({snapshot, busy}: {
    snapshot: Snapshot; busy: boolean; submit: (command: string) => Promise<boolean>; error?: string; status?: string;
}) {
    const geometry: Geometry = snapshot.game.rules.map === 'EUROPEAN' ? europeanGeometryData : geometryData;
    const [inspectOnly, setInspectOnly] = useState(false);
    const [hovered, setHovered] = useState('');
    const [focused, setFocused] = useState('');
    const [tabProvince, setTabProvince] = useState('');
    const [tabArea, setTabArea] = useState('');
    const bounds = useMemo(() => mapBounds(snapshot.game.catalog?.provinces ?? [], geometry), [snapshot.game.catalog, geometry]);
    const [view, setView] = useState(bounds);
    useEffect(() => setView(current => clampView(current, bounds)), [bounds.w, bounds.h]);
    const svg = useRef<SVGSVGElement>(null);
    const [mapWidth, setMapWidth] = useState(0);
    useEffect(() => {
        if (!svg.current) return;
        const observer = new ResizeObserver(entries => setMapWidth(entries[0].contentRect.width));
        observer.observe(svg.current);
        return () => observer.disconnect();
    }, [!!snapshot.game.catalog]);
    const pointers = useRef(new Map<number, {x: number; y: number}>());
    const gesture = useRef<{x: number; y: number; distance: number; view: MapView} | null>(null);
    const suppressClick = useRef(false);
    const {picker, selected, setSelected, selectedArea, setSelectedArea, selectedCard, setSelectedCard,
        selectedAdvance, setSelectedAdvance, focusRevision, inspection, setInspection, selectedAction} = useMapSelection();
    useLayoutEffect(() => {
        setHovered(''); setFocused('');
        if (document.activeElement instanceof SVGElement && svg.current?.contains(document.activeElement)) document.activeElement.blur();
    }, [focusRevision]);
    const prefix = useId().replace(/:/g, '');
    const catalog = snapshot.game.catalog;
    const definitions = useMemo(() => new Map(catalog?.provinces.map(province => [province.id, province])), [catalog]);
    const occupancy = useMemo(() => new Map(snapshot.game.board.map(pieces => [pieces.id, pieces])), [snapshot.game.board]);
    const inspectedProvince = inspection?.kind === 'province' ? inspection.id : inspection ? '' : selected;
    const landNeighbors = new Set(neighbors(catalog?.landBorders ?? [], inspectedProvince));
    const coastNeighbors = new Set(neighbors(catalog?.coastalConnections ?? [], inspectedProvince));
    const selectedSeas = new Set(catalog?.seaCoasts.filter(coast => coast.province === inspectedProvince).map(coast => coast.sea));
    const cardDraft = snapshot.game.cardOffers.some(offer => offer.reference === selectedCard && offer.playable);
    const advanceDraft = catalog?.advances.some(advance => advance.id === selectedAdvance && snapshot.legalChoices.commands.includes(`buy-advance ${advance.letter}`));
    const chooseArea = (area: string) => {
        if (inspectOnly) {setInspection({kind: 'closed'}); setSelectedArea(area); return;}
        setInspection(null);
        setSelected('');
        setSelectedArea(area);
        if (!picker && !cardDraft && !advanceDraft) { setSelectedCard(''); setSelectedAdvance(''); }
        if (!busy && picker?.kind === 'area' && picker.eligible.includes(area)) picker.pick(area);
    };
    const clearInspection = () => {
        setInspection({kind: 'closed'});
        setSelectedArea('');
        setHovered(''); setFocused('');
        if (document.activeElement instanceof SVGElement && svg.current?.contains(document.activeElement)) document.activeElement.blur();
    };
    const choose = (id: string) => {
        if (inspectOnly) {setInspection({kind: 'province', id}); return;}
        if (picker?.kind === 'area') {
            const area = definitions.get(id)?.area;
            if (area) chooseArea(area);
            return;
        }
        if (!picker && (cardDraft || advanceDraft || !!selectedAction)) {
            setInspection({kind: 'province', id});
            return;
        }
        setInspection(null);
        setSelectedArea('');
        setSelected(id);
        if (!picker) { setSelectedCard(''); setSelectedAdvance(''); }
        if (!busy && picker?.eligible.includes(id)) picker.pick(id);
        if (!picker && window.matchMedia('(max-width: 700px)').matches) {
            requestAnimationFrame(() => document.getElementById('interactive-board')?.scrollIntoView({block: 'start'}));
        }
    };
    const reveal = ([x, y]: number[]) => setView(current =>
        x < current.x + 50 || x > current.x + current.w - 50 || y < current.y + 50 || y > current.y + current.h - 50
            ? clampView({...current, x: x - current.w / 2, y: y - current.h / 2}, bounds) : current);
    const traverse = (event: ReactKeyboardEvent<SVGGElement>, id: string, ids: string[], attribute: string) => {
        if (!['ArrowRight', 'ArrowDown', 'ArrowLeft', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
        event.preventDefault();
        const offset = ['ArrowLeft', 'ArrowUp'].includes(event.key) ? -1 : 1;
        const next = event.key === 'Home' ? ids[0] : event.key === 'End' ? ids[ids.length - 1] : ids[(ids.indexOf(id) + offset + ids.length) % ids.length];
        svg.current?.querySelector<SVGGElement>(`[${attribute}="${next}"]`)?.focus();
    };
    const zoomView = (current: MapView, factor: number, clientX?: number, clientY?: number) => {
        const rect = svg.current?.getBoundingClientRect();
        const anchorX = rect && clientX !== undefined ? (clientX - rect.left) / rect.width : .5;
        const anchorY = rect && clientY !== undefined ? (clientY - rect.top) / rect.height : .5;
        const w = Math.max(500, Math.min(bounds.w, current.w * factor)), h = w * bounds.h / bounds.w;
        return clampView({x: current.x + (current.w - w) * anchorX,
            y: current.y + (current.h - h) * anchorY, w, h}, bounds);
    };
    const zoom = (factor: number) => setView(current => zoomView(current, factor));
    const wheelZoom = useRef<(event: WheelEvent) => void>();
    wheelZoom.current = event => {
        if (!event.ctrlKey && !event.metaKey && !event.altKey) return;
        event.preventDefault();
        const pixels = event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? 300 : 1);
        setView(current => zoomView(current, Math.exp(pixels * .002), event.clientX, event.clientY));
    };
    useEffect(() => {
        const element = svg.current;
        const wheel = (event: WheelEvent) => wheelZoom.current?.(event);
        element?.addEventListener('wheel', wheel, {passive: false});
        return () => element?.removeEventListener('wheel', wheel);
    }, [!!catalog]);
    const measure = () => {
        const points = [...pointers.current.values()];
        const first = points[0], second = points[1] ?? first;
        return {x: (first.x + second.x) / 2, y: (first.y + second.y) / 2,
            distance: points.length > 1 ? Math.hypot(first.x - second.x, first.y - second.y) : 0};
    };
    const pointerDown = (event: ReactPointerEvent<SVGSVGElement>) => {
        if (event.button !== 0) return;
        pointers.current.set(event.pointerId, {x: event.clientX, y: event.clientY});
        suppressClick.current = pointers.current.size > 1;
        gesture.current = {...measure(), view};
        if (pointers.current.size > 1) svg.current?.setPointerCapture(event.pointerId);
    };
    const pointerMove = (event: ReactPointerEvent<SVGSVGElement>) => {
        const start = gesture.current;
        if (!start || !svg.current || !pointers.current.has(event.pointerId)) return;
        pointers.current.set(event.pointerId, {x: event.clientX, y: event.clientY});
        const current = measure(), dx = current.x - start.x, dy = current.y - start.y;
        if (Math.abs(dx) + Math.abs(dy) < 5 && !current.distance && !suppressClick.current) return;
        svg.current.setPointerCapture(event.pointerId);
        suppressClick.current = true;
        const rect = svg.current.getBoundingClientRect();
        const next = current.distance && start.distance
            ? zoomView(start.view, start.distance / current.distance, start.x, start.y) : start.view;
        setView(clampView({...next, x: next.x - dx * next.w / rect.width,
            y: next.y - dy * next.h / rect.height}, bounds));
    };
    const pointerEnd = (event: ReactPointerEvent<SVGSVGElement>) => {
        pointers.current.delete(event.pointerId);
        gesture.current = pointers.current.size ? {...measure(), view} : null;
    };
    if (!catalog) return null;
    const reachable = new Set(snapshot.game.expansionOffers.filter(offer => offer.reachable).map(offer => offer.destination));
    const legalDestinations = new Set(snapshot.game.expansionOffers.filter(offer => offer.primaryAction !== 'INSPECT' &&
        (offer.blockers.length === 0 || offer.partialPlacements.length > 0)).map(offer => offer.destination));
    const expansionHighlight = !picker && snapshot.game.expansionOffers.length > 0;
    const highlight = (id: string) => ({
        reachable: picker ? (picker.kind === 'area'
            ? picker.eligible.includes(definitions.get(id)?.area ?? '')
            : picker.eligible.includes(id)) : expansionHighlight && definitions.has(id) ? reachable.has(id) : undefined,
        selected: picker ? picker.kind !== 'area' && picker.selected.includes(id) : id === selected,
        eligible: picker ? (picker.kind === 'area' ? picker.eligible.includes(definitions.get(id)?.area ?? '') : picker.eligible.includes(id)) : legalDestinations.has(id),
        neighbor: landNeighbors.has(id) || coastNeighbors.has(id),
        emphasized: hovered === id || focused === id || id === inspectedProvince,
    });
    const highlightArea = (area: string) => ({
        selected: picker?.kind === 'area' ? picker.selected.includes(area) : area === selectedArea,
        eligible: picker?.kind === 'area' && picker.eligible.includes(area),
        neighbor: false,
        emphasized: hovered === `area:${area}` || focused === `area:${area}`,
    });
    const emphasizedArea = selectedArea || (picker?.kind === 'area' ? picker.selected[0] : '') ||
        (hovered.startsWith('area:') ? hovered.slice(5) : '') || (focused.startsWith('area:') ? focused.slice(5) : '');
    const areaEntries = Object.entries(geometry.areas).sort(([first], [second]) => first.localeCompare(second));
    const lockedProvinces = new Set(snapshot.game.lockedProvinces);
    const areaAccess = Object.fromEntries(areaEntries.map(([area]) => {
        const members = catalog.provinces.filter(province => province.area === area);
        return [area, members.length && members.every(province => lockedProvinces.has(province.id))
            ? members[0].access.filter(requirement => members.every(province => province.access.includes(requirement))) : []];
    }));
    const overseasLocks = Object.fromEntries(['FAR_EAST', 'NEW_WORLD'].map(group => {
        const members = catalog.provinces.filter(province => province.overseas === group);
        return [group, members.length > 0 && members.every(province => lockedProvinces.has(province.id))];
    }));
    const highlights = [
        ...catalog.provinces.map(province => ({id: province.id, kind: 'province', inspected: province.id === inspectedProvince, path: geometry.provinces[province.id].outline, state: highlight(province.id)})),
        ...areaEntries.map(([area, shape]) => ({id: area, kind: 'area', inspected: area === selectedArea, path: shape.path, state: highlightArea(area)})),
    ].filter(item => highlightPriority(item.state) > 0)
        .sort((first, second) => highlightPriority(first.state) - highlightPriority(second.state) || Number(first.inspected) - Number(second.inspected));
    const activeAreas = new Set(catalog.provinces.map(province => province.area ?? province.overseas));
    const inspectedPieces = occupancy.get(inspectedProvince);
    const groupCount = inspectedPieces?.marker ? 1 : inspectedPieces?.tokens.reduce((count, tokens) =>
        count + Number(tokens.coloured > 0) + Number(tokens.white > 0), 0) ?? 0;
    const inspectedLayout = geometry.provinces[inspectedProvince]?.pieces[String(groupCount)];
    const denseInspection = inspectedLayout && (inspectedLayout.crowded || inspectedLayout.size * mapWidth / view.w < 14);
    // Keep the sea texture sparse at Fit, practical zoom, and narrow screen widths.
    const seaHatchScale = mapWidth ? view.w / mapWidth : 1;
    return <section className="interactive-board" id="interactive-board" aria-label={t('board.world')}>
        {picker && <div className="map-pick-banner" role="status"><strong>{picker.title}</strong></div>}
        <div className="map-viewport">
            <div className="map-zoom" role="group" aria-label={t('board.zoom')}>
                <button type="button" className="secondary" aria-label={t('board.zoomIn')} onClick={() => zoom(.7)}>+</button>
                <button type="button" className="secondary" aria-label={t('board.zoomOut')} onClick={() => zoom(1.4)}>−</button>
                <button type="button" className="secondary" onClick={() => setView(bounds)}>{t('board.fit')}</button>
                <button type="button" className="secondary" aria-pressed={inspectOnly} onClick={() => setInspectOnly(current => !current)}>{t('table.inspection')}</button>
                <HelpLink topic="map-legend" label={t('board.mapKey')}/>
            </div>
            <svg ref={svg} className="world-map" viewBox={`${view.x} ${view.y} ${view.w} ${view.h}`} role="group" aria-label={t('board.interactiveMap')} fillRule="evenodd"
                style={{aspectRatio: `${bounds.w} / ${bounds.h}`, height: 'auto', minHeight: 0}}
                onPointerDown={pointerDown} onPointerMove={pointerMove} onPointerUp={pointerEnd}
                onPointerLeave={() => setHovered('')}
                onClick={event => {
                    if (!suppressClick.current && event.target instanceof Element && !event.target.closest('[data-map-province].active, [data-coastal-space], [data-map-area-label]')) clearInspection();
                }}
                onPointerCancel={event => {pointerEnd(event); suppressClick.current = true;}}>
                <defs>
                    <clipPath id={`${prefix}-frame`}><path d={geometry.frame}/></clipPath>
                    {Object.entries(geometry.areas).map(([area, shape]) => <clipPath key={area} id={`${prefix}-area-${area}`}>
                        <path d={shape.path} clipRule="evenodd"/>
                    </clipPath>)}
                    <pattern id={`${prefix}-satellite`} width="12" height="12" patternUnits="userSpaceOnUse"><rect width="12" height="12" fill="#e0e4d9"/><circle cx="4" cy="4" r=".8" fill="#879481"/></pattern>
                    <pattern id={`${prefix}-connected-sea`} className="sea-connection-pattern" width="16" height="16"
                        patternUnits="userSpaceOnUse" patternTransform={`scale(${seaHatchScale})`}>
                        <rect className="sea-connection-water" width="16" height="16"/>
                        <path className="sea-connection-hatch" d="M0 16L16 0"/>
                    </pattern>
                    <marker id={`${prefix}-arrow`} viewBox="0 0 24 24" refX="21" refY="12" markerWidth="22" markerHeight="22" markerUnits="userSpaceOnUse" orient="auto"><path d="M2 2L21 12L2 22Z" fill="#263d4b" stroke="#fffdf3" strokeWidth="2"/></marker>
                    {catalog.provinces.filter(province => province.commodities.length > 1).map(province => <linearGradient id={`${prefix}-${province.id}`} key={province.id}>
                        {province.commodities.flatMap((commodity, index) => [<stop key={`${commodity}-start`} offset={`${index / province.commodities.length * 100}%`} stopColor={commodityColors[commodity][0]}/>,
                            <stop key={`${commodity}-end`} offset={`${(index + 1) / province.commodities.length * 100}%`} stopColor={commodityColors[commodity][0]}/>])}
                    </linearGradient>)}
                </defs>
                <rect x={bounds.x} y={bounds.y} width={bounds.w} height={bounds.h} fill="#eae8dd"/>
                <g clipPath={`url(#${prefix}-frame)`}>
                    <path d={geometry.frame} fill="#90b8cb"/>
                    {Object.entries(geometry.seas).map(([sea, shape]) => <g key={sea} data-sea={sea} data-sea-area={catalog.seaAreas[sea]}
                        className={`map-sea${selectedSeas.has(sea) ? ' is-connected' : ''}`}>
                        <title>{label(sea)}{catalog.seaAreas[sea] ? ` · ${t('board.area')} ${catalog.seaAreas[sea]}` : ''}</title>
                        <path className="sea-shape" d={shape.path} fill={selectedSeas.has(sea) ? `url(#${prefix}-connected-sea)` : shape.color} stroke="#739eac" strokeWidth={1}/>
                        <text className="sea-name" x={shape.label[0]} y={shape.label[1]} textAnchor="middle" fontSize={20} fill="#315d70" fontWeight={500} aria-label={label(sea)}
                            textLength={label(sea).length <= 18 ? label(sea).length * 9.5 : undefined} lengthAdjust="spacingAndGlyphs"
                            transform={`rotate(${shape.labelAngle} ${shape.label[0]} ${shape.label[1]})`}>
                            {label(sea).length > 18 ? label(sea).split(' ').map((word, index) => <tspan key={index} x={shape.label[0]} y={shape.label[1] - 10 + index * 24}
                                textLength={word.length * 9.5} lengthAdjust="spacingAndGlyphs">{word}</tspan>) : label(sea)}
                        </text></g>)}
                    <path data-map-terrain d={geometry.terrain} fill="#e3e3d5" stroke="#b3b7a5"/>
                    {Object.entries(geometry.coasts).map(([id, path]) => definitions.has(id) && <g key={id} data-coastal-space={id}
                        onPointerEnter={() => setHovered(id)} onPointerLeave={() => setHovered('')}
                        onClick={() => {if (!suppressClick.current) choose(id);}}>
                        <title>{provinceName(id)} {t('board.coastalWater')}</title>
                        <path d={path} fill={id === inspectedProvince || coastNeighbors.has(id) ? '#9adddf' : '#d2efed'} stroke="#56929c" strokeWidth={1.4}/>
                        <ProvinceHighlight path={path} {...highlight(id)} outline={false}/>
                    </g>)}
                    <g fill="#f6f3e8" stroke="#a5b5aa" strokeWidth={1.5} pointerEvents="none">
                        <rect x={3} y={630} width={182} height={405} rx={8}/><rect x={3} y={1050} width={182} height={600} rx={8}/>
                    </g>
                    {Object.entries(geometry.provinces).map(([id, shape]) => {
                        const definition = definitions.get(id);
                        const pieces = occupancy.get(id);
                        const picked = picker?.selected.includes(id);
                        const eligible = picker ? picker.kind === 'area' ? !!definition?.area && picker.eligible.includes(definition.area) : picker.eligible.includes(id) : legalDestinations.has(id);
                        return <g key={id} data-map-province={id} role={definition ? 'button' : undefined}
                            tabIndex={definition ? id === (tabProvince || catalog.provinces[0]?.id) ? 0 : -1 : undefined}
                            aria-pressed={definition ? picker ? picker.selected.includes(id) : id === selected : undefined}
                            aria-label={definition ? `${provinceName(id)}${definition.capital ? `, ${t('board.capital')}` : ''}, ${t('board.market')} ${definition.marketNumber}, ${definition.satellite ? t('board.satellite') : definition.commodities.map(value => label(value)).join(', ')}, ${occupancyDescription(snapshot, pieces)}${!picker && reachable.has(id) ? `, ${t('board.reachable')}` : ''}` : undefined}
                            className={`map-province ${definition ? 'active' : 'inactive'} ${expansionHighlight && definition ? reachable.has(id) ? 'expansion-reachable' : 'expansion-unreachable' : ''} ${picked ? 'picked' : ''} ${eligible ? 'eligible' : ''}`}
                            onPointerEnter={() => {if (definition) setHovered(id);}} onPointerLeave={() => setHovered('')}
                            onFocus={() => {
                                setFocused(id); setTabProvince(id);
                                reveal(shape.label);
                            }} onBlur={() => setFocused('')}
                            onClick={() => {if (definition && !suppressClick.current) choose(id);}} onKeyDown={event => {
                                if (definition) traverse(event, id, catalog.provinces.map(province => province.id), 'data-map-province');
                                if (definition && (event.key === 'Enter'  || event.key === ' ')) {event.preventDefault(); suppressClick.current = false; choose(id);}
                            }}>
                            <title>{provinceName(id)}{definition ? '' : ` · ${t('board.notUsed')}`}</title>
                            <path className="province-shape" d={shape.path}
                                fill={!definition ? '#daddd1' : definition.satellite ? `url(#${prefix}-satellite)` : definition.commodities.length > 1 ? `url(#${prefix}-${id})` : commodityColors[definition.commodities[0]][0]}
                                stroke="#777d6c" strokeWidth={1.4}/>
                            {definition && <ProvinceHighlight path={shape.path} {...highlight(id)} outline={false}/>}
                        </g>;
                    })}
                    {areaEntries.map(([area, shape]) => <g key={area} pointerEvents="none" opacity={activeAreas.has(area) ? 1 : .3} data-map-area={area}>
                        <path d={shape.path} fill="none" stroke={areaColors[area]} strokeWidth={8} strokeLinejoin="round"
                            clipPath={`url(#${prefix}-area-${area})`}/>
                    </g>)}
                    {catalog.satelliteSupports.map(edge => {
                        const d = geometry.supports[`${edge.satellite} ${edge.market}`];
                        return <g key={`${edge.satellite}-${edge.market}`} data-support={`${edge.satellite} ${edge.market}`} pointerEvents="none">
                            <path d={d} fill="none" stroke="#fffdf3" strokeWidth={7}/><path d={d} fill="none" stroke="#263d4b" strokeWidth={3.5} markerEnd={`url(#${prefix}-arrow)`}/>
                        </g>;
                    })}
                    {emphasizedArea && <path data-area-dimming={emphasizedArea} d={`${geometry.frame}${geometry.areas[emphasizedArea].path}`}
                        fill="#142a31" fillRule="evenodd" opacity={.48} pointerEvents="none"/>}
                    <g className="sea-connections" pointerEvents="none" aria-hidden="true">
                        {[...selectedSeas].map(sea => <g key={sea} data-connected-sea={sea}>
                            <path className="sea-connection-halo" d={geometry.seas[sea].path}/>
                            <path className="sea-connection-outline" d={geometry.seas[sea].path}/>
                        </g>)}
                    </g>
                    <g data-map-layer="highlights" pointerEvents="none">
                        {highlights.map(item => <g key={`${item.kind}:${item.id}`} data-highlight-priority={highlightPriority(item.state)}
                            data-province-outline={item.kind === 'province' ? item.id : undefined}
                            data-area-outline={item.kind === 'area' ? item.id : undefined}>
                            {item.kind === 'area' && <path d={item.path} fill="#fffdf3" opacity={item.state.selected ? .14 : .08}/>}
                            <ProvinceHighlight path={item.path} {...item.state} reachable={undefined}
                                strokeWidth={item.kind === 'area' ? item.state.selected ? 10 : 7 : undefined}
                                className={item.kind === 'area' ? 'area-highlight' : 'province-highlight'}/>
                        </g>)}
                    </g>
                    {catalog.provinces.map(province => <ProvinceLabel key={province.id} province={province} pieces={occupancy.get(province.id)} snapshot={snapshot} geometry={geometry}
                        dimmed={!!emphasizedArea && province.area !== emphasizedArea}/>)}
                    {areaEntries.map(([area, shape]) => <g key={area} data-map-area-label={area} className="map-area-label" role="button" tabIndex={area === (tabArea || areaEntries[0]?.[0]) ? 0 : -1}
                        aria-label={`${t('board.area')} ${area}${areaAccess[area].length ? `, ${t('board.requires', {advances: areaAccess[area].map(requirement => label(requirement)).join(', ')})}` : ''}`} aria-pressed={highlightArea(area).selected}
                        opacity={activeAreas.has(area) ? 1 : .4}
                        onPointerEnter={() => setHovered(`area:${area}`)} onPointerLeave={() => setHovered('')}
                        onFocus={() => {setFocused(`area:${area}`); setTabArea(area); reveal(shape.label);}} onBlur={() => setFocused('')}
                        onClick={() => {if (!suppressClick.current) chooseArea(area);}}
                        onKeyDown={event => {traverse(event, area, areaEntries.map(([id]) => id), 'data-map-area-label'); if (event.key === 'Enter' || event.key === ' ') {event.preventDefault(); suppressClick.current = false; chooseArea(area);}}}>
                        <title>{`${t('board.area')} ${area}${areaAccess[area].length ? ` · ${areaAccess[area].map(requirement => label(requirement)).join(', ')}` : ''}`}</title>
                        <rect x={shape.label[0] - 65} y={shape.label[1] - 55} width={130} height={110} fill="transparent"/>
                        <rect className="area-label-bg" x={shape.label[0] - 44} y={shape.label[1] - 28} width={88} height={56} rx={8}
                            fill={areaColors[area]} stroke={highlightArea(area).selected ? '#143e4e' : highlightArea(area).eligible ? '#2b6d9a' : '#fffdf3'} strokeWidth={highlightArea(area).selected || highlightArea(area).eligible ? 5 : 2.5}/>
                        <text x={shape.label[0] - (areaAccess[area].length ? 10 : 0)} y={shape.label[1] + 10} fontSize={29} fontWeight={750} fill="#fffdf3" textAnchor="middle" pointerEvents="none">{area}</text>
                        {areaAccess[area].length > 0 && <g data-area-lock={area} pointerEvents="none">
                            <AccessLock x={shape.label[0] + 12} y={shape.label[1] - 12} color="#fffdf3"/>
                        </g>}
                    </g>)}
                    <g pointerEvents="none" fill="#315d70" fontSize={18} fontWeight={700} textAnchor="middle">
                        {[{group:'NEW_WORLD', title:label('NEW_WORLD'), y:660}, {group:'FAR_EAST', title:label('FAR_EAST'), y:1080}].map(({group,title,y}) => <g key={group} data-overseas-label={group}>
                            <text x={overseasLocks[group] ? 84 : 95} y={y}>{title}</text>
                            {overseasLocks[group] && <g data-overseas-lock={group}><title>{t('board.advanceRequired')}</title><AccessLock x={146} y={y - 17} size={20}/></g>}
                        </g>)}
                    </g>
                </g>
                <path d={geometry.frame} fill="none" stroke="#84958b" strokeWidth={2} pointerEvents="none"/>
            </svg>
        </div>
        {denseInspection && <div className="dense-map-detail" data-dense-province={inspectedProvince}>
            <strong>{provinceName(inspectedProvince)}</strong>
            <p>{occupancyDescription(snapshot, inspectedPieces)}</p>
        </div>}

    </section>;
}
