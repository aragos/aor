import type {CSSProperties} from 'react';
import {t} from './messages';

/** Color follows the authoritative track's ordered spaces, including terminal Chaos. */
export function MiseryBox({value, track}: {
    value: number | null; track: readonly (number | null)[] | null | undefined;
}) {
    const step = track?.indexOf(value) ?? -1;
    const last = (track?.length ?? 0) - 1;
    const style = step >= 0 && last > 0
        ? {'--misery-hue': 135 * (1 - step / last)} as CSSProperties : undefined;
    return <span className={`misery-box${value === null ? ' is-chaos' : ''}`}
        data-misery-box={value ?? 'chaos'} data-misery-step={step >= 0 ? step : undefined}
        style={style} title={`${t('game.misery')}: ${value ?? t('game.chaos')}`}>
        {value ?? t('game.chaos')}
    </span>;
}
