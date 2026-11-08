import {useCallback, useEffect, useMemo, useSyncExternalStore} from 'react';
import type {SetStateAction} from 'react';

export const isString = (value: unknown): value is string => typeof value === 'string';
export const isBoolean = (value: unknown): value is boolean => typeof value === 'boolean';
export const isUndoPolicy = (value: unknown): value is string => typeof value === 'string' && ['regular-turn', 'competitive', 'friendly-turn', 'linear-game'].includes(value);
export const isGameFilter = (value: unknown): value is string => typeof value === 'string' && ['ongoing', 'my-move', 'finished', 'cancelled', 'abandoned', 'unavailable', 'all'].includes(value);

const memory = new Map<string, string>();
const listeners = new Map<string, Set<() => void>>();
const storageKey = (key: string) => `aor.setting.${key}`;

function storedValue(key: string): string | null {
    try { return memory.get(key) ?? localStorage.getItem(storageKey(key)) ?? null; }
    catch { return memory.get(key) ?? null; }
}

function decodedValue<T>(key: string, stored: string | null, initial: T,
    accept: (value: unknown) => value is T): T {
    try {
        const value: unknown = JSON.parse(stored ?? 'null');
        const migrated = key === 'undo-policy' && value === 'friendly-game' ? 'linear-game' : value;
        return accept(migrated) ? migrated : initial;
    } catch { return initial; }
}

/** Browser preferences share updates across mounted controls and fall back to memory without storage. */
export function useStoredSetting<T>(key: string, initial: T, accept: (value: unknown) => value is T) {
    const subscribe = useCallback((notify: () => void) => {
        const observers = listeners.get(key) ?? new Set<() => void>();
        observers.add(notify); listeners.set(key, observers);
        const update = (event: StorageEvent) => {
            if (event.key !== null && event.key !== storageKey(key)) return;
            memory.delete(key); notify();
        };
        window.addEventListener('storage', update);
        return () => {
            observers.delete(notify);
            if (observers.size === 0) listeners.delete(key);
            window.removeEventListener('storage', update);
        };
    }, [key]);
    const serialized = useSyncExternalStore(subscribe, () => storedValue(key), () => null);
    const value = useMemo(() => decodedValue(key, serialized, initial, accept),
        [key, serialized, initial, accept]);
    const setValue = useCallback((next: SetStateAction<T>) => {
        const previous = decodedValue(key, storedValue(key), initial, accept);
        const updated = typeof next === 'function' ? (next as (value: T) => T)(previous) : next;
        const serialized = JSON.stringify(updated);
        memory.set(key, serialized);
        try { localStorage.setItem(storageKey(key), serialized); } catch { /* Memory remains usable. */ }
        listeners.get(key)?.forEach(notify => notify());
    }, [key, initial, accept]);
    return [value, setValue] as const;
}

/** Apply the personal theme at the app root, including loading screens and portalled dialogs. */
export function usePersonalTheme() {
    const [darkMode] = useStoredSetting('dark-mode', false, isBoolean);
    useEffect(() => { document.documentElement.dataset.theme = darkMode ? 'dark' : 'light'; }, [darkMode]);
}
