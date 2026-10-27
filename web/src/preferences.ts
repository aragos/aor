import {useEffect, useState} from 'react';

/** Browser preferences are optional; unavailable storage falls back to in-memory state.
 * The key stays fixed for the component's lifetime. Values are validated before use.
 */
export function useStoredSetting<T>(key: string, initial: T, accept: (value: unknown) => value is T) {
    const [value, setValue] = useState<T>(() => {
        try {
            const stored: unknown = JSON.parse(localStorage.getItem(`aor.setting.${key}`) ?? 'null');
            return accept(stored) ? stored : initial;
        } catch { return initial; }
    });
    useEffect(() => {
        try { localStorage.setItem(`aor.setting.${key}`, JSON.stringify(value)); } catch { /* Settings remain usable in memory. */ }
    }, [key, value]);
    return [value, setValue] as const;
}
