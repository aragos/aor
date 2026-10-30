import {Fragment} from 'react';
import type {ReactNode} from 'react';
import type {Snapshot} from './api';
import {playerName, provinceName} from './labels';
import {PlayerPiece} from './map/MapSymbols';

/** Reuse the board's capital silhouette wherever a capital is named in the table. */
export function CapitalLabel({capital, children}: {capital: string | null; children: ReactNode}) {
    return <span className="identity-label">
        {capital && <svg width="16" height="16" viewBox="-28 -28 56 56" aria-hidden="true">
            <PlayerPiece capital={capital} size={44}/>
        </svg>}
        {children}
    </span>;
}

export function PlayerLabel({snapshot, id}: {snapshot: Snapshot; id: string}) {
    const player = snapshot.game.players.find(player => player.id === id);
    return <CapitalLabel capital={player?.capital ?? null}>
        {playerName(snapshot, id)}
    </CapitalLabel>;
}

export function ProvinceLabel({snapshot, id}: {snapshot: Snapshot; id: string}) {
    const province = snapshot.game.catalog?.provinces.find(province => province.id === id);
    return <CapitalLabel capital={province?.capital ?? null}>
        {provinceName(id)}
    </CapitalLabel>;
}

export function PlayerLabels({snapshot, ids, separator = ', '}: {
    snapshot: Snapshot; ids: string[]; separator?: string;
}) {
    if (!ids.length) return <>—</>;
    return <>{ids.map((id, index) => <Fragment key={id}>
        {index > 0 && separator}<PlayerLabel snapshot={snapshot} id={id}/>
    </Fragment>)}</>;
}

/** Keep translated sentence order while allowing names to retain their symbols. */
export function LabelMessage({text, values}: {text: string; values: Record<string, ReactNode>}) {
    return <>{text.split(/(\{[^}]+\})/).map((part, index) => <Fragment key={index}>
        {part.startsWith('{') ? values[part.slice(1, -1)] ?? part : part}
    </Fragment>)}</>;
}
