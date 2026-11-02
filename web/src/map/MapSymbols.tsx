import type {PlayerView} from '../api';

export const commodityColors: Record<string, [string, string]> = {
    WINE: ['#edbfd0', '#893453'], WOOL: ['#f4dfb9', '#83551c'], SILK: ['#d9c4ed', '#70439a'],
    GRAIN: ['#f3d575', '#735313'], METAL: ['#b9cddb', '#365b75'], TIMBER: ['#bedbb7', '#37683c'],
    IVORY: ['#eee4bc', '#746332'], STONE: ['#d7d5ca', '#626152'], CLOTH: ['#bcded9', '#2d6d68'],
    FUR: ['#d6bca7', '#775036'], GOLD: ['#edbf5c', '#8b6312'], SPICE: ['#ebae91', '#963c25'],
};

export const areaColors: Record<string, string> = {
    I: '#3e668e', II: '#827127', III: '#41715e', IV: '#89651a', V: '#a44b3c',
    VI: '#577726', VII: '#586d73', VIII: '#78436f', FAR_EAST: '#42677c', NEW_WORLD: '#77523c',
};

/** Screen-sized stippling distinguishes satellites from solid commodity fills at any zoom. */
export function SatellitePattern({id, scale = 1}: {id: string; scale?: number}) {
    return <pattern id={id} width="7" height="7" patternUnits="userSpaceOnUse"
        patternTransform={`scale(${scale})`}>
        <rect width="7" height="7" fill="#dce7c6"/>
        <circle cx="3.5" cy="3.5" r="1.15" fill="#657a4f"/>
    </pattern>;
}

/** Missing access advances shade geography without replacing its commodity colors. */
export function LockedAreaPattern({id, scale = 1}: {id: string; scale?: number}) {
    return <pattern id={id} width="10" height="10" patternUnits="userSpaceOnUse"
        patternTransform={`scale(${scale})`}>
        <rect width="10" height="10" fill="#e9e5db" opacity={.45}/>
        <path d="M-2 2L2 -2M0 10L10 0M8 12L12 8" fill="none"
            stroke="#675d4f" strokeWidth={1.1} opacity={.35}/>
    </pattern>;
}

/**
 * Geometry exports a straight M…L… centerline. Stop visible strokes 10 SVG units before its tip,
 * inside the arrowhead, while the unpainted centerline keeps the marker at the catalogued endpoint.
 */
export function SupportArrow({path, markerId}: {path: string; markerId: string}) {
    const [start, end] = path.slice(1).split('L').map(point => point.split(' ').map(Number));
    const inset = 10 / Math.hypot(end[0] - start[0], end[1] - start[1]);
    const shaft = `M${start[0]} ${start[1]}L${end[0] + (start[0] - end[0]) * inset} ${end[1] + (start[1] - end[1]) * inset}`;
    return <>
        <path d={shaft} fill="none" stroke="#fffdf3" strokeWidth={7}/>
        <path d={shaft} fill="none" stroke="#263d4b" strokeWidth={3.5}/>
        <path d={path} fill="none" stroke="none" markerEnd={`url(#${markerId})`}/>
    </>;
}

export function AccessLock({x = 0, y = 0, size = 24, color = '#315d70'}: {
    x?: number; y?: number; size?: number; color?: string;
}) {
    return <g transform={`translate(${x} ${y}) scale(${size / 24})`} fill="none" stroke={color} strokeWidth={2.5}>
        <rect x={4} y={10} width={16} height={12} rx={2}/><path d="M7 10V6a5 5 0 0 1 10 0v4"/>
    </g>;
}

const identities: Record<string, {color: string; path: string}> = {
    venice: {color: '#187d7f', path: 'M0 -24L23 0L0 24L-23 0Z'},
    genoa: {color: '#365eb1', path: 'M23 0A23 23 0 1 1 -23 0A23 23 0 1 1 23 0Z'},
    barcelona: {color: '#b84d36', path: 'M-22 -22H22V1Q20 17 0 26Q-20 17 -22 1Z'},
    paris: {color: '#7950a4', path: 'M-13 -23H13L26 0L13 23H-13L-26 0Z'},
    london: {color: '#8b6d16', path: 'M-22 -22H22V22H-22Z'},
    hamburg: {color: '#3b7541', path: 'M0 -26L25 21H-25Z'},
};

export function PlayerPiece({capital, fresh = false, count, x = 0, y = 0, size = 32}: {
    capital: PlayerView['capital']; fresh?: boolean; count?: number; x?: number; y?: number; size?: number;
}) {
    const identity = identities[capital ?? ''] ?? {color: '#626b6a', path: 'M-20 -20H20V20H-20Z'};
    return <g transform={`translate(${x} ${y}) scale(${size / 48})`} data-piece-owner={capital ?? 'unassigned'}
        data-piece-kind={count === undefined ? 'marker' : 'tokens'} data-piece-age={fresh ? 'new' : 'established'}>
        <path d={identity.path} fill={fresh ? '#fffdf4' : identity.color} stroke={identity.color} strokeWidth={3}/>
        {count !== undefined && <text textAnchor="middle" y={8} fontSize={27} fontWeight={750}
            fill={fresh ? identity.color : '#fffdf4'}>{count}</text>}
    </g>;
}

const commodityPaths: Record<string, string> = {
    WINE: 'M3 1H15V7Q15 14 9 14Q3 14 3 7ZM9 14V20M4 20H14',
    WOOL: 'M18 10A9 8 0 1 1 0 10A9 8 0 1 1 18 10M3 4Q15 7 16 14M1 8Q12 9 13 17M6 2Q11 8 5 17',
    SILK: 'M2 2Q7 6 15 2L18 18Q9 14 5 19ZM7 4L10 16M12 4L14 16',
    GRAIN: 'M9 2V20M9 6L3 2M9 10L2 6M9 14L2 10M9 6L15 2M9 10L16 6M9 14L16 10',
    METAL: 'M2 18L6 3H13L17 18ZM7 15L9 7M12 7L14 15',
    TIMBER: 'M9 0L2 7H5L0 13H6V20H12V13H18L13 7H16Z',
    IVORY: 'M5 1Q20 9 6 20Q14 10 5 1Z',
    STONE: 'M1 6L5 1H14L18 7V19H1ZM1 10H18M7 10V19M12 1V10',
    CLOTH: 'M1 2H17V19H1ZM1 8H17M1 14H17M6 2V19M12 2V19',
    FUR: 'M5 1L9 4L13 1L18 6L14 9L15 16L12 20L9 17L6 20L3 16L4 9L0 6Z',
    GOLD: 'M1 18L5 6H14L18 18ZM5 6L8 2H15L14 6M18 18L20 13L15 2',
    SPICE: 'M4 2H14L12 7Q20 15 14 20H4Q-2 15 6 7ZM5 7H13M7 12H11M7 16H11',
};

export function CommodityIcon({commodity, x = 0, y = 0, size = 24}: {
    commodity: string; x?: number; y?: number; size?: number;
}) {
    return <g transform={`translate(${x} ${y}) scale(${size / 24})`}>
        <circle cx={12} cy={12} r={12} fill={commodityColors[commodity]?.[0] ?? '#ddd'}/>
        <path d={commodityPaths[commodity]} transform="translate(3 2) scale(.9)" fill="none"
            stroke={commodityColors[commodity]?.[1] ?? '#333'} strokeWidth={1.8} strokeLinejoin="round"/>
    </g>;
}
