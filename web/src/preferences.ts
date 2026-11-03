import {useEffect, useState} from 'react';

export const isString = (value: unknown): value is string => typeof value === 'string';
export const isUndoPolicy = (value: unknown): value is string => typeof value === 'string' && ['regular-turn', 'competitive', 'friendly-turn', 'linear-game'].includes(value);
export const isGameFilter = (value: unknown): value is string => typeof value === 'string' && ['ongoing', 'my-move', 'finished', 'cancelled', 'abandoned', 'unavailable', 'all'].includes(value);

/** Browser preferences are optional; unavailable storage falls back to in-memory state.
 * The key stays fixed for the component's lifetime. Values are validated before use.
 */
export function useStoredSetting<T>(key: string, initial: T, accept: (value: unknown) => value is T) {
    const [value, setValue] = useState<T>(() => {
        try {
            const stored: unknown = JSON.parse(localStorage.getItem(`aor.setting.${key}`) ?? 'null');
            const migrated = key === 'undo-policy' && stored === 'friendly-game' ? 'linear-game' : stored;
            return accept(migrated) ? migrated : initial;
        } catch { return initial; }
    });
    useEffect(() => {
        try { localStorage.setItem(`aor.setting.${key}`, JSON.stringify(value)); } catch { /* Settings remain usable in memory. */ }
    }, [key, value]);
    return [value, setValue] as const;
}
